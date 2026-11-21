using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;
using XamlRectangle = Microsoft.UI.Xaml.Shapes.Rectangle;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text.Json.Nodes;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using Windows.Graphics.Imaging;
using WinRT.Interop;

namespace DeskTwin.WinUI;

public sealed partial class MainWindow : Window
{
    private readonly DesktopBridge _bridge;
    private readonly AppWindow _appWindow;
    private readonly TaskbarTrayIcon _trayIcon;
    private readonly DispatcherTimer _refreshTimer = new() { Interval = TimeSpan.FromSeconds(5) };
    private ElementTheme _currentTheme = ElementTheme.Dark;
    private string _currentLanguage = "ar";
    private string _activePage = "dashboard";
    private readonly List<string> _navigationHistory = new() { "dashboard" };
    private int _pendingApprovalsCount;
    private bool _uiInitialized;
    private bool _suppressNavigationSelection;
    private bool _startupDialogsReady;
    private string? _pendingIncompleteTransferWarning;
    private bool _refreshInProgress;
    private bool _serviceToggleInProgress;
    private bool _bridgeUnavailable;
    private bool _allowWindowClose;
    private int _shutdownStarted;
    private bool _hasApprovalSnapshot;
    private string _chatDraft = "";
    private PhoneFilesPageState? _phoneFilesPageState;

    public MainWindow()
    {
        InitializeComponent();
        // Apply an explicit theme before the first frame is presented. This
        // prevents the system's light default from flashing behind dark text.
        ApplyTheme(_currentTheme);
        Title = "DeskTwin";
        SystemBackdrop = new MicaBackdrop();
        _appWindow = GetAppWindow();
        _appWindow.Resize(new Windows.Graphics.SizeInt32(1180, 780));

        _trayIcon = new TaskbarTrayIcon(
            WindowNative.GetWindowHandle(this),
            WinUIStrings.Translate("فتح DeskTwin", "Open DeskTwin"),
            WinUIStrings.Translate("خروج", "Exit"),
            RestoreFromTray,
            () => _ = ExitApplicationAsync());
        _appWindow.Closing += (sender, args) =>
        {
            if (_allowWindowClose) return;
            args.Cancel = true;
            _ = ExitApplicationAsync();
        };

        try
        {
            var notifications = AppNotificationManager.Default;
            notifications.NotificationInvoked += (_, _) => DispatcherQueue.TryEnqueue(RestoreFromTray);
            notifications.Register();
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"DeskTwin Windows notifications could not be registered: {ex.Message}");
        }

