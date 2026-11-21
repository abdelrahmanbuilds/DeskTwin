using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using System.Diagnostics;
using System.Globalization;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace DeskTwin.WinUI;

public sealed class SettingsPage : Page
{
    private readonly DesktopBridge _bridge;
    private readonly Action<ElementTheme> _applyTheme;
    private readonly Action<string> _applyLanguage;
    private readonly Func<string, Task> _playIntro;
    private readonly ComboBox _theme = new() { Width = 220 };
    private readonly ComboBox _language = new() { Width = 220 };
    private readonly ComboBox _introMode = new() { Width = 220 };
    private readonly ComboBox _activityLimit = new() { Width = 220 };
    private readonly ComboBox _transferRetention = new() { Width = 220 };
    private readonly ToggleSwitch _autoStart = new() { Header = "تشغيل الخدمة عند فتح DeskTwin" };
    private readonly ToggleSwitch _reduceMotion = new() { Header = "تقليل الحركة" };
    private readonly TextBlock _introPath = new() { Text = "لم يتم اختيار فيديو", TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _commandDirectory = new() { Text = "سطح المكتب (افتراضي)", TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly TextBlock _commandRequestExportDirectory = new() { Text = "اختيار المسار عند كل حفظ", TextTrimming = TextTrimming.CharacterEllipsis };
    private readonly ToggleSwitch _useDefaultCommandRequestExportDirectory = new() { Header = "استخدم المجلد تلقائيًا عند حفظ TXT" };
    private readonly TextBlock _boundUser = new() { Text = "لسه محدش اتربط", TextWrapping = TextWrapping.Wrap };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _playIntroButton = new() { Content = "تشغيل المقدمة الآن" };
    private readonly Button _removeIntroButton = new() { Content = "إزالة الفيديو" };
    private readonly Dictionary<string, object?> _savedSettings = new();
    private readonly HashSet<string> _savingSettings = new();
    private string _selectedIntroPath = "";
    private bool _loading = true;

    public SettingsPage(DesktopBridge bridge, Action<ElementTheme> applyTheme, Action<string> applyLanguage, Func<string, Task> playIntro)
    {
        _bridge = bridge;
        _applyTheme = applyTheme;
        _applyLanguage = applyLanguage;
        _playIntro = playIntro;

        var root = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Background = WinUIHelpers.Brush("AppBackground"),
        };
        var stack = new StackPanel { Spacing = 16, Margin = new Thickness(30, 24, 30, 24), MaxWidth = 850 };
        stack.Children.Add(new TextBlock { Text = "الإعدادات", Style = (Style)Application.Current.Resources["TitleTextBlockStyle"] });
        stack.Children.Add(new TextBlock { Text = "تفضيلات واجهة WinUI وخيارات بدء الخدمة.", Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });

        var appearance = Card("المظهر");
        var appearanceContent = (StackPanel)appearance.Child!;
        _theme.Items.Add(new ComboBoxItem { Content = "فاتح — أزرق وأبيض", Tag = "light" });
        _theme.Items.Add(new ComboBoxItem { Content = "داكن", Tag = "dark" });
        _language.Items.Add(new ComboBoxItem { Content = "العربية", Tag = "ar" });
        _language.Items.Add(new ComboBoxItem { Content = "English", Tag = "en" });
        _theme.SelectionChanged += async (_, _) =>
        {
            if (_loading || _theme.SelectedItem is not ComboBoxItem item || item.Tag is not string value) return;
            await SaveSelectionAsync(_theme, "theme_mode", value,
                () => _applyTheme(value == "dark" ? ElementTheme.Dark : ElementTheme.Light));
        };
        appearanceContent.Children.Add(Field("سمة التطبيق", _theme));
        _language.SelectionChanged += async (_, _) =>
        {
            if (_loading || _language.SelectedItem is not ComboBoxItem item || item.Tag is not string value) return;
            await SaveSelectionAsync(_language, "language", value, () => _applyLanguage(value));
        };
        appearanceContent.Children.Add(Field("لغة التطبيق", _language));
        stack.Children.Add(appearance);

        var account = Card("المستخدم المرتبط");
        ((StackPanel)account.Child!).Children.Add(_boundUser);
        stack.Children.Add(account);

        var startup = Card("بدء التشغيل");
        var startupContent = (StackPanel)startup.Child!;
        _autoStart.Toggled += async (_, _) => { if (!_loading) await SaveToggleAsync(_autoStart, "auto_start_service", _autoStart.IsOn); };
        startupContent.Children.Add(_autoStart);
        startupContent.Children.Add(new TextBlock { Text = "التغيير يطبّق في المرة التالية التي تفتح فيها البرنامج.", FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        stack.Children.Add(startup);

        var motion = Card("إمكانية الوصول");
        var motionContent = (StackPanel)motion.Child!;
        _reduceMotion.Toggled += async (_, _) => { if (!_loading) await SaveToggleAsync(_reduceMotion, "reduce_motion", _reduceMotion.IsOn); };
        motionContent.Children.Add(_reduceMotion);
        stack.Children.Add(motion);

        var activity = Card("سجل النشاط");
        var activityContent = (StackPanel)activity.Child!;
        activityContent.Children.Add(new TextBlock
        {
            Text = "عدد عمليات الهاتف التي يحتفظ بها الكمبيوتر.",
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        foreach (var limit in new[] { 50, 100, 200, 300, 1000 })
            _activityLimit.Items.Add(new ComboBoxItem { Content = limit.ToString(), Tag = limit.ToString() });
        _activityLimit.SelectionChanged += async (_, _) =>
        {
            if (!_loading && _activityLimit.SelectedItem is ComboBoxItem item && int.TryParse(item.Tag?.ToString(), out var limit))
                await SaveSelectionAsync(_activityLimit, "activity_log_limit", limit);
        };
        activityContent.Children.Add(Field("الحد الأقصى للسجل", _activityLimit));
        stack.Children.Add(activity);

        var transfers = Card("الملفات الكبيرة غير المكتملة");
        var transferContent = (StackPanel)transfers.Child!;
        transferContent.Children.Add(new TextBlock
        {
            Text = "مدة الاحتفاظ بجزء الرفع الناقص قبل حذفه تلقائيًا.",
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        foreach (var option in new[] { ("ساعة", 1), ("6 ساعات", 6), ("12 ساعة", 12), ("يوم", 24), ("3 أيام", 72), ("أسبوع", 168) })
            _transferRetention.Items.Add(new ComboBoxItem { Content = option.Item1, Tag = option.Item2.ToString() });
        _transferRetention.SelectionChanged += async (_, _) =>
        {
            if (!_loading && _transferRetention.SelectedItem is ComboBoxItem item && int.TryParse(item.Tag?.ToString(), out var hours))
                await SaveSelectionAsync(_transferRetention, "incomplete_transfer_retention_hours", hours);
        };
        transferContent.Children.Add(Field("مدة الاحتفاظ", _transferRetention));
        stack.Children.Add(transfers);

        var commands = Card("مجلد العمل لأوامر CMD / PowerShell");
        var commandContent = (StackPanel)commands.Child!;
        commandContent.Children.Add(new TextBlock
        {
            Text = "هذا مجلد العمل الذي يبدأ منه الأمر بعد الموافقة، وليس مكان حفظ نسخة TXT.",
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        commandContent.Children.Add(_commandDirectory);
        var commandActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        var chooseDirectory = new Button { Content = "اختيار مجلد…" };
        chooseDirectory.Click += async (_, _) => await ChooseCommandDirectoryAsync();
        var resetDirectory = new Button { Content = "استخدام الافتراضي" };
        resetDirectory.Click += async (_, _) => await ResetCommandDirectoryAsync();
        commandActions.Children.Add(chooseDirectory);
        commandActions.Children.Add(resetDirectory);
        commandContent.Children.Add(commandActions);
        stack.Children.Add(commands);

        var commandExports = Card("حفظ طلبات الأوامر كملفات TXT");
        var commandExportContent = (StackPanel)commandExports.Child!;
        commandExportContent.Children.Add(new TextBlock
        {
            Text = "اختر مجلدًا مرة واحدة. عند تفعيل الخيار، زر «حفظ TXT» يحفظ فيه مباشرة؛ وإلا تختار مكان الحفظ لكل طلب. الحفظ لا ينفّذ الأمر.",
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        commandExportContent.Children.Add(_commandRequestExportDirectory);
        var commandExportActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        var chooseExportDirectory = new Button { Content = "اختيار مجلد…" };
        chooseExportDirectory.Click += async (_, _) => await ChooseCommandRequestExportDirectoryAsync();
        var resetExportDirectory = new Button { Content = "اختيار المكان كل مرة" };
        resetExportDirectory.Click += async (_, _) => await ResetCommandRequestExportDirectoryAsync();
        commandExportActions.Children.Add(chooseExportDirectory);
        commandExportActions.Children.Add(resetExportDirectory);
        commandExportContent.Children.Add(commandExportActions);
        _useDefaultCommandRequestExportDirectory.Toggled += async (_, _) =>
        {
            if (_loading) return;
            var directory = _savedSettings["command_request_export_directory"] as string ?? "";
            if (_useDefaultCommandRequestExportDirectory.IsOn && string.IsNullOrWhiteSpace(directory))
            {
                _loading = true;
                _useDefaultCommandRequestExportDirectory.IsOn = false;
                _loading = false;
                _status.Text = "اختَر مجلد الحفظ أولًا.";
                return;
            }
            await SaveToggleAsync(_useDefaultCommandRequestExportDirectory, "use_default_command_request_export_directory", _useDefaultCommandRequestExportDirectory.IsOn);
        };
        commandExportContent.Children.Add(_useDefaultCommandRequestExportDirectory);
        stack.Children.Add(commandExports);

        var diagnostics = Card("التشخيص");
        var diagnosticsContent = (StackPanel)diagnostics.Child!;
        diagnosticsContent.Children.Add(new TextBlock
        {
            Text = "السجلات محلية، وتساعد في تحديد سبب فشل الاتصال أو التشغيل.",
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            TextWrapping = TextWrapping.Wrap,
        });
        var openDiagnostics = new Button { Content = "فتح مجلد سجل التشخيص" };
        openDiagnostics.Click += (_, _) => OpenDiagnosticsFolder();
        diagnosticsContent.Children.Add(openDiagnostics);
        stack.Children.Add(diagnostics);

        var intro = Card("مقدمة فيديو من اختيارك");
        var introContent = (StackPanel)intro.Child!;
        _introMode.Items.Add(new ComboBoxItem { Content = "تلقائي", Tag = "auto" });
        _introMode.Items.Add(new ComboBoxItem { Content = "يدوي", Tag = "manual" });
        _introMode.Items.Add(new ComboBoxItem { Content = "إيقاف", Tag = "off" });
        _introMode.SelectionChanged += async (_, _) =>
        {
            if (!_loading && _introMode.SelectedItem is ComboBoxItem item && item.Tag is string value)
            {
                await SaveSelectionAsync(_introMode, "intro_mode", value);
                UpdateIntroControls();
            }
        };
        introContent.Children.Add(Field("تشغيل المقدمة", _introMode));
        var videoRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, VerticalAlignment = VerticalAlignment.Center };
        var chooseVideo = new Button { Content = "اختيار فيديو…" };
        chooseVideo.Click += async (_, _) => await PickIntroAsync();
        videoRow.Children.Add(chooseVideo);
        videoRow.Children.Add(_introPath);
        introContent.Children.Add(videoRow);
        var introActions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        _playIntroButton.Click += async (_, _) => await PlayIntroAsync();
        _removeIntroButton.Click += async (_, _) => await RemoveIntroAsync();
        introActions.Children.Add(_playIntroButton);
        introActions.Children.Add(_removeIntroButton);
        introContent.Children.Add(introActions);
        introContent.Children.Add(new TextBlock
        {
            Text = "التلقائي يعمل عند فتح DeskTwin؛ اليدوي لا يعمل إلا من زر التشغيل؛ إيقاف يعطّل المقدمة.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        });
        stack.Children.Add(intro);

        stack.Children.Add(_status);
        root.Content = stack;
        Content = root;
        SelectByTag(_theme, WinUIHelpers.CurrentTheme == ElementTheme.Dark ? "dark" : "light");
        SelectByTag(_language, WinUIStrings.Language);
        SelectByTag(_introMode, "auto");
        SelectByTag(_activityLimit, "300");
        SelectByTag(_transferRetention, "24");
        _autoStart.IsOn = true;
        _reduceMotion.IsOn = false;
        _ = LoadAsync();
    }

    private async Task LoadAsync()
    {
        try
        {
            var values = await _bridge.CallAsync("get_settings") as System.Text.Json.Nodes.JsonObject;
            if (values is null)
                throw new InvalidOperationException(WinUIStrings.Translate("تعذر تحميل الإعدادات.", "Could not load settings."));
            var language = values["language"]?.GetValue<string>() ?? "en";
            var introMode = values["intro_mode"]?.GetValue<string>() ?? "auto";
            var activityLimit = values["activity_log_limit"]?.GetValue<int>() ?? 300;
            var transferRetention = values["incomplete_transfer_retention_hours"]?.GetValue<int>() ?? 24;
            var autoStart = values["auto_start_service"]?.GetValue<bool>() ?? true;
            var reduceMotion = values["reduce_motion"]?.GetValue<bool>() ?? false;
            _savedSettings["language"] = language;
            _savedSettings["intro_mode"] = introMode;
            _savedSettings["activity_log_limit"] = activityLimit;
            _savedSettings["incomplete_transfer_retention_hours"] = transferRetention;
            _savedSettings["auto_start_service"] = autoStart;
            _savedSettings["reduce_motion"] = reduceMotion;
            _savedSettings["custom_intro_video"] = values["custom_intro_video"]?.GetValue<string>() ?? "";
            _savedSettings["command_draft_directory"] = values["command_draft_directory"]?.GetValue<string>() ?? "";
            _savedSettings["command_request_export_directory"] = values["command_request_export_directory"]?.GetValue<string>() ?? "";
            _savedSettings["use_default_command_request_export_directory"] = values["use_default_command_request_export_directory"]?.GetValue<bool>() ?? false;
            var theme = values["theme_mode"]?.GetValue<string>() switch
            {
                "dark" => ElementTheme.Dark,
                "light" => ElementTheme.Light,
                _ => WinUIHelpers.CurrentTheme,
            };
            _savedSettings["theme_mode"] = theme == ElementTheme.Dark ? "dark" : "light";
            SelectByTag(_theme, theme == ElementTheme.Dark ? "dark" : "light");
            SelectByTag(_language, language);
            SelectByTag(_introMode, introMode);
            SelectByTag(_activityLimit, activityLimit.ToString(CultureInfo.InvariantCulture));
            SelectByTag(_transferRetention, transferRetention.ToString(CultureInfo.InvariantCulture));
            _autoStart.IsOn = autoStart;
            _reduceMotion.IsOn = reduceMotion;
            _selectedIntroPath = values["custom_intro_video"]?.GetValue<string>() ?? "";
            _introPath.Text = string.IsNullOrWhiteSpace(_selectedIntroPath)
                ? WinUIStrings.Translate("لم يتم اختيار فيديو", "No video selected")
                : _selectedIntroPath;
            _commandDirectory.Text = string.IsNullOrWhiteSpace(values["command_draft_directory"]?.GetValue<string>())
                ? WinUIStrings.Translate("سطح المكتب (افتراضي)", "Desktop (default)")
                : values["command_draft_directory"]!.GetValue<string>();
            _commandRequestExportDirectory.Text = string.IsNullOrWhiteSpace(values["command_request_export_directory"]?.GetValue<string>())
                ? WinUIStrings.Translate("اختيار المسار عند كل حفظ", "Choose a location each time")
                : values["command_request_export_directory"]!.GetValue<string>();
            _useDefaultCommandRequestExportDirectory.IsOn = values["use_default_command_request_export_directory"]?.GetValue<bool>() ?? false;
            _applyTheme(theme);
            await RefreshBoundUserAsync();
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر تحميل الإعدادات: ", "Could not load settings: ") + WinUIStrings.SafeMessage(ex.Message, "راجع سجل التشخيص لمزيد من التفاصيل.", "See diagnostics for details.");
        }
        finally
        {
            _loading = false;
            UpdateIntroControls();
        }
    }

    public async Task RefreshBoundUserAsync()
    {
        try
        {
            var user = await _bridge.CallAsync("get_bound_user") as System.Text.Json.Nodes.JsonObject;
            var name = user?["user_name"]?.GetValue<string>();
            var date = user?["bound_at"]?.GetValue<string>();
            _boundUser.Text = string.IsNullOrWhiteSpace(name)
                ? WinUIStrings.Translate("لسه محدش اتربط", "No user is linked yet")
                : $"{name} ({WinUIStrings.Translate("من", "since")} {date?.Split('T')[0] ?? "—"})";
        }
        catch (Exception ex)
        {
            _boundUser.Text = WinUIStrings.Translate("تعذر تحميل المستخدم المرتبط: ", "Could not load the linked user: ") + WinUIStrings.SafeMessage(ex.Message, "راجع سجل التشخيص لمزيد من التفاصيل.", "See diagnostics for details.");
        }
    }

    private async Task PickIntroAsync()
    {
        var picker = new FileOpenPicker();
        foreach (var type in new[] { ".mp4", ".m4v", ".mov", ".wmv", ".webm" }) picker.FileTypeFilter.Add(type);
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
        var file = await picker.PickSingleFileAsync();
        if (file is null) return;
        var previousMode = (_introMode.SelectedItem as ComboBoxItem)?.Tag as string ?? "off";
        if (!await SaveAsync("custom_intro_video", file.Path)) return;
        _selectedIntroPath = file.Path;
        _introPath.Text = file.Path;
        // A newly selected video is enabled by default. The user can switch
        // to Manual or Off immediately afterward.
        _loading = true;
        try { SelectByTag(_introMode, "auto"); }
        finally { _loading = false; }
        if (!await SaveAsync("intro_mode", "auto"))
        {
            _loading = true;
            try { SelectByTag(_introMode, previousMode); }
            finally { _loading = false; }
        }
        UpdateIntroControls();
    }

    private async Task PlayIntroAsync()
    {
        if (_introMode.SelectedItem is not ComboBoxItem item || Equals(item.Tag, "off"))
        {
            _status.Text = WinUIStrings.Translate(
                "المقدمة متوقفة. اختر تلقائي أو يدوي أولًا.",
                "The intro is off. Choose Automatic or Manual first.");
            return;
        }
        if (string.IsNullOrWhiteSpace(_selectedIntroPath) || !File.Exists(_selectedIntroPath))
        {
            _status.Text = WinUIStrings.Translate("اختر ملف فيديو موجودًا أولًا.", "Choose an existing video first.");
            UpdateIntroControls();
            return;
        }
        try
        {
            await _playIntro(_selectedIntroPath);
            _status.Text = WinUIStrings.Translate("انتهى تشغيل المقدمة.", "Intro playback finished.");
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر تشغيل الفيديو: ", "Could not play the video: ") + WinUIStrings.SafeMessage(ex.Message, "راجع سجل التشخيص لمزيد من التفاصيل.", "See diagnostics for details.");
        }
    }

    private async Task RemoveIntroAsync()
    {
        if (!await SaveAsync("custom_intro_video", "")) return;
        _selectedIntroPath = "";
        _introPath.Text = WinUIStrings.Translate("لم يتم اختيار فيديو", "No video selected");
        UpdateIntroControls();
    }

    private async Task ChooseCommandDirectoryAsync()
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        if (await SaveAsync("command_draft_directory", folder.Path)) _commandDirectory.Text = folder.Path;
    }

    private async Task ResetCommandDirectoryAsync()
    {
        if (await SaveAsync("command_draft_directory", "")) _commandDirectory.Text = WinUIStrings.Translate("سطح المكتب (افتراضي)", "Desktop (default)");
    }

    private async Task ChooseCommandRequestExportDirectoryAsync()
    {
        var picker = new FolderPicker();
        picker.FileTypeFilter.Add("*");
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
        var folder = await picker.PickSingleFolderAsync();
        if (folder is null) return;
        if (await SaveAsync("command_request_export_directory", folder.Path))
            _commandRequestExportDirectory.Text = folder.Path;
    }

    private async Task ResetCommandRequestExportDirectoryAsync()
    {
        if (_useDefaultCommandRequestExportDirectory.IsOn)
        {
            _loading = true;
            _useDefaultCommandRequestExportDirectory.IsOn = false;
            _loading = false;
            await SaveToggleAsync(_useDefaultCommandRequestExportDirectory, "use_default_command_request_export_directory", false);
            if (_useDefaultCommandRequestExportDirectory.IsOn) return;
        }
        if (await SaveAsync("command_request_export_directory", ""))
            _commandRequestExportDirectory.Text = WinUIStrings.Translate("اختيار المسار عند كل حفظ", "Choose a location each time");
    }

    private void OpenDiagnosticsFolder()
    {
        try
        {
            var path = Path.Combine(_bridge.ProjectRoot, "data", "logs");
            Directory.CreateDirectory(path);
            Process.Start(new ProcessStartInfo { FileName = path, UseShellExecute = true });
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر فتح مجلد التشخيص: ", "Could not open the diagnostics folder: ") + WinUIStrings.SafeMessage(ex.Message, "راجع سجل التشخيص لمزيد من التفاصيل.", "See diagnostics for details.");
        }
    }

    private void UpdateIntroControls()
    {
        var mode = (_introMode.SelectedItem as ComboBoxItem)?.Tag as string ?? "off";
        var hasVideo = !string.IsNullOrWhiteSpace(_selectedIntroPath) && File.Exists(_selectedIntroPath);
        _playIntroButton.IsEnabled = mode != "off" && hasVideo;
        _removeIntroButton.IsEnabled = !string.IsNullOrWhiteSpace(_selectedIntroPath);
    }

    private async Task<bool> SaveAsync(string key, object value)
    {
        if (!_savingSettings.Add(key))
        {
            _status.Text = WinUIStrings.Translate("جارٍ حفظ هذا الإعداد…", "This setting is already being saved…");
            return false;
        }
        try
        {
            await _bridge.CallAsync("set_setting", new { key, value });
            _savedSettings[key] = value;
            _status.Text = WinUIStrings.Translate("تم حفظ الإعداد.", "Setting saved.");
            return true;
        }
        catch (Exception ex)
        {
            _status.Text = WinUIStrings.Translate("تعذر حفظ الإعداد: ", "Could not save the setting: ") + WinUIStrings.SafeMessage(ex.Message, "راجع سجل التشخيص لمزيد من التفاصيل.", "See diagnostics for details.");
            return false;
        }
        finally { _savingSettings.Remove(key); }
    }

    private async Task SaveSelectionAsync(ComboBox combo, string key, object value, Action? onSaved = null)
    {
        var hadSavedValue = _savedSettings.TryGetValue(key, out var savedValue);
        combo.IsEnabled = false;
        try
        {
            if (await SaveAsync(key, value))
            {
                onSaved?.Invoke();
                return;
            }
            if (hadSavedValue)
            {
                var tag = savedValue is IFormattable formattable
                    ? formattable.ToString(null, CultureInfo.InvariantCulture) ?? ""
                    : savedValue?.ToString() ?? "";
                var wasLoading = _loading;
                _loading = true;
                SelectByTag(combo, tag);
                _loading = wasLoading;
            }
        }
        finally { combo.IsEnabled = true; }
    }

    private async Task SaveToggleAsync(ToggleSwitch toggle, string key, bool value)
    {
        var previousValue = _savedSettings.TryGetValue(key, out var savedValue) && savedValue is bool savedBool
            ? savedBool
            : !value;
        toggle.IsEnabled = false;
        try
        {
            if (await SaveAsync(key, value)) return;
            var wasLoading = _loading;
            _loading = true;
            toggle.IsOn = previousValue;
            _loading = wasLoading;
        }
        finally { toggle.IsEnabled = true; }
    }

    private static void SelectByTag(ComboBox combo, string tag)
    {
        combo.SelectedItem = combo.Items.OfType<ComboBoxItem>().FirstOrDefault(item => Equals(item.Tag, tag));
    }

    private static Border Card(string title)
    {
        var border = new Border
        {
            Padding = new Thickness(18),
            CornerRadius = new CornerRadius(12),
            Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
            BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
        };
        var stack = new StackPanel { Spacing = 12 };
        stack.Children.Add(new TextBlock { Text = title, FontSize = 16, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold });
        border.Child = stack;
        return border;
    }

    private static StackPanel Field(string label, Control control)
    {
        var stack = new StackPanel { Spacing = 8 };
        stack.Children.Add(new TextBlock { Text = label, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
        stack.Children.Add(control);
        return stack;
    }
}
