using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace DeskTwin.WinUI;

public sealed class TransfersPage : Page
{
    private readonly DesktopBridge _bridge;
    private readonly StackPanel _rows = new() { Spacing = 10 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _updated = new() { FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") };
    private readonly Button _refresh = new() { Content = "تحديث النقل" };
    private bool _loading;
    private string? _lastSnapshot;

    public TransfersPage(DesktopBridge bridge)
    {
        _bridge = bridge;
        var root = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Background = WinUIHelpers.Brush("AppBackground"),
        };
        var content = new StackPanel { Spacing = 14, Margin = new Thickness(30, 24, 30, 28), MaxWidth = 1000 };
        content.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("النقل والاستكمال", "Transfers and resume"),
            Style = (Style)Application.Current.Resources["TitleTextBlockStyle"],
        });
        content.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate(
                "تابع الملفات الكبيرة غير المكتملة. يستأنف الإرسال تلقائيًا عند فتح DeskTwin على الهاتف واتصاله بالشبكة نفسها.",
                "Track incomplete large files. Sending resumes when DeskTwin is open on the phone and both devices share a network."),
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        _refresh.Click += async (_, _) => await RefreshAsync();
        content.Children.Add(_refresh);
        content.Children.Add(_message);
        content.Children.Add(_rows);
        content.Children.Add(_updated);
        root.Content = content;
        Content = root;
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_loading) return;
        _loading = true;
        _refresh.IsEnabled = false;
        if (_rows.Children.Count == 0)
            _message.Text = WinUIStrings.Translate("جارٍ تحميل حالة النقل…", "Loading transfer status…");
        try
        {
            var result = await _bridge.CallAsync("get_incomplete_transfers", new { limit = 100 }) as JsonObject;
            if (result?["available"]?.GetValue<bool>() != true)
            {
                _rows.Children.Clear();
                _message.Text = WinUIStrings.Translate(
                    "شغّل خدمة DeskTwin لقراءة حالة النقل المحفوظة.",
                    "Start the DeskTwin service to read saved transfer status.");
                _lastSnapshot = null;
                _updated.Text = UpdatedText();
                return;
            }
            var total = Math.Max(0, result?["total"]?.GetValue<int>() ?? 0);
            var items = result?["items"] as JsonArray ?? new JsonArray();
            var snapshot = $"{total}:{items.ToJsonString()}";
            if (snapshot == _lastSnapshot)
            {
                _updated.Text = UpdatedText();
                return;
            }

            _rows.Children.Clear();
            if (total == 0)
            {
                _message.Text = WinUIStrings.Translate("لا توجد ملفات غير مكتملة.", "No incomplete files.");
            }
            else
            {
                _message.Text = total > items.Count
                    ? WinUIStrings.Translate($"{total} ملفات غير مكتملة؛ يُعرض أحدث {items.Count}.", $"{total} incomplete files; showing the latest {items.Count}.")
                    : WinUIStrings.Translate($"{total} ملفات غير مكتملة.", $"{total} incomplete files.");
                foreach (var node in items)
                {
                    if (node is JsonObject item) _rows.Children.Add(CreateTransferCard(item));
                }
            }
            _lastSnapshot = snapshot;
            _updated.Text = UpdatedText();
        }
        catch (Exception ex)
        {
            _message.Text = WinUIStrings.Translate("تعذر تحميل حالة النقل: ", "Could not load transfer status: ") + ex.Message;
        }
        finally
        {
            _loading = false;
            _refresh.IsEnabled = true;
        }
    }

    private static Border CreateTransferCard(JsonObject item)
    {
        var path = item["file_path"]?.GetValue<string>() ?? "";
        var name = Path.GetFileName(path.Replace('/', Path.DirectorySeparatorChar));
        if (string.IsNullOrWhiteSpace(name)) name = path;
        var received = ReadLong(item, "bytes_received");
        var totalSize = ReadLong(item, "total_size_bytes");
        var percent = totalSize > 0 ? Math.Clamp(received * 100d / totalSize, 0, 100) : 0;
        var direction = item["direction"]?.GetValue<string>() ?? "upload";
        var transferState = item["status"]?.GetValue<string>() ?? "in_progress";
        var lastProgress = item["last_progress_at"]?.GetValue<string>() ?? "";
        var retentionHours = Math.Max(1, item["retention_hours"]?.GetValue<int>() ?? 24);

        var details = new StackPanel { Spacing = 8 };
        details.Children.Add(new TextBlock
        {
            Text = name,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        details.Children.Add(new TextBlock
        {
            Text = direction == "upload"
                ? WinUIStrings.Translate("استقبال من الهاتف", "Receiving from phone")
                : WinUIStrings.Translate("إرسال إلى الهاتف", "Sending to phone"),
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        if (!string.IsNullOrWhiteSpace(path) && !string.Equals(path, name, StringComparison.Ordinal))
        {
            details.Children.Add(new TextBlock
            {
                Text = path,
                FontSize = 12,
                Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
        }
        details.Children.Add(new TextBlock
        {
            Text = transferState == "stalled"
                ? WinUIStrings.Translate("متوقف مؤقتًا", "Paused")
                : WinUIStrings.Translate("غير مكتمل", "Incomplete"),
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
        });
        details.Children.Add(new ProgressBar
        {
            Minimum = 0,
            Maximum = 100,
            Value = percent,
            IsIndeterminate = totalSize <= 0,
            Height = 6,
        });
        var progressText = totalSize > 0
            ? $"{percent:0}%  •  {FormatBytes(received)} / {FormatBytes(totalSize)}"
            : received > 0
                ? WinUIStrings.Translate($"تم استقبال {FormatBytes(received)}", $"{FormatBytes(received)} received")
                : WinUIStrings.Translate("بانتظار الاستكمال", "Waiting to resume");
        details.Children.Add(new TextBlock
        {
            Text = progressText,
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        if (!string.IsNullOrWhiteSpace(lastProgress))
        {
            details.Children.Add(new TextBlock
            {
                Text = WinUIStrings.Translate("آخر تقدم: ", "Last progress: ") + FormatTimestamp(lastProgress),
                FontSize = 12,
                Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            });
        }
        details.Children.Add(new TextBlock
        {
            Text = transferState == "stalled"
                ? WinUIStrings.Translate($"سيُحذف الجزء غير المكتمل بعد {retentionHours} ساعة من آخر تقدم.", $"The partial file is removed {retentionHours} hours after its last progress.")
                : WinUIStrings.Translate("يحافظ DeskTwin على الجزء غير المكتمل لاستكماله.", "DeskTwin keeps the partial file available for resume."),
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        details.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("يستأنف الهاتف الإرسال عند فتح DeskTwin واتصاله بالشبكة نفسها.", "The phone resumes sending when DeskTwin is open and both devices share a network."),
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
            TextWrapping = TextWrapping.Wrap,
        });

        var card = WinUIHelpers.Card();
        card.Padding = new Thickness(16);
        card.Child = details;
        return card;
    }

    private static long ReadLong(JsonObject item, string key)
    {
        try { return Math.Max(0, item[key]?.GetValue<long>() ?? 0); }
        catch (InvalidOperationException) { return 0; }
    }

    private static string FormatBytes(long value)
    {
        double amount = value;
        var units = new[] { "B", "KB", "MB", "GB", "TB" };
        var index = 0;
        while (amount >= 1024 && index < units.Length - 1) { amount /= 1024; index++; }
        return index == 0 ? $"{value} B" : $"{amount:0.0} {units[index]}";
    }

    private static string FormatTimestamp(string value)
    {
        if (!DateTimeOffset.TryParse(value, System.Globalization.CultureInfo.InvariantCulture,
                System.Globalization.DateTimeStyles.AssumeUniversal, out var timestamp))
            return value.Replace('T', ' ').Split('.')[0];
        return timestamp.ToLocalTime().ToString("yyyy-MM-dd HH:mm", System.Globalization.CultureInfo.InvariantCulture);
    }

    private static string UpdatedText() => WinUIStrings.Translate("آخر تحديث: ", "Updated: ")
        + DateTime.Now.ToString("HH:mm:ss", System.Globalization.CultureInfo.InvariantCulture);
}