        _bridge = new DesktopBridge();
        _bridge.EventReceived += OnBridgeEvent;
        RootGrid.Loaded += RootGrid_Loaded;
        Navigation.BackRequested += Navigation_BackRequested;
        _refreshTimer.Tick += async (_, _) => await RefreshStatusAsync();
        Closed += (_, _) =>
        {
            PhoneAssistantWorkspacePage.ClearTemporaryMediaCache();
            _refreshTimer.Stop();
        };
    }

    private async Task ExitApplicationAsync()
    {
        if (Interlocked.Exchange(ref _shutdownStarted, 1) != 0) return;
        _refreshTimer.Stop();
        _trayIcon.Dispose();
        PhoneAssistantWorkspacePage.ClearTemporaryMediaCache();
        using var saveTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        try
        {
            await _bridge.CallAsync("set_setting", new { key = "window_width", value = Math.Clamp(_appWindow.Size.Width, 880, 5000) }, saveTimeout.Token);
            await _bridge.CallAsync("set_setting", new { key = "window_height", value = Math.Clamp(_appWindow.Size.Height, 600, 5000) }, saveTimeout.Token);
            await _bridge.CallAsync("set_setting", new { key = "sidebar_collapsed", value = !Navigation.IsPaneOpen }, saveTimeout.Token);
            await _bridge.CallAsync("set_setting", new { key = "last_active_view", value = _activePage }, saveTimeout.Token);
        }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"DeskTwin settings save on exit failed: {ex.Message}"); }

        try { await _bridge.DisposeAsync(); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"DeskTwin service shutdown failed: {ex.Message}"); }
        try { AppNotificationManager.Default.Unregister(); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"DeskTwin Windows notifications could not be unregistered: {ex.Message}"); }

        _allowWindowClose = true;
        Close();
    }

    private void RestoreFromTray()
    {
        _appWindow.Show();
        Activate();
    }

    private void ShowApprovalToast(int count)
    {
        try
        {
            var notification = new AppNotificationBuilder()
                .AddText(WinUIStrings.Translate("طلب موافقة جديد", "New approval request"))
                .AddText(WinUIStrings.Translate(
                    $"لديك {count} طلبات معلّقة من الهاتف.",
                    $"You have {count} pending request(s) from the phone."))
                .BuildNotification();
            AppNotificationManager.Default.Show(notification);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"DeskTwin approval notification failed: {ex.Message}");
        }
    }

    public async Task InitializeAsync()
    {
        JsonObject? settings = null;
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            settings = await _bridge.CallAsync("get_settings", cancellationToken: timeout.Token) as JsonObject;
            ApplyTheme(settings?["theme_mode"]?.GetValue<string>() == "light" ? ElementTheme.Light : ElementTheme.Dark);
            ApplyLanguage(settings?["language"]?.GetValue<string>() ?? "ar", refreshPage: false);
        }
        catch (Exception ex)
        {
            // Keep the Python default (dark) if settings cannot be read; never
            // expose light surfaces with dark-theme white text at first launch.
            ApplyTheme(ElementTheme.Dark);
            ApplyLanguage("ar", refreshPage: false);
            FooterText.Text = $"تعذر تحميل تفضيلات المظهر؛ استُخدم الداكن مؤقتًا: {ex.Message}";
        }

        var width = Math.Clamp(settings?["window_width"]?.GetValue<int>() ?? 1180, 880, 5000);
        var height = Math.Clamp(settings?["window_height"]?.GetValue<int>() ?? 780, 600, 5000);
        _appWindow.Resize(new Windows.Graphics.SizeInt32(width, height));
        Navigation.IsPaneOpen = settings?["sidebar_collapsed"]?.GetValue<bool>() != true;
        var savedPage = settings?["last_active_view"]?.GetValue<string>() ?? "dashboard";
        var selectedPage = Navigation.MenuItems.OfType<NavigationViewItem>()
            .FirstOrDefault(item => Equals(item.Tag, savedPage)) ?? Navigation.MenuItems[0] as NavigationViewItem;
        var selectedPageTag = selectedPage?.Tag as string ?? "dashboard";
        _navigationHistory.Clear();
        _navigationHistory.Add("dashboard");
        if (selectedPageTag != "dashboard") _navigationHistory.Add(selectedPageTag);
        UpdateBackNavigationState();
        Navigation.SelectedItem = selectedPage;
        if (ContentFrame.Content is null) ShowPage(selectedPageTag, persist: false);
        _uiInitialized = true;
        _refreshTimer.Start();
        _ = RefreshStatusAsync();

    }

    private async Task CaptureAllPagesAsync()
    {
        var pages = Navigation.MenuItems.OfType<NavigationViewItem>()
            .Select(item => item.Tag as string).Where(tag => !string.IsNullOrWhiteSpace(tag)).ToList();
        foreach (var page in pages)
        {
            ShowPage(page!, persist: false, trackHistory: false);
            await Task.Delay(250);
            var bitmap = new RenderTargetBitmap();
            await bitmap.RenderAsync(RootGrid);
            var pixels = await bitmap.GetPixelsAsync();
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskTwin", "Screenshots");
            Directory.CreateDirectory(root);
            var folder = await StorageFolder.GetFolderFromPathAsync(root);
            var file = await folder.CreateFileAsync($"page-{page}-{DateTime.Now:yyyyMMdd-HHmmss}.png", CreationCollisionOption.GenerateUniqueName);
            using var stream = await file.OpenAsync(FileAccessMode.ReadWrite);
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
            encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied, (uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight, 96, 96, pixels.ToArray());
            await encoder.FlushAsync();
        }
        FooterText.Text = WinUIStrings.Translate("تم حفظ لقطات كل الصفحات", "Screenshots for all pages saved");
    }

    private async void RootGrid_Loaded(object sender, RoutedEventArgs args)
    {
        RootGrid.Loaded -= RootGrid_Loaded;
        // Keep the saved explicit theme; falling back to Default here can flash
        // the system light theme before the first page has finished rendering.
        ApplyTheme(_currentTheme);
        await PlayStartupIntroIfEnabledAsync();
        _startupDialogsReady = true;
        if (Environment.GetEnvironmentVariable("DESKTWIN_CAPTURE_ALL_PAGES") == "1")
            _ = CaptureAllPagesAsync();
        if (_pendingIncompleteTransferWarning is not null)
        {
            var warning = _pendingIncompleteTransferWarning;
            _pendingIncompleteTransferWarning = null;
            await ShowIncompleteTransfersWarningAsync(warning);
        }
    }

    private async void CaptureScreenshotButton_Click(object sender, RoutedEventArgs args)
    {
        await CaptureAllPagesAsync();
    }

    private async Task CaptureUiScreenshotAsync()
    {
        CaptureScreenshotButton.IsEnabled = false;
        try
        {
            // RenderTargetBitmap captures the DeskTwin XAML tree only. It never
            // includes the desktop, other windows, or the user's screen.
            var bitmap = new RenderTargetBitmap();
            await bitmap.RenderAsync(RootGrid);
            var pixels = await bitmap.GetPixelsAsync();
            var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskTwin", "Screenshots");
            Directory.CreateDirectory(root);
            var folder = await StorageFolder.GetFolderFromPathAsync(root);
            var fileName = $"desktwin-{DateTime.Now:yyyyMMdd-HHmmss}.png";
            var file = await folder.CreateFileAsync(fileName, CreationCollisionOption.GenerateUniqueName);
            using var stream = await file.OpenAsync(FileAccessMode.ReadWrite);
            var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
            encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied,
                (uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight, 96, 96, pixels.ToArray());
            await encoder.FlushAsync();

            var dialog = new ContentDialog
            {
                Title = WinUIStrings.Translate("تم حفظ لقطة الواجهة", "Interface screenshot saved"),
                Content = file.Path,
                CloseButtonText = WinUIStrings.Translate("إغلاق", "Close"),
                XamlRoot = RootGrid.XamlRoot,
                DefaultButton = ContentDialogButton.Close
            };
            await dialog.ShowAsync();
        }
        catch (Exception ex)
        {
            FooterText.Text = WinUIStrings.Translate($"تعذر حفظ اللقطة: {ex.Message}", $"Screenshot failed: {ex.Message}");
        }
        finally
        {
            CaptureScreenshotButton.IsEnabled = true;
        }
    }

    private AppWindow GetAppWindow()
    {
        var hwnd = WindowNative.GetWindowHandle(this);
        var windowId = Win32Interop.GetWindowIdFromWindow(hwnd);
        return AppWindow.GetFromWindowId(windowId);
    }

    private void Navigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_suppressNavigationSelection) return;
        if (args.SelectedItem is not NavigationViewItem item || item.Tag is not string page) return;
        if (page == _activePage && ContentFrame.Content is not null) return;
        ShowPage(page, persist: _uiInitialized);
    }

    private void Navigation_BackRequested(NavigationView sender, NavigationViewBackRequestedEventArgs args)
    {
        if (_navigationHistory.Count <= 1) return;
        _navigationHistory.RemoveAt(_navigationHistory.Count - 1);
        var page = _navigationHistory[^1];
        var item = Navigation.MenuItems.OfType<NavigationViewItem>()
            .FirstOrDefault(menuItem => Equals(menuItem.Tag, page));
        _suppressNavigationSelection = true;
        try
        {
            if (item is not null) Navigation.SelectedItem = item;
        }
        finally { _suppressNavigationSelection = false; }
        ShowPage(page, persist: true, trackHistory: false);
    }

    private void ShowPage(string page, bool persist = true, bool trackHistory = true)
    {
        if (trackHistory && persist && _uiInitialized && page != _activePage)
        {
            if (page == "dashboard")
            {
                _navigationHistory.Clear();
                _navigationHistory.Add("dashboard");
            }
            else
            {
                var existingIndex = _navigationHistory.IndexOf(page);
                if (existingIndex >= 0)
                    _navigationHistory.RemoveRange(existingIndex + 1, _navigationHistory.Count - existingIndex - 1);
                else
                    _navigationHistory.Add(page);
            }
        }
        if (ContentFrame.Content is DashboardPage previousDashboard) previousDashboard.OnNavigatedAway();
        if (ContentFrame.Content is ChatPage previousChat) _chatDraft = previousChat.Draft;
        if (ContentFrame.Content is PhoneFilesPage previousPhoneFiles) _phoneFilesPageState = previousPhoneFiles.CaptureState();
        _activePage = page;
        UpdateHeaderPageTitle();
        var phoneWorkspace = page == "phone_files";
        AppHeader.Visibility = phoneWorkspace ? Visibility.Collapsed : Visibility.Visible;
        FooterText.Visibility = phoneWorkspace ? Visibility.Collapsed : Visibility.Visible;
        Navigation.IsPaneVisible = !phoneWorkspace;
        ContentFrame.Content = page switch
        {
            "dashboard" => new DashboardPage(_bridge, _currentTheme),
            "folders" => new FoldersPage(_bridge),
            "devices" => new DevicesPage(_bridge),
            "approvals" => new ApprovalsPage(_bridge),
            "activity" => new ActivityPage(_bridge),
            "security" => new SecurityPage(_bridge, () => NavigateToPage("devices")),
            "phone_files" => new PhoneAssistantWorkspacePage(
                _bridge,
                () => NavigateToPage("dashboard")),
            "chat" => new ChatPage(_bridge, _chatDraft),
            "transfers" => new TransfersPage(_bridge),
            "integration" => new IntegrationPage(_bridge),
            "settings" => new SettingsPage(_bridge, ApplyTheme, ApplyLanguageFromSettings, PlayIntroVideoAsync),
            _ => new ComingSoonPage(),
        };
        WinUIStrings.Localize(ContentFrame.Content as UIElement);
        UpdateBackNavigationState();
        if (persist) _ = PersistSettingAsync("last_active_view", page);
    }

    private void NavigateToPage(string page)
    {
        var item = Navigation.MenuItems.OfType<NavigationViewItem>()
            .FirstOrDefault(menuItem => Equals(menuItem.Tag, page));
        if (item is not null) Navigation.SelectedItem = item;
    }

    private void UpdateBackNavigationState()
    {
        var canGoBack = _navigationHistory.Count > 1;
        Navigation.IsBackEnabled = canGoBack;
        Navigation.IsBackButtonVisible = canGoBack
            ? NavigationViewBackButtonVisible.Visible
            : NavigationViewBackButtonVisible.Collapsed;
    }

    private async Task PersistSettingAsync(string key, object value)
    {
        try { await _bridge.CallAsync("set_setting", new { key, value }); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"DeskTwin setting {key} was not saved: {ex.Message}"); }
    }

    private void ApplyLanguageFromSettings(string language) => ApplyLanguage(language, refreshPage: true);

    private void ApplyLanguage(string language, bool refreshPage)
    {
        var normalized = language == "en" ? "en" : "ar";
        var changed = _currentLanguage != normalized;
        _currentLanguage = normalized;
        WinUIStrings.Language = normalized;
        RootGrid.FlowDirection = normalized == "ar" ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        HeaderSubtitle.Text = normalized == "ar" ? "ربط الكمبيوتر والهاتف بأمان" : "Secure phone and computer connection";
        FooterText.Text = normalized == "ar" ? "محلي وآمن • لا يوجد اتصال سحابي" : "Local and secure • No cloud connection";
        UpdateHeaderPageTitle();
        var labels = normalized == "ar"
            ? new Dictionary<string, string> { ["dashboard"] = "الرئيسية", ["folders"] = "المجلدات", ["devices"] = "الأجهزة", ["approvals"] = "الموافقات", ["activity"] = "سجل النشاط", ["phone_files"] = "ملفات الهاتف", ["security"] = "الأمان", ["chat"] = "المحادثة", ["transfers"] = "النقل", ["integration"] = "التكامل", ["settings"] = "الإعدادات" }
            : new Dictionary<string, string> { ["dashboard"] = "Home", ["folders"] = "Folders", ["devices"] = "Devices", ["approvals"] = "Approvals", ["activity"] = "Activity", ["phone_files"] = "Phone files", ["security"] = "Security", ["chat"] = "Chat", ["transfers"] = "Transfers", ["integration"] = "Integration", ["settings"] = "Settings" };
        foreach (var menuItem in Navigation.MenuItems.OfType<NavigationViewItem>())
            if (menuItem.Tag is string tag && labels.TryGetValue(tag, out var label)) menuItem.Content = label;
        UpdateApprovalsBadge();
        if (refreshPage && changed) ShowPage(_activePage, persist: false);
    }

    private void UpdateHeaderPageTitle()
    {
        var labels = _currentLanguage == "en"
            ? new Dictionary<string, string>
            {
                ["dashboard"] = "Home", ["folders"] = "Folders", ["devices"] = "Devices",
                ["approvals"] = "Approvals", ["activity"] = "Activity", ["settings"] = "Settings",
                ["security"] = "Security", ["chat"] = "Chat", ["integration"] = "Integration",
                ["phone_files"] = "Phone files",
                ["transfers"] = "Transfers",
            }
            : new Dictionary<string, string>
            {
                ["dashboard"] = "الرئيسية", ["folders"] = "المجلدات", ["devices"] = "الأجهزة",
                ["approvals"] = "الموافقات", ["activity"] = "سجل النشاط", ["settings"] = "الإعدادات",
                ["security"] = "الأمان", ["chat"] = "المحادثة", ["integration"] = "التكامل",
                ["phone_files"] = "ملفات الهاتف",
                ["transfers"] = "النقل",
            };
        HeaderPageTitle.Text = labels.TryGetValue(_activePage, out var label) ? label : "DeskTwin";
    }

    private void UpdateApprovalsBadge()
    {
        var item = Navigation.MenuItems.OfType<NavigationViewItem>()
            .FirstOrDefault(menuItem => Equals(menuItem.Tag, "approvals"));
        if (item is null) return;
        var label = _currentLanguage == "en" ? "Approvals" : "الموافقات";
        item.Content = _pendingApprovalsCount > 0 ? $"{label} ({_pendingApprovalsCount})" : label;
    }

    private async Task CheckIncompleteTransfersAsync()
    {
        try
        {
            var result = await _bridge.CallAsync("get_incomplete_transfers") as JsonObject;
            var total = result?["total"]?.GetValue<int>() ?? 0;
            if (total <= 0) return;

            var items = result?["items"] as JsonArray ?? new JsonArray();
            var lines = new List<string>();
            foreach (var node in items)
            {
                if (node is not JsonObject item) continue;
                var path = item["file_path"]?.GetValue<string>() ?? "";
                var received = item["bytes_received"]?.GetValue<long>() ?? 0;
                var size = item["total_size_bytes"]?.GetValue<long>() ?? 0;
                var percent = size > 0 ? (int)Math.Clamp(received * 100d / size, 0, 100) : 0;
                lines.Add($"• {Path.GetFileName(path)} — {percent}%");
            }
            var message = WinUIStrings.Translate(
                "يوجد رفع ملفات كبير غير مكتمل:\n\n",
                "There are incomplete large-file uploads:\n\n") + string.Join(Environment.NewLine, lines);
            if (total > items.Count)
                message += Environment.NewLine + WinUIStrings.Translate($"(+{total - items.Count} ملفات أخرى)", $"(+{total - items.Count} more)");
            message += Environment.NewLine + Environment.NewLine + WinUIStrings.Translate(
                "افتح DeskTwin على الهاتف وهو على الشبكة نفسها ليكمل الرفع تلقائيًا.",
                "Open DeskTwin on the phone on the same network to resume uploads automatically.");

            if (!_startupDialogsReady || RootGrid.XamlRoot is null)
            {
                _pendingIncompleteTransferWarning = message;
                return;
            }
            await ShowIncompleteTransfersWarningAsync(message);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"DeskTwin incomplete-transfer check failed: {ex.Message}");
        }
    }

    private async Task ShowIncompleteTransfersWarningAsync(string message)
    {
        var dialog = new ContentDialog
        {
            Title = WinUIStrings.Translate("يوجد رفع غير مكتمل", "Incomplete uploads"),
            Content = new ScrollViewer { MaxHeight = 420, Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap } },
            PrimaryButtonText = WinUIStrings.Translate("عرض عمليات النقل", "View transfers"),
            CloseButtonText = WinUIStrings.Translate("إغلاق", "Close"),
            XamlRoot = RootGrid.XamlRoot,
        };
        try
        {
            if (await dialog.ShowAsync() == ContentDialogResult.Primary)
                NavigateToPage("transfers");
        }
        catch (InvalidOperationException ex)
        {
            System.Diagnostics.Debug.WriteLine($"DeskTwin warning dialog was deferred: {ex.Message}");
            _pendingIncompleteTransferWarning = message;
        }
    }

    public async Task PlayStartupIntroIfEnabledAsync()
    {
        try
        {
            var settings = await _bridge.CallAsync("get_settings") as JsonObject;
            if (settings?["intro_mode"]?.GetValue<string>() != "auto") return;
            var path = settings["custom_intro_video"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(path)) return;
            if (!File.Exists(path))
            {
                FooterText.Text = "فيديو المقدمة المحدد غير موجود؛ اختر فيديو آخر من الإعدادات.";
                return;
            }
            await PlayIntroVideoAsync(path);
        }
        catch (Exception ex) { FooterText.Text = $"تعذر تشغيل مقدمة الفيديو: {ex.Message}"; }
    }

    public async Task PlayIntroVideoAsync(string path)
    {
        if (!File.Exists(path)) throw new FileNotFoundException("ملف فيديو المقدمة غير موجود.", path);
        if (RootGrid.XamlRoot is null) throw new InvalidOperationException("نافذة DeskTwin لم تجهز لتشغيل الفيديو بعد.");

        var file = await StorageFile.GetFileFromPathAsync(Path.GetFullPath(path));
        var player = new MediaPlayer();
        var video = new MediaPlayerElement
        {
            AreTransportControlsEnabled = true,
            AutoPlay = true,
            MinWidth = 560,
            MinHeight = 315,
        };
        video.SetMediaPlayer(player);
        player.Source = MediaSource.CreateFromStorageFile(file);
        var dialog = new ContentDialog
        {
            Title = "مقدمة DeskTwin",
            Content = video,
            CloseButtonText = "إغلاق",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = RootGrid.XamlRoot,
        };
        player.MediaEnded += (_, _) => DispatcherQueue.TryEnqueue(() =>
        {
            try { dialog.Hide(); } catch { }
        });
        player.MediaFailed += (_, error) => DispatcherQueue.TryEnqueue(() =>
        {
            FooterText.Text = $"تعذر فك ترميز فيديو المقدمة: {error.Error}";
            try { dialog.Hide(); } catch { }
        });
        try { await dialog.ShowAsync(); }
        finally
        {
            player.Pause();
            player.Dispose();
        }
    }

    private void ApplyTheme(ElementTheme theme)
    {
        _currentTheme = theme;
        WinUIHelpers.SetTheme(theme);
        RootGrid.RequestedTheme = theme;
        RootGrid.Background = WinUIHelpers.Brush("AppBackground");
        Navigation.RequestedTheme = theme;
        Navigation.Background = WinUIHelpers.Brush("AppBackground");
        ContentFrame.RequestedTheme = theme;
        ContentFrame.Background = WinUIHelpers.Brush("AppBackground");
        if (ContentFrame.Content is DashboardPage dashboard) dashboard.ApplyTheme(theme);
    }

    private async Task RefreshStatusAsync()
    {
        if (_refreshInProgress) return;
        _refreshInProgress = true;
        JsonNode? state;
        try
        {
            state = await _bridge.CallAsync("get_state");
        }
        catch (Exception ex)
        {
            MarkBridgeUnavailable(ex.Message);
            _refreshInProgress = false;
            return;
        }

        _bridgeUnavailable = false;
        try
        {
            var running = state?["running"]?.GetValue<bool>() == true;
            var starting = state?["starting"]?.GetValue<bool>() == true;
            var phoneConnected = state?["phone_connected"]?.GetValue<bool>() == true;
            var previousApprovalCount = _pendingApprovalsCount;
            _pendingApprovalsCount = state?["pending_approvals_count"]?.GetValue<int>() ?? 0;
            if (_hasApprovalSnapshot && _pendingApprovalsCount > previousApprovalCount)
                ShowApprovalToast(_pendingApprovalsCount);
            _hasApprovalSnapshot = true;
            UpdateApprovalsBadge();
            if (_startupDialogsReady && _pendingIncompleteTransferWarning is not null)
            {
                var warning = _pendingIncompleteTransferWarning;
                _pendingIncompleteTransferWarning = null;
                await ShowIncompleteTransfersWarningAsync(warning);
            }
            StatusText.Text = running
                ? phoneConnected
                    ? WinUIStrings.Translate("الهاتف متصل — الخدمة تعمل", "Phone connected — service running")
                    : WinUIStrings.Translate("الخدمة تعمل — بانتظار اتصال الهاتف", "Service running — waiting for phone")
                : starting
                    ? WinUIStrings.Translate("جاري تجهيز الخدمة…", "Preparing service…")
                    : WinUIStrings.Translate("الخدمة متوقفة", "Service stopped");
            if (ContentFrame.Content is ChatPage chatPage)
                chatPage.SetServiceState(running, starting, phoneConnected);
            StatusDot.Fill = new SolidColorBrush(running
                ? Windows.UI.Color.FromArgb(255, 38, 166, 124)
                : starting
                    ? Windows.UI.Color.FromArgb(255, 232, 170, 62)
                    : Windows.UI.Color.FromArgb(255, 145, 150, 160));
            ServiceToggleButton.Content = running
                ? WinUIStrings.Translate("إيقاف الخدمة", "Stop service")
                : starting
                    ? WinUIStrings.Translate("جاري التشغيل…", "Starting…")
                    : WinUIStrings.Translate("تشغيل الخدمة", "Start service");
            ServiceToggleButton.IsEnabled = !_bridgeUnavailable && !starting && !_serviceToggleInProgress;
            if (_serviceToggleInProgress)
                ServiceToggleButton.Content = WinUIStrings.Translate("جارٍ تغيير الحالة…", "Changing state…");
            try
            {
                if (ContentFrame.Content is DashboardPage dashboard) await dashboard.RefreshAsync();
                if (ContentFrame.Content is ChatPage chat) await chat.RefreshAsync();
                if (ContentFrame.Content is TransfersPage transfers) await transfers.RefreshAsync();
                if (ContentFrame.Content is FoldersPage folders) await folders.RefreshAsync();
                if (ContentFrame.Content is DevicesPage devices) await devices.RefreshAsync();
                if (ContentFrame.Content is ApprovalsPage approvals) await approvals.RefreshAsync();
                if (ContentFrame.Content is ActivityPage activity) await activity.RefreshCurrentAsync();
                if (ContentFrame.Content is SecurityPage security) await security.RefreshAsync();
                if (ContentFrame.Content is IntegrationPage integration) await integration.RefreshAsync();
                if (ContentFrame.Content is SettingsPage settings) await settings.RefreshBoundUserAsync();
                if (ContentFrame.Content is PhoneFilesPage phoneFiles) await phoneFiles.RefreshEventsAsync();
            }
            catch (Exception ex)
            {
                FooterText.Text = WinUIStrings.Translate("تعذر تحديث محتوى الصفحة: ", "Could not refresh page content: ") + ex.Message;
                if (ContentFrame.Content is DashboardPage dashboard)
                    dashboard.SetServiceMessage(FooterText.Text);
            }
        }
        catch (Exception ex)
        {
            FooterText.Text = WinUIStrings.Translate("تعذر تحديث حالة الواجهة: ", "Could not update interface state: ") + ex.Message;
        }
        finally
        {
            _refreshInProgress = false;
        }
    }

    private async void ServiceToggleButton_Click(object sender, RoutedEventArgs args)
    {
        if (_serviceToggleInProgress) return;
        _serviceToggleInProgress = true;
        ServiceToggleButton.IsEnabled = false;
        ServiceToggleButton.Content = WinUIStrings.Translate("جارٍ تغيير الحالة…", "Changing state…");
        try
        {
            var state = await _bridge.CallAsync("get_state");
            if (state?["starting"]?.GetValue<bool>() == true)
            {
                await RefreshStatusAsync();
                return;
            }
            if (state?["running"]?.GetValue<bool>() == true)
                await _bridge.CallAsync("stop_service");
            else
                await _bridge.CallAsync("start_service");
            await RefreshStatusAsync();
        }
        catch (Exception ex)
        {
            FooterText.Text = WinUIStrings.Translate("تعذر تغيير حالة الخدمة: ", "Could not change service state: ") + ex.Message;
        }
        finally
        {
            _serviceToggleInProgress = false;
            _ = RefreshStatusAsync();
        }
    }

    private void OnBridgeEvent(JsonObject message)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            if (message["event"]?.GetValue<string>() == "service_started")
            {
                var success = message["success"]?.GetValue<bool>() == true;
                StatusText.Text = success
                    ? WinUIStrings.Translate("الخدمة تعمل — بانتظار اتصال الهاتف", "Service running — waiting for phone")
                    : WinUIStrings.Translate("تعذر تشغيل الخدمة", "Could not start service");
                FooterText.Text = message["message"]?.GetValue<string>() ?? (success ? "الخدمة المحلية تعمل." : "راجع تفاصيل بدء الخدمة.");
                if (ContentFrame.Content is DashboardPage dashboard)
                {
                    dashboard.SetServiceMessage(FooterText.Text);
                    if (success) await dashboard.GenerateCodeAsync();
                    else dashboard.ShowPairingError(FooterText.Text);
                }
                await RefreshStatusAsync();
                if (success) await CheckIncompleteTransfersAsync();
            }
            else if (message["event"]?.GetValue<string>() == "log")
            {
                var log = message["message"]?.GetValue<string>() ?? "";
                FooterText.Text = log;
                if (ContentFrame.Content is DashboardPage dashboard) dashboard.SetServiceMessage(log);
            }
            else if (message["event"]?.GetValue<string>() == "bridge_error")
            {
                MarkBridgeUnavailable(message["message"]?.GetValue<string>() ?? "انقطع الاتصال بالخدمة المحلية.");
            }
            else if (message["event"]?.GetValue<string>() == "bridge_exited")
            {
                var detail = message["message"]?.GetValue<string>() ?? "أُغلقت خدمة DeskTwin المحلية.";
                MarkBridgeUnavailable(detail);
            }
        });
    }

    private void MarkBridgeUnavailable(string detail)
    {
        _bridgeUnavailable = true;
        StatusText.Text = WinUIStrings.Translate("انقطع الاتصال بالخدمة المحلية", "Local service unavailable");
        StatusDot.Fill = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 176, 42, 55));
        ServiceToggleButton.Content = WinUIStrings.Translate("الخدمة غير متاحة", "Service unavailable");
        ServiceToggleButton.IsEnabled = false;
        FooterText.Text = detail;
        if (ContentFrame.Content is ChatPage chatPage)
            chatPage.SetServiceState(false, false, false);
        if (ContentFrame.Content is DashboardPage dashboard)
            dashboard.SetServiceMessage(detail);
    }
}

