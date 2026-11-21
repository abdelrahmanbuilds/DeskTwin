using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using System.Text.Json.Nodes;

namespace DeskTwin.WinUI;

public sealed class IntegrationPage : Page
{
    private readonly DesktopBridge _bridge;
    private readonly StackPanel _rows = new() { Spacing = 10 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _refreshButton;
    private bool _refreshing;
    private string? _lastHealthSnapshot;

    public IntegrationPage(DesktopBridge bridge)
    {
        _bridge = bridge;
        var refresh = new Button { Content = WinUIStrings.Translate("إعادة الفحص", "Run check") };
        _refreshButton = refresh;
        refresh.Click += async (_, _) => await RefreshAsync();
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { refresh } };
        Content = WinUIHelpers.PageLayout(WinUIStrings.Translate("فحص توافق DeskTwin", "DeskTwin compatibility"), WinUIStrings.Translate("حالة الخدمة والأجهزة الموثوقة وقناة المحادثة والبروتوكول.", "Service, trusted devices, chat channel, and protocol status."), _message, new StackPanel
        {
            Spacing = 12,
            Children = { actions, _rows },
        });
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_refreshing) return;
        _refreshing = true;
        _refreshButton.IsEnabled = false;
        if (_rows.Children.Count == 0) _message.Text = WinUIStrings.Translate("جارٍ فحص التكامل…", "Checking integration…");
        try
        {
            var health = await _bridge.CallAsync("get_integration_health") as JsonObject;
            if (health is null) throw new InvalidOperationException(WinUIStrings.Translate("لم تُرجع الخدمة حالة التكامل.", "The service did not return integration status."));
            var snapshot = health.ToJsonString();
            if (snapshot == _lastHealthSnapshot)
            {
                _message.Text = string.Empty;
                return;
            }
            _rows.Children.Clear();
            _message.Text = string.Empty;
            AddRow(WinUIStrings.Translate("الحالة", "Status"), health["status"]?.GetValue<string>() == "healthy" ? WinUIStrings.Translate("سليم", "Healthy") : WinUIStrings.Translate("يحتاج مراجعة", "Needs attention"));
            AddRow(WinUIStrings.Translate("الخدمة", "Service"), health["running"]?.GetValue<bool>() == true ? WinUIStrings.Translate("تعمل", "Running") : WinUIStrings.Translate("متوقفة", "Stopped"));
            AddRow(WinUIStrings.Translate("إصدار البروتوكول", "Protocol version"), health["protocol_version"]?.GetValue<string>() ?? "—");
            AddRow(WinUIStrings.Translate("إصدار الخادم", "Service version"), health["server_version"]?.GetValue<string>() ?? "—");
            AddRow(WinUIStrings.Translate("أجهزة موثوقة", "Trusted devices"), health["trusted_devices"]?.ToString() ?? "0");
            AddRow(WinUIStrings.Translate("اتصالات نشطة", "Active connections"), health["active_connections"]?.ToString() ?? "0");
            AddRow("WebSocket", health["websocket"]?.GetValue<string>() ?? "—");
            AddRow(WinUIStrings.Translate("المحادثة", "Chat"), health["chat"]?.GetValue<string>() ?? "—");
            if (health["hints"] is JsonArray hints)
                foreach (var hint in hints)
                    AddRow(WinUIStrings.Translate("إجراء مقترح", "Suggested action"), WinUIStrings.SafeMessage(hint?.GetValue<string>(), "راجع إعداد الاتصال وحالة الخدمة.", "Review the connection settings and service status."));
            _lastHealthSnapshot = snapshot;
        }
        catch (Exception) { _message.Text = WinUIStrings.Translate("تعذر فحص التكامل. راجع سجل التشخيص لمزيد من التفاصيل.", "Could not check integration. See diagnostics for details."); }
        finally
        {
            _refreshing = false;
            _refreshButton.IsEnabled = true;
        }
    }

    private void AddRow(string label, string value)
    {
        var row = WinUIHelpers.Card();
        row.Child = new StackPanel
        {
            Spacing = 4,
            Children =
            {
                new TextBlock { Text = label, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                new TextBlock
                {
                    Text = value,
                    Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
                    TextWrapping = TextWrapping.Wrap,
                },
            },
        };
        _rows.Children.Add(row);
    }
}
