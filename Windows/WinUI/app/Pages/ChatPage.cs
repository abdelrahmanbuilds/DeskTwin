using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace DeskTwin.WinUI;

public sealed class ChatPage : Page
{
    private readonly DesktopBridge _bridge;
    private readonly StackPanel _messages = new() { Spacing = 10 };
    private readonly TextBox _composer = new()
    {
        AcceptsReturn = true,
        TextWrapping = TextWrapping.Wrap,
        MinHeight = 76,
        PlaceholderText = WinUIStrings.Translate("اكتب رسالة للهاتف…", "Message your phone…"),
    };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _connectionStatus = new() { FontSize = 12 };
    private readonly ScrollViewer _scroll;
    private readonly Button _sendButton;
    private readonly Dictionary<string, MessageVisual> _renderedMessages = new();
    private TextBlock? _emptyState;
    private bool _sending;
    private string? _lastSignature;

    private sealed class MessageVisual
    {
        public MessageVisual(Border bubble, TextBlock body, TextBlock metadata)
        {
            Bubble = bubble;
            Body = body;
            Metadata = metadata;
        }

        public Border Bubble { get; }
        public TextBlock Body { get; }
        public TextBlock Metadata { get; }
    }

    public ChatPage(DesktopBridge bridge, string? initialDraft = null)
    {
        _bridge = bridge;
        _composer.Text = initialDraft ?? "";
        var root = new Grid
        {
            Padding = new Thickness(30, 24, 30, 24),
            RowSpacing = 14,
            Background = WinUIHelpers.Brush("AppBackground"),
        };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

        var heading = new StackPanel { Spacing = 4 };
        heading.Children.Add(new TextBlock { Text = "المحادثة", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        heading.Children.Add(new TextBlock { Text = "رسائل مباشرة بين الكمبيوتر والهاتف عبر الاتصال المحلي.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        heading.Children.Add(_connectionStatus);
        root.Children.Add(heading);

        _scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = _messages };
        Grid.SetRow(_scroll, 1);
        root.Children.Add(_scroll);

        var composerPanel = new Grid { ColumnSpacing = 10 };
        composerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        composerPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        composerPanel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        composerPanel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        composerPanel.Children.Add(_composer);
        _sendButton = new Button { Content = "إرسال", MinWidth = 100, VerticalAlignment = VerticalAlignment.Stretch };
        _sendButton.Click += async (_, _) => await SendAsync();
        _sendButton.KeyboardAccelerators.Add(new KeyboardAccelerator
        {
            Key = VirtualKey.Enter,
            Modifiers = VirtualKeyModifiers.Control,
        });
        Grid.SetColumn(_sendButton, 1);
        composerPanel.Children.Add(_sendButton);
        _status.FontSize = 12;
        Grid.SetRow(_status, 1);
        Grid.SetColumnSpan(_status, 2);
        composerPanel.Children.Add(_status);
        Grid.SetRow(composerPanel, 2);
        root.Children.Add(composerPanel);
        Content = root;
        _ = RefreshAsync();
    }

    public string Draft => _composer.Text;

    public void SetServiceState(bool running, bool starting, bool phoneConnected = false)
    {
        var text = running
            ? phoneConnected
                ? WinUIStrings.Translate("● الهاتف متصل", "● Phone connected")
                : WinUIStrings.Translate("● الخدمة تعمل — بانتظار الهاتف", "● Service running — waiting for phone")
            : starting
                ? WinUIStrings.Translate("● جاري تشغيل خدمة الكمبيوتر…", "● Starting computer service…")
                : WinUIStrings.Translate("● خدمة الكمبيوتر متوقفة", "● Computer service is stopped");
        _connectionStatus.Text = text;
        _connectionStatus.Foreground = WinUIHelpers.Brush(running
            ? "SystemFillColorSuccessBrush"
            : starting ? "SystemFillColorCautionBrush" : "SystemFillColorCriticalBrush");
    }

    public async Task RefreshAsync(bool forceScrollToBottom = false)
    {
        try
        {
            var items = await _bridge.CallAsync("get_chat_messages") as JsonArray;
            var signature = items?.ToJsonString() ?? "[]";
            if (signature == _lastSignature) return;
            var previousOffset = _scroll.VerticalOffset;
            var wasAtBottom = _scroll.ScrollableHeight - previousOffset <= 48;

            var orderedMessages = (items ?? new JsonArray())
                .OfType<JsonObject>()
                .Reverse()
                .ToList();
            if (orderedMessages.Count == 0)
            {
                _messages.Children.Clear();
                _renderedMessages.Clear();
                _emptyState ??= new TextBlock
                {
                    Text = WinUIStrings.Translate("ابدأ المحادثة من الكمبيوتر أو الهاتف.", "Start a conversation from your computer or phone."),
                    Margin = new Thickness(8, 18, 8, 0),
                    Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
                };
                _messages.Children.Add(_emptyState);
                _lastSignature = signature;
                return;
            }

            if (_emptyState is not null)
            {
                _messages.Children.Remove(_emptyState);
                _emptyState = null;
            }

            var desired = new List<(string Key, MessageVisual Visual)>(orderedMessages.Count);
            var duplicateFallbackKeys = new Dictionary<string, int>();
            foreach (var message in orderedMessages)
            {
                var key = MessageKey(message, duplicateFallbackKeys);
                if (!_renderedMessages.TryGetValue(key, out var visual))
                {
                    visual = CreateMessageVisual(message, key);
                    _renderedMessages.Add(key, visual);
                }
                else
                {
                    UpdateMessageVisual(visual, message);
                }
                desired.Add((key, visual));
            }

            var desiredKeys = desired.Select(item => item.Key).ToHashSet(StringComparer.Ordinal);
            foreach (var staleKey in _renderedMessages.Keys.Where(key => !desiredKeys.Contains(key)).ToList())
            {
                _messages.Children.Remove(_renderedMessages[staleKey].Bubble);
                _renderedMessages.Remove(staleKey);
            }

            var currentKeys = _messages.Children.OfType<Border>()
                .Select(bubble => bubble.Tag as string ?? "")
                .ToList();
            var canAppend = currentKeys.Count <= desired.Count
                && currentKeys.SequenceEqual(desired.Take(currentKeys.Count).Select(item => item.Key), StringComparer.Ordinal);
            if (canAppend)
            {
                foreach (var item in desired.Skip(currentKeys.Count)) _messages.Children.Add(item.Visual.Bubble);
            }
            else if (!currentKeys.SequenceEqual(desired.Select(item => item.Key), StringComparer.Ordinal))
            {
                _messages.Children.Clear();
                foreach (var item in desired) _messages.Children.Add(item.Visual.Bubble);
            }

            await Task.Yield();
            var targetOffset = forceScrollToBottom || wasAtBottom
                ? _scroll.ScrollableHeight
                : Math.Min(previousOffset, _scroll.ScrollableHeight);
            if (forceScrollToBottom || wasAtBottom || Math.Abs(targetOffset - previousOffset) > 1)
                _scroll.ChangeView(null, targetOffset, null, true);
            _lastSignature = signature;
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر تحديث المحادثة: ", "Could not refresh the conversation: ") + ex.Message;
        }
    }

    private static string MessageKey(JsonObject message, Dictionary<string, int> duplicateFallbackKeys)
    {
        var id = message["id"]?.GetValue<string>();
        if (!string.IsNullOrWhiteSpace(id)) return id;

        // Older service versions may not include IDs. Keep a stable identity
        // for unchanged legacy records, including duplicate messages.
        var fallback = $"{message["sender"]?.GetValue<string>() ?? ""}\u001f{message["received_at"]?.GetValue<string>() ?? ""}\u001f{message["text"]?.GetValue<string>() ?? ""}";
        duplicateFallbackKeys.TryGetValue(fallback, out var occurrence);
        duplicateFallbackKeys[fallback] = occurrence + 1;
        return $"legacy:{fallback}:{occurrence}";
    }

    private static MessageVisual CreateMessageVisual(JsonObject message, string key)
    {
        var outgoing = message["sender"]?.GetValue<string>() == "computer";
        var bubble = new Border
        {
            Tag = key,
            MaxWidth = 680,
            Padding = new Thickness(14, 10, 14, 8),
            CornerRadius = new CornerRadius(12),
            HorizontalAlignment = outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left,
            Background = WinUIHelpers.Brush(outgoing ? "AccentFillColorDefaultBrush" : "CardBackgroundFillColorDefaultBrush"),
        };
        var content = new StackPanel { Spacing = 5 };
        var body = new TextBlock { TextWrapping = TextWrapping.Wrap, Foreground = WinUIHelpers.Brush(outgoing ? "TextOnAccentFillColorPrimaryBrush" : "TextFillColorPrimaryBrush") };
        var metadata = new TextBlock { FontSize = 11, Opacity = 0.8, Foreground = WinUIHelpers.Brush(outgoing ? "TextOnAccentFillColorSecondaryBrush" : "TextFillColorSecondaryBrush") };
        content.Children.Add(body);
        content.Children.Add(metadata);
        bubble.Child = content;
        var visual = new MessageVisual(bubble, body, metadata);
        UpdateMessageVisual(visual, message);
        return visual;
    }

    private static void UpdateMessageVisual(MessageVisual visual, JsonObject message)
    {
        visual.Body.Text = message["text"]?.GetValue<string>() ?? "";
        visual.Metadata.Text = $"{message["received_at"]?.GetValue<string>() ?? ""}  •  {MessageStatus(message["status"]?.GetValue<string>())}";
    }

    private async Task SendAsync()
    {
        var text = _composer.Text.Trim();
        if (_sending || text.Length == 0) return;
        _sending = true;
        _sendButton.IsEnabled = false;
        try
        {
            await _bridge.CallAsync("send_chat_message", new { text });
            _composer.Text = "";
            _status.Text = WinUIStrings.Translate("أُرسلت الرسالة؛ بانتظار تأكيد الهاتف.", "Message sent; waiting for phone confirmation.");
            await RefreshAsync(forceScrollToBottom: true);
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر إرسال الرسالة: ", "Could not send the message: ") + ex.Message;
        }
        finally
        {
            _sending = false;
            _sendButton.IsEnabled = true;
        }
    }

    private static string MessageStatus(string? status) => status switch
    {
        "sent" => WinUIStrings.Translate("بانتظار استلام الهاتف", "Waiting for phone delivery"),
        "delivered" or "received" => WinUIStrings.Translate("تم الاستلام", "Delivered"),
        _ => status ?? "",
    };
}
