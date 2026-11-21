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
        var refresh = new Button { Content = "إعادة الفحص" };
        _refreshButton = refresh;
        refresh.Click += async (_, _) => await RefreshAsync();
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { refresh } };
        Content = WinUIHelpers.PageLayout("فحص توافق DeskTwin", "حالة الخدمة والأجهزة الموثوقة وقناة المحادثة والبروتوكول.", _message, new StackPanel
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
        if (_rows.Children.Count == 0) _message.Text = "جارٍ فحص التكامل…";
        try
        {
            var health = await _bridge.CallAsync("get_integration_health") as JsonObject;
            if (health is null) throw new InvalidOperationException("لم تُرجع الخدمة حالة التكامل.");
            var snapshot = health.ToJsonString();
            if (snapshot == _lastHealthSnapshot)
            {
                _message.Text = string.Empty;
                return;
            }
            _rows.Children.Clear();
            _message.Text = string.Empty;
            AddRow("الحالة", health["status"]?.GetValue<string>() == "healthy" ? "سليم" : "يحتاج مراجعة");
            AddRow("الخدمة", health["running"]?.GetValue<bool>() == true ? "تعمل" : "متوقفة");
            AddRow("إصدار البروتوكول", health["protocol_version"]?.GetValue<string>() ?? "—");
            AddRow("إصدار الخادم", health["server_version"]?.GetValue<string>() ?? "—");
            AddRow("أجهزة موثوقة", health["trusted_devices"]?.ToString() ?? "0");
            AddRow("اتصالات نشطة", health["active_connections"]?.ToString() ?? "0");
            AddRow("WebSocket", health["websocket"]?.GetValue<string>() ?? "—");
            AddRow("المحادثة", health["chat"]?.GetValue<string>() ?? "—");
            if (health["hints"] is JsonArray hints)
                foreach (var hint in hints)
                    AddRow("إجراء مقترح", hint?.GetValue<string>() ?? "—");
            _lastHealthSnapshot = snapshot;
        }
        catch (Exception ex) { _message.Text = $"تعذر فحص التكامل: {ex.Message}"; }
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
