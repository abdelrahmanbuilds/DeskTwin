using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace DeskTwin.WinUI;

/// <summary>
/// Exposes a paired phone video to MediaPlayer as a loopback HTTP byte-range
/// source. Only requested ranges are fetched from Android; no video is staged
/// on the PC.
/// </summary>
internal sealed class PhoneVideoStreamProxy : IAsyncDisposable
{
    private const int ReadChunkBytes = 2 * 1024 * 1024;
    private readonly DesktopBridge _bridge;
    private readonly TcpListener _listener;
    private readonly CancellationTokenSource _lifetime = new();
    private readonly ConcurrentDictionary<TcpClient, byte> _clients = new();
    private readonly string _phonePath;
    private readonly string _key;
    private readonly long _length;
    private readonly string _contentType;
    private readonly Task _acceptLoop;

    public Uri SourceUri { get; }

    private PhoneVideoStreamProxy(
        DesktopBridge bridge,
        string phonePath,
        string fileName,
        long length,
        TcpListener listener,
        int port,
        string key)
    {
        _bridge = bridge;
        _phonePath = phonePath;
        _length = length;
        _contentType = ContentTypeFor(fileName);
        _listener = listener;
        _key = key;
        SourceUri = new Uri($"http://127.0.0.1:{port}/{key}/{Uri.EscapeDataString(Path.GetFileName(fileName))}");
        _acceptLoop = AcceptLoopAsync();
    }

