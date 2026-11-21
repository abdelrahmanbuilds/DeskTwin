using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text;

namespace DeskTwin.WinUI;

public sealed class DesktopBridge : IAsyncDisposable
{
    private static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(15);
    private readonly Process _process;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonNode?>> _pending = new();
    private long _nextId;

    public event Action<JsonObject>? EventReceived;
    public string ProjectRoot { get; }

    public DesktopBridge()
    {
        var root = FindProjectRoot();
        ProjectRoot = root;
        var bundledHost = Path.Combine(root, "WinUI", "bridge", "DeskTwinPythonHost", "DeskTwinPythonHost.exe");
        var sourceHost = Path.Combine(root, "WinUI", "bridge", "winui_host.py");
        var useBundledHost = File.Exists(bundledHost);
        if (!useBundledHost && !File.Exists(sourceHost))
            throw new FileNotFoundException(WinUIStrings.Translate("ملف تشغيل خدمة DeskTwin غير موجود بجوار المشروع.", "The DeskTwin service host was not found beside the app."), sourceHost);

        var python = Environment.GetEnvironmentVariable("DESKTWIN_PYTHON") ?? "python";
        _process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = useBundledHost ? bundledHost : python,
                Arguments = useBundledHost ? string.Empty : $"-u \"{sourceHost}\"",
                WorkingDirectory = root,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = new UTF8Encoding(false),
                StandardErrorEncoding = new UTF8Encoding(false),
            },
            EnableRaisingEvents = true,
        };
        _process.StartInfo.Environment["DESKTWIN_ROOT"] = root;
        _process.StartInfo.Environment["PYTHONUTF8"] = "1";
        _process.StartInfo.Environment["PYTHONIOENCODING"] = "utf-8:backslashreplace";
        _process.Exited += (_, _) => FailPendingRequests();
        _process.Start();
        _ = ReadOutputAsync();
        _ = DrainErrorsAsync();
    }

    public async Task<JsonNode?> CallAsync(
        string command,
        object? args = null,
        CancellationToken cancellationToken = default,
        TimeSpan? requestTimeoutOverride = null)
    {
        if (_process.HasExited) throw new InvalidOperationException(WinUIStrings.Translate("خدمة DeskTwin المحلية متوقفة.", "The DeskTwin local service has stopped."));
        var effectiveTimeout = requestTimeoutOverride ?? RequestTimeout;
        using var requestTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        requestTimeout.CancelAfter(effectiveTimeout);
        var requestToken = requestTimeout.Token;
        var id = Interlocked.Increment(ref _nextId).ToString();
        var completion = new TaskCompletionSource<JsonNode?>(TaskCreationOptions.RunContinuationsAsynchronously);
        _pending[id] = completion;
        var request = JsonSerializer.Serialize(new { id, command, args = args ?? new { } });
        try
        {
            await _writeLock.WaitAsync(requestToken);
            try
            {
                await _process.StandardInput.WriteLineAsync(request.AsMemory(), requestToken);
                await _process.StandardInput.FlushAsync(requestToken);
            }
            finally { _writeLock.Release(); }

            using var registration = requestToken.Register(() => completion.TrySetCanceled(requestToken));
            return await completion.Task;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TimeoutException(WinUIStrings.Translate($"انتهت مهلة طلب DeskTwin: {command} بعد {effectiveTimeout.TotalSeconds:0} ثانية.", $"DeskTwin request '{command}' timed out after {effectiveTimeout.TotalSeconds:0} seconds."));
        }
        finally { _pending.TryRemove(id, out _); }
    }

    private async Task ReadOutputAsync()
    {
        try
        {
            while (await _process.StandardOutput.ReadLineAsync() is { } line)
            {
                if (JsonNode.Parse(line) is not JsonObject message) continue;
                if (message["event"] is not null)
                {
                    EventReceived?.Invoke(message);
                    continue;
                }
                var id = message["id"]?.GetValue<string>();
                if (id is null || !_pending.TryRemove(id, out var completion)) continue;
                if (message["ok"]?.GetValue<bool>() == true)
                    completion.TrySetResult(message["result"]?.DeepClone());
                else
                    completion.TrySetException(new InvalidOperationException(message["error"]?.GetValue<string>() ?? WinUIStrings.Translate("حدث خطأ في خدمة DeskTwin.", "The DeskTwin service returned an error.")));
            }
        }
        catch (Exception ex)
        {
            foreach (var request in _pending.Values) request.TrySetException(ex);
            _pending.Clear();
            EventReceived?.Invoke(new JsonObject { ["event"] = "bridge_error", ["message"] = ex.Message });
        }
    }

    private async Task DrainErrorsAsync()
    {
        while (await _process.StandardError.ReadLineAsync() is { } line)
            Debug.WriteLine($"DeskTwin host: {line}");
    }

    private void FailPendingRequests()
    {
        var message = WinUIStrings.Translate($"خدمة DeskTwin المحلية أُغلقت (رمز الخروج {_process.ExitCode}).", $"The DeskTwin local service stopped (exit code {_process.ExitCode}).");
        foreach (var request in _pending.Values)
            request.TrySetException(new InvalidOperationException(message));
        _pending.Clear();
        EventReceived?.Invoke(new JsonObject
        {
            ["event"] = "bridge_exited",
            ["message"] = message,
        });
    }

    private static string FindProjectRoot()
    {
        var configured = Environment.GetEnvironmentVariable("DESKTWIN_ROOT");
        if (!string.IsNullOrWhiteSpace(configured) && HasBridgeHost(configured))
            return Path.GetFullPath(configured);

        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
            if (HasBridgeHost(directory.FullName)) return directory.FullName;
        throw new DirectoryNotFoundException(WinUIStrings.Translate("لم أجد خدمة DeskTwin المحلية. عيّن DESKTWIN_ROOT لمسار المشروع.", "The DeskTwin local service was not found. Set DESKTWIN_ROOT to the project path."));
    }

    private static bool HasBridgeHost(string root) =>
        File.Exists(Path.Combine(root, "WinUI", "bridge", "DeskTwinPythonHost", "DeskTwinPythonHost.exe")) ||
        File.Exists(Path.Combine(root, "WinUI", "bridge", "winui_host.py"));

    public async ValueTask DisposeAsync()
    {
        if (!_process.HasExited)
        {
            try { await CallAsync("shutdown").WaitAsync(TimeSpan.FromSeconds(2)); } catch { }
            try { await _process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(4)); }
            catch { _process.Kill(entireProcessTree: true); }
        }
        _process.Dispose();
        _writeLock.Dispose();
    }
}
