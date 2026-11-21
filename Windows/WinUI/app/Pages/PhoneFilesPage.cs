using System.Text.Json.Nodes;
using System.Diagnostics;
using Windows.ApplicationModel.DataTransfer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace DeskTwin.WinUI;

public sealed record PhoneFilesPageState(string Path, string Search, string SortTag, string? CopiedPath, bool ShowingTrash);

public sealed class PhoneFilesPage : Page
{
    private const int PageSize = 150;
    private static readonly TimeSpan FileOperationTimeout = TimeSpan.FromSeconds(40);
    private static readonly TimeSpan FileDownloadTimeout = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan FileUploadTimeout = TimeSpan.FromSeconds(305);
    private readonly DesktopBridge _bridge;
    private readonly TextBox _path = new() { PlaceholderText = "مسار الهاتف (اتركه فارغًا للجذر)" };
    private readonly TextBox _search = new() { PlaceholderText = "ابحث في ملفات الهاتف…", MaxLength = 512 };
    private readonly ComboBox _sort = new() { Width = 190 };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly StackPanel _rows = new() { Spacing = 8 };
    private readonly StackPanel _activeTransfers = new() { Spacing = 8 };
    private readonly Border _activeTransfersCard = WinUIHelpers.Card();
    private readonly ScrollViewer _scroll;
    private readonly Button _pasteButton = new() { Content = WinUIStrings.Translate("لصق", "Paste"), IsEnabled = false };
    private string? _copiedPath;
    private bool _busy;
    private bool _operationBusy;
    private bool _showingTrash;
    private bool _hasMore;
    private bool _serverPaged;
    private bool _drainingPendingLoads;
    private bool _pendingOperationReload;
    private int _nextOffset;
    private string? _pendingPath;
    private CancellationTokenSource? _filterReload;
    private long _lastPhoneEventRevision = -1;
    private readonly List<JsonObject> _entries = new();
    private readonly Button _trashButton = new() { Content = WinUIStrings.Translate("سلة الهاتف", "Phone trash") };