    public static Task<PhoneVideoStreamProxy> StartAsync(
        DesktopBridge bridge,
        string path,
        string fileName,
        long length)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("Phone video path is missing.", nameof(path));
        if (length <= 0) throw new InvalidOperationException("The phone did not provide a valid video size.");

        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var key = Guid.NewGuid().ToString("N");
            return Task.FromResult(new PhoneVideoStreamProxy(bridge, path, fileName, length, listener, port, key));
        }
        catch
        {
            listener.Stop();
            throw;
        }
    }

    private async Task AcceptLoopAsync()
    {
        while (!_lifetime.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync(_lifetime.Token); }
            catch (OperationCanceledException) when (_lifetime.IsCancellationRequested) { break; }
            catch (SocketException) when (_lifetime.IsCancellationRequested) { break; }
            catch (ObjectDisposedException) when (_lifetime.IsCancellationRequested) { break; }

            _clients.TryAdd(client, 0);
            _ = HandleClientAsync(client);
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        try
        {
            using (client)
            {
                var stream = client.GetStream();
                using var reader = new StreamReader(
                    stream, Encoding.ASCII, detectEncodingFromByteOrderMarks: false, bufferSize: 1024, leaveOpen: true);
                var requestLine = await reader.ReadLineAsync(_lifetime.Token);
                if (string.IsNullOrWhiteSpace(requestLine)) return;
                var parts = requestLine.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 2)
                {
                    await WriteErrorAsync(stream, 400, "Bad Request", _lifetime.Token);
                    return;
                }

                string? rangeHeader = null;
                string? line;
                while (!string.IsNullOrEmpty(line = await reader.ReadLineAsync(_lifetime.Token)))
                {
                    var separator = line.IndexOf(':');
                    if (separator > 0 && line[..separator].Equals("Range", StringComparison.OrdinalIgnoreCase))
                        rangeHeader = line[(separator + 1)..].Trim();
                }

                var method = parts[0];
                var requestPath = Uri.UnescapeDataString(parts[1].Split('?', 2)[0]);
                if (method is not ("GET" or "HEAD"))
                {
                    await WriteErrorAsync(stream, 405, "Method Not Allowed", _lifetime.Token);
                    return;
                }
                if (!requestPath.StartsWith($"/{_key}/", StringComparison.Ordinal))
                {
                    await WriteErrorAsync(stream, 404, "Not Found", _lifetime.Token);
                    return;
                }

                var isPartial = !string.IsNullOrWhiteSpace(rangeHeader);
                long start = 0;
                long end = _length - 1;
                if (isPartial && !TryParseRange(rangeHeader!, _length, out start, out end))
                {
                    await WriteHeadersAsync(stream, 416, "Range Not Satisfiable", new Dictionary<string, string>
                    {
                        ["Content-Range"] = $"bytes */{_length}",
                        ["Content-Length"] = "0",
                    }, _lifetime.Token);
                    return;
                }

                // Bound every response so a Media Foundation request for the
                // whole file can begin playback after its first small window.
                end = Math.Min(end, start + ReadChunkBytes - 1);
                var partialResponse = isPartial || end < _length - 1;
                var responseLength = end - start + 1;
                var headers = new Dictionary<string, string>
                {
                    ["Content-Type"] = _contentType,
                    ["Accept-Ranges"] = "bytes",
                    ["Cache-Control"] = "no-store",
                    ["Content-Length"] = responseLength.ToString(),
                    ["Connection"] = "close",
                };
                if (partialResponse) headers["Content-Range"] = $"bytes {start}-{end}/{_length}";
                await WriteHeadersAsync(stream, partialResponse ? 206 : 200,
                    partialResponse ? "Partial Content" : "OK", headers, _lifetime.Token);
                if (method == "HEAD") return;

                var offset = start;
                var remaining = responseLength;
                while (remaining > 0 && !_lifetime.IsCancellationRequested)
                {
                    var wanted = (int)Math.Min(ReadChunkBytes, remaining);
                    var bytes = await ReadPhoneRangeAsync(offset, wanted, _lifetime.Token);
                    if (bytes.Length == 0 || bytes.Length > wanted)
                        throw new IOException("The phone returned an invalid video range.");
                    await stream.WriteAsync(bytes, _lifetime.Token);
                    offset += bytes.Length;
                    remaining -= bytes.Length;
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (IOException) { }
        catch (SocketException) { }
        catch (ObjectDisposedException) { }
        catch (Exception) { }
        finally { _clients.TryRemove(client, out _); }
    }

    private static async Task WriteErrorAsync(NetworkStream stream, int status, string reason, CancellationToken token)
    {
        await WriteHeadersAsync(stream, status, reason, new Dictionary<string, string>
        {
            ["Content-Length"] = "0",
            ["Connection"] = "close",
        }, token);
    }

    private static async Task WriteHeadersAsync(
        NetworkStream stream,
        int status,
        string reason,
        Dictionary<string, string> headers,
        CancellationToken token)
    {
        var text = new StringBuilder($"HTTP/1.1 {status} {reason}\r\n");
        foreach (var (name, value) in headers) text.Append(name).Append(": ").Append(value).Append("\r\n");
        text.Append("\r\n");
        await stream.WriteAsync(Encoding.ASCII.GetBytes(text.ToString()), token);
    }

    private async Task<byte[]> ReadPhoneRangeAsync(long offset, int length, CancellationToken cancellationToken)
    {
        var result = await _bridge.CallAsync(
            "phone_files_read_range",
            new { path = _phonePath, offset, length },
            cancellationToken,
            TimeSpan.FromSeconds(50)) as JsonObject;
        if (result?["success"]?.GetValue<bool>() != true)
            throw new IOException(result?["error"]?.GetValue<string>() ?? "The phone video range could not be read.");
        var contentFile = result["content_file"]?.GetValue<string>();
        if (!string.IsNullOrWhiteSpace(contentFile))
        {
            var transferDirectory = Path.GetFullPath(Path.Combine(_bridge.ProjectRoot, "data", "phone_transfers"));
            var allowedPrefix = transferDirectory.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            var fullPath = Path.GetFullPath(contentFile);
            if (!fullPath.StartsWith(allowedPrefix, StringComparison.OrdinalIgnoreCase))
                throw new IOException("The phone video range was written outside the DeskTwin transfer folder.");
            try
            {
                var bytes = await File.ReadAllBytesAsync(fullPath, cancellationToken);
                if (bytes.Length == 0 || bytes.Length > length)
                    throw new IOException("The phone returned an invalid video range file.");
                return bytes;
            }
            finally
            {
                try { File.Delete(fullPath); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        var encoded = result["content_base64"]?.GetValue<string>() ?? string.Empty;
        return Convert.FromBase64String(encoded);
    }

    private static bool TryParseRange(string header, long length, out long start, out long end)
    {
        start = 0;
        end = length - 1;
        if (!header.StartsWith("bytes=", StringComparison.OrdinalIgnoreCase) || length <= 0) return false;
        var value = header[6..].Split(',', 2)[0].Trim();
        var separator = value.IndexOf('-');
        if (separator < 0) return false;

        var first = value[..separator].Trim();
        var last = value[(separator + 1)..].Trim();
        if (first.Length == 0)
        {
            if (!long.TryParse(last, out var suffix) || suffix <= 0) return false;
            start = Math.Max(0, length - suffix);
            return true;
        }
        if (!long.TryParse(first, out start) || start < 0 || start >= length) return false;
        if (last.Length > 0 && (!long.TryParse(last, out end) || end < start)) return false;
        end = Math.Min(end, length - 1);
        return true;
    }

    private static string ContentTypeFor(string fileName) => Path.GetExtension(fileName).ToLowerInvariant() switch
    {
        ".mp4" or ".m4v" => "video/mp4",
        ".mov" => "video/quicktime",
        ".3gp" => "video/3gpp",
        ".webm" => "video/webm",
        ".mkv" => "video/x-matroska",
        ".avi" => "video/x-msvideo",
        _ => "application/octet-stream",
    };

    public async ValueTask DisposeAsync()
    {
        _lifetime.Cancel();
        _listener.Stop();
        foreach (var client in _clients.Keys) client.Close();
        try { await _acceptLoop; }
        catch (SocketException) { }
        _lifetime.Dispose();
    }
}