public sealed class DashboardPage : Page
{
    private readonly TextBlock _status = new() { Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] };
    private readonly TextBlock _files = new();
    private readonly TextBlock _folders = new();
    private readonly TextBlock _devices = new();
    private readonly TextBlock _approvals = new();
    private readonly TextBlock _serviceMessage = new() { TextWrapping = TextWrapping.Wrap, FontSize = 12 };
    private readonly TextBlock _phoneConnectionStatus = new()
    {
        Text = "بانتظار التحقق من اتصال الهاتف",
        FontSize = 18,
        FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        TextWrapping = TextWrapping.Wrap,
    };
    private readonly TextBlock _pairingCode = new()
    {
        Text = "------",
        FontSize = 46,
        FontWeight = Microsoft.UI.Text.FontWeights.Bold,
        CharacterSpacing = 150,
        Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
    };
    private readonly Canvas _qr = new()
    {
        Width = 290,
        Height = 290,
        Background = new SolidColorBrush(Colors.White),
        UseLayoutRounding = true,
        IsHitTestVisible = false,
    };
    private readonly Image _qrImage = new()
    {
        Width = 290,
        Height = 290,
        Stretch = Stretch.Fill,
        IsHitTestVisible = false,
    };
    private readonly TextBlock _qrStatus = new()
    {
        Text = "سيظهر QR هنا بعد إنشاء الكود",
        FontSize = 11,
        TextAlignment = TextAlignment.Center,
        TextWrapping = TextWrapping.Wrap,
        Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
    };
    private readonly TextBlock _qrTimerStatus = new()
    {
        FontSize = 12,
        TextAlignment = TextAlignment.Center,
        Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
    };
    private readonly TextBlock _qrPlaceholder = new()
    {
        Text = "QR غير جاهز",
        FontSize = 14,
        FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
        TextAlignment = TextAlignment.Center,
        VerticalAlignment = VerticalAlignment.Center,
        HorizontalAlignment = HorizontalAlignment.Center,
        Foreground = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 75, 85, 99)),
    };
    private readonly Border _qrFrame;
    private ScrollViewer? _rootScroll;
    private readonly DispatcherTimer _pairingExpiryTimer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly Button _serviceToggleButton = new() { Content = "تشغيل الخدمة", MinWidth = 150 };
    private readonly Button _generatePairingButton = new() { Content = "إنشاء كود وQR", MinWidth = 150 };
    private DateTimeOffset? _pairingExpiresAt;
    private string? _currentPairingCode;
    private int _pairingStateVersion;
    private bool _serviceToggleInProgress;
    private bool _serviceStarting;
    private bool _pairingGenerationInProgress;
    private bool _dashboardRefreshing;

    public DashboardPage()
    {
        _qrFrame = new Border
        {
            Width = 306,
            Height = 306,
            Padding = new Thickness(8),
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(Colors.White),
            BorderThickness = new Thickness(1),
            Child = new Grid { Children = { _qr, _qrImage, _qrPlaceholder } },
        };
        _pairingExpiryTimer.Tick += (_, _) => UpdatePairingCountdown();
        Content = BuildContent();
    }

    public DashboardPage(DesktopBridge bridge, ElementTheme theme) : this()
    {
        _bridgeField = bridge;
        ApplyTheme(theme);
        _ = RefreshAsync();
    }

    private DesktopBridge _bridgeField { get; set; } = null!;

    private UIElement BuildContent()
    {
        _rootScroll = new ScrollViewer
        {
            Padding = new Thickness(34, 28, 34, 30),
            Background = WinUIHelpers.Brush("AppBackground"),
        };
        var stack = new StackPanel { Spacing = 20, MaxWidth = 1080, HorizontalAlignment = HorizontalAlignment.Stretch };
        stack.Children.Add(new TextBlock { Text = "نظرة عامة", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        var serviceCard = Card();
        var serviceRow = new Grid { ColumnSpacing = 20 };
        serviceRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        serviceRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var serviceInfo = new StackPanel { Spacing = 5 };
        serviceInfo.Children.Add(_status);
        serviceInfo.Children.Add(new TextBlock { Text = "خدمة محلية مشفّرة — لا حاجة لتشغيل نافذة أوامر منفصلة", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        serviceInfo.Children.Add(_serviceMessage);
        serviceRow.Children.Add(serviceInfo);
        _serviceToggleButton.VerticalAlignment = VerticalAlignment.Center;
        _serviceToggleButton.Click += async (_, _) => await ToggleServiceAsync();
        Grid.SetColumn(_serviceToggleButton, 1);
        serviceRow.Children.Add(_serviceToggleButton);
        serviceCard.Child = serviceRow;
        stack.Children.Add(serviceCard);

        var phoneConnectionCard = Card();
        var phoneConnectionInfo = new StackPanel { Spacing = 8 };
        phoneConnectionInfo.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("اتصال الهاتف بالكمبيوتر", "Phone connection"),
            Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"],
        });
        phoneConnectionInfo.Children.Add(_phoneConnectionStatus);
        phoneConnectionCard.Child = phoneConnectionInfo;
        stack.Children.Add(phoneConnectionCard);

        var stats = new Grid { ColumnSpacing = 14, RowSpacing = 14 };
        AddStat(stats, 0, "ملفات مفهرسة", _files);
        AddStat(stats, 1, "مجلدات مراقبة", _folders);
        AddStat(stats, 2, "أجهزة موثوقة", _devices);
        AddStat(stats, 3, "موافقات معلّقة", _approvals);
        stack.Children.Add(stats);

        var pairingCard = Card();
        var pairingGrid = new Grid { ColumnSpacing = 28, RowSpacing = 20 };
        var qrContent = new StackPanel { Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center };
        qrContent.Children.Add(_qrFrame);
        qrContent.Children.Add(_qrStatus);
        qrContent.Children.Add(_qrTimerStatus);
        pairingGrid.Children.Add(qrContent);
        var pairingInfo = new StackPanel { Spacing = 12, VerticalAlignment = VerticalAlignment.Center };
        pairingInfo.Children.Add(new TextBlock { Text = "اقتران الهاتف", Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"] });
        pairingInfo.Children.Add(new TextBlock { Text = "امسح QR أو أدخل الكود في تطبيق الهاتف. الكود صالح لدقيقتين.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        pairingInfo.Children.Add(new TextBlock { Text = "الكود الرقمي", FontSize = 13, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        pairingInfo.Children.Add(_pairingCode);
        _generatePairingButton.Click += async (_, _) => await GenerateCodeAsync();
        pairingInfo.Children.Add(_generatePairingButton);
        pairingGrid.Children.Add(pairingInfo);
        pairingCard.Child = pairingGrid;
        stack.Children.Add(pairingCard);
        _rootScroll.Content = stack;
        var appliedStatColumns = 0;
        bool? appliedCompactPadding = null;
        bool? appliedStackedPairing = null;
        _rootScroll.SizeChanged += (_, args) =>
        {
            var width = args.NewSize.Width;
            var compactPadding = width < 700;
            if (appliedCompactPadding != compactPadding)
            {
                _rootScroll.Padding = compactPadding
                    ? new Thickness(16, 18, 16, 20)
                    : new Thickness(34, 28, 34, 30);
                appliedCompactPadding = compactPadding;
            }

            var statColumns = width >= 960 ? 4 : width >= 560 ? 2 : 1;
            if (appliedStatColumns != statColumns)
            {
                stats.ColumnDefinitions.Clear();
                stats.RowDefinitions.Clear();
                for (var column = 0; column < statColumns; column++)
                    stats.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                var statRows = (stats.Children.Count + statColumns - 1) / statColumns;
                for (var row = 0; row < statRows; row++)
                    stats.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                for (var index = 0; index < stats.Children.Count; index++)
                {
                    if (stats.Children[index] is FrameworkElement statItem)
                    {
                        Grid.SetColumn(statItem, index % statColumns);
                        Grid.SetRow(statItem, index / statColumns);
                    }
                }
                appliedStatColumns = statColumns;
            }

            var stackedPairing = width < 780;
            if (appliedStackedPairing != stackedPairing)
            {
                pairingGrid.ColumnDefinitions.Clear();
                pairingGrid.RowDefinitions.Clear();
                if (stackedPairing)
                {
                    pairingGrid.ColumnSpacing = 0;
                    pairingGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    pairingGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                    pairingGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                    Grid.SetColumn(qrContent, 0);
                    Grid.SetRow(qrContent, 0);
                    Grid.SetColumn(pairingInfo, 0);
                    Grid.SetRow(pairingInfo, 1);
                }
                else
                {
                    pairingGrid.ColumnSpacing = 28;
                    pairingGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                    pairingGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                    pairingGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
                    Grid.SetColumn(qrContent, 0);
                    Grid.SetRow(qrContent, 0);
                    Grid.SetColumn(pairingInfo, 1);
                    Grid.SetRow(pairingInfo, 0);
                }
                appliedStackedPairing = stackedPairing;
            }
        };
        return _rootScroll;
    }

    private static Border Card() => new()
    {
        Padding = new Thickness(22),
        CornerRadius = new CornerRadius(14),
        Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
        BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
        BorderThickness = new Thickness(1),
    };

    private static void AddStat(Grid grid, int column, string caption, TextBlock value)
    {
        var body = new StackPanel { Spacing = 8 };
        value.Text = "—";
        value.FontSize = 30;
        value.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        body.Children.Add(value);
        body.Children.Add(new TextBlock { Text = caption, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        var card = Card();
        card.Child = body;
        Grid.SetColumn(card, column);
        grid.Children.Add(card);
    }

    public void SetServiceMessage(string message) => _serviceMessage.Text = message;

    public void ApplyTheme(ElementTheme theme)
    {
        var dark = theme == ElementTheme.Dark;
        Background = WinUIHelpers.Brush("AppBackground");
        if (_rootScroll is not null) _rootScroll.Background = WinUIHelpers.Brush("AppBackground");
        _pairingCode.Foreground = new SolidColorBrush(dark ? Colors.White : Colors.Black);
        _qrFrame.BorderBrush = new SolidColorBrush(dark
            ? Windows.UI.Color.FromArgb(255, 95, 111, 133)
            : Windows.UI.Color.FromArgb(255, 194, 205, 220));
        _qrStatus.Foreground = new SolidColorBrush(dark
            ? Windows.UI.Color.FromArgb(255, 210, 216, 224)
            : Windows.UI.Color.FromArgb(255, 72, 78, 86));
        _qrTimerStatus.Foreground = _qrStatus.Foreground;
    }

    public void ShowPairingError(string message)
    {
        ClearPairing(message);
    }

    public async Task RefreshAsync()
    {
        if (_bridgeField is null || _dashboardRefreshing) return;
        _dashboardRefreshing = true;
        try
        {
            var s = await _bridgeField.CallAsync("get_state");
            var running = s?["running"]?.GetValue<bool>() == true;
            var starting = s?["starting"]?.GetValue<bool>() == true;
            var phoneConnected = s?["phone_connected"]?.GetValue<bool>() == true;
            _phoneConnectionStatus.Text = phoneConnected
                ? WinUIStrings.Translate("الهاتف متصل بالكمبيوتر الآن", "Phone is connected to this computer")
                : starting
                    ? WinUIStrings.Translate("جارٍ تشغيل الخدمة…", "Starting the service…")
                    : running
                        ? WinUIStrings.Translate("الخدمة جاهزة — بانتظار اتصال الهاتف", "Service is ready — waiting for the phone")
                        : WinUIStrings.Translate("الخدمة متوقفة — شغّلها لبدء الاتصال", "Service is stopped — start it to connect");
            _serviceStarting = starting;
            _status.Text = running
                ? WinUIStrings.Translate("DeskTwin يعمل", "DeskTwin is running")
                : starting
                    ? WinUIStrings.Translate("جاري تجهيز الخدمة…", "Preparing service…")
                    : WinUIStrings.Translate("DeskTwin متوقف", "DeskTwin is stopped");
            _serviceToggleButton.Content = running
                ? WinUIStrings.Translate("إيقاف الخدمة", "Stop service")
                : starting
                    ? WinUIStrings.Translate("جاري التشغيل…", "Starting…")
                    : WinUIStrings.Translate("تشغيل الخدمة", "Start service");
            _serviceToggleButton.IsEnabled = !_serviceToggleInProgress && !starting;
            _files.Text = s?["files_indexed"]?.ToString() ?? "0";
            _folders.Text = s?["targets_count"]?.ToString() ?? "0";
            _devices.Text = s?["trusted_devices_count"]?.ToString() ?? "0";
            _approvals.Text = s?["pending_approvals_count"]?.ToString() ?? "0";
            if (!running)
            {
                if (_currentPairingCode is not null)
                    ClearPairing(WinUIStrings.Translate("الخدمة متوقفة؛ شغّلها لإنشاء QR.", "Service stopped; start it to generate a QR."));
                return;
            }

            if (_pairingGenerationInProgress) return;
            var pairingStateVersion = _pairingStateVersion;
            var activePairing = await _bridgeField.CallAsync("get_active_pairing") as JsonObject;
            // A slow polling response must not replace the QR created while it
            // was in flight (or any result fetched during code generation).
            if (_pairingGenerationInProgress || pairingStateVersion != _pairingStateVersion) return;
            var activeCode = activePairing?["code"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(activeCode))
            {
                if (_currentPairingCode is not null)
                    ClearPairing(WinUIStrings.Translate("انتهت صلاحية كود الاقتران؛ أنشئ كودًا جديدًا.", "Pairing code expired; generate a new one."));
            }
            else if (activeCode != _currentPairingCode)
            {
                var seconds = activePairing?["seconds_left"]?.GetValue<int>() ?? 0;
                await DisplayPairingAsync(activeCode, activePairing?["qr_png"]?.GetValue<string>(), activePairing?["qr_matrix"] as JsonArray, seconds,
                    activePairing?["qr_error"]?.GetValue<string>());
            }
            else if (_pairingExpiresAt is null)
            {
                StartPairingCountdown(activePairing?["seconds_left"]?.GetValue<int>() ?? 0);
            }
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر قراءة الحالة: ", "Could not read status: ") + ex.Message;
        }
        finally { _dashboardRefreshing = false; }
    }

    private async Task ToggleServiceAsync()
    {
        if (_serviceToggleInProgress) return;
        _serviceToggleInProgress = true;
        _serviceToggleButton.IsEnabled = false;
        _serviceToggleButton.Content = WinUIStrings.Translate("جارٍ تغيير الحالة…", "Changing state…");
        try
        {
            var s = await _bridgeField.CallAsync("get_state");
            if (s?["starting"]?.GetValue<bool>() == true)
            {
                _serviceStarting = true;
                _status.Text = WinUIStrings.Translate("الخدمة قيد التشغيل بالفعل؛ انتظر لحظات.", "Service is already starting; wait a moment.");
                await RefreshAsync();
                return;
            }
            if (s?["running"]?.GetValue<bool>() == true) await _bridgeField.CallAsync("stop_service");
            else await _bridgeField.CallAsync("start_service");
            await RefreshAsync();
        }
        catch (Exception ex) { _status.Text = WinUIStrings.Translate("تعذر تغيير حالة الخدمة: ", "Could not change service state: ") + ex.Message; }
        finally
        {
            _serviceToggleInProgress = false;
            _serviceToggleButton.IsEnabled = !_serviceStarting;
        }
    }

    public async Task GenerateCodeAsync()
    {
        if (_pairingGenerationInProgress) return;
        _pairingGenerationInProgress = true;
        _pairingStateVersion++;
        _generatePairingButton.IsEnabled = false;
        _generatePairingButton.Content = WinUIStrings.Translate("جارٍ الإنشاء…", "Generating…");
        string? generatedCode = null;
        ClearPairing(WinUIStrings.Translate("جارٍ إنشاء QR…", "Generating QR…"));
        _qrStatus.Text = WinUIStrings.Translate("جارٍ إنشاء QR…", "Generating QR…");
        try
        {
            var serviceState = await _bridgeField.CallAsync("get_state") as JsonObject;
            if (serviceState?["running"]?.GetValue<bool>() != true)
            {
                var starting = serviceState?["starting"]?.GetValue<bool>() == true;
                ClearPairing(WinUIStrings.Translate(
                    starting ? "انتظر اكتمال تشغيل الخدمة؛ سيظهر QR تلقائيًا." : "شغّل الخدمة أولًا لإنشاء كود الاقتران.",
                    starting ? "Wait for the service to finish starting; the QR will appear automatically." : "Start the service before generating a pairing code."));
                return;
            }

            var result = await _bridgeField.CallAsync("generate_pairing_code");
            var code = result?["code"]?.GetValue<string>();
            if (string.IsNullOrWhiteSpace(code))
            {
                _pairingCode.Text = "------";
                _qr.Children.Clear();
                _qrImage.Source = null;
                _qrPlaceholder.Visibility = Visibility.Visible;
                _qrStatus.Text = result?["error"]?.ToString() ?? "ابدأ الخدمة أولًا لإنشاء كود الاقتران.";
                return;
            }
            if (code.Length != 6 || code.Any(character => !char.IsAsciiDigit(character)))
                throw new InvalidDataException("خدمة الاقتران أعادت كودًا غير مكوّن من 6 أرقام.");

            generatedCode = code;
            var seconds = (result?["valid_for_minutes"]?.GetValue<int>() ?? 2) * 60;
            await DisplayPairingAsync(code, result?["qr_png"]?.GetValue<string>(), result?["qr_matrix"] as JsonArray, seconds,
                result?["qr_error"]?.GetValue<string>());
        }
        catch (Exception ex)
        {
            _pairingCode.Text = generatedCode ?? "------";
            _qr.Children.Clear();
            _qrImage.Source = null;
            _qrPlaceholder.Visibility = Visibility.Visible;
            _pairingExpiryTimer.Stop();
            _pairingExpiresAt = null;
            _currentPairingCode = null;
            _qrStatus.Text = generatedCode is null
                ? $"تعذر إنشاء كود الاقتران: {ex.Message}"
                : $"الكود جاهز؛ تعذر عرض QR. أدخله يدويًا: {ex.Message}";
        }
        finally
        {
            // Invalidate refreshes that began while generation was in flight.
            _pairingStateVersion++;
            _pairingGenerationInProgress = false;
            _generatePairingButton.IsEnabled = true;
            _generatePairingButton.Content = WinUIStrings.Translate("إنشاء كود وQR", "Generate code and QR");
        }
    }

    private async Task DisplayPairingAsync(string code, string? pngBase64, JsonArray? matrix, int secondsLeft, string? qrError)
    {
        if (code.Length != 6 || code.Any(character => !char.IsAsciiDigit(character)))
            throw new InvalidDataException("كود الاقتران غير مكوّن من 6 أرقام.");

        _currentPairingCode = code;
        _pairingCode.Text = code;
        _qr.Children.Clear();
        _qrImage.Source = null;
        _qrPlaceholder.Visibility = Visibility.Visible;
        if (string.IsNullOrWhiteSpace(pngBase64) && matrix is null)
        {
            _qrStatus.Text = WinUIStrings.Translate("الكود جاهز؛ تعذر إنشاء QR: ", "Code ready; QR could not be generated: ")
                + (qrError ?? WinUIStrings.Translate("خطأ غير معروف", "unknown error"));
        }
        else
        {
            try
            {
                // The matrix renderer is the original WinUI path and avoids
                // bitmap decoding differences across Windows 10 devices.
                // Keep the generated PNG as a fallback for older bridge payloads.
                if (matrix is not null)
                    RenderQr(matrix!);
                else
                    await RenderQrImageAsync(pngBase64!);
                _qrPlaceholder.Visibility = Visibility.Collapsed;
                _qrStatus.Text = WinUIStrings.Translate(
                    "المربع الأبيض للتباين؛ داخله QR أسود يحتوي الكود نفسه.",
                    "White canvas ensures contrast; black QR inside contains this code.");
            }
            catch (Exception matrixError) when (matrix is not null && !string.IsNullOrWhiteSpace(pngBase64))
            {
                try
                {
                    await RenderQrImageAsync(pngBase64!);
                    _qrPlaceholder.Visibility = Visibility.Collapsed;
                    _qrStatus.Text = WinUIStrings.Translate(
                        "عُرض QR بالصورة الاحتياطية.",
                        "QR displayed using the image fallback.");
                }
                catch (Exception imageError)
                {
                    _qr.Children.Clear();
                    _qrImage.Source = null;
                    _qrPlaceholder.Visibility = Visibility.Visible;
                    _qrStatus.Text = WinUIStrings.Translate(
                        "الكود جاهز؛ تعذر عرض QR. المصفوفة: ",
                        "Code ready; QR display failed. Matrix: ")
                        + matrixError.Message + " | PNG: " + imageError.Message;
                }
            }
            catch (Exception ex)
            {
                _qr.Children.Clear();
                _qrImage.Source = null;
                _qrPlaceholder.Visibility = Visibility.Visible;
                _qrStatus.Text = WinUIStrings.Translate("الكود جاهز؛ تعذر عرض QR: ", "Code ready; QR could not be displayed: ") + ex.Message;
            }
        }
        StartPairingCountdown(secondsLeft);
    }

    private async Task RenderQrImageAsync(string pngBase64)
    {
        var bytes = Convert.FromBase64String(pngBase64);
        if (bytes.Length == 0 || bytes.Length > 256 * 1024)
            throw new InvalidDataException("صورة QR غير صالحة أو أكبر من الحد المسموح.");

        using var stream = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(stream))
        {
            writer.WriteBytes(bytes);
            await writer.StoreAsync();
            writer.DetachStream();
        }
        stream.Seek(0);
        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream);
        _qr.Children.Clear();
        _qrImage.Source = bitmap;
    }

    private void StartPairingCountdown(int secondsLeft)
    {
        _pairingExpiryTimer.Stop();
        if (secondsLeft <= 0)
        {
            ClearPairing(WinUIStrings.Translate("انتهت صلاحية كود الاقتران؛ أنشئ كودًا جديدًا.", "Pairing code expired; generate a new one."));
            return;
        }
        _pairingExpiresAt = DateTimeOffset.UtcNow.AddSeconds(secondsLeft);
        _pairingExpiryTimer.Start();
        UpdatePairingCountdown();
    }

    private void UpdatePairingCountdown()
    {
        if (_pairingExpiresAt is not DateTimeOffset expiresAt) return;
        var secondsLeft = (int)Math.Ceiling((expiresAt - DateTimeOffset.UtcNow).TotalSeconds);
        if (secondsLeft <= 0)
        {
            ClearPairing(WinUIStrings.Translate("انتهت صلاحية كود الاقتران؛ أنشئ كودًا جديدًا.", "Pairing code expired; generate a new one."));
            return;
        }
        var minutes = (secondsLeft / 60).ToString("00", CultureInfo.InvariantCulture);
        var seconds = (secondsLeft % 60).ToString("00", CultureInfo.InvariantCulture);
        _qrTimerStatus.Text = WinUIStrings.Translate("صالح لمدة ", "Valid for ") + minutes + ":" + seconds;
    }

    private void ClearPairing(string message)
    {
        _pairingExpiryTimer.Stop();
        _pairingExpiresAt = null;
        _currentPairingCode = null;
        _pairingCode.Text = "------";
        _qr.Children.Clear();
        _qrImage.Source = null;
        _qrPlaceholder.Visibility = Visibility.Visible;
        _qrStatus.Text = message;
        _qrTimerStatus.Text = string.Empty;
    }

    public void OnNavigatedAway() => _pairingExpiryTimer.Stop();

    private void RenderQr(JsonArray rows)
    {
        var size = rows.Count;
        if (size < 29 || size > 177 || size % 2 == 0)
            throw new InvalidDataException("مصفوفة QR لها أبعاد غير صالحة.");

        var modules = new bool[size, size];
        for (var rowIndex = 0; rowIndex < size; rowIndex++)
        {
            if (rows[rowIndex] is not JsonArray row || row.Count != size)
                throw new InvalidDataException("مصفوفة QR ليست مربعة.");
            for (var columnIndex = 0; columnIndex < size; columnIndex++)
            {
                if (row[columnIndex] is not JsonValue cell || !cell.TryGetValue<bool>(out var isDark))
                    throw new InvalidDataException("مصفوفة QR تحتوي قيمة غير صالحة.");
                modules[rowIndex, columnIndex] = isDark;
            }
        }

        // The white quiet zone remains visible in dark mode; the QR itself is
        // drawn as explicit black modules, never as a blank white image tile.
        _qr.Children.Clear();
        _qrImage.Source = null;
        var moduleSize = _qr.Width / size;
        var black = new SolidColorBrush(Colors.Black);
        for (var rowIndex = 0; rowIndex < size; rowIndex++)
        {
            for (var columnIndex = 0; columnIndex < size; columnIndex++)
            {
                if (!modules[rowIndex, columnIndex]) continue;
                var cell = new XamlRectangle
                {
                    Width = moduleSize,
                    Height = moduleSize,
                    Fill = black,
                    IsHitTestVisible = false,
                };
                Canvas.SetLeft(cell, columnIndex * moduleSize);
                Canvas.SetTop(cell, rowIndex * moduleSize);
                _qr.Children.Add(cell);
            }
        }
        if (_qr.Children.Count == 0)
            throw new InvalidDataException("مصفوفة QR فارغة.");
    }
}

public sealed class FoldersPage : Page
{
    private DesktopBridge? _bridge;
    private readonly StackPanel _items = new() { Spacing = 10 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private readonly ToggleSwitch _shareEntireDevice = new() { Header = "مشاركة الكمبيوتر بالكامل" };
    private bool _loadingShareSetting;
    private bool _shareScopeChanging;
    private bool _pathOperationInProgress;
    private bool _targetsRefreshing;
    private string? _lastTargetsSnapshot;

    public FoldersPage()
    {
        var root = new StackPanel { Spacing = 18, Margin = new Thickness(34, 28, 34, 28) };
        root.Children.Add(new TextBlock { Text = "المجلدات المراقبة", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        root.Children.Add(new TextBlock { Text = "هذه هي المسارات التي يسمح DeskTwin للهاتف بالوصول إليها.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        var addFolder = new Button { Content = "إضافة مجلد" };
        addFolder.Click += async (_, _) => await AddPathAsync(folder: true);
        var addFile = new Button { Content = "إضافة ملف" };
        addFile.Click += async (_, _) => await AddPathAsync(folder: false);
        actions.Children.Add(addFolder);
        actions.Children.Add(addFile);
        root.Children.Add(actions);
        var shareCard = WinUIHelpers.Card();
        var shareContent = new StackPanel { Spacing = 8 };
        _shareEntireDevice.Toggled += async (_, _) => await SetShareScopeAsync();
        shareContent.Children.Add(_shareEntireDevice);
        shareContent.Children.Add(new TextBlock
        {
            Text = "عند التفعيل، يستطيع الهاتف تصفح جميع الأقراص والملفات؛ لا يقتصر على المسارات المختارة أعلاه.",
            Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        shareCard.Child = shareContent;
        root.Children.Add(shareCard);
        root.Children.Add(_message);
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Background = WinUIHelpers.Brush("AppBackground"), Content = _items };
        root.Children.Add(scroll);
        Content = root;
    }

    public FoldersPage(DesktopBridge bridge) : this()
    {
        _bridge = bridge;
        _ = RefreshAsync();
        _ = LoadShareScopeAsync();
    }

    private async Task LoadShareScopeAsync()
    {
        if (_bridge is null) return;
        try
        {
            var scope = await _bridge.CallAsync("get_share_scope") as JsonObject;
            _loadingShareSetting = true;
            _shareEntireDevice.IsOn = scope?["share_entire_device"]?.GetValue<bool>() == true;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _loadingShareSetting = false; }
    }

    private async Task SetShareScopeAsync()
    {
        if (_loadingShareSetting || _bridge is null || _shareScopeChanging) return;
        _shareScopeChanging = true;
        _shareEntireDevice.IsEnabled = false;
        var enable = _shareEntireDevice.IsOn;
        try
        {
            if (enable)
            {
                var dialog = new ContentDialog
                {
                    Title = "تأكيد مشاركة الكمبيوتر بالكامل",
                    Content = "سيتمكن الهاتف الموثوق من تصفح جميع الأقراص والملفات على هذا الكمبيوتر، وليس المسارات المحددة فقط. هل تريد المتابعة؟",
                    PrimaryButtonText = "مشاركة كاملة",
                    CloseButtonText = "إلغاء",
                    XamlRoot = XamlRoot,
                };
                if (await dialog.ShowAsync() != ContentDialogResult.Primary)
                {
                    _loadingShareSetting = true;
                    _shareEntireDevice.IsOn = false;
                    _loadingShareSetting = false;
                    return;
                }
            }
            await _bridge.CallAsync("set_share_entire_device", new { enabled = enable });
            _message.Text = enable ? "تم تفعيل مشاركة الجهاز بالكامل." : "عادت المشاركة إلى المسارات المحددة فقط.";
        }
        catch (Exception ex)
        {
            _loadingShareSetting = true;
            _shareEntireDevice.IsOn = !enable;
            _loadingShareSetting = false;
            _message.Text = ex.Message;
        }
        finally
        {
            _shareScopeChanging = false;
            _shareEntireDevice.IsEnabled = true;
        }
    }

    private async Task AddPathAsync(bool folder)
    {
        if (_bridge is null) return;
        if (_pathOperationInProgress)
        {
            _message.Text = "هناك عملية مسارات جارية بالفعل.";
            return;
        }
        _pathOperationInProgress = true;
        try
        {
            _message.Text = folder ? "اختر المجلد الذي تريد مشاركته…" : "اختر الملف الذي تريد مشاركته…";
            string? path;
            if (folder)
            {
                var picker = new Windows.Storage.Pickers.FolderPicker();
                picker.SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.ComputerFolder;
                picker.FileTypeFilter.Add("*");
                WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindowInstance!));
                var selected = await picker.PickSingleFolderAsync();
                path = selected?.Path;
            }
            else
            {
                var picker = new Windows.Storage.Pickers.FileOpenPicker();
                picker.SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.ComputerFolder;
                picker.FileTypeFilter.Add("*");
                WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindowInstance!));
                var selected = await picker.PickSingleFileAsync();
                path = selected?.Path;
            }
            if (string.IsNullOrWhiteSpace(path))
            {
                _message.Text = "لم يتم اختيار مسار؛ لم يتغير شيء.";
                return;
            }
            _message.Text = "جارٍ إضافة المسار…";
            await _bridge.CallAsync("add_target", new { path });
            _lastTargetsSnapshot = null;
            _pathOperationInProgress = false;
            await RefreshAsync();
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _pathOperationInProgress = false; }
    }

    public async Task RefreshAsync()
    {
        if (_bridge is null || _targetsRefreshing || _pathOperationInProgress) return;
        _targetsRefreshing = true;
        try
        {
            var list = await _bridge.CallAsync("get_targets") as JsonArray;
            var snapshot = list?.ToJsonString() ?? "[]";
            if (snapshot == _lastTargetsSnapshot) return;
            _items.Children.Clear();
            if (list is null || list.Count == 0)
            {
                _message.Text = "لم تتم إضافة مجلدات بعد.";
                _lastTargetsSnapshot = snapshot;
                return;
            }
            _message.Text = string.Empty;
            foreach (var node in list)
            {
                if (node is not JsonObject target) continue;
                var path = target["path"]?.GetValue<string>() ?? string.Empty;
                var row = new Grid { ColumnSpacing = 16, Padding = new Thickness(16), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var info = new StackPanel { Spacing = 4 };
                info.Children.Add(new TextBlock { Text = target["label"]?.GetValue<string>() ?? path, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
                info.Children.Add(new TextBlock { Text = path, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextTrimming = TextTrimming.CharacterEllipsis });
                row.Children.Add(info);
                var remove = new Button { Content = "إزالة", Tag = path };
                remove.Click += async (_, _) => await RemoveAsync(path);
                Grid.SetColumn(remove, 1);
                row.Children.Add(remove);
                _items.Children.Add(row);
            }
            _lastTargetsSnapshot = snapshot;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _targetsRefreshing = false; }
    }

    private async Task RemoveAsync(string path)
    {
        if (_bridge is null) return;
        var dialog = new ContentDialog
        {
            Title = "إزالة المسار من المشاركة؟",
            Content = "سيُزال من DeskTwin فقط؛ لن يُحذف الملف أو المجلد من الكمبيوتر.",
            PrimaryButtonText = "إزالة",
            CloseButtonText = "إلغاء",
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || _pathOperationInProgress) return;
        _pathOperationInProgress = true;
        _message.Text = "جارٍ إزالة المسار…";
        try
        {
            await _bridge.CallAsync("remove_target", new { path });
            _lastTargetsSnapshot = null;
            _pathOperationInProgress = false;
            await RefreshAsync();
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _pathOperationInProgress = false; }
    }
}

public sealed class ComingSoonPage : Page
{
    public ComingSoonPage()
    {
        Content = new StackPanel
        {
            Spacing = 12,
            Margin = new Thickness(40),
            Children =
            {
                new TextBlock { Text = "واجهة WinUI قيد النقل", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] },
                new TextBlock { Text = "سيتم نقل هذه الشاشة وربط عملياتها قبل إيقاف الواجهة القديمة. بياناتك وخدمة الاتصال الحالية لم تتغير.", TextWrapping = TextWrapping.Wrap, MaxWidth = 680, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") },
            }
        };
    }
}

public sealed class DevicesPage : Page
{
    private DesktopBridge? _bridge;
    private readonly StackPanel _rows = new() { Spacing = 10 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private string? _lastDevicesSnapshot;
    private bool _deviceMutationInProgress;
    private bool _devicesRefreshing;

    public DevicesPage()
    {
        var stack = new StackPanel { Spacing = 12, Margin = new Thickness(32, 26, 32, 24) };
        stack.Children.Add(new TextBlock { Text = "الأجهزة الموثوقة", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        stack.Children.Add(new TextBlock { Text = "الأجهزة التي اكتمل اقترانها فقط تقدر توصل لبيانات DeskTwin.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var refresh = new Button { Content = "تحديث القائمة" };
        refresh.Click += async (_, _) => await RefreshAsync();
        var clearRevoked = new Button { Content = "مسح سجلات الأجهزة الملغاة" };
        clearRevoked.Click += async (_, _) => await ClearRevokedAsync();
        actions.Children.Add(refresh);
        actions.Children.Add(clearRevoked);
        stack.Children.Add(actions);
        stack.Children.Add(_message);
        stack.Children.Add(_rows);
        Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Background = WinUIHelpers.Brush("AppBackground"), Content = stack };
    }

    public DevicesPage(DesktopBridge bridge) : this()
    {
        _bridge = bridge;
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_bridge is null || _devicesRefreshing || _deviceMutationInProgress) return;
        _devicesRefreshing = true;
        if (_rows.Children.Count == 0) _message.Text = "جارٍ تحميل الأجهزة…";
        try
        {
            var devices = await _bridge.CallAsync("get_devices") as JsonArray;
            if (_deviceMutationInProgress) return;
            var snapshot = devices?.ToJsonString() ?? "[]";
            if (snapshot == _lastDevicesSnapshot)
            {
                _message.Text = string.Empty;
                return;
            }
            _rows.Children.Clear();
            if (devices is null || devices.Count == 0)
            {
                _message.Text = "شغّل الخدمة لعرض الأجهزة المقترنة.";
                _lastDevicesSnapshot = snapshot;
                return;
            }
            _message.Text = string.Empty;
            foreach (var node in devices)
            {
                if (node is not JsonObject device) continue;
                var id = device["id"]?.GetValue<int>() ?? 0;
                var revoked = device["revoked"]?.GetValue<bool>() == true;
                var row = new Grid { ColumnSpacing = 14, Padding = new Thickness(16), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var info = new StackPanel { Spacing = 5 };
                info.Children.Add(new TextBlock { Text = device["device_name"]?.GetValue<string>() ?? "جهاز غير معروف", FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
                info.Children.Add(new TextBlock { Text = revoked ? "ملغي الثقة" : $"موثوق • آخر ظهور: {FormatDate(device["last_seen_at"]?.GetValue<string>())}", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
                row.Children.Add(info);
                if (!revoked)
                {
                    var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
                    var trust = new ComboBox { Width = 125, SelectedValuePath = "Tag", DisplayMemberPath = "Content" };
                    foreach (var (label, value) in new[] { ("عادي", "standard"), ("موثوق", "trusted"), ("كامل", "full") })
                        trust.Items.Add(new ComboBoxItem { Content = label, Tag = value });
                    var savedTrustLevel = device["trust_level"]?.GetValue<string>() ?? "standard";
                    trust.SelectedValue = savedTrustLevel;
                    var trustChangeInProgress = false;
                    trust.SelectionChanged += async (_, _) =>
                    {
                        if (trustChangeInProgress || trust.SelectedValue is not string level || level == savedTrustLevel || _bridge is null) return;
                        if (_deviceMutationInProgress || _devicesRefreshing)
                        {
                            trust.SelectedValue = savedTrustLevel;
                            _message.Text = "انتظر انتهاء عملية الجهاز الحالية.";
                            return;
                        }
                        trustChangeInProgress = true;
                        _deviceMutationInProgress = true;
                        trust.IsEnabled = false;
                        var previousLevel = savedTrustLevel;
                        try
                        {
                            await _bridge.CallAsync("set_device_trust", new { device_id = id, trust_level = level });
                            savedTrustLevel = level;
                            _lastDevicesSnapshot = null;
                            _message.Text = "تم تحديث مستوى الثقة.";
                        }
                        catch (Exception ex)
                        {
                            trust.SelectedValue = previousLevel;
                            _message.Text = ex.Message;
                        }
                        finally
                        {
                            trustChangeInProgress = false;
                            _deviceMutationInProgress = false;
                            trust.IsEnabled = true;
                        }
                    };
                    actions.Children.Add(trust);
                    var revoke = new Button { Content = "إلغاء الثقة" };
                    revoke.Click += async (_, _) => await RevokeAsync(id, device["device_name"]?.GetValue<string>() ?? "الجهاز");
                    actions.Children.Add(revoke);
                    Grid.SetColumn(actions, 1);
                    row.Children.Add(actions);
                }
                _rows.Children.Add(row);
            }
            _lastDevicesSnapshot = snapshot;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _devicesRefreshing = false; }
    }

    private async Task ClearRevokedAsync()
    {
        if (_bridge is null || _deviceMutationInProgress || _devicesRefreshing) return;
        var dialog = new ContentDialog
        {
            Title = "حذف سجلات الأجهزة الملغاة؟",
            Content = "سيُحذف سجل الأجهزة التي أُلغيت ثقتها فقط؛ الأجهزة الموثوقة لن تتأثر.",
            PrimaryButtonText = "حذف السجلات",
            CloseButtonText = "إلغاء",
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || _deviceMutationInProgress || _devicesRefreshing) return;
        _deviceMutationInProgress = true;
        try
        {
            var result = await _bridge.CallAsync("delete_revoked_devices") as JsonObject;
            var count = result?["deleted_count"]?.GetValue<int>() ?? 0;
            _lastDevicesSnapshot = null;
            _deviceMutationInProgress = false;
            await RefreshAsync();
            _message.Text = count == 0 ? "لا توجد سجلات ملغاة للحذف." : $"تم حذف {count} سجل جهاز ملغى.";
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _deviceMutationInProgress = false; }
    }

    private async Task RevokeAsync(int id, string name)
    {
        if (_deviceMutationInProgress || _devicesRefreshing) return;
        var dialog = new ContentDialog { Title = "إلغاء ثقة الجهاز؟", Content = $"لن يتمكن {name} من الاتصال مجددًا حتى إتمام اقتران جديد.", PrimaryButtonText = "إلغاء الثقة", CloseButtonText = "رجوع", XamlRoot = XamlRoot };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || _bridge is null || _deviceMutationInProgress || _devicesRefreshing) return;
        _deviceMutationInProgress = true;
        try
        {
            await _bridge.CallAsync("revoke_device", new { device_id = id });
            _lastDevicesSnapshot = null;
            _deviceMutationInProgress = false;
            await RefreshAsync();
            _message.Text = $"تم إلغاء ثقة {name}.";
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _deviceMutationInProgress = false; }
    }

    private static string FormatDate(string? value) => string.IsNullOrWhiteSpace(value) ? "غير معروف" : value.Replace('T', ' ').Split('.')[0];
}

public sealed class ApprovalsPage : Page
{
    private DesktopBridge? _bridge;
    private readonly StackPanel _rows = new() { Spacing = 10 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private readonly HashSet<int> _resolvingApprovals = new();
    private bool _refreshing;
    private string? _lastApprovalsSnapshot;
    private bool _exportSettingsLoaded;
    private string _commandRequestExportDirectory = "";
    private bool _useDefaultCommandRequestExportDirectory;

    public ApprovalsPage()
    {
        Content = WinUIHelpers.PageLayout(
            WinUIStrings.Translate("الموافقات المعلّقة", "Pending approvals"),
            WinUIStrings.Translate("راجع تفاصيل كل طلب قبل الموافقة؛ الأوامر والعمليات الحساسة لا تُنفّذ دون اختيارك.", "Review each request; commands and sensitive operations run only with your approval."),
            _message, _rows);
    }

    public ApprovalsPage(DesktopBridge bridge) : this()
    {
        _bridge = bridge;
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_bridge is null || _refreshing) return;
        _refreshing = true;
        try
        {
            var approvals = await _bridge.CallAsync("get_pending_approvals") as JsonArray;
            await LoadCommandExportSettingsAsync();
            var snapshot = approvals?.ToJsonString() ?? "[]";
            if (snapshot == _lastApprovalsSnapshot) return;
            _rows.Children.Clear();
            if (approvals is null || approvals.Count == 0)
            {
                _message.Text = WinUIStrings.Translate("لا توجد طلبات معلّقة.", "No pending requests.");
                _lastApprovalsSnapshot = snapshot;
                return;
            }
            _message.Text = string.Empty;
            foreach (var node in approvals)
            {
                if (node is not JsonObject approval) continue;
                var action = approval["action_type"]?.GetValue<string>() ?? "طلب";
                var path = approval["file_path"]?.GetValue<string>() ?? "";
                var command = action == "command_execute" ? ReadCommandPayload(approval) : null;
                // Use a vertical card layout so long command previews never share
                // a grid cell with the action buttons or force them off-screen.
                var row = new StackPanel { Spacing = 14 };
                var card = new Border
                {
                    Padding = new Thickness(16),
                    Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
                    Child = row,
                };
                var info = new StackPanel { Spacing = 5 };
                info.Children.Add(new TextBlock { Text = ActionName(action), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
                var age = FormatApprovalAge(approval["requested_at"]?.GetValue<string>());
                if (!string.IsNullOrWhiteSpace(age))
                    info.Children.Add(new TextBlock { Text = age, FontSize = 11, Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush") });
                info.Children.Add(new TextBlock { Text = path, TextWrapping = TextWrapping.Wrap, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
                if (action == "command_execute")
                {
                    var shell = command?["shell"]?.GetValue<string>() ?? "cmd";
                    var commandText = command?["command"]?.GetValue<string>() ?? ReadRawCommandPayload(approval);
                    var preview = command is null
                        ? WinUIStrings.Translate("محتوى الطلب (للمراجعة فقط): ", "Request content (review only): ") + commandText
                        : shell + ": " + commandText;
                    info.Children.Add(new TextBlock
                    {
                        Text = preview,
                        TextWrapping = TextWrapping.Wrap,
                        Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
                    });
                    var exportMode = _useDefaultCommandRequestExportDirectory && !string.IsNullOrWhiteSpace(_commandRequestExportDirectory)
                        ? WinUIStrings.Translate("سيُحفظ TXT في: ", "TXT will save to: ") + _commandRequestExportDirectory
                        : WinUIStrings.Translate("سيُطلب اختيار مكان حفظ TXT عند الضغط على حفظ.", "You will choose a TXT save location when you press Save.");
                    info.Children.Add(new TextBlock
                    {
                        Text = exportMode,
                        TextWrapping = TextWrapping.Wrap,
                        FontSize = 11,
                        Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
                    });
                }
                row.Children.Add(info);
                var actions = new StackPanel { Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
                var decisionActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, VerticalAlignment = VerticalAlignment.Center };
                var reject = new Button { Content = WinUIStrings.Translate("رفض", "Reject") };
                var approve = new Button { Content = WinUIStrings.Translate("موافقة", "Approve") };
                reject.Click += async (_, _) => await ResolveAsync(approval, approve: false, approveButton: approve, rejectButton: reject);
                approve.Click += async (_, _) => await ResolveAsync(approval, approve: true, approveButton: approve, rejectButton: reject);
                decisionActions.Children.Add(reject);
                decisionActions.Children.Add(approve);
                actions.Children.Add(decisionActions);
                if (action == "command_execute")
                {
                    var saveCommand = new Button
                    {
                        Content = WinUIStrings.Translate("حفظ الطلب كملف TXT…", "Save request as TXT…"),
                        HorizontalAlignment = HorizontalAlignment.Left,
                        MinWidth = 190,
                    };
                    saveCommand.Click += async (_, _) => await ExportCommandRequestAsync(approval, saveCommand, approve, reject);
                    actions.Children.Add(saveCommand);
                }
                if (!string.IsNullOrWhiteSpace(path) && CanOpenContainingFolder(path))
                {
                    var openFolder = new Button { Content = WinUIStrings.Translate("افتح مكان الملف", "Open file location") };
                    openFolder.Click += (_, _) => OpenContainingFolder(path);
                    decisionActions.Children.Add(openFolder);
                }
                row.Children.Add(actions);
                _rows.Children.Add(card);
            }
            _lastApprovalsSnapshot = snapshot;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _refreshing = false; }
    }

    private async Task ResolveAsync(JsonObject approval, bool approve, Button approveButton, Button rejectButton)
    {
        if (_bridge is null) return;
        var id = approval["id"]?.GetValue<int>() ?? 0;
        if (id <= 0)
        {
            await ShowApprovalNoticeAsync(WinUIStrings.Translate("طلب غير صالح", "Invalid request"), WinUIStrings.Translate("معرّف الطلب غير صالح؛ لم يُنفذ أي إجراء.", "Invalid request ID. Nothing was executed."));
            return;
        }
        if (!_resolvingApprovals.Add(id)) return;
        approveButton.IsEnabled = false;
        rejectButton.IsEnabled = false;
        try
        {
        var action = approval["action_type"]?.GetValue<string>() ?? "طلب";
        var filePath = approval["file_path"]?.GetValue<string>() ?? "";
        var command = action == "command_execute" ? ReadCommandPayload(approval) : null;
        if (action == "command_execute" && command is null)
        {
            await ShowApprovalNoticeAsync(
                WinUIStrings.Translate("طلب غير صالح", "Invalid request"),
                WinUIStrings.Translate("تعذر قراءة الأمر؛ لم يتم تنفيذه.", "Could not read the command. Nothing was executed."));
            return;
        }
        string? commandWorkingDirectory = null;
        if (command is not null)
        {
            try { commandWorkingDirectory = await ResolveCommandWorkingDirectoryAsync(command); }
            catch (Exception ex)
            {
                await ShowApprovalNoticeAsync(WinUIStrings.Translate("تعذر فحص مجلد العمل", "Could not resolve working directory"), ex.Message);
                return;
            }
        }

        var details = new StackPanel { Spacing = 10 };
        if (command is not null)
        {
            var shell = command["shell"]?.GetValue<string>() ?? "cmd";
            var commandText = command["command"]?.GetValue<string>() ?? "";
            details.Children.Add(new TextBlock
            {
                Text = WinUIStrings.Translate("طلب shell قديم محظور ولن يُنفذ:", "This legacy shell request is blocked and will not run:"),
                TextWrapping = TextWrapping.Wrap,
            });
            details.Children.Add(new TextBlock { Text = WinUIStrings.Translate("الطرفية: ", "Shell: ") + shell, TextWrapping = TextWrapping.Wrap });
            details.Children.Add(new TextBlock
            {
                Text = WinUIStrings.Translate("مجلد العمل: ", "Working directory: ") + commandWorkingDirectory,
                TextWrapping = TextWrapping.Wrap,
            });
            details.Children.Add(new TextBlock
            {
                Text = commandText,
                TextWrapping = TextWrapping.Wrap,
            });
            details.Children.Add(new TextBlock
            {
                Text = WinUIStrings.Translate("يمكنك مراجعة المحتوى، لكن DeskTwin سيرفض هذا الطلب عند تأكيده.", "You may review the content, but DeskTwin will reject this request when confirmed."),
                TextWrapping = TextWrapping.Wrap,
                Foreground = WinUIHelpers.Brush("SystemFillColorCautionBrush"),
            });
        }
        else
        {
            details.Children.Add(new TextBlock { Text = ActionName(action), TextWrapping = TextWrapping.Wrap });
            if (!string.IsNullOrWhiteSpace(filePath))
                details.Children.Add(new TextBlock { Text = filePath, TextWrapping = TextWrapping.Wrap, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
            details.Children.Add(new TextBlock
            {
                Text = approve
                    ? WinUIStrings.Translate("هل راجعت تفاصيل الطلب وتريد تنفيذه؟", "Have you reviewed this request and want to proceed?")
                    : WinUIStrings.Translate("سيُرفض الطلب ولن يُنفذ.", "This request will be rejected and not executed."),
                TextWrapping = TextWrapping.Wrap,
            });
        }

        var dialog = new ContentDialog
        {
            Title = approve ? WinUIStrings.Translate("تأكيد الموافقة", "Confirm approval") : WinUIStrings.Translate("رفض الطلب", "Reject request"),
            Content = new ScrollViewer { MaxHeight = 420, Content = details },
            PrimaryButtonText = approve
                ? command is not null
                    ? WinUIStrings.Translate("رفض الطلب المحظور", "Reject blocked request")
                    : WinUIStrings.Translate("موافقة", "Approve")
                : WinUIStrings.Translate("رفض الطلب", "Reject request"),
            CloseButtonText = WinUIStrings.Translate("إلغاء", "Cancel"),
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            var result = await _bridge.CallAsync(approve ? "approve_request" : "reject_request", new { approval_id = id }) as JsonObject;
            if (approve)
            {
                if (result?["success"]?.GetValue<bool>() != true)
                {
                    var error = result?["error"]?.GetValue<string>() ?? WinUIStrings.Translate("تعذر تنفيذ الطلب.", "The request could not be completed.");
                    await ShowApprovalNoticeAsync(WinUIStrings.Translate("فشل التنفيذ", "Execution failed"), error);
                }
                else
                {
                    var message = SuccessMessage(action);
                    var output = result?["output"]?.GetValue<string>();
                    if (!string.IsNullOrWhiteSpace(output)) message += "\n\n" + output.Substring(Math.Max(0, output.Length - 1200));
                    await ShowApprovalNoticeAsync(WinUIStrings.Translate("تم", "Done"), message);
                }
            }
            else
            {
                await ShowApprovalNoticeAsync(
                    WinUIStrings.Translate("تم الرفض", "Request rejected"),
                    WinUIStrings.Translate("تم رفض الطلب.", "The request was rejected."));
            }
            await RefreshAsync();
        }
        catch (Exception ex)
        {
            await ShowApprovalNoticeAsync(WinUIStrings.Translate("تعذر إكمال الطلب", "Could not complete request"), ex.Message);
        }
        }
        finally
        {
            _resolvingApprovals.Remove(id);
            approveButton.IsEnabled = true;
            rejectButton.IsEnabled = true;
        }
    }

    private async Task ExportCommandRequestAsync(JsonObject approval, Button saveButton, Button approveButton, Button rejectButton)
    {
        if (_bridge is null) return;
        var id = approval["id"]?.GetValue<int>() ?? 0;
        if (id <= 0 || !_resolvingApprovals.Add(id)) return;

        saveButton.IsEnabled = false;
        approveButton.IsEnabled = false;
        rejectButton.IsEnabled = false;
        try
        {
            var command = ReadCommandPayload(approval);
            var workingDirectory = command?["working_directory"]?.GetValue<string>() ?? approval["file_path"]?.GetValue<string>() ?? "";
            if (command is not null)
            {
                try { workingDirectory = await ResolveCommandWorkingDirectoryAsync(command); }
                catch { /* Export must remain available even if the requested working directory is invalid. */ }
            }
            await SaveCommandRequestAsTextAsync(approval, command, workingDirectory);
        }
        catch (Exception ex)
        {
            await ShowApprovalNoticeAsync(WinUIStrings.Translate("تعذر حفظ الطلب", "Could not save request"), ex.Message);
        }
        finally
        {
            _resolvingApprovals.Remove(id);
            saveButton.IsEnabled = true;
            approveButton.IsEnabled = true;
            rejectButton.IsEnabled = true;
        }
    }

    private async Task SaveCommandRequestAsTextAsync(JsonObject approval, JsonObject? command, string? workingDirectory)
    {
        var shell = command?["shell"]?.GetValue<string>() ?? "cmd";
        var commandText = command?["command"]?.GetValue<string>() ?? ReadRawCommandPayload(approval);
        var prefix = shell.Equals("cmd", StringComparison.OrdinalIgnoreCase) ? "REM " : "# ";
        var content = string.Join(Environment.NewLine,
            prefix + "DeskTwin command request; not executed by saving this file.",
            prefix + "Shell: " + shell,
            prefix + "Working directory: " + (workingDirectory ?? "unspecified"),
            prefix + "Requested by: " + (command?["user_name"]?.GetValue<string>() ?? "unknown"),
            "",
            commandText,
            "");
        var approvalId = approval["id"]?.GetValue<int>() ?? 0;
        string savedPath;
        var settings = await _bridge!.CallAsync("get_settings") as JsonObject;
        var defaultDirectory = settings?["command_request_export_directory"]?.GetValue<string>()?.Trim() ?? "";
        var useDefaultDirectory = settings?["use_default_command_request_export_directory"]?.GetValue<bool>() ?? false;

        if (useDefaultDirectory && !string.IsNullOrWhiteSpace(defaultDirectory))
        {
            if (!Directory.Exists(defaultDirectory))
                throw new DirectoryNotFoundException(WinUIStrings.Translate("مجلد الحفظ الافتراضي لم يعد موجودًا. حدّثه من الإعدادات.", "The default export folder no longer exists. Update it in Settings."));
            savedPath = CreateCommandExportPath(defaultDirectory, approvalId);
            await File.WriteAllTextAsync(savedPath, content);
        }
        else
        {
            var picker = new FileSavePicker { SuggestedFileName = $"DeskTwin-Command-{approvalId}.txt" };
            picker.FileTypeChoices.Add(WinUIStrings.Translate("ملف نصي", "Text file"), new List<string> { ".txt" });
            picker.DefaultFileExtension = ".txt";
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
            var file = await picker.PickSaveFileAsync();
            if (file is null) return;
            await FileIO.WriteTextAsync(file, content);
            savedPath = file.Path;
        }

        await ShowApprovalNoticeAsync(
            WinUIStrings.Translate("تم حفظ الطلب", "Request saved"),
            WinUIStrings.Translate(
                "حُفظ الطلب في الملف التالي ولم يتم تنفيذه. سيظل الطلب الأصلي معلّقًا حتى تختار تنفيذه أو رفضه:\n" + savedPath,
                "The request was saved to this file and was not executed. The original request remains pending until you execute or reject it:\n" + savedPath));
    }

    private static string CreateCommandExportPath(string directory, int approvalId)
    {
        var timestamp = DateTime.Now.ToString("yyyyMMdd-HHmmss-fff", CultureInfo.InvariantCulture);
        var baseName = $"DeskTwin-Command-{approvalId}-{timestamp}";
        var path = Path.Combine(directory, baseName + ".txt");
        var suffix = 1;
        while (File.Exists(path))
            path = Path.Combine(directory, $"{baseName}-{suffix++}.txt");
        return path;
    }

    private async Task LoadCommandExportSettingsAsync()
    {
        if (_exportSettingsLoaded || _bridge is null) return;
        try
        {
            var settings = await _bridge.CallAsync("get_settings") as JsonObject;
            _commandRequestExportDirectory = settings?["command_request_export_directory"]?.GetValue<string>()?.Trim() ?? "";
            _useDefaultCommandRequestExportDirectory = settings?["use_default_command_request_export_directory"]?.GetValue<bool>() ?? false;
        }
        catch
        {
            _commandRequestExportDirectory = "";
            _useDefaultCommandRequestExportDirectory = false;
        }
        finally { _exportSettingsLoaded = true; }
    }

    private static JsonObject? ReadCommandPayload(JsonObject approval)
    {
        try
        {
            var rawNode = approval["new_value"];
            if (rawNode is JsonObject directPayload) return ValidateCommandPayload(directPayload);
            var raw = ReadRawCommandPayload(approval);
            if (string.IsNullOrWhiteSpace(raw)) return null;
            var parsed = JsonNode.Parse(raw);
            if (parsed is JsonValue nestedJson && nestedJson.TryGetValue<string>(out var nestedRaw))
                parsed = JsonNode.Parse(nestedRaw);
            if (parsed is not JsonObject payload) return null;
            return ValidateCommandPayload(payload);
        }
        catch (Exception) { return null; }
    }

    private static JsonObject? ValidateCommandPayload(JsonObject payload)
    {
        try
        {
            var shell = payload["shell"]?.GetValue<string>() ?? "cmd";
            var command = payload["command"]?.GetValue<string>();
            var supportedShell = shell.Equals("cmd", StringComparison.OrdinalIgnoreCase)
                || shell.Equals("powershell", StringComparison.OrdinalIgnoreCase);
            return supportedShell && !string.IsNullOrWhiteSpace(command) ? payload : null;
        }
        catch (Exception) { return null; }
    }

    private static string ReadRawCommandPayload(JsonObject approval)
    {
        var node = approval["new_value"];
        if (node is null) return "";
        try { return node.GetValue<string>(); }
        catch { return node.ToJsonString(); }
    }

    private async Task<string> ResolveCommandWorkingDirectoryAsync(JsonObject command)
    {
        var requested = command["working_directory"]?.GetValue<string>()?.Trim() ?? "";
        if (string.IsNullOrWhiteSpace(requested))
        {
            var settings = await _bridge!.CallAsync("get_settings") as JsonObject;
            requested = settings?["command_draft_directory"]?.GetValue<string>()?.Trim() ?? "";
            if (string.IsNullOrWhiteSpace(requested))
                requested = Path.Combine(Environment.GetEnvironmentVariable("USERPROFILE") ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Desktop");
        }
        requested = Environment.ExpandEnvironmentVariables(requested);
        var home = Environment.GetEnvironmentVariable("USERPROFILE") ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if (requested == "~") requested = home;
        else if (requested.StartsWith("~\\", StringComparison.Ordinal) || requested.StartsWith("~/", StringComparison.Ordinal))
            requested = Path.Combine(home, requested[2..]);
        return Path.GetFullPath(requested, _bridge!.ProjectRoot);
    }

    private static string FormatApprovalAge(string? requestedAt)
    {
        if (!DateTimeOffset.TryParse(requestedAt, CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var requested)) return "";
        var elapsed = DateTimeOffset.UtcNow - requested;
        if (elapsed < TimeSpan.Zero) elapsed = TimeSpan.Zero;
        if (elapsed.TotalDays >= 1)
            return WinUIStrings.Translate("من ", "") + Math.Floor(elapsed.TotalDays) + WinUIStrings.Translate(" يوم", " days ago");
        if (elapsed.TotalHours >= 1)
            return WinUIStrings.Translate("من ", "") + Math.Floor(elapsed.TotalHours) + WinUIStrings.Translate(" ساعة", " hours ago");
        return WinUIStrings.Translate("من ", "") + Math.Max(1, (int)elapsed.TotalMinutes) + WinUIStrings.Translate(" دقيقة", " minutes ago");
    }

    private static bool CanOpenContainingFolder(string path)
    {
        var folder = Directory.Exists(path) ? path : Path.GetDirectoryName(path);
        return !string.IsNullOrWhiteSpace(folder) && Directory.Exists(folder);
    }

    private async void OpenContainingFolder(string path)
    {
        try
        {
            var folder = Directory.Exists(path) ? path : Path.GetDirectoryName(path);
            if (string.IsNullOrWhiteSpace(folder) || !Directory.Exists(folder))
                throw new DirectoryNotFoundException("المجلد لم يعد موجودًا.");
            Process.Start(new ProcessStartInfo { FileName = folder, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            await ShowApprovalNoticeAsync(WinUIStrings.Translate("تعذر فتح المجلد", "Could not open folder"), ex.Message);
        }
    }

    private async Task ShowApprovalNoticeAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = new ScrollViewer
            {
                MaxHeight = 420,
                Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            },
            CloseButtonText = WinUIStrings.Translate("إغلاق", "Close"),
            XamlRoot = XamlRoot,
        };
        await dialog.ShowAsync();
    }

    private static string SuccessMessage(string action) => action switch
    {
        "delete_file" => WinUIStrings.Translate("تم نقل الملف إلى سلة DeskTwin.", "The file was moved to DeskTwin Trash."),
        "rename_file" => WinUIStrings.Translate("تم تغيير اسم الملف على الكمبيوتر.", "The file was renamed on the computer."),
        "create_file" => WinUIStrings.Translate("تم تنفيذ الطلب بنجاح.", "The request was completed."),
        "make_linked" => WinUIStrings.Translate("أصبح الملف متاحًا للمزامنة الكاملة.", "The file is now available for full synchronization."),
        "overwrite_file" => WinUIStrings.Translate("سُجلت الموافقة؛ سيحدث الاستبدال عند الرفع من الهاتف.", "Approval recorded; replacement occurs on the next phone upload."),
        "command_execute" => WinUIStrings.Translate("تم حظر طلب shell القديم.", "The legacy shell request was blocked."),
        _ => WinUIStrings.Translate("تم تنفيذ الطلب بنجاح.", "The request was completed."),
    };

    private static string ActionName(string action) => action switch
    {
        "delete_file" => WinUIStrings.Translate("حذف ملف", "Delete file"),
        "rename_file" => WinUIStrings.Translate("إعادة تسمية ملف", "Rename file"),
        "create_file" => WinUIStrings.Translate("إنشاء / رفع ملف", "Create / upload file"),
        "make_linked" => WinUIStrings.Translate("تفعيل المزامنة", "Enable sync"),
        "overwrite_file" => WinUIStrings.Translate("استبدال ملف", "Replace file"),
        "command_execute" => WinUIStrings.Translate("طلب shell قديم محظور", "Blocked legacy shell request"),
        _ => action,
    };
}

public sealed class ActivityPage : Page
{
    private const int PageSize = 15;
    private DesktopBridge? _bridge;
    private readonly StackPanel _rows = new() { Spacing = 8 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _loadMore = new() { Content = "تحميل أنشطة أقدم" };
    private int _offset;
    private bool _hasMore;
    private bool _loading;
    private string? _lastActivityPageSnapshot;

    public ActivityPage()
    {
        var stack = new StackPanel { Spacing = 12, Margin = new Thickness(32, 26, 32, 24) };
        stack.Children.Add(new TextBlock { Text = "سجل النشاط", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        stack.Children.Add(new TextBlock { Text = "نشاط الهاتف، منفصل عن سجل تغيّرات الملفات.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var refresh = new Button { Content = "تحديث" };
        refresh.Click += async (_, _) => await RefreshAsync();
        var clear = new Button { Content = "مسح سجل النشاط" };
        clear.Click += async (_, _) => await ClearAsync();
        actions.Children.Add(refresh);
        actions.Children.Add(clear);
        stack.Children.Add(actions);
        stack.Children.Add(_message);
        stack.Children.Add(_rows);
        _loadMore.Click += async (_, _) =>
        {
            if (!_hasMore || _loading) return;
            _message.Text = "جارٍ تحميل أنشطة أقدم…";
            await LoadPageAsync(append: true);
        };
        stack.Children.Add(_loadMore);
        Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Background = WinUIHelpers.Brush("AppBackground"), Content = stack };
        _loadMore.Visibility = Visibility.Collapsed;
    }

    public ActivityPage(DesktopBridge bridge) : this()
    {
        _bridge = bridge;
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_loading) return;
        _offset = 0;
        _lastActivityPageSnapshot = null;
        _message.Text = "جارٍ تحميل سجل النشاط…";
        await LoadPageAsync(append: false);
    }

    public Task RefreshCurrentAsync() => _offset == 0 ? LoadPageAsync(append: false) : Task.CompletedTask;

    private async Task LoadPageAsync(bool append)
    {
        if (_bridge is null || _loading) return;
        _loading = true;
        _loadMore.IsEnabled = false;
        var requestedOffset = append ? _offset + PageSize : 0;
        try
        {
            var activity = await _bridge.CallAsync("get_recent_activity", new { limit = PageSize + 1, offset = requestedOffset }) as JsonArray;
            var snapshot = $"{requestedOffset}:{activity?.ToJsonString() ?? "[]"}";
            if (!append && snapshot == _lastActivityPageSnapshot)
            {
                _message.Text = string.Empty;
                return;
            }
            if (activity is null || activity.Count == 0)
            {
                if (!append) _rows.Children.Clear();
                _offset = requestedOffset;
                _lastActivityPageSnapshot = snapshot;
                _message.Text = requestedOffset == 0 ? "لا يوجد نشاط مسجل." : "لا توجد أنشطة أقدم.";
                _hasMore = false;
                _loadMore.Visibility = Visibility.Collapsed;
                return;
            }
            var pageRows = new List<Border>();
            foreach (var node in activity.Take(PageSize))
            {
                if (node is not JsonObject item) continue;
                var type = item["activity_type"]?.GetValue<string>() ?? "unknown";
                var label = ActivityName(type);
                var detailText = item["detail"]?.GetValue<string>() ?? "";
                var timestamp = item["created_at"]?.GetValue<string>() ?? "";
                var row = WinUIHelpers.Card();
                var detail = new StackPanel { Spacing = 4 };
                detail.Children.Add(new TextBlock { Text = label, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
                if (!string.IsNullOrWhiteSpace(detailText))
                    detail.Children.Add(new TextBlock { Text = detailText, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
                detail.Children.Add(new TextBlock { Text = timestamp.Replace('T', ' ').Split('.')[0], FontSize = 11, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
                row.Child = detail;
                pageRows.Add(row);
            }
            if (!append) _rows.Children.Clear();
            foreach (var row in pageRows) _rows.Children.Add(row);
            _offset = requestedOffset;
            _lastActivityPageSnapshot = snapshot;
            _message.Text = string.Empty;
            _hasMore = activity.Count > PageSize;
            _loadMore.Visibility = _hasMore ? Visibility.Visible : Visibility.Collapsed;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally
        {
            _loading = false;
            _loadMore.IsEnabled = _hasMore;
        }
    }

    private async Task ClearAsync()
    {
        if (_bridge is null || _loading) return;
        var dialog = new ContentDialog
        {
            Title = "مسح سجل النشاط؟",
            Content = "سيُحذف سجل النشاط المحلي بالكامل، ولا يمكن التراجع.",
            PrimaryButtonText = "مسح السجل",
            CloseButtonText = "إلغاء",
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await _bridge.CallAsync("clear_activity");
            _offset = 0;
            _lastActivityPageSnapshot = null;
            await LoadPageAsync(append: false);
        }
        catch (Exception ex) { _message.Text = ex.Message; }
    }

    private static string ActivityName(string type) => type switch
    {
        "browsed_folder" => "تصفح مجلد", "opened_file" or "open" => "فتح ملف",
        "opened_location" => "فتح مكان الملف", "searched" => "بحث",
        "downloaded_file" => "تنزيل ملف", "uploaded_file" => "رفع ملف",
        "copy" => "نسخ ملف", "move" => "نقل ملف", "delete" => "حذف ملف",
        "rename" => "إعادة تسمية ملف", "mkdir" => "إنشاء مجلد",
        "requested_delete" => "طلب حذف", "requested_rename" => "طلب تسمية",
        "connected" => "اتصل الهاتف", "disconnected" => "انقطع الاتصال", _ => type,
    };
}

public sealed class SecurityPage : Page
{
    private DesktopBridge? _bridge;
    private readonly Action? _openDevices = null;
    private readonly StackPanel _rows = new() { Spacing = 12 };
    private readonly TextBlock _message = new() { TextWrapping = TextWrapping.Wrap };
    private string? _lastSecuritySnapshot;
    private bool _securityRefreshing;
    private bool _securityMutationInProgress;

    public SecurityPage()
    {
        var stack = new StackPanel { Spacing = 12, Margin = new Thickness(32, 26, 32, 24) };
        stack.Children.Add(new TextBlock { Text = "الأمان", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        stack.Children.Add(new TextBlock { Text = "حالة الاتصالات ومحاولات الدخول على الكمبيوتر.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        var refresh = new Button { Content = "تحديث الحالة" };
        refresh.Click += async (_, _) => await RefreshAsync();
        var manageDevices = new Button { Content = "إدارة الأجهزة" };
        manageDevices.Click += (_, _) => _openDevices?.Invoke();
        var revokeAll = new Button { Content = "إلغاء ثقة كل الأجهزة", Background = new SolidColorBrush(Windows.UI.Color.FromArgb(255, 166, 55, 61)), Foreground = new SolidColorBrush(Colors.White) };
        revokeAll.Click += async (_, _) => await RevokeAllAsync();
        actions.Children.Add(refresh);
        actions.Children.Add(manageDevices);
        actions.Children.Add(revokeAll);
        stack.Children.Add(actions);
        stack.Children.Add(_message);
        stack.Children.Add(_rows);
        Content = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Background = WinUIHelpers.Brush("AppBackground"), Content = stack };
    }

    public SecurityPage(DesktopBridge bridge, Action? openDevices = null) : this()
    {
        _bridge = bridge;
        _openDevices = openDevices;
        _ = RefreshAsync();
    }

    public async Task RefreshAsync()
    {
        if (_bridge is null || _securityRefreshing || _securityMutationInProgress) return;
        _securityRefreshing = true;
        try
        {
            var status = await _bridge.CallAsync("get_security_status") as JsonObject;
            if (_securityMutationInProgress) return;
            var snapshot = status?.ToJsonString() ?? "{}";
            if (snapshot == _lastSecuritySnapshot) return;
            _rows.Children.Clear();
            if (status is null)
            {
                _message.Text = "شغّل الخدمة لعرض حالة الأمان.";
                _lastSecuritySnapshot = snapshot;
                return;
            }
            _message.Text = string.Empty;
            foreach (var pair in status)
            {
                var row = WinUIHelpers.Card();
                row.Child = new StackPanel
                {
                    Spacing = 4,
                    Children =
                    {
                        new TextBlock { Text = Humanize(pair.Key), FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                        new TextBlock { Text = pair.Value?.ToString() ?? "—", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap },
                    }
                };
                _rows.Children.Add(row);
            }
            _lastSecuritySnapshot = snapshot;
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _securityRefreshing = false; }
    }

    private async Task RevokeAllAsync()
    {
        if (_bridge is null || _securityMutationInProgress || _securityRefreshing) return;
        var dialog = new ContentDialog
        {
            Title = "تأكيد إجراء الطوارئ",
            Content = "سيتم إلغاء ثقة كل الأجهزة المتصلة، وستحتاج لإعادة الاقتران يدويًا. هل تريد المتابعة؟",
            PrimaryButtonText = "إلغاء الثقة للجميع",
            CloseButtonText = "رجوع",
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary || _securityMutationInProgress || _securityRefreshing) return;
        _securityMutationInProgress = true;
        try
        {
            var result = await _bridge.CallAsync("revoke_all_devices") as JsonObject;
            var count = result?["revoked_count"]?.GetValue<int>() ?? 0;
            _lastSecuritySnapshot = null;
            _securityMutationInProgress = false;
            await RefreshAsync();
            _message.Text = $"تم إلغاء ثقة {count} جهاز.";
        }
        catch (Exception ex) { _message.Text = ex.Message; }
        finally { _securityMutationInProgress = false; }
    }

    private static string Humanize(string key) => key switch
    {
        "active_connections" => "اتصالات نشطة", "recent_failed_attempts" => "محاولات دخول فاشلة مؤخرًا",
        "blocked_sources" => "مصادر محظورة مؤقتًا", "severity" => "مستوى التنبيه", _ => key,
    };
}

internal static class WinUIHelpers
{
    private static ElementTheme _theme = ElementTheme.Dark;
    private static readonly Dictionary<string, SolidColorBrush> ThemeBrushes = new();
    public static ElementTheme CurrentTheme => _theme;

    public static Brush Brush(string key)
    {
        if (!ThemeBrushes.TryGetValue(key, out var brush))
        {
            brush = new SolidColorBrush(ResolveColor(key, _theme));
            ThemeBrushes[key] = brush;
        }
        return brush;
    }

    public static void SetTheme(ElementTheme theme)
    {
        _theme = theme;
        foreach (var pair in ThemeBrushes) pair.Value.Color = ResolveColor(pair.Key, theme);
    }

    private static Windows.UI.Color ResolveColor(string key, ElementTheme theme)
    {
        var dark = theme == ElementTheme.Dark;
        if (key.Contains("Transparent", StringComparison.OrdinalIgnoreCase))
            return Windows.UI.Color.FromArgb(0, 0, 0, 0);
        var (r, g, b) = key switch
        {
            "AppBackground" => dark ? (15, 23, 36) : (244, 247, 251),
            "TextFillColorPrimaryBrush" => dark ? (243, 247, 252) : (24, 37, 54),
            "TextFillColorSecondaryBrush" => dark ? (179, 192, 209) : (82, 96, 114),
            "TextFillColorTertiaryBrush" => dark ? (145, 160, 179) : (102, 116, 134),
            "TextFillColorDisabledBrush" => dark ? (112, 125, 142) : (145, 155, 168),
            "TextFillColorInverseBrush" => dark ? (24, 37, 54) : (255, 255, 255),
            "CardBackgroundFillColorDefaultBrush" => dark ? (25, 38, 57) : (255, 255, 255),
            "CardBackgroundFillColorSecondaryBrush" => dark ? (31, 45, 65) : (249, 250, 252),
            "CardBackgroundFillColorTertiaryBrush" => dark ? (38, 52, 72) : (242, 245, 249),
            "CardStrokeColorDefaultBrush" => dark ? (52, 69, 92) : (216, 225, 236),
            "CardStrokeColorSecondaryBrush" => dark ? (43, 59, 81) : (229, 234, 241),
            "LayerFillColorDefaultBrush" => dark ? (25, 38, 57) : (255, 255, 255),
            "LayerFillColorAltBrush" => dark ? (31, 45, 65) : (249, 250, 252),
            "LayerOnAcrylicFillColorDefaultBrush" => dark ? (25, 38, 57) : (255, 255, 255),
            "SolidBackgroundFillColorBaseBrush" => dark ? (15, 23, 36) : (244, 247, 251),
            "SolidBackgroundFillColorSecondaryBrush" => dark ? (20, 31, 47) : (238, 242, 247),
            "SolidBackgroundFillColorTertiaryBrush" => dark ? (25, 38, 57) : (230, 236, 243),
            "ControlFillColorDefaultBrush" => dark ? (42, 57, 77) : (246, 248, 251),
            "ControlFillColorSecondaryBrush" => dark ? (36, 51, 70) : (240, 243, 247),
            "ControlFillColorTertiaryBrush" => dark ? (31, 46, 65) : (234, 239, 245),
            "ControlFillColorDisabledBrush" => dark ? (31, 41, 54) : (232, 235, 239),
            "ControlStrokeColorDefaultBrush" => dark ? (76, 94, 118) : (190, 201, 215),
            "ControlStrokeColorSecondaryBrush" => dark ? (59, 76, 98) : (211, 220, 231),
            "AccentFillColorDefaultBrush" => dark ? (40, 105, 183) : (30, 90, 168),
            "AccentFillColorSecondaryBrush" => dark ? (51, 119, 199) : (43, 111, 197),
            "AccentFillColorTertiaryBrush" => dark ? (65, 132, 207) : (68, 130, 207),
            "AccentFillColorDisabledBrush" => dark ? (49, 69, 94) : (190, 208, 231),
            "AccentTextFillColorPrimaryBrush" => dark ? (111, 177, 255) : (0, 84, 165),
            "AccentTextFillColorSecondaryBrush" => dark ? (139, 192, 255) : (0, 72, 145),
            "AccentTextFillColorTertiaryBrush" => dark ? (163, 205, 255) : (0, 62, 125),
            "TextOnAccentFillColorPrimaryBrush" => (255, 255, 255),
            "TextOnAccentFillColorSecondaryBrush" => (230, 240, 255),
            "SystemFillColorSuccessBrush" => dark ? (92, 218, 164) : (20, 125, 82),
            "SystemFillColorCautionBrush" => dark ? (255, 205, 115) : (142, 76, 12),
            "SystemFillColorCriticalBrush" => dark ? (255, 126, 138) : (176, 42, 55),
            "SystemFillColorAttentionBrush" => dark ? (111, 177, 255) : (0, 84, 165),
            "SubtleFillColorSecondaryBrush" => dark ? (31, 45, 65) : (249, 250, 252),
            "SubtleFillColorTertiaryBrush" => dark ? (38, 52, 72) : (242, 245, 249),
            "SubtleFillColorDisabledBrush" => dark ? (31, 41, 54) : (232, 235, 239),
            _ => ResolveFallbackColor(key, dark),
        };
        return Windows.UI.Color.FromArgb(255, (byte)r, (byte)g, (byte)b);
    }

    private static (int r, int g, int b) ResolveFallbackColor(string key, bool dark)
    {
        if (key.Contains("TextOnAccent", StringComparison.OrdinalIgnoreCase)) return (255, 255, 255);
        if (key.Contains("Text", StringComparison.OrdinalIgnoreCase))
            return dark ? (179, 192, 209) : (82, 96, 114);
        if (key.Contains("Critical", StringComparison.OrdinalIgnoreCase) || key.Contains("Error", StringComparison.OrdinalIgnoreCase))
            return dark ? (255, 126, 138) : (176, 42, 55);
        if (key.Contains("Success", StringComparison.OrdinalIgnoreCase)) return dark ? (92, 218, 164) : (20, 125, 82);
        if (key.Contains("Caution", StringComparison.OrdinalIgnoreCase) || key.Contains("Warning", StringComparison.OrdinalIgnoreCase))
            return dark ? (255, 205, 115) : (142, 76, 12);
        if (key.Contains("Accent", StringComparison.OrdinalIgnoreCase)) return dark ? (40, 105, 183) : (30, 90, 168);
        if (key.Contains("Stroke", StringComparison.OrdinalIgnoreCase) || key.Contains("Border", StringComparison.OrdinalIgnoreCase))
            return dark ? (52, 69, 92) : (216, 225, 236);
        if (key.Contains("Fill", StringComparison.OrdinalIgnoreCase) || key.Contains("Layer", StringComparison.OrdinalIgnoreCase)
            || key.Contains("Background", StringComparison.OrdinalIgnoreCase) || key.Contains("Card", StringComparison.OrdinalIgnoreCase))
            return dark ? (25, 38, 57) : (255, 255, 255);
        return dark ? (42, 57, 77) : (246, 248, 251);
    }

    public static UIElement PageLayout(string title, string subtitle, TextBlock message, UIElement content)
    {
        var stack = new StackPanel { Spacing = 16, Margin = new Thickness(32, 26, 32, 24) };
        stack.Children.Add(new TextBlock { Text = title, Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        stack.Children.Add(new TextBlock { Text = subtitle, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
        stack.Children.Add(message);
        stack.Children.Add(content);
        return new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Background = WinUIHelpers.Brush("AppBackground"),
            Content = stack,
        };
    }
    public static Border Card() => new()
    {
        Padding = new Thickness(18),
        CornerRadius = new CornerRadius(12),
        Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
        BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
        BorderThickness = new Thickness(1),
    };
}

internal static class WinUIStrings
{
    public static string Language { get; set; } = "ar";
    public static string Translate(string arabic, string english) => Language == "en" ? english : arabic;

    private static readonly Dictionary<string, string> English = new()
    {
        ["الإعدادات"] = "Settings", ["تفضيلات واجهة WinUI وخيارات بدء الخدمة."] = "WinUI preferences and service startup options.",
        ["المظهر"] = "Appearance", ["فاتح — أزرق وأبيض"] = "Light — blue and white", ["داكن"] = "Dark",
        ["العربية"] = "Arabic", ["لغة التطبيق"] = "App language", ["سمة التطبيق"] = "App theme",
        ["بدء التشغيل"] = "Startup", ["تشغيل الخدمة عند فتح DeskTwin"] = "Start service when DeskTwin opens",
        ["التغيير يطبّق في المرة التالية التي تفتح فيها البرنامج."] = "This change takes effect the next time you open the app.",
        ["إمكانية الوصول"] = "Accessibility", ["تقليل الحركة"] = "Reduce motion",
        ["سجل النشاط"] = "Activity log", ["عدد عمليات الهاتف التي يحتفظ بها الكمبيوتر."] = "Number of phone events kept on this computer.",
        ["الحد الأقصى للسجل"] = "Activity limit", ["الملفات الكبيرة غير المكتملة"] = "Incomplete large transfers",
        ["مدة الاحتفاظ بجزء الرفع الناقص قبل حذفه تلقائيًا."] = "How long an incomplete upload is kept before automatic deletion.",
        ["مدة الاحتفاظ"] = "Retention period", ["ساعة"] = "1 hour", ["6 ساعات"] = "6 hours", ["12 ساعة"] = "12 hours",
        ["يوم"] = "1 day", ["3 أيام"] = "3 days", ["أسبوع"] = "1 week",
        ["مسودات أوامر CMD / PowerShell"] = "CMD / PowerShell command drafts",
        ["اختر مكان حفظ الملفات قبل طلب موافقة الهاتف. اتركه افتراضيًا للحفظ على سطح المكتب."] = "Choose where files are saved before requesting phone approval. Default saves to Desktop.",
        ["سطح المكتب (افتراضي)"] = "Desktop (default)", ["اختيار مجلد…"] = "Choose folder…", ["استخدام الافتراضي"] = "Use default",
        ["التشخيص"] = "Diagnostics", ["السجلات محلية، وتساعد في تحديد سبب فشل الاتصال أو التشغيل."] = "Logs stay local and help diagnose connection or startup failures.",
        ["فتح مجلد سجل التشخيص"] = "Open diagnostics folder", ["مقدمة فيديو من اختيارك"] = "Your video intro",
        ["تلقائي"] = "Automatic", ["يدوي"] = "Manual", ["إيقاف"] = "Off", ["تشغيل المقدمة"] = "Intro playback",
        ["اختيار فيديو…"] = "Choose video…", ["تشغيل المقدمة الآن"] = "Play intro now", ["إزالة الفيديو"] = "Remove video",
        ["التلقائي يعمل عند فتح DeskTwin؛ اليدوي لا يعمل إلا من زر التشغيل؛ إيقاف يعطّل المقدمة."] = "Automatic plays on launch; Manual plays only on demand; Off disables the intro.",
        ["لم يتم اختيار فيديو"] = "No video selected", ["تم حفظ الإعداد."] = "Setting saved.",
        ["محلي وآمن • لا يوجد اتصال سحابي"] = "Local and secure • No cloud connection",
        ["الرئيسية"] = "Home", ["المجلدات"] = "Folders", ["الأجهزة"] = "Devices", ["الموافقات"] = "Approvals",
        ["ملفات الهاتف"] = "Phone files", ["الأمان"] = "Security",
        ["المحادثة"] = "Chat", ["التكامل"] = "Integration", ["ربط الكمبيوتر والهاتف بأمان"] = "Secure phone and computer connection",
        ["نظرة عامة"] = "Overview", ["خدمة محلية مشفّرة — لا حاجة لتشغيل نافذة أوامر منفصلة"] = "Encrypted local service — no separate command window needed",
        ["اقتران الهاتف"] = "Pair your phone", ["الكود الرقمي"] = "Pairing code",
        ["امسح QR أو أدخل الكود في تطبيق الهاتف. الكود صالح لدقيقتين."] = "Scan the QR or enter its code in the phone app. The code is valid for two minutes.",
        ["QR غير جاهز"] = "QR not ready", ["ابدأ المحادثة من الكمبيوتر أو الهاتف."] = "Start a conversation from your computer or phone.",
        ["رسائل مباشرة بين الكمبيوتر والهاتف عبر الاتصال المحلي."] = "Direct messages between your computer and phone over the local connection.",
        ["المجلد فارغ."] = "This folder is empty.", ["مجلد"] = "Folder",
        ["المجلدات المراقبة"] = "Shared folders", ["هذه هي المسارات التي يسمح DeskTwin للهاتف بالوصول إليها."] = "These are the locations DeskTwin allows your phone to access.",
        ["نشاط الهاتف، منفصل عن سجل تغيّرات الملفات."] = "Phone activity, separate from file-change history.",
        ["تحديث"] = "Refresh", ["مسح سجل النشاط"] = "Clear activity log", ["تحميل أنشطة أقدم"] = "Load older activity",
        ["لا يوجد نشاط مسجل."] = "No activity recorded.", ["لا توجد أنشطة أقدم."] = "No older activity.",
        ["مسح سجل النشاط؟"] = "Clear activity log?", ["سيُحذف سجل النشاط المحلي بالكامل، ولا يمكن التراجع."] = "The local activity log will be deleted. This cannot be undone.",
        ["مسح السجل"] = "Clear log", ["الأجهزة الموثوقة"] = "Trusted devices",
        ["الأجهزة التي اكتمل اقترانها فقط تقدر توصل لبيانات DeskTwin."] = "Only fully paired devices can access DeskTwin data.",
        ["تحديث القائمة"] = "Refresh list", ["مسح سجلات الأجهزة الملغاة"] = "Clear revoked device records",
        ["عادي"] = "Standard", ["موثوق"] = "Trusted", ["كامل"] = "Full",
        ["إلغاء الثقة"] = "Revoke trust", ["ملغي الثقة"] = "Trust revoked", ["آخر ظهور"] = "Last seen",
        ["شغّل الخدمة لعرض الأجهزة المقترنة."] = "Start the service to view paired devices.",
        ["حذف سجلات الأجهزة الملغاة؟"] = "Delete revoked device records?",
        ["سيُحذف سجل الأجهزة التي أُلغيت ثقتها فقط؛ الأجهزة الموثوقة لن تتأثر."] = "Only revoked-device records will be removed; trusted devices are unaffected.",
        ["حذف السجلات"] = "Delete records", ["لا توجد سجلات ملغاة للحذف."] = "No revoked records to delete.",
        ["الأمان"] = "Security", ["حالة الاتصالات ومحاولات الدخول على الكمبيوتر."] = "Connection and sign-in monitoring for this computer.",
        ["تحديث الحالة"] = "Refresh status", ["إدارة الأجهزة"] = "Manage devices", ["إلغاء ثقة كل الأجهزة"] = "Revoke all devices",
        ["اكتب رسالة للهاتف…"] = "Message your phone…", ["مسار الهاتف (اتركه فارغًا للجذر)"] = "Phone path (blank for root)",
        ["● خدمة الكمبيوتر تعمل"] = "● Computer service is running", ["● جاري تشغيل خدمة الكمبيوتر…"] = "● Starting computer service…", ["● خدمة الكمبيوتر متوقفة"] = "● Computer service is stopped",
        ["إرسال"] = "Send", ["أُرسلت الرسالة؛ بانتظار تأكيد الهاتف."] = "Message sent; waiting for phone confirmation.",
        ["تعذر قراءة ملفات الهاتف."] = "Could not read phone files.", ["تم النسخ."] = "Copied.",
        ["ابحث في ملفات الهاتف…"] = "Search phone files…", ["الاسم"] = "Name", ["الأحدث"] = "Recently modified", ["الأكبر"] = "Largest first",
        ["فتح المسار"] = "Open path", ["إرسال ملف للهاتف"] = "Send file to phone", ["فتح"] = "Open", ["تنزيل"] = "Download",
        ["تصفية"] = "Filter", ["لا توجد ملفات مطابقة."] = "No matching files.",
        ["تأكيد إجراء الطوارئ"] = "Confirm emergency action",
        ["سيتم إلغاء ثقة كل الأجهزة المتصلة، وستحتاج لإعادة الاقتران يدويًا. هل تريد المتابعة؟"] = "All connected devices will be revoked and must pair again. Continue?",
        ["إلغاء الثقة للجميع"] = "Revoke all", ["رجوع"] = "Back",
        ["تم إلغاء ثقة {count} جهاز."] = "Revoked {count} device(s).",
        ["مشاركة الكمبيوتر بالكامل"] = "Share entire computer",
        ["عند التفعيل، يستطيع الهاتف تصفح جميع الأقراص والملفات؛ لا يقتصر على المسارات المختارة أعلاه."] = "When enabled, the phone can browse all drives and files, not only the selected locations.",
        ["تأكيد مشاركة الكمبيوتر بالكامل"] = "Confirm full-computer sharing",
        ["سيتمكن الهاتف الموثوق من تصفح جميع الأقراص والملفات على هذا الكمبيوتر، وليس المسارات المحددة فقط. هل تريد المتابعة؟"] = "The trusted phone can browse every drive and file on this computer, not only selected locations. Continue?",
        ["مشاركة كاملة"] = "Share everything", ["تم تفعيل مشاركة الجهاز بالكامل."] = "Full-device sharing enabled.",
        ["عادت المشاركة إلى المسارات المحددة فقط."] = "Sharing is limited to selected locations again.",
        ["سلة الهاتف"] = "Phone trash", ["العودة للملفات"] = "Back to files", ["جاري قراءة سلة الهاتف…"] = "Loading phone trash…",
        ["سلة الهاتف فارغة."] = "Phone trash is empty.", ["{0} عنصر في السلة"] = "{0} items in trash",
        ["استعادة"] = "Restore", ["حذف نهائي"] = "Delete permanently", ["حذف نهائي من الهاتف؟"] = "Permanently delete from phone?",
        ["سيُحذف الملف نهائيًا ولا يمكن استعادته:\n{path}"] = "This file will be permanently deleted and cannot be restored:\n{path}",
    };

    public static string Text(string value) => Language == "en" && English.TryGetValue(value, out var translation) ? translation : value;

    public static void Localize(UIElement? root)
    {
        if (root is null || Language != "en") return;
        if (root is TextBlock text) text.Text = Text(text.Text);
        if (root is TextBox textBox && !string.IsNullOrWhiteSpace(textBox.PlaceholderText))
            textBox.PlaceholderText = Text(textBox.PlaceholderText);
        if (root is Button button && button.Content is string buttonText) button.Content = Text(buttonText);
        if (root is ComboBoxItem comboItem && comboItem.Content is string itemText) comboItem.Content = Text(itemText);
        if (root is ToggleSwitch toggle && toggle.Header is string toggleHeader) toggle.Header = Text(toggleHeader);
        if (root is Panel panel)
        {
            foreach (var child in panel.Children) Localize(child);
        }
        if (root is Border border && border.Child is not null) Localize(border.Child);
        if (root is Page page && page.Content is UIElement pageContent) Localize(pageContent);
        if (root is ContentControl contentControl && contentControl.Content is UIElement content) Localize(content);
        if (root is ItemsControl itemsControl)
        {
            foreach (var item in itemsControl.Items.OfType<UIElement>()) Localize(item);
        }
    }
}