    public PhoneFilesPage(DesktopBridge bridge, PhoneFilesPageState? state = null)
    {
        _bridge = bridge;
        _path.Text = state?.Path ?? "";
        _search.Text = state?.Search ?? "";
        _copiedPath = state?.CopiedPath;
        _pasteButton.IsEnabled = !string.IsNullOrWhiteSpace(_copiedPath);
        var root = new Grid
        {
            Padding = new Thickness(28, 22, 28, 22),
            RowSpacing = 12,
            Background = WinUIHelpers.Brush("AppBackground"),
        };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var pageHeader = new StackPanel { Spacing = 5 };
        pageHeader.Children.Add(new TextBlock { Text = "ملفات الهاتف", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        pageHeader.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate(
                "اتصال مشفّر وموثّق • يعرض فقط الملفات التي أتاحها الهاتف",
                "Encrypted, verified connection • only phone-authorized files are visible"),
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            FontSize = 13,
        });
        root.Children.Add(pageHeader);
        var toolbar = new Grid { ColumnSpacing = 8 };
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.Children.Add(_path);
        var open = new Button { Content = "فتح المسار" };
        open.Click += async (_, _) => await LoadAsync(_path.Text);
        Grid.SetColumn(open, 1); toolbar.Children.Add(open);
        var upload = new Button { Content = "إرسال ملف للهاتف" };
        upload.Click += async (_, _) => await UploadAsync();
        Grid.SetColumn(upload, 2); toolbar.Children.Add(upload);
        _pasteButton.Click += async (_, _) => await PasteAsync();
        Grid.SetColumn(_pasteButton, 3); toolbar.Children.Add(_pasteButton);
        _trashButton.Click += async (_, _) =>
        {
            if (_showingTrash) await LoadAsync(_path.Text);
            else await ShowTrashAsync();
        };
        Grid.SetColumn(_trashButton, 4); toolbar.Children.Add(_trashButton);
        Grid.SetRow(toolbar, 1); root.Children.Add(toolbar);

        var dropTarget = WinUIHelpers.Card();
        dropTarget.Padding = new Thickness(14, 10, 14, 10);
        dropTarget.BorderBrush = WinUIHelpers.Brush("AccentFillColorDefaultBrush");
        dropTarget.BorderThickness = new Thickness(1);
        dropTarget.AllowDrop = true;
        dropTarget.Child = new TextBlock
        {
            Text = WinUIStrings.Translate("اسحب ملفات هنا لإرسالها لمجلد الهاتف الحالي", "Drop files here to send them to the current phone folder"),
            TextAlignment = TextAlignment.Center,
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        };
        dropTarget.DragOver += (_, args) =>
        {
            args.AcceptedOperation = args.DataView.Contains(StandardDataFormats.StorageItems)
                ? DataPackageOperation.Copy
                : DataPackageOperation.None;
        };
        dropTarget.Drop += async (_, args) =>
        {
            var items = await args.DataView.GetStorageItemsAsync();
            if (items.Any(item => item is not StorageFile))
            {
                _status.Text = WinUIStrings.Translate("اسحب ملفات فقط؛ المجلدات غير مدعومة.", "Drop files only; folders are not supported.");
                return;
            }
            await UploadFilesAsync(items.OfType<StorageFile>().ToList());
        };
        Grid.SetRow(dropTarget, 2); root.Children.Add(dropTarget);

        _activeTransfersCard.Visibility = Visibility.Collapsed;
        _activeTransfersCard.Child = _activeTransfers;
        Grid.SetRow(_activeTransfersCard, 3); root.Children.Add(_activeTransfersCard);

        var filters = new Grid { ColumnSpacing = 8 };
        filters.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        filters.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _search.TextChanged += (_, _) => _ = ReloadAfterFilterChangeAsync();
        filters.Children.Add(_search);
        _sort.Items.Add(new ComboBoxItem { Content = "الاسم", Tag = "name" });
        _sort.Items.Add(new ComboBoxItem { Content = "الأحدث", Tag = "modified" });
        _sort.Items.Add(new ComboBoxItem { Content = "الأكبر", Tag = "size" });
        var initialSort = _sort.Items.OfType<ComboBoxItem>()
            .FirstOrDefault(item => Equals(item.Tag, state?.SortTag));
        _sort.SelectedItem = initialSort ?? _sort.Items.OfType<ComboBoxItem>().First();
        _sort.SelectionChanged += (_, _) => _ = ReloadAfterFilterChangeAsync();
        Grid.SetColumn(_sort, 1);
        filters.Children.Add(_sort);
        Grid.SetRow(filters, 4); root.Children.Add(filters);

        var pathRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var up = new Button { Content = WinUIStrings.Translate("↑ المجلد الأعلى", "↑ Parent folder") };
        up.Click += async (_, _) => await LoadAsync(ParentPath(_path.Text));
        var refresh = new Button { Content = "تحديث" };
        refresh.Click += async (_, _) => await LoadAsync(_path.Text);
        pathRow.Children.Add(up); pathRow.Children.Add(refresh); pathRow.Children.Add(_status);
        Grid.SetRow(pathRow, 5); root.Children.Add(pathRow);

        _scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Background = WinUIHelpers.Brush("AppBackground"),
            Content = _rows,
        };
        Grid.SetRow(_scroll, 6); root.Children.Add(_scroll);
        Content = root;
        _ = RefreshActiveTransfersAsync();
        if (state?.ShowingTrash == true) _ = ShowTrashAsync();
        else _ = LoadAsync(_path.Text);
    }

    public PhoneFilesPageState CaptureState() => new(
        _path.Text,
        _search.Text,
        (_sort.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "name",
        _copiedPath,
        _showingTrash);

    private string SelectedSort => (_sort.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "name";

    private async Task ReloadAfterFilterChangeAsync()
    {
        if (_showingTrash)
        {
            RenderEntries();
            return;
        }

        var current = new CancellationTokenSource();
        var previous = Interlocked.Exchange(ref _filterReload, current);
        try { previous?.Cancel(); }
        catch (ObjectDisposedException) { }
        _entries.Clear();
        _hasMore = false;
        _serverPaged = false;
        _nextOffset = 0;
        _rows.Children.Clear();
        _rows.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("جاري تطبيق البحث والفرز…", "Applying search and sort…"),
            Margin = new Thickness(8, 16, 8, 0),
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        _status.Text = WinUIStrings.Translate("جارٍ تحديث القائمة…", "Updating the list…");
        try
        {
            await Task.Delay(250, current.Token);
            while (_busy || _operationBusy)
                await Task.Delay(120, current.Token);
            current.Token.ThrowIfCancellationRequested();
            await LoadAsync(_path.Text);
        }
        catch (OperationCanceledException) { }
        finally
        {
            Interlocked.CompareExchange(ref _filterReload, null, current);
            current.Dispose();
        }
    }

    private async Task LoadAsync(string path, bool operationReload = false)
    {
        if (_busy || (_operationBusy && !operationReload))
        {
            _pendingPath = path;
            _pendingOperationReload |= operationReload;
            _status.Text = WinUIStrings.Translate("سيُفتح المسار بعد اكتمال الطلب الحالي…", "The requested folder will open when the current request finishes…");
            if (!_drainingPendingLoads) _ = DrainPendingLoadsAsync();
            return;
        }
        _busy = true;
        _status.Text = WinUIStrings.Translate("جاري الاتصال بالهاتف…", "Connecting to phone…");
        try
        {
            var revisionNode = await _bridge.CallAsync("get_phone_file_event_revision");
            var listingRevision = revisionNode?.GetValue<long>() ?? _lastPhoneEventRevision;
            var result = await _bridge.CallAsync("phone_files_list", new
            {
                path = path.Trim(),
                offset = 0,
                limit = PageSize,
                sort = SelectedSort,
                search = _search.Text.Trim(),
            }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذر قراءة ملفات الهاتف.");
            _showingTrash = false;
            _trashButton.Content = WinUIStrings.Translate("سلة الهاتف", "Phone trash");
            _path.Text = path.Trim();
            var entries = result["entries"] as JsonArray;
            _entries.Clear();
            if (entries is not null)
                foreach (var node in entries)
                    if (node is JsonObject entry) _entries.Add((JsonObject)entry.DeepClone());
            _serverPaged = result["next_offset"] is not null;
            _nextOffset = ReadInt(result, "next_offset", _entries.Count);
            _hasMore = ReadBool(result, "has_more");
            RenderEntries();
            // Acknowledge only the revision captured before this listing. Events
            // arriving during the request remain pending for the next poll.
            _lastPhoneEventRevision = listingRevision;
        }
        catch (Exception ex)
        {
            var message = WinUIStrings.Translate("تعذر تحميل ملفات الهاتف: ", "Could not load phone files: ") + ex.Message;
            RenderEntries();
            _status.Text = message;
        }
        finally { _busy = false; }
    }

    private async Task DrainPendingLoadsAsync()
    {
        if (_drainingPendingLoads) return;
        _drainingPendingLoads = true;
        try
        {
            while (_pendingPath is not null)
            {
                while (_busy || _operationBusy)
                    await Task.Delay(120);
                var pendingPath = _pendingPath;
                var operationReload = _pendingOperationReload;
                _pendingPath = null;
                _pendingOperationReload = false;
                if (pendingPath is not null)
                    await LoadAsync(pendingPath, operationReload);
            }
        }
        finally
        {
            _drainingPendingLoads = false;
            if (_pendingPath is not null) _ = DrainPendingLoadsAsync();
        }
    }

    public async Task RefreshEventsAsync()
    {
        await RefreshActiveTransfersAsync();
        if (_busy || _operationBusy || _showingTrash) return;
        try
        {
            var revisionNode = await _bridge.CallAsync("get_phone_file_event_revision");
            var eventRevision = revisionNode?.GetValue<long>() ?? 0;
            if (eventRevision == _lastPhoneEventRevision) return;
            await LoadAsync(_path.Text);
        }
        catch (Exception ex) { _status.Text = ex.Message; }
    }

    private async Task RefreshActiveTransfersAsync()
    {
        try
        {
            var uploads = await _bridge.CallAsync("get_active_phone_uploads") as JsonArray;
            var active = uploads?.OfType<JsonObject>().ToList() ?? new List<JsonObject>();
            _activeTransfers.Children.Clear();
            _activeTransfersCard.Visibility = active.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
            foreach (var upload in active)
                _activeTransfers.Children.Add(CreateActiveTransferRow(upload));
        }
        catch
        {
            // Transfer status is supplemental; file operations keep their own errors.
        }
    }

    private UIElement CreateActiveTransferRow(JsonObject upload)
    {
        var transferId = upload["transfer_id"]?.GetValue<string>() ?? "";
        var name = upload["name"]?.GetValue<string>() ?? WinUIStrings.Translate("ملف", "File");
        var sent = upload["bytes_sent"]?.GetValue<long>() ?? 0;
        var total = upload["total_bytes"]?.GetValue<long>() ?? 0;
        var state = upload["state"]?.GetValue<string>() ?? "sending";
        var percent = total > 0 ? Math.Clamp((double)sent / total * 100, 0, 100) : 0;
        var row = new StackPanel { Spacing = 6 };
        row.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate($"جارٍ إرسال {name}", $"Sending {name}"),
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        row.Children.Add(new ProgressBar { Minimum = 0, Maximum = 100, Value = percent });
        var details = new Grid { ColumnSpacing = 10 };
        details.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        details.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        details.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate(
                $"{FormatSize(sent)} من {FormatSize(total)} · {TransferStateLabel(state)}",
                $"{FormatSize(sent)} of {FormatSize(total)} · {TransferStateLabel(state)}"),
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        var cancel = new Button { Content = WinUIStrings.Translate("إلغاء النقل", "Cancel transfer"), IsEnabled = !string.IsNullOrWhiteSpace(transferId) && state != "cancelling" };
        cancel.Click += async (_, _) => await CancelActiveTransferAsync(transferId);
        Grid.SetColumn(cancel, 1);
        details.Children.Add(cancel);
        row.Children.Add(details);
        return row;
    }

    private async Task CancelActiveTransferAsync(string transferId)
    {
        try
        {
            var result = await _bridge.CallAsync("cancel_phone_upload", new { transfer_id = transferId }) as JsonObject;
            _status.Text = result?["cancelled"]?.GetValue<bool>() == true
                ? WinUIStrings.Translate("أُرسل طلب إلغاء النقل للهاتف.", "Transfer cancellation requested from the phone.")
                : WinUIStrings.Translate("النقل لم يعد نشطًا.", "The transfer is no longer active.");
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر إلغاء النقل: ", "Could not cancel the transfer: ") + ex.Message; }
        finally { await RefreshActiveTransfersAsync(); }
    }

    private static string TransferStateLabel(string state) => state switch
    {
        "starting" => WinUIStrings.Translate("بانتظار بدء الهاتف", "Waiting for phone start"),
        "confirming" => WinUIStrings.Translate("بانتظار تأكيد الهاتف", "Waiting for phone confirmation"),
        "cancelling" => WinUIStrings.Translate("جارٍ الإلغاء", "Cancelling"),
        _ => WinUIStrings.Translate("جارٍ الإرسال", "Sending"),
    };

    private void RenderEntries()
    {
        if (_showingTrash) return;
        _rows.Children.Clear();
        var query = _search.Text.Trim();
        var selectedSort = (_sort.SelectedItem as ComboBoxItem)?.Tag?.ToString() ?? "name";
        IEnumerable<JsonObject> filtered = _entries.Where(entry =>
            (entry["name"]?.GetValue<string>() ?? "").Contains(query, StringComparison.CurrentCultureIgnoreCase));
        if (!_serverPaged)
        {
            filtered = selectedSort switch
            {
                "modified" => filtered.OrderBy(entry => entry["directory"]?.GetValue<bool>() == true ? 0 : 1)
                    .ThenByDescending(entry => ReadLong(entry, "modified"))
                    .ThenBy(entry => entry["name"]?.GetValue<string>() ?? "", StringComparer.CurrentCultureIgnoreCase),
                "size" => filtered.OrderBy(entry => entry["directory"]?.GetValue<bool>() == true ? 0 : 1)
                    .ThenByDescending(entry => ReadLong(entry, "size"))
                    .ThenBy(entry => entry["name"]?.GetValue<string>() ?? "", StringComparer.CurrentCultureIgnoreCase),
                _ => filtered.OrderBy(entry => entry["directory"]?.GetValue<bool>() == true ? 0 : 1)
                    .ThenBy(entry => entry["name"]?.GetValue<string>() ?? "", StringComparer.CurrentCultureIgnoreCase),
            };
        }
        var visible = filtered.ToList();
        if (visible.Count == 0)
        {
            var emptyText = string.IsNullOrWhiteSpace(query) && _entries.Count == 0
                ? WinUIStrings.Translate("المجلد فارغ.", "This folder is empty.")
                : WinUIStrings.Translate("لا توجد ملفات مطابقة.", "No matching files.");
            _rows.Children.Add(new TextBlock { Text = emptyText, Margin = new Thickness(8, 16, 8, 0), Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        }
        else
        {
            foreach (var entry in visible) AddEntry(entry);
            if (_hasMore)
            {
                var loadMore = new Button
                {
                    Content = WinUIStrings.Translate("تحميل الصفحة التالية", "Load the next page"),
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    Margin = new Thickness(0, 8, 0, 16),
                };
                loadMore.Click += async (_, _) => await LoadMoreAsync();
                _rows.Children.Add(loadMore);
            }
        }
        _status.Text = _hasMore
            ? WinUIStrings.Translate($"عُرض {visible.Count} عنصرًا؛ توجد صفحة أخرى", $"Showing {visible.Count} items; more available")
            : WinUIStrings.Translate($"عدد العناصر: {visible.Count}", $"Items: {visible.Count}");
    }

    private static long ReadLong(JsonObject item, string key)
    {
        try { return item[key]?.GetValue<long>() ?? 0; }
        catch (InvalidOperationException) { return 0; }
    }

    private static int ReadInt(JsonObject item, string key, int fallback)
    {
        try { return item[key]?.GetValue<int>() ?? fallback; }
        catch (InvalidOperationException) { return fallback; }
    }

    private static bool ReadBool(JsonObject item, string key)
    {
        try { return item[key]?.GetValue<bool>() ?? false; }
        catch (InvalidOperationException) { return false; }
    }

    private async Task LoadMoreAsync()
    {
        if (_busy || _operationBusy || !_hasMore) return;
        _busy = true;
        _status.Text = WinUIStrings.Translate("جاري تحميل الصفحة التالية…", "Loading the next page…");
        try
        {
            var result = await _bridge.CallAsync("phone_files_list", new
            {
                path = _path.Text.Trim(),
                offset = _nextOffset,
                limit = PageSize,
                sort = SelectedSort,
                search = _search.Text.Trim(),
            }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذر تحميل الصفحة التالية.");

            var entries = result["entries"] as JsonArray;
            var received = 0;
            if (entries is not null)
            {
                foreach (var node in entries)
                {
                    if (node is not JsonObject entry) continue;
                    var copy = (JsonObject)entry.DeepClone();
                    _entries.Add(copy);
                    received++;
                }
            }
            _serverPaged = result["next_offset"] is not null;
            _nextOffset = ReadInt(result, "next_offset", _nextOffset + received);
            _hasMore = ReadBool(result, "has_more");
            RenderEntries();
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر تحميل الصفحة التالية: ", "Could not load the next page: ") + ex.Message;
        }
        finally { _busy = false; }
    }

    private async Task ShowTrashAsync()
    {
        if (_busy || _operationBusy) return;
        _busy = true;
        _status.Text = WinUIStrings.Translate("جاري قراءة سلة الهاتف…", "Loading phone trash…");
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "trash_list" }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذر قراءة سلة الهاتف.");
            _showingTrash = true;
            _hasMore = false;
            _serverPaged = false;
            _nextOffset = 0;
            _trashButton.Content = WinUIStrings.Translate("العودة للملفات", "Back to files");
            _entries.Clear();
            _rows.Children.Clear();
            var items = result["items"] as JsonArray;
            if (items is null || items.Count == 0)
            {
                _rows.Children.Add(new TextBlock { Text = WinUIStrings.Translate("سلة الهاتف فارغة.", "Phone trash is empty."), Margin = new Thickness(8, 16, 8, 0), Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
            }
            else
            {
                foreach (var node in items)
                    if (node is JsonObject item) AddTrashEntry(item);
            }
            _status.Text = WinUIStrings.Translate($"في السلة {items?.Count ?? 0} عنصر", $"{items?.Count ?? 0} items in trash");
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر تحميل سلة الهاتف: ", "Could not load phone trash: ") + ex.Message; }
        finally { _busy = false; }
    }

    private void AddTrashEntry(JsonObject item)
    {
        var trashId = item["trash_id"]?.GetValue<string>() ?? "";
        var path = item["path"]?.GetValue<string>() ?? WinUIStrings.Translate("عنصر محذوف", "Deleted item");
        var row = new Grid { ColumnSpacing = 10, Padding = new Thickness(12, 8, 12, 8), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(new TextBlock { Text = path, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
        var restore = new Button { Content = WinUIStrings.Translate("استعادة", "Restore") };
        restore.Click += async (_, _) => await RunTrashActionAsync("restore", trashId);
        var remove = new Button { Content = WinUIStrings.Translate("حذف نهائي", "Delete permanently") };
        remove.Click += async (_, _) => await ConfirmPermanentDeleteAsync(trashId, path);
        actions.Children.Add(restore);
        actions.Children.Add(remove);
        Grid.SetColumn(actions, 1);
        row.Children.Add(actions);
        _rows.Children.Add(row);
    }

    private async Task ConfirmPermanentDeleteAsync(string trashId, string path)
    {
        var dialog = new ContentDialog
        {
            Title = WinUIStrings.Translate("حذف نهائي من الهاتف؟", "Permanently delete from phone?"),
            Content = WinUIStrings.Translate($"سيُحذف الملف نهائيًا ولا يمكن استعادته:\n{path}", $"This item will be permanently deleted and cannot be restored:\n{path}"),
            PrimaryButtonText = WinUIStrings.Translate("حذف نهائي", "Delete permanently"),
            CloseButtonText = WinUIStrings.Translate("إلغاء", "Cancel"),
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() == ContentDialogResult.Primary)
            await RunTrashActionAsync("trash_delete", trashId);
    }

    private async Task RunTrashActionAsync(string operation, string trashId)
    {
        if (!TryBeginOperation(WinUIStrings.Translate("جاري تنفيذ العملية…", "Working…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation, trash_id = trashId }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "فشلت عملية سلة الهاتف.");
            _operationBusy = false;
            await ShowTrashAsync();
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر تنفيذ العملية: ", "Could not complete the operation: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private void AddEntry(JsonObject entry)
    {
        var name = entry["name"]?.GetValue<string>() ?? WinUIStrings.Translate("عنصر", "Item");
        var path = entry["path"]?.GetValue<string>() ?? name;
        var directory = entry["directory"]?.GetValue<bool>() == true;
        var ambiguousPath = _entries.Count(candidate =>
            string.Equals(candidate["path"]?.GetValue<string>() ?? "", path, StringComparison.Ordinal)) > 1;
        var row = new Grid { ColumnSpacing = 14, Padding = new Thickness(14, 10, 14, 10) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var iconTile = new Border
        {
            Width = 44,
            Height = 44,
            CornerRadius = new CornerRadius(12),
            Background = WinUIHelpers.Brush("AccentFillColorDefaultBrush"),
            VerticalAlignment = VerticalAlignment.Center,
            Child = new SymbolIcon(directory ? Symbol.Folder : Symbol.Page)
            {
                Width = 22,
                Height = 22,
                Foreground = WinUIHelpers.Brush("TextOnAccentFillColorPrimaryBrush"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        row.Children.Add(iconTile);
        var details = new StackPanel { Spacing = 3, VerticalAlignment = VerticalAlignment.Center };
        details.Children.Add(new TextBlock { Text = name, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
        details.Children.Add(new TextBlock
        {
            Text = ambiguousPath
                ? WinUIStrings.Translate("اسم مكرر؛ أُوقفت العمليات لحماية الملف", "Duplicate name; actions disabled for safety")
                : directory ? WinUIStrings.Translate("مجلد", "Folder") : FormatSize(entry["size"]?.GetValue<long>() ?? 0),
            FontSize = 11,
            TextWrapping = TextWrapping.Wrap,
            Foreground = ambiguousPath ? WinUIHelpers.Brush("SystemFillColorCautionBrush") : WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        Grid.SetColumn(details, 1);
        row.Children.Add(details);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, VerticalAlignment = VerticalAlignment.Center };
        if (ambiguousPath)
        {
            Grid.SetColumn(actions, 2);
            row.Children.Add(actions);
            _rows.Children.Add(CreateFileCard(row));
            return;
        }
        if (directory)
        {
            var open = new Button { Content = WinUIStrings.Translate("فتح", "Open") };
            open.Click += async (_, _) => await LoadAsync(path);
            actions.Children.Add(open);
        }
        else
        {
            var mime = entry["mime"]?.GetValue<string>() ?? "";
            if (mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase)
                || mime.StartsWith("video/", StringComparison.OrdinalIgnoreCase))
            {
                var preview = new Button { Content = WinUIStrings.Translate("معاينة", "Preview") };
                preview.Click += async (_, _) => await PreviewMediaAsync(path, name);
                actions.Children.Add(preview);
            }
            var openFile = new Button { Content = WinUIStrings.Translate("فتح", "Open") };
            openFile.Click += async (_, _) => await OpenFileAsync(path, name);
            actions.Children.Add(openFile);
            var download = new Button { Content = WinUIStrings.Translate("تنزيل", "Download") };
            download.Click += async (_, _) => await DownloadAsync(path, name);
            actions.Children.Add(download);
        }
        var copy = new Button { Content = WinUIStrings.Translate("نسخ", "Copy") };
        copy.Click += (_, _) =>
        {
            _copiedPath = path;
            _pasteButton.IsEnabled = true;
            _status.Text = WinUIStrings.Translate($"تم نسخ {name}; افتح المجلد الهدف واضغط لصق.", $"Copied {name}; open the destination folder and choose Paste.");
        };
        actions.Children.Add(copy);
        var rename = new Button { Content = WinUIStrings.Translate("إعادة تسمية", "Rename") };
        rename.Click += async (_, _) => await RenameAsync(path, name);
        actions.Children.Add(rename);
        var delete = new Button { Content = WinUIStrings.Translate("حذف", "Delete") };
        delete.Click += async (_, _) => await DeleteAsync(path, name);
        actions.Children.Add(delete);
        Grid.SetColumn(actions, 2); row.Children.Add(actions);
        _rows.Children.Add(CreateFileCard(row));
    }

    private async Task PreviewMediaAsync(string path, string name)
    {
        try
        {
            _status.Text = WinUIStrings.Translate("جاري تجهيز صورة معاينة من الهاتف…", "Preparing a media preview from the phone…");
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "thumbnail", path }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذرت معاينة الملف.");
            var encoded = result["thumbnail_base64"]?.GetValue<string>()
                ?? throw new InvalidOperationException("لم تصل بيانات المعاينة.");
            var bytes = Convert.FromBase64String(encoded);
            var stream = new Windows.Storage.Streams.InMemoryRandomAccessStream();
            using (var writer = new Windows.Storage.Streams.DataWriter(stream))
            {
                writer.WriteBytes(bytes);
                await writer.StoreAsync();
                writer.DetachStream();
            }
            stream.Seek(0);
            var bitmap = new BitmapImage();
            await bitmap.SetSourceAsync(stream);
            var image = new Image
            {
                Source = bitmap,
                Stretch = Stretch.Uniform,
                MaxWidth = 900,
                MaxHeight = 620,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
            AutomationProperties.SetName(image, name);
            var dialog = new ContentDialog
            {
                Title = name,
                Content = new ScrollViewer { Content = image, MaxHeight = 660, MaxWidth = 920 },
                CloseButtonText = WinUIStrings.Translate("إغلاق", "Close"),
                XamlRoot = XamlRoot,
            };
            await dialog.ShowAsync();
            _status.Text = WinUIStrings.Translate("المعاينة مؤقتة؛ لم يُنزّل الملف الأصلي.", "Preview is temporary; the original file was not downloaded.");
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذرت المعاينة: ", "Could not preview media: ") + ex.Message;
        }
    }

    private static Border CreateFileCard(UIElement content) => new()
    {
        Margin = new Thickness(0, 0, 0, 8),
        CornerRadius = new CornerRadius(14),
        BorderThickness = new Thickness(1),
        BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
        Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
        Child = content,
    };

    private async Task OpenFileAsync(string path, string name)
    {
        if (!TryBeginOperation(WinUIStrings.Translate($"جاري فتح {name} من الهاتف…", $"Opening {name} from phone…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "open", path }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذر فتح الملف من الهاتف.");

            if (result["mode"]?.GetValue<string>() == "chunked_required")
            {
                var download = await _bridge.CallAsync("phone_files_download", new { path }, requestTimeoutOverride: FileDownloadTimeout) as JsonObject;
                var localPath = download?["local_path"]?.GetValue<string>();
                if (download?["success"]?.GetValue<bool>() != true || string.IsNullOrWhiteSpace(localPath) || !File.Exists(localPath))
                    throw new InvalidOperationException(download?["error"]?.GetValue<string>() ?? "تعذر نقل الملف الكبير من الهاتف.");
                Process.Start(new ProcessStartInfo(localPath) { UseShellExecute = true });
                _status.Text = WinUIStrings.Translate("تم فتح نسخة مؤقتة من ملف الهاتف.", "Opened a temporary copy of the phone file.");
                return;
            }

            var encoded = result["content_base64"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(encoded)) throw new InvalidDataException(WinUIStrings.Translate("رد الهاتف لا يحتوي الملف.", "The phone response did not include the file."));
            var picker = new FileSavePicker { SuggestedFileName = result["name"]?.GetValue<string>() ?? name };
            var extension = Path.GetExtension(name);
            picker.FileTypeChoices.Add(WinUIStrings.Translate("الملف", "File"), new List<string> { string.IsNullOrEmpty(extension) ? ".bin" : extension });
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
            var destination = await picker.PickSaveFileAsync();
            if (destination is null) { _status.Text = WinUIStrings.Translate("أُلغي فتح الملف.", "Open cancelled."); return; }
            File.WriteAllBytes(destination.Path, Convert.FromBase64String(encoded));
            Process.Start(new ProcessStartInfo(destination.Path) { UseShellExecute = true });
            _status.Text = WinUIStrings.Translate($"تم فتح نسخة من الهاتف: {destination.Path}", $"Opened a copy from the phone: {destination.Path}");
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر فتح الملف: ", "Could not open the file: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task DownloadAsync(string path, string name)
    {
        if (!TryBeginOperation(WinUIStrings.Translate($"جاري تنزيل {name}…", $"Downloading {name}…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_download", new { path }, requestTimeoutOverride: FileDownloadTimeout) as JsonObject;
            var localPath = result?["local_path"]?.GetValue<string>();
            if (result?["success"]?.GetValue<bool>() != true || string.IsNullOrWhiteSpace(localPath) || !File.Exists(localPath))
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "لم يصل الملف من الهاتف.");
            var settings = await _bridge.CallAsync("get_settings") as JsonObject;
            var directory = settings?["phone_download_directory"]?.GetValue<string>() ?? "";
            if (string.IsNullOrWhiteSpace(directory))
                directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "DeskTwin");
            Directory.CreateDirectory(directory);
            var safeName = string.Concat(Path.GetFileName(name).Select(character =>
                Path.GetInvalidFileNameChars().Contains(character) ? '_' : character));
            if (string.IsNullOrWhiteSpace(safeName)) safeName = "phone-file";
            var destination = Path.Combine(directory, safeName);
            var index = 1;
            while (File.Exists(destination))
            {
                var extension = Path.GetExtension(safeName);
                destination = Path.Combine(directory, $"{Path.GetFileNameWithoutExtension(safeName)} ({index++}){extension}");
            }
            File.Copy(localPath, destination, overwrite: false);
            _status.Text = WinUIStrings.Translate($"تم حفظ الملف: {destination}", $"File saved: {destination}");
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر تنزيل الملف: ", "Could not download the file: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task UploadAsync()
    {
        if (!TryBeginOperation(WinUIStrings.Translate("اختَر ملفات لإرسالها للهاتف…", "Choose files to send to your phone…"))) return;
        try
        {
            var picker = new FileOpenPicker();
            picker.FileTypeFilter.Add("*");
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
            var files = await picker.PickMultipleFilesAsync();
            await UploadFilesAsync(files, operationStarted: true);
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر إرسال الملف: ", "Could not send the file: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task UploadFilesAsync(IReadOnlyList<StorageFile> files, bool operationStarted = false)
    {
        if (files.Count == 0) return;
        if (!operationStarted && !TryBeginOperation(WinUIStrings.Translate("جاري تجهيز الملفات…", "Preparing files…"))) return;
        try
        {
            for (var index = 0; index < files.Count; index++)
            {
                var file = files[index];
                _status.Text = WinUIStrings.Translate(
                    $"جاري إرسال {file.Name} ({index + 1}/{files.Count}) للهاتف…",
                    $"Sending {file.Name} ({index + 1}/{files.Count}) to phone…");
                var result = await _bridge.CallAsync("phone_files_upload", new { local_path = file.Path, destination = _path.Text }, requestTimeoutOverride: FileUploadTimeout) as JsonObject;
                if (result?["success"]?.GetValue<bool>() != true)
                    throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? $"فشل إرسال {file.Name}.");
            }
            _status.Text = WinUIStrings.Translate(
                $"تم إرسال {files.Count} ملف للهاتف.",
                $"Sent {files.Count} file(s) to the phone.");
            await LoadAsync(_path.Text, operationReload: true);
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر إرسال الملفات: ", "Could not send files: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task PasteAsync()
    {
        if (string.IsNullOrWhiteSpace(_copiedPath)) { _status.Text = WinUIStrings.Translate("انسخ ملفًا أو مجلدًا أولًا.", "Copy a file or folder first."); return; }
        if (!TryBeginOperation(WinUIStrings.Translate("جاري لصق العنصر في الهاتف…", "Pasting item on phone…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "copy", path = _copiedPath, destination = _path.Text }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "تعذر نسخ العنصر.");
            _copiedPath = null;
            _pasteButton.IsEnabled = false;
            _status.Text = WinUIStrings.Translate("تم النسخ.", "Copied.");
            await LoadAsync(_path.Text, operationReload: true);
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر نسخ العنصر: ", "Could not copy the item: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task RenameAsync(string path, string oldName)
    {
        var input = new TextBox { Text = oldName, MinWidth = 320 };
        var dialog = new ContentDialog { Title = WinUIStrings.Translate("إعادة تسمية", "Rename"), Content = input, PrimaryButtonText = WinUIStrings.Translate("حفظ", "Save"), CloseButtonText = WinUIStrings.Translate("إلغاء", "Cancel"), XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || string.IsNullOrWhiteSpace(input.Text)) return;
        if (!TryBeginOperation(WinUIStrings.Translate("جاري إعادة التسمية…", "Renaming…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "rename", path, new_name = input.Text.Trim() }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true) throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? WinUIStrings.Translate("فشلت إعادة التسمية.", "Rename failed."));
            await LoadAsync(_path.Text, operationReload: true);
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذرت إعادة التسمية: ", "Could not rename: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private async Task DeleteAsync(string path, string name)
    {
        var dialog = new ContentDialog { Title = WinUIStrings.Translate("حذف من الهاتف؟", "Delete from phone?"), Content = WinUIStrings.Translate($"سيُنقل {name} إلى سلة محذوفات الهاتف إذا كان ذلك مدعومًا.", $"{name} will be moved to the phone trash if supported."), PrimaryButtonText = WinUIStrings.Translate("حذف", "Delete"), CloseButtonText = WinUIStrings.Translate("إلغاء", "Cancel"), XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        if (!TryBeginOperation(WinUIStrings.Translate("جاري حذف العنصر…", "Deleting item…"))) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "delete", path }, requestTimeoutOverride: FileOperationTimeout) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true) throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? WinUIStrings.Translate("فشل الحذف.", "Delete failed."));
            await LoadAsync(_path.Text, operationReload: true);
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر حذف العنصر: ", "Could not delete the item: ") + ex.Message; }
        finally { _operationBusy = false; }
    }

    private bool TryBeginOperation(string status)
    {
        if (_busy || _operationBusy)
        {
            _status.Text = WinUIStrings.Translate("هناك عملية ملفات جارية بالفعل.", "A file operation is already in progress.");
            return false;
        }
        _operationBusy = true;
        _status.Text = status;
        return true;
    }

    private static string ParentPath(string path)
    {
        var index = path.LastIndexOf('/');
        return index <= 0 ? "" : path[..index];
    }

    private static string FormatSize(long size)
    {
        double value = size;
        var units = new[] { "B", "KB", "MB", "GB", "TB" };
        var index = 0;
        while (value >= 1024 && index < units.Length - 1) { value /= 1024; index++; }
        return index == 0 ? $"{size} B" : $"{value:0.0} {units[index]}";
    }
}
