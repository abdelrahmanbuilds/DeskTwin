using System.Text.Json.Nodes;
using System.ComponentModel;
using System.Windows.Input;
using System.IO;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.Storage.Streams;
using WinRT.Interop;

namespace DeskTwin.WinUI;

/// <summary>
/// SHAREit-inspired phone workspace. It deliberately renders only data exposed by
/// DeskTwin's granted SAF tree or explicitly granted shared-storage scope; it never invents device capacity.
/// </summary>
public sealed class PhoneAssistantWorkspacePage : Page
{
    private const int GalleryBatchSize = 24;
    private const int CatalogPageSize = 24;
    private const long ThumbnailCacheMaxBytes = 128L * 1024 * 1024;
    private const int ThumbnailCacheMaxEntries = 1024;
    private static string TemporaryMediaDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskTwin", "PhoneMedia");
    private static string ThumbnailCacheDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "DeskTwin", "PhoneThumbnails");

    public static void ClearTemporaryMediaCache()
    {
        try
        {
            if (Directory.Exists(TemporaryMediaDirectory)) Directory.Delete(TemporaryMediaDirectory, recursive: true);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private sealed record Category(string Id, string Name, string Glyph);
    private sealed class MediaCard : INotifyPropertyChanged
    {
        private bool _isSelected;
        private BitmapImage? _thumbnail;
        public string Name { get; init; } = "";
        public string Path { get; init; } = "";
        public string Details { get; init; } = "";
        private string _durationText = "";
        public string DurationText
        {
            get => _durationText;
            set
            {
                if (_durationText == value) return;
                _durationText = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(DurationText)));
            }
        }
        public BitmapImage? Thumbnail
        {
            get => _thumbnail;
            set
            {
                if (ReferenceEquals(_thumbnail, value)) return;
                _thumbnail = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Thumbnail)));
            }
        }
        public ICommand? PreviewCommand { get; set; }
        public ICommand? DownloadCommand { get; set; }
        public ICommand? DeleteCommand { get; set; }
        public JsonObject Source { get; init; } = new();
        public bool IsSelected
        {
            get => _isSelected;
            set
            {
                if (_isSelected == value) return;
                _isSelected = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsSelected)));
            }
        }
        public event PropertyChangedEventHandler? PropertyChanged;
    }

    private sealed class ImagePreviewControls
    {
        private readonly ScrollViewer _viewer;
        private readonly Image _image;
        private readonly Border _stage;
        private readonly Grid _stageHost;
        private readonly double _aspectRatio;

        public Grid Root { get; }

        public ImagePreviewControls(BitmapImage source, double width, double height, Action toggleFullscreen)
        {
            Root = new Grid { Width = width, Height = height, Background = WinUIHelpers.Brush("LayerFillColorDefaultBrush") };
            Root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            Root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            _aspectRatio = source.PixelHeight > 0 ? (double)source.PixelWidth / source.PixelHeight : 1;
            _image = new Image { Source = source, Stretch = Stretch.Uniform };
            _viewer = new ScrollViewer
            {
                Content = _image,
                ZoomMode = ZoomMode.Enabled,
                MinZoomFactor = 1f,
                MaxZoomFactor = 4f,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
                VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
                HorizontalContentAlignment = HorizontalAlignment.Center,
                VerticalContentAlignment = VerticalAlignment.Center,
            };
            _stage = new Border
            {
                Background = new SolidColorBrush(Colors.Black),
                BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Child = _viewer,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
            _stageHost = new Grid();
            _stageHost.Children.Add(_stage);
            Grid.SetRow(_stageHost, 0);
            Root.Children.Add(_stageHost);

            var toolbar = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Spacing = 8 };
            var zoomOut = CreatePreviewButton("−", WinUIStrings.Translate("تصغير الصورة", "Zoom out"), 44);
            var zoomIn = CreatePreviewButton("+", WinUIStrings.Translate("تكبير الصورة", "Zoom in"), 44);
            var fullscreen = CreatePreviewButton("⛶ " + WinUIStrings.Translate("شاشة كاملة", "Fullscreen"), WinUIStrings.Translate("تبديل الشاشة الكاملة", "Toggle fullscreen"), 132);
            zoomOut.Click += (_, _) => SetZoom(_viewer.ZoomFactor - 0.25f);
            zoomIn.Click += (_, _) => SetZoom(_viewer.ZoomFactor + 0.25f);
            fullscreen.Click += (_, _) => toggleFullscreen();
            toolbar.Children.Add(zoomOut);
            toolbar.Children.Add(zoomIn);
            toolbar.Children.Add(fullscreen);
            var toolbarFrame = new Border
            {
                Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
                BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(8),
                Padding = new Thickness(12, 8, 12, 8),
                Margin = new Thickness(0, 12, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Center,
                Child = toolbar,
            };
            Grid.SetRow(toolbarFrame, 1);
            Root.Children.Add(toolbarFrame);
            _stageHost.SizeChanged += (_, _) => UpdateStageSize();
            Root.SizeChanged += (_, _) => UpdateStageSize();
            Root.Loaded += (_, _) => UpdateStageSize();
        }

        private void SetZoom(float value)
        {
            _viewer.ChangeView(null, null, Math.Clamp(value, 1f, 4f), disableAnimation: true);
        }

        private static Button CreatePreviewButton(string label, string tooltip, double width)
        {
            var button = new Button
            {
                Content = label,
                Width = width,
                Height = 38,
                Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
                FontSize = 14,
            };
            ToolTipService.SetToolTip(button, tooltip);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, tooltip);
            return button;
        }

        public void SetSize(double width, double height)
        {
            Root.Width = width;
            Root.Height = height;
            UpdateStageSize();
        }

        private void UpdateStageSize()
        {
            var availableWidth = Math.Max(1, _stageHost.ActualWidth - 24);
            var availableHeight = Math.Max(1, _stageHost.ActualHeight - 24);
            var stageWidth = availableWidth;
            var stageHeight = stageWidth / _aspectRatio;
            if (stageHeight > availableHeight)
            {
                stageHeight = availableHeight;
                stageWidth = stageHeight * _aspectRatio;
            }
            _stage.Width = stageWidth;
            _stage.Height = stageHeight;
            _viewer.Width = stageWidth;
            _viewer.Height = stageHeight;
            _image.Width = stageWidth;
            _image.Height = stageHeight;
        }
    }

    private sealed class MediaPreviewControls : IDisposable
    {
        private readonly MediaPlayer _player;
        private readonly Slider _timeline;
        private readonly TextBlock _positionText;
        private readonly TextBlock _durationText;
        private readonly Button _playButton;
        private readonly Button _fullscreenButton;
        private readonly TimeSpan? _fallbackDuration;
        private Image? _audioArtwork;
        private readonly DispatcherTimer _timer;
        private bool _updatingTimeline;
        private bool _dragging;

        public Grid Root { get; }

        public MediaPreviewControls(MediaPlayer player, double width, double height, Action? toggleFullscreen = null, bool audioOnly = false, BitmapImage? artwork = null, TimeSpan? fallbackDuration = null)
        {
            _player = player;
            _fallbackDuration = fallbackDuration;
            Root = new Grid { Width = width, Height = height, Background = new SolidColorBrush(Colors.Black) };
            Root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            Root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            UIElement stageContent;
            if (audioOnly)
            {
                var audioStack = new StackPanel
                {
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center,
                    Spacing = 12,
                };
                var coverContent = new Grid();
                coverContent.Children.Add(new TextBlock { Text = "♫", FontSize = 72, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Foreground = WinUIHelpers.Brush("TextOnAccentFillColorPrimaryBrush") });
                _audioArtwork = new Image { Source = artwork, Stretch = Stretch.UniformToFill, Visibility = artwork is null ? Visibility.Collapsed : Visibility.Visible };
                coverContent.Children.Add(_audioArtwork);
                var cover = new Border
                {
                    Width = 176,
                    Height = 176,
                    HorizontalAlignment = HorizontalAlignment.Center,
                    Background = WinUIHelpers.Brush("AccentFillColorDefaultBrush"),
                    CornerRadius = new CornerRadius(12),
                    Child = coverContent,
                };
                audioStack.Children.Add(cover);
                var audioCard = new Border
                {
                    Background = WinUIHelpers.Brush("LayerFillColorDefaultBrush"),
                    CornerRadius = new CornerRadius(10),
                    Child = audioStack,
                };
                stageContent = audioCard;
            }
            else
            {
                var media = new MediaPlayerElement
                {
                    AreTransportControlsEnabled = false,
                    AutoPlay = false,
                    // Keep the native video surface and its coordinates LTR inside
                    // the Arabic RTL page. RTL on MediaPlayerElement mirrors video.
                    FlowDirection = FlowDirection.LeftToRight,
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    VerticalAlignment = VerticalAlignment.Stretch,
                    Stretch = Stretch.Uniform,
                    Background = new SolidColorBrush(Colors.Black),
                };
                media.SetMediaPlayer(player);
                stageContent = media;
            }
            var stage = new Border
            {
                Child = stageContent,
                Background = new SolidColorBrush(Colors.Black),
                BorderThickness = new Thickness(0),
                CornerRadius = new CornerRadius(0),
                HorizontalAlignment = HorizontalAlignment.Stretch,
                VerticalAlignment = VerticalAlignment.Stretch,
                FlowDirection = FlowDirection.LeftToRight,
            };
            var stageHost = new Grid
            {
                Background = new SolidColorBrush(Colors.Black),
                FlowDirection = FlowDirection.LeftToRight,
            };
            stageHost.Children.Add(stage);
            Grid.SetRow(stageHost, 0);
            Root.Children.Add(stageHost);
            Root.Loaded += (_, _) =>
            {
                // Starting before the dialog is loaded can initialize audio without
                // attaching the video surface. Retry after the visual tree is ready.
                _player.Play();
                RefreshTimeline();
            };

            _playButton = new Button { Content = "Ⅱ", MinWidth = 42, Height = 34 };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_playButton, WinUIStrings.Translate("تشغيل أو إيقاف مؤقت", "Play or pause"));
            _playButton.Click += (_, _) =>
            {
                if (_player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing) _player.Pause();
                else _player.Play();
                UpdatePlayButton();
            };
            _positionText = new TextBlock { Text = "0:00", MinWidth = 48, VerticalAlignment = VerticalAlignment.Center, TextAlignment = TextAlignment.Center };
            _durationText = new TextBlock { Text = "--:--", MinWidth = 48, VerticalAlignment = VerticalAlignment.Center, TextAlignment = TextAlignment.Center };
            _timeline = new Slider
            {
                Minimum = 0,
                Maximum = 1,
                IsEnabled = false,
                FlowDirection = FlowDirection.LeftToRight,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(4, 0, 4, 0),
            };
            _timeline.PointerPressed += (_, _) => _dragging = true;
            _timeline.PointerReleased += (_, _) => SeekToTimeline();
            _timeline.PointerCaptureLost += (_, _) => SeekToTimeline();
            _timeline.KeyUp += (_, _) => SeekToTimeline();
            _timeline.ValueChanged += (_, args) =>
            {
                if (_updatingTimeline) return;
                _positionText.Text = FormatTimeline(TimeSpan.FromSeconds(args.NewValue));
                if (!_dragging && _timeline.IsEnabled)
                    _player.PlaybackSession.Position = TimeSpan.FromSeconds(args.NewValue);
            };

            var controls = new Grid { ColumnSpacing = 6, Padding = new Thickness(12, 8, 12, 8), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            controls.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var backButton = CreateSeekButton("−10", WinUIStrings.Translate("رجوع 10 ثوانٍ", "Back 10 seconds"), -10);
            Grid.SetColumn(backButton, 0); controls.Children.Add(backButton);
            _playButton.Width = 48;
            _playButton.Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush");
            _playButton.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
            _playButton.BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush");
            _playButton.BorderThickness = new Thickness(1);
            _positionText.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
            _durationText.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
            _timeline.MinWidth = 80;
            Grid.SetColumn(_playButton, 1); controls.Children.Add(_playButton);
            Grid.SetColumn(_positionText, 2); controls.Children.Add(_positionText);
            Grid.SetColumn(_timeline, 3); controls.Children.Add(_timeline);
            Grid.SetColumn(_durationText, 4); controls.Children.Add(_durationText);
            _fullscreenButton = new Button
            {
                Content = "⛶ " + WinUIStrings.Translate("شاشة كاملة", "Fullscreen"),
                MinWidth = 40,
                Height = 38,
                Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
            };
            ToolTipService.SetToolTip(_fullscreenButton, WinUIStrings.Translate("تبديل الشاشة الكاملة", "Toggle fullscreen"));
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(_fullscreenButton, WinUIStrings.Translate("تبديل الشاشة الكاملة", "Toggle fullscreen"));
            _fullscreenButton.Click += (_, _) => toggleFullscreen?.Invoke();
            var forwardButton = CreateSeekButton("+10", WinUIStrings.Translate("تقديم 10 ثوانٍ", "Forward 10 seconds"), 10);
            Grid.SetColumn(forwardButton, 5); controls.Children.Add(forwardButton);
            Grid.SetColumn(_fullscreenButton, 6); controls.Children.Add(_fullscreenButton);
            controls.SizeChanged += (_, args) =>
            {
                var compact = args.NewSize.Width < 620;
                _timeline.MinWidth = compact ? 40 : 120;
                _positionText.MinWidth = compact ? 40 : 48;
                _durationText.MinWidth = compact ? 40 : 48;
                _fullscreenButton.Content = compact ? "⛶" : "⛶ " + WinUIStrings.Translate("شاشة كاملة", "Fullscreen");
                _fullscreenButton.MinWidth = compact ? 40 : 132;
            };
            Grid.SetRow(controls, 1);
            Root.Children.Add(controls);

            _timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(200) };
            _timer.Tick += (_, _) => RefreshTimeline();
            _timer.Start();
            RefreshTimeline();
        }

        public void SetSize(double width, double height)
        {
            Root.Width = width;
            Root.Height = height;
        }

        private Button CreateSeekButton(string label, string tooltip, double seconds)
        {
            var button = new Button
            {
                Content = label,
                MinWidth = 44,
                Height = 38,
                Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
            };
            ToolTipService.SetToolTip(button, tooltip);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, tooltip);
            button.Click += (_, _) =>
            {
                var session = _player.PlaybackSession;
                var duration = session.NaturalDuration > TimeSpan.Zero ? session.NaturalDuration : _fallbackDuration ?? TimeSpan.Zero;
                var target = session.Position + TimeSpan.FromSeconds(seconds);
                if (target < TimeSpan.Zero) target = TimeSpan.Zero;
                if (duration > TimeSpan.Zero && target > duration) target = duration;
                if (session.CanSeek) session.Position = target;
                RefreshTimeline();
            };
            return button;
        }

        public void SetArtwork(BitmapImage? artwork)
        {
            if (artwork is null || _audioArtwork is null) return;
            _audioArtwork.Source = artwork;
            _audioArtwork.Visibility = Visibility.Visible;
        }

        private void RefreshTimeline()
        {
            var session = _player.PlaybackSession;
            var duration = session.NaturalDuration > TimeSpan.Zero ? session.NaturalDuration : _fallbackDuration ?? TimeSpan.Zero;
            var durationSeconds = duration > TimeSpan.Zero ? duration.TotalSeconds : 0;
            _timeline.IsEnabled = session.CanSeek && durationSeconds > 0;
            _updatingTimeline = true;
            _timeline.Maximum = Math.Max(1, durationSeconds);
            if (!_dragging) _timeline.Value = Math.Clamp(session.Position.TotalSeconds, 0, _timeline.Maximum);
            _updatingTimeline = false;
            if (!_dragging) _positionText.Text = FormatTimeline(session.Position);
            _durationText.Text = durationSeconds > 0 ? FormatTimeline(duration) : "--:--";
            UpdatePlayButton();
        }

        private void SeekToTimeline()
        {
            if (!_dragging) return;
            _dragging = false;
            if (_timeline.IsEnabled) _player.PlaybackSession.Position = TimeSpan.FromSeconds(_timeline.Value);
            RefreshTimeline();
        }

        private void UpdatePlayButton() => _playButton.Content =
            _player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing ? "Ⅱ" : "▶";

        private static string FormatTimeline(TimeSpan value) => value.TotalHours >= 1
            ? value.ToString(@"h\:mm\:ss")
            : $"{(int)value.TotalMinutes}:{value.Seconds:00}";

        public void Dispose() => _timer.Stop();
    }

    private sealed class PreviewOverlay : IDisposable
    {
        private readonly Grid _owner;
        private readonly Grid _overlay;
        private readonly Border _panel;
        private readonly Grid _contentHost;
        private readonly TaskCompletionSource<bool> _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private Action<double, double>? _resizeContent;
        private bool _fullscreen;

        public PreviewOverlay(Grid owner, string title)
        {
            _owner = owner;
            _overlay = new Grid { Background = new SolidColorBrush(Colors.Black) { Opacity = 0.58 } };
            var layout = new Grid();
            layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            layout.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            layout.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            var heading = new Grid { Padding = new Thickness(20, 14, 16, 12), ColumnSpacing = 12 };
            heading.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            heading.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            heading.Children.Add(new TextBlock
            {
                Text = title,
                FontSize = 18,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                TextTrimming = TextTrimming.CharacterEllipsis,
                VerticalAlignment = VerticalAlignment.Center,
            });
            var closeTop = CreateButton("×", WinUIStrings.Translate("إغلاق المعاينة", "Close preview"));
            closeTop.Width = 40;
            closeTop.MinWidth = 40;
            Grid.SetColumn(closeTop, 1);
            heading.Children.Add(closeTop);
            Grid.SetRow(heading, 0);
            layout.Children.Add(heading);

            _contentHost = new Grid { Padding = new Thickness(0) };
            Grid.SetRow(_contentHost, 1);
            layout.Children.Add(_contentHost);

            var footer = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Right,
                Spacing = 10,
                Padding = new Thickness(16, 12, 20, 16),
                Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
            };
            var download = CreateButton(WinUIStrings.Translate("تنزيل إلى الكمبيوتر", "Download to PC"), WinUIStrings.Translate("تنزيل إلى الكمبيوتر", "Download to PC"));
            var close = CreateButton(WinUIStrings.Translate("إغلاق", "Close"), WinUIStrings.Translate("إغلاق المعاينة", "Close preview"));
            closeTop.Click += (_, _) => _closed.TrySetResult(false);
            close.Click += (_, _) => _closed.TrySetResult(false);
            download.Click += (_, _) => _closed.TrySetResult(true);
            footer.Children.Add(download);
            footer.Children.Add(close);
            Grid.SetRow(footer, 2);
            layout.Children.Add(footer);

            _panel = new Border
            {
                Background = WinUIHelpers.Brush("SolidBackgroundFillColorBaseBrush"),
                BorderThickness = new Thickness(0),
                CornerRadius = new CornerRadius(0),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Child = layout,
            };
            _overlay.Children.Add(_panel);
            _overlay.SizeChanged += (_, _) => UpdateBounds();
            _contentHost.SizeChanged += (_, _) => ResizeContent();
            _overlay.KeyDown += (_, args) =>
            {
                if (args.Key == Windows.System.VirtualKey.Escape)
                {
                    _closed.TrySetResult(false);
                    args.Handled = true;
                }
                else if (args.Key == Windows.System.VirtualKey.F11)
                {
                    ToggleFullscreen();
                    args.Handled = true;
                }
            };
            _overlay.Loaded += (_, _) =>
            {
                UpdateBounds();
                closeTop.Focus(FocusState.Programmatic);
            };
        }

        private static Button CreateButton(string label, string tooltip)
        {
            var button = new Button
            {
                Content = label,
                MinWidth = 108,
                Height = 38,
                Padding = new Thickness(12, 6, 12, 6),
                Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
                BorderThickness = new Thickness(1),
            };
            ToolTipService.SetToolTip(button, tooltip);
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, tooltip);
            return button;
        }

        public void SetContent(UIElement content, Action<double, double> resize)
        {
            _contentHost.Children.Clear();
            if (content is FrameworkElement element)
            {
                element.Width = double.NaN;
                element.Height = double.NaN;
            }
            _contentHost.Children.Add(content);
            _resizeContent = resize;
            ResizeContent();
        }

        public async Task<bool> ShowAsync()
        {
            _owner.Children.Add(_overlay);
            Grid.SetRowSpan(_overlay, Math.Max(1, _owner.RowDefinitions.Count));
            Grid.SetColumnSpan(_overlay, Math.Max(1, _owner.ColumnDefinitions.Count));
            UpdateBounds();
            try { return await _closed.Task; }
            finally { _owner.Children.Remove(_overlay); }
        }

        public void ToggleFullscreen()
        {
            _fullscreen = !_fullscreen;
            UpdateBounds();
        }

        private void UpdateBounds()
        {
            var width = Math.Max(1, _overlay.ActualWidth);
            var height = Math.Max(1, _overlay.ActualHeight);
            var inset = _fullscreen ? 0 : Math.Min(40, Math.Max(8, width * 0.025));
            _panel.Width = Math.Max(1, width - inset * 2);
            _panel.Height = Math.Max(1, height - inset * 2);
            ResizeContent();
        }

        private void ResizeContent()
        {
            if (_contentHost.ActualWidth <= 0 || _contentHost.ActualHeight <= 0) return;
            _resizeContent?.Invoke(Math.Max(1, _contentHost.ActualWidth), Math.Max(1, _contentHost.ActualHeight));
        }

        public void Dispose()
        {
            _closed.TrySetResult(false);
            _owner.Children.Remove(_overlay);
        }
    }

    private sealed class AsyncCommand(Func<Task> execute) : ICommand
    {
        public event EventHandler? CanExecuteChanged { add { } remove { } }
        public bool CanExecute(object? parameter) => true;
        public async void Execute(object? parameter) => await execute();
    }

    private static readonly Category[] Categories =
    {
        new("photos", "Photos", "▧"),
        new("videos", "Videos", "▶"),
        new("apps", "Apps", "▦"),
        new("music", "Music", "♫"),
        new("docs", "Docs", "▤"),
        new("storage", "Storage", "▣"),
        new("transfer", "Transfer", "⇄"),
    };

    private readonly DesktopBridge _bridge;
    private readonly SemaphoreSlim _catalogGate = new(1, 1);
    private readonly Action _back;
    private readonly Grid _shellRoot = new();
    private readonly StackPanel _categoryList = new() { Spacing = 4 };
    private readonly Grid _content = new();
    private readonly TextBox _search = new() { PlaceholderText = WinUIStrings.Translate("ابحث في الصور", "Search all photos"), HorizontalAlignment = HorizontalAlignment.Stretch };
    private readonly TextBlock _deviceStatus = new();
    private readonly TextBlock _capacityText = new();
    private readonly Border _capacityTrack = new();
    private readonly Border _capacityFill = new();
    private readonly TextBlock _pageTitle = new();
    private readonly TextBlock _pageCount = new();
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap };
    private readonly Button _refresh = new() { Content = "↻", Width = 38, Height = 34 };
    private readonly List<JsonObject> _entries = new();
    private readonly List<JsonObject> _storageRoots = new();
    private readonly Dictionary<string, BitmapImage> _thumbnailCache = new(StringComparer.Ordinal);
    private sealed record CatalogSnapshot(List<JsonObject> Entries, int NextOffset, bool HasMore, int TotalCount);
    private readonly Dictionary<string, CatalogSnapshot> _catalogSnapshots = new(StringComparer.Ordinal);
    private readonly object _thumbnailCacheLock = new();
    private readonly SemaphoreSlim _thumbnailLoadGate = new(4);
    private readonly SemaphoreSlim _mediaInfoLoadGate = new(2);
    private readonly Dictionary<string, string> _temporaryMedia = new(StringComparer.Ordinal);
    private ScrollViewer? _galleryScrollViewer;
    private string _selected = "photos";
    private bool _controlConnected;
    private bool _phoneFilesConnected;
    private bool _transferLoadInProgress;
    private bool _loadingMore;
    private bool _advancingGallery;
    private bool _hasMore;
    private int _loadVersion;
    private int _totalCount;
    private int _nextOffset;
    private int _galleryVisibleCount = GalleryBatchSize;
    private double _galleryScrollOffset;
    private string? _loadError;
    private CancellationTokenSource? _searchReload;
    private CancellationTokenSource? _previewCancellation;
    private readonly DispatcherTimer _connectionTimer = new() { Interval = TimeSpan.FromSeconds(3) };
    private bool _connectionPollRunning;

    public PhoneAssistantWorkspacePage(DesktopBridge bridge, Action back)
    {
        _bridge = bridge;
        _back = back;
        Content = BuildShell();
        _search.TextChanged += (_, _) => _ = ReloadForSearchAsync();
        _connectionTimer.Tick += async (_, _) => await PollPhoneConnectionAsync();
        _refresh.Click += async (_, _) =>
        {
            _catalogSnapshots.Clear();
            RenderLoadingCategory();
            if (_phoneFilesConnected)
                try { await _bridge.CallAsync("phone_files_action", new { operation = "refresh" }); }
                catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Phone catalog refresh hint failed: {ex.Message}"); }
            await LoadAsync();
        };
        Unloaded += (_, _) =>
        {
            _connectionTimer.Stop();
            try { _searchReload?.Cancel(); } catch (ObjectDisposedException) { }
            CancelPreviewLoads();
        };
        Loaded += (_, _) => _connectionTimer.Start();
        RenderLoadingCategory();
        _ = LoadAsync();
    }

    private UIElement BuildShell()
    {
        var root = _shellRoot;
        root.FlowDirection = WinUIStrings.Language == "ar" ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        root.Background = WinUIHelpers.Brush("AppBackground");
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(66) });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(196) });
        root.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var top = new Grid
        {
            Background = WinUIHelpers.Brush("AppBackground"),
            Padding = new Thickness(18, 0, 22, 0),
            ColumnSpacing = 24,
            BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(0, 0, 0, 1),
        };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("مكتبة الهاتف", "Phone library"),
            FontSize = 16,
            FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
            Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
            VerticalAlignment = VerticalAlignment.Center,
        });

        var device = new Grid { ColumnSpacing = 10, VerticalAlignment = VerticalAlignment.Center };
        device.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        device.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        device.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(190) });
        device.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        device.Children.Add(new Border
        {
            Width = 34, Height = 34, CornerRadius = new CornerRadius(17), Background = WinUIHelpers.Brush("ControlFillColorSecondaryBrush"),
            Child = new TextBlock { Text = "▯", FontSize = 20, Foreground = WinUIHelpers.Brush("AccentTextFillColorPrimaryBrush"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        });
        var deviceName = new StackPanel { Spacing = 2, VerticalAlignment = VerticalAlignment.Center };
        deviceName.Children.Add(_deviceStatus);
        _deviceStatus.Text = "Phone not connected";
        _deviceStatus.FontSize = 13;
        _deviceStatus.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
        _capacityText.Text = "Storage details unavailable";
        _capacityText.FontSize = 11;
        _capacityText.Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush");
        deviceName.Children.Add(_capacityText);
        Grid.SetColumn(deviceName, 1);
        device.Children.Add(deviceName);
        var capacity = new Grid { Height = 5, VerticalAlignment = VerticalAlignment.Center, Background = WinUIHelpers.Brush("LayerFillColorAltBrush") };
        _capacityTrack.Child = capacity;
        _capacityTrack.CornerRadius = new CornerRadius(3);
        _capacityFill.Width = 0;
        _capacityFill.HorizontalAlignment = HorizontalAlignment.Left;
        _capacityFill.Background = WinUIHelpers.Brush("AccentFillColorDefaultBrush");
        _capacityFill.CornerRadius = new CornerRadius(3);
        capacity.Children.Add(_capacityFill);
        Grid.SetColumn(_capacityTrack, 2);
        device.Children.Add(_capacityTrack);
        var settingsButton = new Button
        {
            Content = "⚙", Width = 36, Height = 36,
            Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
            Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
            BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
        };
        ApplyButtonHover(settingsButton, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
        ToolTipService.SetToolTip(settingsButton, WinUIStrings.Translate("الإعدادات", "Settings"));
        settingsButton.Click += async (_, _) => await ShowPhoneTransferSettingsAsync();
        Grid.SetColumn(settingsButton, 3);
        device.Children.Add(settingsButton);
        Grid.SetColumn(device, 1);
        top.Children.Add(device);
        Grid.SetColumn(top, 0);
        Grid.SetColumnSpan(top, 2);
        Grid.SetRow(top, 0);
        root.Children.Add(top);

        var categoryRail = new Grid
        {
            Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush"),
            BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(0, 0, 1, 0),
            Padding = new Thickness(10, 20, 10, 12),
        };
        categoryRail.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        categoryRail.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        categoryRail.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var phoneTitle = new TextBlock { Text = WinUIStrings.Translate("محتوى الهاتف", "Phone content"), FontSize = 14, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), Margin = new Thickness(8, 0, 0, 16) };
        categoryRail.Children.Add(phoneTitle);
        Grid.SetRow(_categoryList, 1); categoryRail.Children.Add(_categoryList);
        foreach (var category in Categories)
        {
            var item = RailButton(category.Glyph, LocalizedCategoryName(category.Id), selected: category.Id == _selected);
            item.Tag = category.Id;
            item.Click += (_, _) => SelectCategory(category.Id);
            _categoryList.Children.Add(item);
        }
        var back = new Button
        {
            Content = WinUIStrings.Translate("العودة إلى DeskTwin  →", "←  Back to DeskTwin"),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Left,
            Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
            BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
        };
        ApplyButtonHover(back, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
        back.Click += (_, _) => _back();
        Grid.SetRow(back, 2); categoryRail.Children.Add(back);
        Grid.SetColumn(categoryRail, 0); Grid.SetRow(categoryRail, 1); root.Children.Add(categoryRail);

        var workspace = new Grid { Background = WinUIHelpers.Brush("AppBackground"), Padding = new Thickness(24, 16, 28, 20), RowSpacing = 14 };
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        workspace.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        workspace.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var toolbar = new Grid { ColumnSpacing = 10 };
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var upload = new Button
        {
            Content = WinUIStrings.Translate("↑  رفع إلى الهاتف", "↑  Upload to Phone"),
            Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"),
            Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
            Padding = new Thickness(12, 7, 12, 7),
            BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"),
            BorderThickness = new Thickness(1),
        };
        ApplyButtonHover(upload, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
        upload.Click += async (_, _) => await UploadToPhoneAsync();
        toolbar.Children.Add(upload);
        _search.Margin = new Thickness(18, 0, 0, 0);
        Grid.SetColumn(_search, 1); toolbar.Children.Add(_search);
        _refresh.Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush");
        _refresh.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
        _refresh.BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush");
        _refresh.BorderThickness = new Thickness(1);
        ApplyButtonHover(_refresh, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
        ToolTipService.SetToolTip(_refresh, WinUIStrings.Translate("تحديث ملفات الهاتف", "Refresh phone files"));
        Grid.SetColumn(_refresh, 2); toolbar.Children.Add(_refresh);
        workspace.Children.Add(toolbar);

        var titleRow = new Grid { ColumnSpacing = 12 };
        titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        _pageTitle.Text = LocalizedCategoryName("photos");
        _pageTitle.FontSize = 14;
        _pageTitle.FontWeight = Microsoft.UI.Text.FontWeights.SemiBold;
        _pageTitle.Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush");
        titleRow.Children.Add(_pageTitle);
        _pageCount.Text = "";
        _pageCount.FontSize = 12;
        _pageCount.Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush");
        _pageCount.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_pageCount, 1); titleRow.Children.Add(_pageCount);
        Grid.SetRow(titleRow, 1); workspace.Children.Add(titleRow);
        _content.Background = WinUIHelpers.Brush("AppBackground");
        Grid.SetRow(_content, 2); workspace.Children.Add(_content);
        _status.FontSize = 11;
        _status.Foreground = WinUIHelpers.Brush("TextFillColorTertiaryBrush");
        _status.Margin = new Thickness(2, 5, 2, 0);
        Grid.SetRow(_status, 3); workspace.Children.Add(_status);
        Grid.SetColumn(workspace, 1); Grid.SetRow(workspace, 1); root.Children.Add(workspace);
        RenderCategory();
        return root;
    }

    private Button RailButton(string glyph, string label, bool selected)
    {
        var row = new Grid { ColumnSpacing = 12, Padding = new Thickness(10, 9, 8, 9) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(24) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.Children.Add(new TextBlock { Text = glyph, FontSize = 17, Foreground = WinUIHelpers.Brush(selected ? "AccentTextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center });
        var text = new TextBlock { Text = label, FontSize = 12, Foreground = WinUIHelpers.Brush(selected ? "TextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center };
        Grid.SetColumn(text, 1); row.Children.Add(text);
        var button = new Button
        {
            Content = row,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
            Background = WinUIHelpers.Brush(selected ? "SubtleFillColorSecondaryBrush" : "TransparentBrush"),
            BorderThickness = new Thickness(0),
            CornerRadius = new CornerRadius(6),
            Padding = new Thickness(0),
            MinHeight = 38,
        };
        button.PointerEntered += (_, _) =>
        {
            var isSelected = button.Tag is string categoryId && categoryId == _selected;
            button.Background = WinUIHelpers.Brush(isSelected ? "SubtleFillColorTertiaryBrush" : "ControlFillColorSecondaryBrush");
        };
        button.PointerExited += (_, _) =>
        {
            var isSelected = button.Tag is string categoryId && categoryId == _selected;
            button.Background = WinUIHelpers.Brush(isSelected ? "SubtleFillColorSecondaryBrush" : "TransparentBrush");
        };
        return button;
    }

    private static void ApplyButtonHover(Button button, string normalBrush, string hoverBrush)
    {
        button.PointerEntered += (_, _) =>
        {
            if (button.IsEnabled) button.Background = WinUIHelpers.Brush(hoverBrush);
        };
        button.PointerExited += (_, _) => button.Background = WinUIHelpers.Brush(normalBrush);
    }

    private async Task PollPhoneConnectionAsync()
    {
        if (_connectionPollRunning) return;
        _connectionPollRunning = true;
        try
        {
            var state = await _bridge.CallAsync("get_state") as JsonObject;
            var controlConnected = state?["phone_connected"]?.GetValue<bool>() == true;
            var filesConnected = state?["phone_files_connected"]?.GetValue<bool>() == true;
            if (controlConnected == _controlConnected && filesConnected == _phoneFilesConnected) return;

            _catalogSnapshots.Clear();
            RenderLoadingCategory();
            await LoadAsync();
        }
        catch (Exception ex)
        {
            _deviceStatus.Text = WinUIStrings.Translate("تعذر تحديث حالة اتصال الهاتف", "Phone connection status unavailable");
            _status.Text = ExplainPhoneFileError(ex.Message);
        }
        finally
        {
            _connectionPollRunning = false;
        }
    }

    private static string LocalizedCategoryName(string id) => id switch
    {
        "photos" => WinUIStrings.Translate("الصور", "Photos"),
        "videos" => WinUIStrings.Translate("الفيديوهات", "Videos"),
        "apps" => WinUIStrings.Translate("التطبيقات", "Apps"),
        "music" => WinUIStrings.Translate("الموسيقى", "Music"),
        "docs" => WinUIStrings.Translate("الملفات", "Docs"),
        "storage" => WinUIStrings.Translate("التخزين", "Storage"),
        "transfer" => WinUIStrings.Translate("النقل", "Transfer"),
        _ => id,
    };

    private void SelectCategory(string id)
    {
        _selected = id;
        _galleryVisibleCount = GalleryBatchSize;
        _galleryScrollOffset = 0;
        foreach (var button in _categoryList.Children.OfType<Button>())
        {
            var category = Categories.FirstOrDefault(value => value.Id == button.Tag as string);
            if (category is null) continue;
            var selected = category.Id == id;
            button.Background = WinUIHelpers.Brush(selected ? "SubtleFillColorSecondaryBrush" : "TransparentBrush");
            if (button.Content is Grid row && row.Children.OfType<TextBlock>().LastOrDefault() is { } label)
                label.Foreground = WinUIHelpers.Brush(selected ? "TextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush");
            if (button.Content is Grid iconRow && iconRow.Children.OfType<TextBlock>().FirstOrDefault() is { } icon)
                icon.Foreground = WinUIHelpers.Brush(selected ? "AccentTextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush");
        }
        _search.PlaceholderText = id switch
        {
            "photos" => WinUIStrings.Translate("ابحث في الصور", "Search all photos"),
            "videos" => WinUIStrings.Translate("ابحث في الفيديوهات", "Search all videos"),
            "apps" => WinUIStrings.Translate("ابحث في التطبيقات", "Search apps"),
            "music" => WinUIStrings.Translate("ابحث في الموسيقى", "Search all music"),
            "docs" => WinUIStrings.Translate("ابحث في المستندات", "Search documents"),
            "transfer" => WinUIStrings.Translate("ابحث في سجل النقل", "Search transfer history"),
            _ => WinUIStrings.Translate("ابحث في الملفات", "Search files"),
        };
        RenderLoadingCategory();
        _ = LoadAsync();
    }

    private bool HasFullStorageAccess => _storageRoots.Any(root =>
        root["path"]?.GetValue<string>()?.StartsWith("@fs", StringComparison.Ordinal) == true);

    private string CatalogCacheKey() => $"{_selected}\u001f{_search.Text.Trim()}";

    private static void ApplyCachedVideoDuration(JsonObject source, string thumbnailPath)
    {
        if (ReadDurationValue(source) is not null || !File.Exists(thumbnailPath + ".duration")) return;
        try
        {
            if (long.TryParse(File.ReadAllText(thumbnailPath + ".duration"), out var duration) && duration > 0)
                source["duration"] = JsonValue.Create(duration);
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private void RememberVideoDuration(JsonObject source, long duration)
    {
        source["duration"] = JsonValue.Create(duration);
        var path = source["path"]?.GetValue<string>();
        if (string.IsNullOrWhiteSpace(path)) return;
        foreach (var entry in _catalogSnapshots.Values.SelectMany(snapshot => snapshot.Entries))
            if (string.Equals(entry["path"]?.GetValue<string>(), path, StringComparison.Ordinal))
                entry["duration"] = JsonValue.Create(duration);
    }

    private bool RestoreCatalogSnapshot(string key)
    {
        if (!_catalogSnapshots.TryGetValue(key, out var snapshot)) return false;
        _entries.Clear();
        _entries.AddRange(snapshot.Entries.Select(entry => (JsonObject)entry.DeepClone()));
        _nextOffset = snapshot.NextOffset;
        _hasMore = snapshot.HasMore;
        _totalCount = snapshot.TotalCount;
        return true;
    }

    private void SaveCatalogSnapshot(string key) =>
        _catalogSnapshots[key] = new CatalogSnapshot(
            _entries.Select(entry => (JsonObject)entry.DeepClone()).ToList(),
            _nextOffset, _hasMore, _totalCount);

    private async Task LoadAsync()
    {
        var loadVersion = Interlocked.Increment(ref _loadVersion);
        CancelPreviewLoads();
        var cacheKey = CatalogCacheKey();
        var hasCachedCatalog = RestoreCatalogSnapshot(cacheKey);
        if (hasCachedCatalog)
        {
            _loadError = null;
            RenderCategory();
        }
        else
        {
            _entries.Clear();
            _nextOffset = 0;
            _hasMore = false;
            _totalCount = 0;
            RenderLoadingCategory();
        }
        await _catalogGate.WaitAsync();
        if (loadVersion != Volatile.Read(ref _loadVersion))
        {
            _catalogGate.Release();
            return;
        }
        if (hasCachedCatalog)
        {
            _status.Text = WinUIStrings.Translate("نعرض العناصر المحفوظة ونحدّثها الآن…", "Showing saved phone items while refreshing…");
        }
        else
        {
            _entries.Clear();
            _nextOffset = 0;
            _hasMore = false;
            _totalCount = 0;
            RenderLoadingCategory();
        }
        _status.Text = hasCachedCatalog
            ? WinUIStrings.Translate("نعرض العناصر المحفوظة ونحدّثها الآن…", "Showing saved phone items while refreshing…")
            : WinUIStrings.Translate("تحميل العناصر من الهاتف المتصل…", "Loading items from connected phone…");
        try
        {
            var state = await _bridge.CallAsync("get_state") as JsonObject;
            _controlConnected = state?["phone_connected"]?.GetValue<bool>() == true;
            _phoneFilesConnected = state?["phone_files_connected"]?.GetValue<bool>() == true;
            var phoneName = state?["phone_device_name"]?.GetValue<string>();
            _deviceStatus.Text = _phoneFilesConnected
                ? string.IsNullOrWhiteSpace(phoneName) ? WinUIStrings.Translate("قناة ملفات الهاتف متصلة", "Phone file channel connected") : phoneName
                : _controlConnected ? WinUIStrings.Translate("الهاتف متصل • قناة الملفات غير متصلة", "Phone online • file channel disconnected") : WinUIStrings.Translate("الهاتف غير متصل", "Phone not connected");
            _capacityText.Text = _phoneFilesConnected
                ? "File channel connected • storage capacity unavailable"
                : WinUIStrings.Translate("سعة التخزين غير متاحة", "Storage capacity unavailable");
            _capacityFill.Width = 0;
            _storageRoots.Clear();
            _galleryVisibleCount = GalleryBatchSize;
            _galleryScrollOffset = 0;
            _loadError = null;
            if (_phoneFilesConnected)
            {
                var recursive = _selected is not "storage";
                JsonObject? result = await _bridge.CallAsync("phone_files_action", new
                {
                    operation = "catalog",
                    offset = 0,
                    limit = CatalogPageSize,
                    sort = "modified",
                    search = _search.Text.Trim(),
                    recursive,
                    category = recursive ? _selected : "storage",
                }, requestTimeoutOverride: recursive ? TimeSpan.FromSeconds(120) : TimeSpan.FromSeconds(35)) as JsonObject;
                if (result?["success"]?.GetValue<bool>() != true)
                {
                    var catalogError = result?["error"]?.GetValue<string>() ?? "Phone catalog request failed.";
                    if (catalogError.Contains("غير مدعومة", StringComparison.Ordinal) ||
                        catalogError.Contains("not supported", StringComparison.OrdinalIgnoreCase))
                    {
                        // Older phone builds do not know the combined catalog
                        // operation; retain the previous two-request protocol.
                        var rootsResult = await _bridge.CallAsync("phone_files_action", new { operation = "roots" }) as JsonObject;
                        if (rootsResult?["success"]?.GetValue<bool>() == true && rootsResult["roots"] is JsonArray oldRoots)
                            foreach (var node in oldRoots.OfType<JsonObject>()) _storageRoots.Add((JsonObject)node.DeepClone());
                        result = _selected == "transfer"
                            ? new JsonObject { ["success"] = JsonValue.Create(true), ["entries"] = new JsonArray(), ["total_count"] = JsonValue.Create(0) }
                            : await _bridge.CallAsync("phone_files_list", new
                            {
                                path = recursive && _selected != "apps"
                                    ? _storageRoots.FirstOrDefault(root => root["path"]?.GetValue<string>()?.StartsWith("@fs", StringComparison.Ordinal) == true)?["path"]?.GetValue<string>() ?? ""
                                    : "",
                                offset = 0,
                                limit = CatalogPageSize,
                                sort = "modified",
                                search = _search.Text.Trim(),
                                recursive,
                                category = recursive ? _selected : "",
                            }, requestTimeoutOverride: recursive ? TimeSpan.FromSeconds(120) : TimeSpan.FromSeconds(35)) as JsonObject;
                    }
                    else throw new InvalidOperationException(catalogError);
                }
                if (result?["success"]?.GetValue<bool>() != true)
                    throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "Phone catalog request failed.");
                if (result["roots"] is JsonArray roots)
                    foreach (var node in roots.OfType<JsonObject>()) _storageRoots.Add((JsonObject)node.DeepClone());
                var storage = _storageRoots.Select(root => root["storage"] as JsonObject)
                    .FirstOrDefault(value => value?["scope"]?.GetValue<string>() is "storage_volume" or "full_storage");
                var totalBytes = storage is null ? 0 : ReadLong(storage, "total_bytes");
                var freeBytes = storage is null ? 0 : ReadLong(storage, "free_bytes");
                if (totalBytes > 0 && freeBytes >= 0 && freeBytes <= totalBytes)
                {
                    var usedBytes = totalBytes - freeBytes;
                    _capacityText.Text = $"{FormatByteCount(usedBytes)} used of {FormatByteCount(totalBytes)} on phone storage";
                    _capacityFill.Width = 190 * (usedBytes / (double)totalBytes);
                }

                _entries.Clear();
                if (result["entries"] is JsonArray entries)
                    foreach (var node in entries.OfType<JsonObject>()) _entries.Add((JsonObject)node.DeepClone());
                _nextOffset = ReadInt(result, "next_offset", _entries.Count);
                _hasMore = ReadBool(result, "has_more");
                _totalCount = ReadInt(result, "total_count", _entries.Count);
                SaveCatalogSnapshot(cacheKey);
            }
            _status.Text = _phoneFilesConnected
                ? HasFullStorageAccess ? "Showing items from shared phone storage." : "Showing items from the phone's currently authorized folder."
                : _controlConnected
                    ? "The phone is online, but its dedicated file channel is not connected. Reconnect DeskTwin on the phone."
                    : "Connect DeskTwin on your phone to browse its authorized files.";
            if (loadVersion == Volatile.Read(ref _loadVersion)) RenderCategory();
        }
        catch (Exception ex)
        {
            if (loadVersion != Volatile.Read(ref _loadVersion)) return;
            if (hasCachedCatalog)
            {
                RenderCategory();
                _status.Text = WinUIStrings.Translate("الاتصال بطيء؛ نعرض آخر بيانات محفوظة.", "Phone is slow to respond; showing the last saved items.");
                return;
            }
            _loadError = ex.Message;
            _deviceStatus.Text = _phoneFilesConnected
                ? "Phone file channel • request failed"
                : _controlConnected ? WinUIStrings.Translate("الهاتف متصل • قناة الملفات غير متاحة", "Phone online • file channel unavailable") : WinUIStrings.Translate("الهاتف غير متصل", "Phone not connected");
            _capacityText.Text = WinUIStrings.Translate("سعة التخزين غير متاحة", "Storage capacity unavailable");
            _status.Text = ExplainPhoneFileError(ex.Message);
            RenderCategory();
        }
        finally { _catalogGate.Release(); }
    }

    private async Task ReloadForSearchAsync()
    {
        var current = new CancellationTokenSource();
        var previous = Interlocked.Exchange(ref _searchReload, current);
        try { previous?.Cancel(); }
        catch (ObjectDisposedException) { }
        try
        {
            await Task.Delay(280, current.Token);
            current.Token.ThrowIfCancellationRequested();
            RenderLoadingCategory();
            await LoadAsync();
        }
        catch (OperationCanceledException) { }
        finally
        {
            Interlocked.CompareExchange(ref _searchReload, null, current);
            current.Dispose();
        }
    }

    private async Task LoadNextPageAsync()
    {
        if (_loadingMore || !_hasMore || !_phoneFilesConnected) return;
        _loadingMore = true;
        await _catalogGate.WaitAsync();
        _status.Text = "Loading the next phone-file page…";
        try
        {
            var result = await _bridge.CallAsync("phone_files_list", new
            {
                path = _selected != "apps"
                    ? _storageRoots.FirstOrDefault(root => root["path"]?.GetValue<string>()?.StartsWith("@fs", StringComparison.Ordinal) == true)?["path"]?.GetValue<string>() ?? ""
                    : "",
                offset = _nextOffset,
                limit = CatalogPageSize,
                sort = "modified",
                search = _search.Text.Trim(),
                recursive = _selected is not "storage",
                category = _selected is not "storage" and not "transfer" ? _selected : "",
            }, requestTimeoutOverride: TimeSpan.FromSeconds(120)) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "Could not load the next phone-file page.");
            if (result["entries"] is JsonArray entries)
                foreach (var node in entries.OfType<JsonObject>()) _entries.Add((JsonObject)node.DeepClone());
            _nextOffset = ReadInt(result, "next_offset", _entries.Count);
            _hasMore = ReadBool(result, "has_more");
            _totalCount = ReadInt(result, "total_count", _totalCount > 0 ? _totalCount : _entries.Count);
            SaveCatalogSnapshot(CatalogCacheKey());
            RenderCategory();
            _status.Text = $"Loaded {_entries.Count} entries from the authorized folder.";
        }
        catch (Exception ex) { _status.Text = ex.Message; }
        finally
        {
            _catalogGate.Release();
            _loadingMore = false;
        }
    }

    private void RenderCategory()
    {
        CancelPreviewLoads();
        _galleryScrollViewer = null;
        _content.Children.Clear();
        var title = LocalizedCategoryName(_selected);
        _pageTitle.Text = title;
        if (!_phoneFilesConnected)
        {
            var heading = _controlConnected ? WinUIStrings.Translate("قناة ملفات الهاتف غير متصلة", "Phone file channel not connected") : WinUIStrings.Translate("الهاتف غير متصل", "Phone not connected");
            var message = _loadError ?? (_controlConnected
                ? "The phone control connection is online, but the separate file connection is missing. Reconnect DeskTwin on the phone."
                : "Connect your phone through DeskTwin to browse files.");
            ShowEmpty(heading, message);
            _pageCount.Text = "";
            return;
        }
        if (_loadError is not null)
        {
            ShowEmpty(WinUIStrings.Translate("ملفات الهاتف غير متاحة", "Phone files unavailable"), ExplainPhoneFileError(_loadError));
            _pageCount.Text = "";
            return;
        }
        if (_selected == "apps")
        {
            var apps = _entries.Where(entry => entry["category"]?.GetValue<string>() == "apps").ToList();
            _pageCount.Text = WinUIStrings.Translate($"{_totalCount} تطبيق", $"{_totalCount} apps");
            RenderTable(apps);
            return;
        }
        if (_selected == "transfer")
        {
            _pageCount.Text = WinUIStrings.Translate("عمليات الرفع النشطة للهاتف", "Active phone uploads");
            _ = LoadTransfersAsync();
            return;
        }
        if (_selected == "storage")
        {
            RenderStorage();
            return;
        }

        var matches = _entries.Where(entry => IsCategoryMatch(entry, _selected))
            .Where(entry => (entry["name"]?.GetValue<string>() ?? "").Contains(_search.Text.Trim(), StringComparison.CurrentCultureIgnoreCase))
            .ToList();
        if (_selected is "photos" or "videos")
            matches = matches.OrderByDescending(ModifiedTimestamp).ToList();
        _pageCount.Text = WinUIStrings.Translate(
            HasFullStorageAccess ? $"{_totalCount} ملفًا في التخزين" : $"{_totalCount} ملفًا في المجلد المحدد",
            HasFullStorageAccess ? $"{_totalCount} files in total" : $"{_totalCount} files in the selected folder");
        if (matches.Count == 0)
        {
            ShowEmpty(WinUIStrings.Translate("لا توجد ملفات مطابقة", "No matching files here"), HasFullStorageAccess
                ? WinUIStrings.Translate("لم يعثر الفهرس على ملفات من هذا النوع.", "No files of this type were found in shared storage.")
                : WinUIStrings.Translate("اختر مجلد الهاتف من إعدادات التطبيق لمنح DeskTwin إذن الوصول.", "Choose a phone folder in app settings to grant DeskTwin access."));
            if (_hasMore)
            {
                var more = new Button { Content = "Load next phone page", HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 18, 0, 0) };
                more.Click += async (_, _) => await LoadNextPageAsync();
                _content.Children.Add(more);
            }
            return;
        }
        if (_selected is "photos" or "videos")
            _ = RenderGalleryAsync(matches);
        else
            RenderTable(matches);
    }

    private void RenderLoadingCategory()
    {
        _galleryScrollViewer = null;
        _content.Children.Clear();
        _pageTitle.Text = LocalizedCategoryName(_selected);
        _pageCount.Text = "";
        var panel = new StackPanel { Spacing = 12, VerticalAlignment = VerticalAlignment.Top };
        panel.Children.Add(new ProgressRing { IsActive = true, Width = 26, Height = 26, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 18, 0, 0) });
        panel.Children.Add(new TextBlock
        {
            Text = WinUIStrings.Translate("تحميل بيانات الهاتف…", "Loading phone items…"),
            FontSize = 12,
            Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 6),
        });
        if (_selected is "photos" or "videos")
        {
            var columns = Math.Clamp((int)((_content.ActualWidth > 0 ? _content.ActualWidth : 660) / 214), 1, 6);
            for (var row = 0; row < (12 + columns - 1) / columns; row++)
            {
                var tiles = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
                for (var column = 0; column < Math.Min(columns, 12 - row * columns); column++)
                    tiles.Children.Add(new Border { Width = 204, Height = 204, CornerRadius = new CornerRadius(8), Background = WinUIHelpers.Brush("LayerFillColorAltBrush"), BorderBrush = WinUIHelpers.Brush("CardStrokeColorDefaultBrush"), BorderThickness = new Thickness(1) });
                panel.Children.Add(tiles);
            }
        }
        else
        {
            for (var i = 0; i < 7; i++)
                panel.Children.Add(new Border { Height = 54, Background = WinUIHelpers.Brush("LayerFillColorAltBrush"), CornerRadius = new CornerRadius(6), BorderBrush = WinUIHelpers.Brush("CardStrokeColorSecondaryBrush"), BorderThickness = new Thickness(1) });
        }
        _content.Children.Add(new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = panel });
        _status.Text = WinUIStrings.Translate("التنقل متاح؛ الصور المصغرة تُحمّل تدريجيًا.", "Navigation stays responsive; thumbnails load progressively.");
    }

    private static bool IsCategoryMatch(JsonObject entry, string category)
    {
        if (entry["directory"]?.GetValue<bool>() == true) return false;
        var mime = entry["mime"]?.GetValue<string>() ?? "";
        var name = entry["name"]?.GetValue<string>() ?? "";
        var extension = Path.GetExtension(name).ToLowerInvariant();
        return category switch
        {
            "photos" => mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase) || extension is ".jpg" or ".jpeg" or ".png" or ".webp" or ".gif" or ".heic" or ".bmp",
            "videos" => mime.StartsWith("video/", StringComparison.OrdinalIgnoreCase) || extension is ".mp4" or ".mkv" or ".mov" or ".webm" or ".3gp",
            "music" => mime.StartsWith("audio/", StringComparison.OrdinalIgnoreCase) || extension is ".mp3" or ".m4a" or ".wav" or ".flac" or ".ogg",
            "docs" => !mime.StartsWith("image/", StringComparison.OrdinalIgnoreCase)
                && !mime.StartsWith("video/", StringComparison.OrdinalIgnoreCase)
                && !mime.StartsWith("audio/", StringComparison.OrdinalIgnoreCase),
            _ => false,
        };
    }

    private async Task RenderGalleryAsync(List<JsonObject> entries)
    {
        var current = new CancellationTokenSource();
        var previous = Interlocked.Exchange(ref _previewCancellation, current);
        try { previous?.Cancel(); }
        catch (ObjectDisposedException) { }
        try { await RenderGalleryCoreAsync(entries, current.Token); }
        catch (OperationCanceledException) { }
        finally
        {
            Interlocked.CompareExchange(ref _previewCancellation, null, current);
            current.Dispose();
        }
    }

    private Task RenderGalleryCoreAsync(List<JsonObject> entries, CancellationToken cancellationToken)
    {
        var cards = entries.Take(_galleryVisibleCount).Select(entry => new MediaCard
        {
            Name = entry["name"]?.GetValue<string>() ?? "File",
            Path = entry["path"]?.GetValue<string>() ?? "",
            Details = FormatDate(entry) + "  •  " + FormatSize(entry),
            DurationText = ReadDuration(entry),
            Source = entry,
        }).ToList();
        foreach (var card in cards)
        {
            card.PreviewCommand = new AsyncCommand(() => ShowPreviewAsync(card));
            card.DownloadCommand = new AsyncCommand(() => DownloadMediaAsync(card));
            card.DeleteCommand = new AsyncCommand(() => DeleteMediaAsync(card));
        }
        cancellationToken.ThrowIfCancellationRequested();
        var video = _selected == "videos";
        var selectionName = video ? "Select video" : "Select photo";
        var previewName = video ? "Preview video" : "Preview photo";
        var deleteName = video ? "Delete video from phone" : "Delete photo from phone";
        var videoOverlay = video
            ? "<TextBlock Text='▶' FontSize='30' Foreground='White' HorizontalAlignment='Center' VerticalAlignment='Center' IsHitTestVisible='False'/><Border Background='#CC101318' Padding='6,3' CornerRadius='4' HorizontalAlignment='Right' VerticalAlignment='Bottom' Margin='0,0,7,50'><TextBlock Text='{Binding DurationText}' FontSize='11' Foreground='White'/></Border>"
            : "";
        var previewGlyph = video ? "▶" : "⌕";
        var tileMarkup = "<DataTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'><Grid Width='212' Height='212' AutomationProperties.Name='{Binding Name}'><Border Background='{ThemeResource CardBackgroundFillColorSecondaryBrush}' CornerRadius='8'><Grid><TextBlock Text='▧' FontSize='40' Foreground='{ThemeResource TextFillColorSecondaryBrush}' HorizontalAlignment='Center' VerticalAlignment='Center'/><Image Source='{Binding Thumbnail}' Stretch='UniformToFill'/></Grid></Border>"
            + videoOverlay
            + $"<CheckBox IsChecked='{{Binding IsSelected, Mode=TwoWay}}' HorizontalAlignment='Left' VerticalAlignment='Top' Margin='7' AutomationProperties.Name='{selectionName}'/><Border Background='#D915191E' CornerRadius='6' Padding='5' HorizontalAlignment='Center' VerticalAlignment='Bottom' Margin='6'><StackPanel Orientation='Horizontal' Spacing='5'><Button Content='{previewGlyph}' Command='{{Binding PreviewCommand}}' AutomationProperties.Name='{previewName}' ToolTipService.ToolTip='Preview' Background='{{ThemeResource ControlFillColorDefaultBrush}}' Foreground='{{ThemeResource TextFillColorPrimaryBrush}}' BorderBrush='{{ThemeResource ControlStrokeColorDefaultBrush}}' BorderThickness='1' MinWidth='38' Height='34' Padding='6,2'/><Button Content='↓' Command='{{Binding DownloadCommand}}' AutomationProperties.Name='Download to PC' ToolTipService.ToolTip='Download to PC' Background='{{ThemeResource ControlFillColorDefaultBrush}}' Foreground='{{ThemeResource TextFillColorPrimaryBrush}}' BorderBrush='{{ThemeResource ControlStrokeColorDefaultBrush}}' BorderThickness='1' MinWidth='38' Height='34' Padding='6,2'/><Button Content='×' Command='{{Binding DeleteCommand}}' AutomationProperties.Name='{deleteName}' ToolTipService.ToolTip='Delete from phone' Background='{{ThemeResource ControlFillColorDefaultBrush}}' Foreground='{{ThemeResource TextFillColorPrimaryBrush}}' BorderBrush='{{ThemeResource ControlStrokeColorDefaultBrush}}' BorderThickness='1' MinWidth='38' Height='34' Padding='6,2'/></StackPanel></Border></Grid></DataTemplate>";
        if (WinUIStrings.Language == "ar")
        {
            tileMarkup = tileMarkup
                .Replace("Select video", "تحديد فيديو").Replace("Preview video", "معاينة الفيديو")
                .Replace("Select photo", "تحديد صورة").Replace("Preview photo", "معاينة الصورة")
                .Replace("Preview", "معاينة").Replace("Download to PC", "تنزيل إلى الكمبيوتر")
                .Replace("Delete video from phone", "حذف الفيديو من الهاتف")
                .Replace("Delete photo from phone", "حذف الصورة من الهاتف")
                .Replace("Delete from phone", "حذف من الهاتف");
        }
        var template = (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load(tileMarkup);
        var itemPanel = (ItemsPanelTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load("<ItemsPanelTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'><ItemsWrapGrid Orientation='Horizontal' ItemWidth='222' ItemHeight='222'/></ItemsPanelTemplate>");
        var groups = cards.GroupBy(card => FormatDate(card.Source) is { Length: > 0 } date ? date : "Date unavailable")
            .OrderByDescending(group => group.Max(card => ModifiedTimestamp(card.Source)));
        var gallery = new StackPanel { Spacing = 12 };
        var selectAll = new CheckBox
        {
            Content = _selected == "videos" ? WinUIStrings.Translate("كل الفيديوهات", "All Videos") : WinUIStrings.Translate("كل الصور", "All Photos"),
            Margin = new Thickness(0, 0, 0, 4),
            Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(selectAll, _selected == "videos" ? WinUIStrings.Translate("تحديد الفيديوهات الظاهرة", "Select all visible videos") : WinUIStrings.Translate("تحديد الصور الظاهرة", "Select all visible photos"));
        selectAll.Checked += (_, _) => SetSelection(cards, true);
        selectAll.Unchecked += (_, _) => SetSelection(cards, false);
        gallery.Children.Add(new Border
        {
            Background = WinUIHelpers.Brush("LayerFillColorAltBrush"),
            Padding = new Thickness(4, 2, 8, 2),
            Child = selectAll,
        });
        foreach (var group in groups)
        {
            var groupCards = group.ToList();
            var heading = new CheckBox
            {
                Content = $"{group.Key} ({groupCards.Count})",
                FontSize = 13,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
                Margin = new Thickness(0, 4, 0, 0),
            };
            heading.Checked += (_, _) => SetSelection(groupCards, true);
            heading.Unchecked += (_, _) => SetSelection(groupCards, false);
            gallery.Children.Add(heading);
            var grid = new GridView
            {
                IsItemClickEnabled = true,
                SelectionMode = ListViewSelectionMode.None,
                ItemsPanel = itemPanel,
                ItemTemplate = template,
                Background = WinUIHelpers.Brush("AppBackground"),
                ItemsSource = groupCards,
            };
            grid.ContainerContentChanging += (sender, args) =>
            {
                if (args.InRecycleQueue || args.Item is not MediaCard card || card.Thumbnail is not null) return;
                _ = LoadVisibleThumbnailAsync(card, cancellationToken);
            };
            grid.ItemClick += async (_, args) =>
            {
                if (args.ClickedItem is MediaCard selected)
                    await ShowPreviewAsync(selected);
            };
            gallery.Children.Add(grid);
        }
        if (_galleryVisibleCount < entries.Count || _hasMore)
        {
            var canRevealLoaded = _galleryVisibleCount < entries.Count;
            var more = new Button
            {
                Content = canRevealLoaded ? WinUIStrings.Translate($"إظهار {GalleryBatchSize} التالية", $"Show next {GalleryBatchSize}") : WinUIStrings.Translate("تحميل دفعة الملفات التالية", "Load next phone page"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 10, 0, 18),
                MinWidth = 180,
            };
            more.Click += async (_, _) =>
            {
                if (canRevealLoaded)
                {
                    _galleryVisibleCount = Math.Min(_galleryVisibleCount + GalleryBatchSize, entries.Count);
                    RenderCategory();
                }
                else
                {
                    _galleryVisibleCount += GalleryBatchSize;
                    await LoadNextPageAsync();
                }
            };
            gallery.Children.Add(more);
        }
        var galleryScrollViewer = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Content = gallery,
        };
        _galleryScrollViewer = galleryScrollViewer;
        galleryScrollViewer.ViewChanged += (_, _) =>
        {
            if (!ReferenceEquals(_galleryScrollViewer, galleryScrollViewer)) return;
            _galleryScrollOffset = galleryScrollViewer.VerticalOffset;
            if (galleryScrollViewer.ScrollableHeight > 0 && galleryScrollViewer.ScrollableHeight - galleryScrollViewer.VerticalOffset <= 460)
                _ = AdvanceGalleryOnScrollAsync();
        };
        galleryScrollViewer.Loaded += (_, _) =>
        {
            if (ReferenceEquals(_galleryScrollViewer, galleryScrollViewer) && _galleryScrollOffset > 0)
                galleryScrollViewer.ChangeView(null, Math.Min(_galleryScrollOffset, galleryScrollViewer.ScrollableHeight), null, disableAnimation: true);
        };
        _content.Children.Add(galleryScrollViewer);
        _status.Text = cards.Count < entries.Count
            ? $"Showing {cards.Count} of {entries.Count} matching items from the loaded page."
            : WinUIStrings.Translate("تُحفظ الصور المصغرة مؤقتًا لتسريع التصفح؛ الملفات الأصلية تظل على الهاتف.", "Thumbnails are cached to speed up browsing; originals remain on the phone.");
        return Task.CompletedTask;
    }

    private async Task AdvanceGalleryOnScrollAsync()
    {
        var scrollViewer = _galleryScrollViewer;
        if (_advancingGallery || _loadingMore || scrollViewer is null || _selected is not ("photos" or "videos")) return;
        if (scrollViewer.ScrollableHeight <= 0 || scrollViewer.ScrollableHeight - scrollViewer.VerticalOffset > 460) return;

        _advancingGallery = true;
        try
        {
            var previousOffset = scrollViewer.VerticalOffset;
            var matches = _entries.Where(entry => IsCategoryMatch(entry, _selected))
                .Where(entry => (entry["name"]?.GetValue<string>() ?? "").Contains(_search.Text.Trim(), StringComparison.CurrentCultureIgnoreCase))
                .OrderByDescending(ModifiedTimestamp)
                .ToList();
            if (_galleryVisibleCount < matches.Count)
            {
                _galleryVisibleCount = Math.Min(_galleryVisibleCount + GalleryBatchSize, matches.Count);
                _galleryScrollOffset = previousOffset;
                _content.Children.Clear();
                await RenderGalleryAsync(matches);
            }
            else if (_hasMore)
            {
                _galleryVisibleCount += GalleryBatchSize;
                _galleryScrollOffset = previousOffset;
                await LoadNextPageAsync();
            }
        }
        finally { _advancingGallery = false; }
    }

    private async Task<BitmapImage?> GetThumbnailAsync(string path, JsonObject source, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        var cacheKey = $"{path}|{ModifiedTimestamp(source)}|{ReadLong(source, "size")}";
        var cachePath = ThumbnailCachePath(cacheKey);
        lock (_thumbnailCacheLock)
            if (_thumbnailCache.TryGetValue(cacheKey, out var cached))
            {
                ApplyCachedVideoDuration(source, cachePath);
                return cached;
            }

        if (File.Exists(cachePath))
        {
            try
            {
                var cached = await LoadThumbnailBitmapAsync(cachePath);
                File.SetLastWriteTimeUtc(cachePath, DateTime.UtcNow);
                ApplyCachedVideoDuration(source, cachePath);
                RememberThumbnail(cacheKey, cached);
                return cached;
            }
            catch (Exception ex)
            {
                System.Diagnostics.Debug.WriteLine($"Phone thumbnail cache entry was unreadable: {ex.Message}");
                try { File.Delete(cachePath); } catch (IOException) { }
            }
        }

        var response = await _bridge.CallAsync("phone_files_action", new { operation = "thumbnail", path }, cancellationToken: cancellationToken, requestTimeoutOverride: TimeSpan.FromSeconds(4)) as JsonObject;
        var encoded = response?["thumbnail_base64"]?.GetValue<string>();
        if (response?["success"]?.GetValue<bool>() != true || string.IsNullOrWhiteSpace(encoded)) return null;
        long? videoDuration = null;
        if (response["duration"] is JsonValue durationValue && durationValue.TryGetValue<long>(out var duration) && duration > 0)
        {
            videoDuration = duration;
            RememberVideoDuration(source, duration);
        }
        var bytes = Convert.FromBase64String(encoded);
        await SaveThumbnailCacheAsync(cachePath, bytes);
        if (videoDuration is > 0)
        {
            try { await File.WriteAllTextAsync(cachePath + ".duration", videoDuration.Value.ToString(System.Globalization.CultureInfo.InvariantCulture), cancellationToken); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
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
        RememberThumbnail(cacheKey, bitmap);
        return bitmap;
    }

    private static string ThumbnailCachePath(string cacheKey)
    {
        var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(cacheKey)));
        return Path.Combine(ThumbnailCacheDirectory, $"{digest}.thumb");
    }

    private static async Task<BitmapImage> LoadThumbnailBitmapAsync(string path)
    {
        var file = await StorageFile.GetFileFromPathAsync(path);
        using var stream = await file.OpenReadAsync();
        var bitmap = new BitmapImage();
        await bitmap.SetSourceAsync(stream);
        return bitmap;
    }

    private static async Task SaveThumbnailCacheAsync(string path, byte[] bytes)
    {
        string? temporaryPath = null;
        try
        {
            Directory.CreateDirectory(ThumbnailCacheDirectory);
            temporaryPath = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
            await File.WriteAllBytesAsync(temporaryPath, bytes);
            File.Move(temporaryPath, path, overwrite: true);
            await Task.Run(() => PruneThumbnailCache(path));
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Phone thumbnail cache write failed: {ex.Message}");
            if (temporaryPath is not null)
                try { File.Delete(temporaryPath); } catch (IOException) { }
        }
    }

    private static void PruneThumbnailCache(string keepPath)
    {
        try
        {
            var files = new DirectoryInfo(ThumbnailCacheDirectory).GetFiles("*.thumb")
                .OrderBy(file => file.LastWriteTimeUtc)
                .ToList();
            long totalBytes = files.Sum(file => file.Length);
            var count = files.Count;
            foreach (var file in files)
            {
                if (count <= ThumbnailCacheMaxEntries && totalBytes <= ThumbnailCacheMaxBytes) break;
                if (string.Equals(file.FullName, keepPath, StringComparison.OrdinalIgnoreCase)) continue;
                try
                {
                    var fileLength = file.Length;
                    file.Delete();
                    try { File.Delete(file.FullName + ".duration"); }
                    catch (IOException) { }
                    catch (UnauthorizedAccessException) { }
                    totalBytes -= fileLength;
                    count--;
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        catch (DirectoryNotFoundException) { }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private void RememberThumbnail(string key, BitmapImage bitmap)
    {
        lock (_thumbnailCacheLock)
        {
            if (_thumbnailCache.Count >= 256 && !_thumbnailCache.ContainsKey(key))
                _thumbnailCache.Remove(_thumbnailCache.Keys.First());
            _thumbnailCache[key] = bitmap;
        }
    }

    private BitmapImage? GetCachedThumbnail(string path, JsonObject source)
    {
        if (string.IsNullOrWhiteSpace(path)) return null;
        var cacheKey = $"{path}|{ModifiedTimestamp(source)}|{ReadLong(source, "size")}";
        lock (_thumbnailCacheLock)
            return _thumbnailCache.TryGetValue(cacheKey, out var cached) ? cached : null;
    }

    private async Task LoadPreviewArtworkAsync(MediaPreviewControls controls, string path, JsonObject source)
    {
        try { controls.SetArtwork(await GetThumbnailAsync(path, source, CancellationToken.None)); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Phone music artwork load failed: {ex.Message}"); }
    }

    private async Task LoadVisibleThumbnailAsync(MediaCard card, CancellationToken cancellationToken)
    {
        try
        {
            await _thumbnailLoadGate.WaitAsync(cancellationToken);
            try
            {
                if (card.Thumbnail is null)
                    card.Thumbnail = await GetThumbnailAsync(card.Path, card.Source, cancellationToken);
                if (card.Thumbnail is not null && IsVideo(card.Source))
                {
                    card.DurationText = ReadDuration(card.Source);
                    if (ReadDurationValue(card.Source) is null)
                        _ = LoadMissingVideoInfoAsync(card, cancellationToken);
                }
            }
            finally { _thumbnailLoadGate.Release(); }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Phone thumbnail load failed: {ex.Message}"); }
    }

    private async Task LoadMissingVideoInfoAsync(MediaCard card, CancellationToken cancellationToken)
    {
        try
        {
            await _mediaInfoLoadGate.WaitAsync(cancellationToken);
            try
            {
                if (ReadDurationValue(card.Source) is not null || cancellationToken.IsCancellationRequested) return;
                var result = await _bridge.CallAsync("phone_files_action", new { operation = "media_info", path = card.Path },
                    cancellationToken: cancellationToken, requestTimeoutOverride: TimeSpan.FromSeconds(30)) as JsonObject;
                if (result?["success"]?.GetValue<bool>() != true) return;
                if (result["duration"] is not JsonValue durationValue || !durationValue.TryGetValue<long>(out var duration) || duration <= 0) return;
                RememberVideoDuration(card.Source, duration);
                card.DurationText = ReadDuration(card.Source);
                var cacheKey = $"{card.Path}|{ModifiedTimestamp(card.Source)}|{ReadLong(card.Source, "size")}";
                try
                {
                    var cachePath = ThumbnailCachePath(cacheKey);
                    Directory.CreateDirectory(ThumbnailCacheDirectory);
                    await File.WriteAllTextAsync(cachePath + ".duration", duration.ToString(System.Globalization.CultureInfo.InvariantCulture), cancellationToken);
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
            finally { _mediaInfoLoadGate.Release(); }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Phone video metadata load failed: {ex.Message}"); }
    }

    private void CancelPreviewLoads()
    {
        var current = Interlocked.Exchange(ref _previewCancellation, null);
        try { current?.Cancel(); }
        catch (ObjectDisposedException) { }
    }

    private async Task ShowPreviewAsync(MediaCard card)
    {
        string? localPath = null;
        PhoneVideoStreamProxy? videoStream = null;
        MediaPlayer? player = null;
        MediaPreviewControls? playerControls = null;
        ImagePreviewControls? imageControls = null;
        UIElement preview;
        var overlay = new PreviewOverlay(_shellRoot, card.Name);
        void ToggleFullscreen() => overlay.ToggleFullscreen();
        try
        {
            if (IsVideo(card.Source))
            {
                videoStream = await PhoneVideoStreamProxy.StartAsync(
                    _bridge, card.Path, card.Name, ReadLong(card.Source, "size"));
                player = new MediaPlayer { AutoPlay = false };
                player.Source = MediaSource.CreateFromUri(videoStream.SourceUri);
                playerControls = new MediaPreviewControls(player, 800, 500, ToggleFullscreen, fallbackDuration: ReadDurationValue(card.Source));
                preview = playerControls.Root;
            }
            else
            {
                localPath = await GetTemporaryMediaAsync(card);
                var file = await StorageFile.GetFileFromPathAsync(localPath);
                using var stream = await file.OpenReadAsync();
                var bitmap = new BitmapImage();
                await bitmap.SetSourceAsync(stream);
                imageControls = new ImagePreviewControls(bitmap, 800, 500, ToggleFullscreen);
                preview = imageControls.Root;
            }
            overlay.SetContent(preview, (width, height) =>
            {
                playerControls?.SetSize(width, height);
                imageControls?.SetSize(width, height);
            });
        }
        catch (Exception ex)
        {
            _status.Text = $"Could not open {card.Name}: {ex.Message}";
            overlay.Dispose();
            playerControls?.Dispose();
            player?.Dispose();
            if (videoStream is not null) await videoStream.DisposeAsync();
            return;
        }
        var downloadRequested = false;
        try
        {
            downloadRequested = await overlay.ShowAsync();
        }
        finally
        {
            overlay.Dispose();
            playerControls?.Dispose();
            player?.Pause();
            player?.Dispose();
            if (videoStream is not null) await videoStream.DisposeAsync();
        }
        if (downloadRequested) await DownloadMediaAsync(card);
    }

    private async Task DownloadMediaAsync(MediaCard card)
    {
        try
        {
            var path = await GetTemporaryMediaAsync(card);
            await SaveTemporaryMediaAsync(path, card.Name);
        }
        catch (Exception ex) { _status.Text = $"Could not download {card.Name}: {ex.Message}"; }
    }

    private async Task DeleteMediaAsync(MediaCard card)
    {
        var dialog = new ContentDialog
        {
            Title = "Delete from phone?",
            Content = $"Move {card.Name} to the phone trash if supported?",
            PrimaryButtonText = "Delete",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            var result = await _bridge.CallAsync("phone_files_action", new { operation = "delete", path = card.Path }) as JsonObject;
            if (result?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "Phone file could not be deleted.");
            _catalogSnapshots.Clear();
            await LoadAsync();
            _status.Text = $"Deleted {card.Name} from the phone.";
        }
        catch (Exception ex) { _status.Text = $"Could not delete {card.Name}: {ex.Message}"; }
    }

    private async Task<string> GetTemporaryMediaAsync(MediaCard card)
    {
        var key = $"{card.Path}|{ModifiedTimestamp(card.Source)}";
        if (_temporaryMedia.TryGetValue(key, out var existing) && File.Exists(existing)) return existing;
        var expectedSize = Math.Max(0, ReadLong(card.Source, "size"));
        var estimatedSeconds = expectedSize == 0 ? 255 : Math.Clamp(((expectedSize + 1_048_575) / 1_048_576) * 8 + 75, 120, 915);
        var result = await _bridge.CallAsync("phone_files_download", new { path = card.Path, size_bytes = expectedSize }, requestTimeoutOverride: TimeSpan.FromSeconds(estimatedSeconds)) as JsonObject;
        var source = result?["local_path"]?.GetValue<string>();
        if (result?["success"]?.GetValue<bool>() != true || string.IsNullOrWhiteSpace(source) || !File.Exists(source))
            throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? "The original file could not be read from the phone.");
        Directory.CreateDirectory(TemporaryMediaDirectory);
        var safeName = string.Concat(Path.GetFileName(card.Name).Select(character =>
            Path.GetInvalidFileNameChars().Contains(character) ? '_' : character));
        if (string.IsNullOrWhiteSpace(safeName)) safeName = "phone-file";
        var destination = Path.Combine(TemporaryMediaDirectory, $"{Guid.NewGuid():N}-{safeName}");
        try { File.Move(source, destination); }
        catch (IOException) { File.Copy(source, destination, overwrite: false); try { File.Delete(source); } catch (IOException) { } }
        _temporaryMedia[key] = destination;
        PruneTemporaryMediaCache(destination);
        return destination;
    }

    private static void PruneTemporaryMediaCache(string keepPath)
    {
        try
        {
            var files = new DirectoryInfo(TemporaryMediaDirectory).GetFiles()
                .Where(file => !string.Equals(file.FullName, keepPath, StringComparison.OrdinalIgnoreCase))
                .OrderBy(file => file.LastAccessTimeUtc)
                .ToList();
            var keepSize = new FileInfo(keepPath).Length;
            var total = keepSize + files.Sum(file => file.Length);
            var remainingCount = files.Count + 1;
            foreach (var file in files)
            {
                if (total <= 512L * 1024L * 1024L && remainingCount <= 12) break;
                try { total -= file.Length; file.Delete(); remainingCount--; }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private async Task SaveTemporaryMediaAsync(string sourcePath, string name)
    {
        var settings = await _bridge.CallAsync("get_settings") as JsonObject;
        var directory = ReadText(settings ?? new JsonObject(), "phone_download_directory", "");
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
            var stem = Path.GetFileNameWithoutExtension(safeName);
            destination = Path.Combine(directory, $"{stem} ({index++}){extension}");
        }
        File.Copy(sourcePath, destination, overwrite: false);
        _status.Text = $"Saved to {destination}";
    }

    private async Task ShowPhoneTransferSettingsAsync()
    {
        try
        {
            var current = await _bridge.CallAsync("get_settings") as JsonObject ?? new JsonObject();
            var pcPath = new TextBox { Text = ReadText(current, "phone_download_directory", ""), PlaceholderText = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "DeskTwin") };
            var phonePath = new TextBox { Text = ReadText(current, "phone_upload_directory", ""), PlaceholderText = "Phone storage root" };
            var chooseFolder = new Button { Content = WinUIStrings.Translate("اختيار مجلد…", "Choose folder…"), MinWidth = 104 };
            chooseFolder.Click += async (_, _) =>
            {
                var picker = new FolderPicker();
                picker.FileTypeFilter.Add("*");
                InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
                var folder = await picker.PickSingleFolderAsync();
                if (folder is not null) pcPath.Text = folder.Path;
            };
            var pcRow = new Grid { ColumnSpacing = 8 };
            pcRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            pcRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            pcRow.Children.Add(pcPath);
            Grid.SetColumn(chooseFolder, 1); pcRow.Children.Add(chooseFolder);
            var form = new StackPanel { Spacing = 10, MinWidth = 500 };
            form.Children.Add(new TextBlock { Text = WinUIStrings.Translate("مكان حفظ ما تنزله من الهاتف إلى الكمبيوتر", "Save downloads from phone to this PC folder"), FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush") });
            form.Children.Add(pcRow);
            form.Children.Add(new TextBlock { Text = WinUIStrings.Translate("مجلد الهاتف الافتراضي عند رفع ملفات من الكمبيوتر. اتركه فارغًا لاستخدام جذر التخزين. يمكنك كتابة مسار مثل @fs/Download أو مسار المجلد المصرح به.", "Default phone folder for uploads from this PC. Leave blank for storage root. Enter a path such as @fs/Download or a folder within the phone's authorized tree."), FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap });
            form.Children.Add(phonePath);
            var dialog = new ContentDialog
            {
                Title = WinUIStrings.Translate("إعدادات ملفات الهاتف", "Phone file settings"),
                Content = form,
                PrimaryButtonText = WinUIStrings.Translate("حفظ", "Save"),
                CloseButtonText = WinUIStrings.Translate("إلغاء", "Cancel"),
                DefaultButton = ContentDialogButton.Primary,
                XamlRoot = XamlRoot,
            };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
            await _bridge.CallAsync("set_setting", new { key = "phone_download_directory", value = pcPath.Text.Trim() });
            await _bridge.CallAsync("set_setting", new { key = "phone_upload_directory", value = phonePath.Text.Trim().TrimEnd('/', '\\') });
            _status.Text = WinUIStrings.Translate("تم حفظ إعدادات ملفات الهاتف منفصلة عن إعدادات DeskTwin العامة.", "Phone file paths saved separately from general DeskTwin settings.");
        }
        catch (Exception ex) { _status.Text = $"Could not save phone file settings: {ex.Message}"; }
    }

    private static bool IsVideo(JsonObject source) =>
        (source["mime"]?.GetValue<string>() ?? "").StartsWith("video/", StringComparison.OrdinalIgnoreCase);

    private void RenderTable(List<JsonObject> entries)
    {
        var music = _selected == "music";
        var apps = _selected == "apps";
        var columns = music
            ? new[] { 44.0, 1.0, 78.0, 108.0, 72.0, 72.0, 0.0 }
            : apps ? new[] { 42.0, 1.0, 72.0, 84.0, 104.0, 72.0, 0.0 }
            : new[] { 42.0, 1.0, 72.0, 84.0, 112.0, 0.0 };
        var header = new Grid { ColumnSpacing = 10, Padding = new Thickness(10, 8, 10, 8), Background = WinUIHelpers.Brush("CardBackgroundFillColorSecondaryBrush") };
        foreach (var width in columns) header.ColumnDefinitions.Add(new ColumnDefinition { Width = width == 1.0 ? new GridLength(1, GridUnitType.Star) : width == 0 ? GridLength.Auto : new GridLength(width) });
        var labels = music
            ? new[] { "", WinUIStrings.Translate("الاسم", "Name"), WinUIStrings.Translate("الحجم", "Size"), WinUIStrings.Translate("الفنانون", "Artist"), WinUIStrings.Translate("الصيغة", "Format"), WinUIStrings.Translate("المدة", "Duration"), WinUIStrings.Translate("إجراءات", "Actions") }
            : apps
                ? new[] { "", WinUIStrings.Translate("التطبيق", "App"), WinUIStrings.Translate("الحجم", "Size"), WinUIStrings.Translate("الإصدار", "Version"), WinUIStrings.Translate("التحديث", "Updated"), WinUIStrings.Translate("الحالة", "Status"), WinUIStrings.Translate("إجراءات", "Actions") }
                : new[] { "", WinUIStrings.Translate("الاسم", "Name"), WinUIStrings.Translate("الحجم", "Size"), WinUIStrings.Translate("الصيغة", "Format"), WinUIStrings.Translate("التاريخ", "Time"), WinUIStrings.Translate("إجراءات", "Actions") };
        for (var index = 0; index < labels.Length; index++)
        {
            var label = new TextBlock { Text = labels[index], FontSize = 11, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center };
            Grid.SetColumn(label, index);
            header.Children.Add(label);
        }
        var list = new ListView { Background = WinUIHelpers.Brush("AppBackground"), SelectionMode = ListViewSelectionMode.None };
        list.ContainerContentChanging += (sender, args) =>
        {
            if (args.InRecycleQueue || _selected is not ("apps" or "music") || args.Item is not Border item
                || item.Tag is not JsonObject app || item.Child is not Grid appRow
                || appRow.Children.FirstOrDefault() is not Border icon) return;
            var image = icon.Child as Image ?? (icon.Child as Grid)?.Children.OfType<Image>().FirstOrDefault();
            if (image is null || image.Source is not null) return;
            _ = LoadTableIconAsync(app, image);
        };
        list.Header = header;
        list.HeaderTemplate = (DataTemplate)Microsoft.UI.Xaml.Markup.XamlReader.Load("<DataTemplate xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'><ContentPresenter Content='{Binding}'/></DataTemplate>");
        foreach (var entry in entries)
        {
            var row = new Grid { ColumnSpacing = 10, Padding = new Thickness(10, 9, 10, 9) };
            foreach (var width in columns) row.ColumnDefinitions.Add(new ColumnDefinition { Width = width == 1.0 ? new GridLength(1, GridUnitType.Star) : width == 0 ? GridLength.Auto : new GridLength(width) });
            FrameworkElement icon;
            if (_selected == "apps")
            {
                icon = new Border
                {
                    Width = 48,
                    Height = 48,
                    CornerRadius = new CornerRadius(10),
                    Background = WinUIHelpers.Brush("CardBackgroundFillColorSecondaryBrush"),
                    Child = new Image { Stretch = Stretch.UniformToFill },
                };
            }
            else icon = music ? CreateMusicThumbnailIcon() : CreateFileIcon(entry, music);
            Grid.SetColumn(icon, 0);
            row.Children.Add(icon);
            var rawName = ReadText(entry, "name", "File");
            var displayName = music ? ReadText(entry, "title", rawName) : _selected == "apps" ? ReadText(entry, "label", rawName) : rawName;
            var name = new TextBlock { Text = displayName, FontSize = 13, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
            ToolTipService.SetToolTip(name, music && displayName != rawName ? $"{displayName}\n{rawName}" : rawName);
            Grid.SetColumn(name, 1);
            row.Children.Add(name);
            var values = music
                ? new[] { FormatSize(entry), ReadText(entry, "artists", "—"), ReadText(entry, "format", FormatExtension(entry)), ReadDuration(entry) }
                : apps
                    ? new[] { FormatSize(entry), ReadText(entry, "version", "—"), FormatDate(entry), WinUIStrings.Translate("مثبّت", "Installed") }
                    : new[] { FormatSize(entry), ReadText(entry, "format", FormatExtension(entry)), FormatDate(entry) };
            for (var index = 0; index < values.Length; index++)
            {
                var value = new TextBlock { Text = values[index], FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
                Grid.SetColumn(value, index + 2);
                row.Children.Add(value);
            }
            var path = entry["path"]?.GetValue<string>() ?? "";
            var nameValue = entry["name"]?.GetValue<string>() ?? "File";
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6, VerticalAlignment = VerticalAlignment.Center };
            var open = new Button { Content = music ? "▶" : WinUIStrings.Translate("فتح", "Open"), MinWidth = 38, Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"), Foreground = WinUIHelpers.Brush("AccentTextFillColorPrimaryBrush"), BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"), BorderThickness = new Thickness(1) };
            ApplyButtonHover(open, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
            open.Click += async (_, _) => await OpenTableEntryAsync(entry, music);
            var download = new Button { Content = "↓", MinWidth = 38, Background = WinUIHelpers.Brush("ControlFillColorDefaultBrush"), Foreground = WinUIHelpers.Brush("AccentTextFillColorPrimaryBrush"), BorderBrush = WinUIHelpers.Brush("ControlStrokeColorDefaultBrush"), BorderThickness = new Thickness(1) };
            ApplyButtonHover(download, "ControlFillColorDefaultBrush", "ControlFillColorSecondaryBrush");
            download.Click += async (_, _) => await DownloadTableEntryAsync(entry);
            ToolTipService.SetToolTip(open, music ? WinUIStrings.Translate("تشغيل", "Play") : WinUIStrings.Translate("فتح نسخة مؤقتة", "Open temporary copy"));
            ToolTipService.SetToolTip(download, WinUIStrings.Translate("تنزيل إلى الكمبيوتر", "Download to PC"));
            if (_selected != "apps") actions.Children.Add(open);
            actions.Children.Add(download);
            Grid.SetColumn(actions, columns.Length - 1);
            row.Children.Add(actions);
            list.Items.Add(new Border { Tag = entry, BorderBrush = WinUIHelpers.Brush("CardStrokeColorSecondaryBrush"), BorderThickness = new Thickness(0, 0, 0, 1), Child = row });
        }
        _content.Children.Add(list);
        if (_hasMore)
        {
            var more = new Button { Content = "Load next phone page", HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 10, 0, 0) };
            more.Click += async (_, _) => await LoadNextPageAsync();
            _content.Children.Add(more);
        }
    }

    private async Task LoadTableIconAsync(JsonObject entry, Image image)
    {
        try
        {
            await _thumbnailLoadGate.WaitAsync();
            try
            {
                if (image.Source is not null) return;
                var thumbnail = await GetThumbnailAsync(entry["path"]?.GetValue<string>() ?? "", entry, CancellationToken.None);
                if (thumbnail is not null) image.Source = thumbnail;
            }
            finally { _thumbnailLoadGate.Release(); }
        }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Phone table thumbnail load failed: {ex.Message}"); }
    }

    private async Task OpenTableEntryAsync(JsonObject entry, bool music)
    {
        var name = entry["name"]?.GetValue<string>() ?? "File";
        var path = entry["path"]?.GetValue<string>() ?? "";
        if (!music && IsVideo(entry))
        {
            await ShowPreviewAsync(new MediaCard
            {
                Name = name,
                Path = path,
                Source = entry,
                Thumbnail = GetCachedThumbnail(path, entry),
            });
            return;
        }
        try
        {
            var localPath = await GetTemporaryMediaAsync(new MediaCard
            {
                Name = name,
                Path = path,
                Source = entry,
            });
            if (music)
            {
                var player = new MediaPlayer { AutoPlay = false };
                MediaPreviewControls? controls = null;
                var overlay = new PreviewOverlay(_shellRoot, name);
                void ToggleFullscreen() => overlay.ToggleFullscreen();
                try
                {
                    player.Source = MediaSource.CreateFromStorageFile(await StorageFile.GetFileFromPathAsync(localPath));
                    controls = new MediaPreviewControls(player, 800, 500, ToggleFullscreen, audioOnly: true, artwork: GetCachedThumbnail(path, entry), fallbackDuration: ReadDurationValue(entry));
                    overlay.SetContent(controls.Root, controls.SetSize);
                    if (GetCachedThumbnail(path, entry) is null)
                        _ = LoadPreviewArtworkAsync(controls, path, entry);
                    if (await overlay.ShowAsync())
                        await SaveTemporaryMediaAsync(localPath, name);
                }
                finally
                {
                    overlay.Dispose();
                    controls?.Dispose();
                    player.Pause();
                    player.Dispose();
                }
            }
            else
            {
                Process.Start(new ProcessStartInfo(localPath) { UseShellExecute = true });
                _status.Text = $"Opened temporary copy of {name}.";
            }
        }
        catch (Exception ex) { _status.Text = $"Could not open {name}: {ex.Message}"; }
    }

    private async Task DownloadTableEntryAsync(JsonObject entry)
    {
        var name = entry["name"]?.GetValue<string>() ?? "File";
        var path = entry["path"]?.GetValue<string>() ?? "";
        try
        {
            var localPath = await GetTemporaryMediaAsync(new MediaCard { Name = name, Path = path, Source = entry });
            await SaveTemporaryMediaAsync(localPath, name);
        }
        catch (Exception ex) { _status.Text = $"Could not download {name}: {ex.Message}"; }
    }

    private static FrameworkElement CreateFileIcon(JsonObject entry, bool music)
    {
        var extension = FormatExtension(entry).ToLowerInvariant();
        var (label, color) = music
            ? ("♫", "#ECEEF2")
            : extension switch
            {
                "pdf" => ("PDF", "#F45252"),
                "doc" or "docx" => ("W", "#4789E8"),
                "xls" or "xlsx" or "csv" => ("X", "#33A06F"),
                "ppt" or "pptx" => ("P", "#E77945"),
                "txt" or "md" => ("T", "#F0B51B"),
                "exe" => ("EXE", "#477FC4"),
                "exo" => ("EXO", "#7659A6"),
                "apks" => ("APK", "#328D73"),
                _ => (extension.Length > 0 ? extension[..Math.Min(3, extension.Length)].ToUpperInvariant() : "•", "#8B929A"),
            };
        var foreground = music || color == "#F0B51B" ? "#4D535A" : "#FFFFFF";
        return new Border
        {
            Width = music ? 48 : 40,
            Height = music ? 48 : 40,
            CornerRadius = new CornerRadius(music ? 8 : 4),
            Background = Brush(color),
            Child = new TextBlock
            {
                Text = label,
                FontSize = label.Length > 1 ? 10 : 17,
                FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                Foreground = Brush(foreground),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
    }

    private static FrameworkElement CreateMusicThumbnailIcon()
    {
        var fallback = new TextBlock
        {
            Text = "♫",
            FontSize = 19,
            Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var image = new Image { Stretch = Stretch.UniformToFill };
        var layers = new Grid();
        layers.Children.Add(fallback);
        layers.Children.Add(image);
        return new Border
        {
            Width = 48,
            Height = 48,
            CornerRadius = new CornerRadius(8),
            Background = WinUIHelpers.Brush("CardBackgroundFillColorSecondaryBrush"),
            Child = layers,
        };
    }

    private void RenderStorage()
    {
        var stats = _storageRoots.Select(root => root["storage"] as JsonObject)
            .FirstOrDefault(value => value?["scope"]?.GetValue<string>() is "storage_volume" or "full_storage");
        var totalBytes = stats is null ? 0 : ReadLong(stats, "total_bytes");
        var freeBytes = stats is null ? 0 : ReadLong(stats, "free_bytes");
        var hasCapacity = totalBytes > 0 && freeBytes >= 0 && freeBytes <= totalBytes;
        _pageCount.Text = hasCapacity
            ? $"{FormatByteCount(totalBytes - freeBytes)} used / {FormatByteCount(totalBytes)} on this storage volume"
            : _storageRoots.Count > 0 ? $"{_storageRoots.Count} authorized root(s); device totals unavailable" : "Authorized phone folder; device totals unavailable";
        var roots = new StackPanel { Spacing = 0, MaxWidth = 1120, HorizontalAlignment = HorizontalAlignment.Stretch };
        var visibleRoots = _storageRoots.Count > 0
            ? _storageRoots
            : new List<JsonObject> { new() { ["name"] = "Authorized phone folder", ["path"] = "" } };
        foreach (var storageRoot in visibleRoots)
        {
            var path = storageRoot["path"]?.GetValue<string>() ?? "";
            var name = storageRoot["name"]?.GetValue<string>() ?? "Phone storage";
            var storageInfo = storageRoot["storage"] as JsonObject;
            if (storageInfo?["scope"]?.GetValue<string>() is not ("storage_volume" or "full_storage")) storageInfo = null;
            var rootTotal = storageInfo is null ? 0 : ReadLong(storageInfo, "total_bytes");
            var rootFree = storageInfo is null ? 0 : ReadLong(storageInfo, "free_bytes");
            var rootDetails = rootTotal > 0 && rootFree >= 0 && rootFree <= rootTotal
                ? $"{name} · {FormatByteCount(rootTotal - rootFree)} used / {FormatByteCount(rootTotal)}"
                : name;
            var root = new Button
            {
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Background = WinUIHelpers.Brush("TransparentBrush"),
                BorderThickness = new Thickness(0),
                Padding = new Thickness(30, 5, 12, 5),
                MinHeight = 58,
                Content = new StackPanel
                {
                    Orientation = Orientation.Horizontal,
                    Spacing = 12,
                    Children =
                    {
                        new TextBlock { Text = "\uE8B7", FontFamily = new Microsoft.UI.Xaml.Media.FontFamily("Segoe Fluent Icons"), FontSize = 30, Foreground = WinUIHelpers.Brush("AccentTextFillColorPrimaryBrush"), VerticalAlignment = VerticalAlignment.Center },
                        new TextBlock { Text = rootDetails, FontSize = 13, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), VerticalAlignment = VerticalAlignment.Center },
                    },
                },
            };
            ToolTipService.SetToolTip(root, WinUIStrings.Translate("فتح المجلد الذي أتاحه الهاتف", "Open the folder currently granted by the phone"));
            root.Click += (_, _) => ShowFileBrowser(path);
            roots.Children.Add(root);
        }
        _content.Children.Add(roots);
        _status.Text = hasCapacity
            ? "Phone storage is available to browse under Android's granted file-access scope."
            : "Separate internal/SD roots and device capacity are unavailable; file access remains limited to the authorized folder.";
    }

    private static void SetSelection(IEnumerable<MediaCard> cards, bool selected)
    {
        foreach (var card in cards) card.IsSelected = selected;
    }

    private async Task LoadTransfersAsync()
    {
        if (_transferLoadInProgress) return;
        _transferLoadInProgress = true;
        try
        {
            var result = await _bridge.CallAsync("get_active_phone_uploads") as JsonArray;
            var history = await _bridge.CallAsync("get_phone_file_upload_history") as JsonArray;
            if (_selected != "transfer") return;
            _content.Children.Clear();
            var panel = new StackPanel { Spacing = 12, MaxWidth = 1000, HorizontalAlignment = HorizontalAlignment.Stretch };
            panel.Children.Add(new TextBlock
            {
                Text = "Phone uploads and recent completed transfers.",
                FontSize = 12,
                Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"),
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 0, 0, 8),
            });
            var uploads = result?.OfType<JsonObject>().ToList() ?? new List<JsonObject>();
            foreach (var upload in uploads)
            {
                var name = ReadText(upload, "name", "File");
                var sent = ReadLong(upload, "bytes_sent");
                var total = ReadLong(upload, "total_bytes");
                var state = ReadText(upload, "state", "sending");
                var percent = total > 0 ? Math.Clamp(sent / (double)total * 100, 0, 100) : 0;
                var row = new Grid { ColumnSpacing = 18, Padding = new Thickness(12, 10, 12, 10), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(180) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
                row.Children.Add(new TextBlock { Text = name, FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis });
                var size = new TextBlock { Text = $"{FormatByteCount(sent)} / {FormatByteCount(total)}", FontSize = 11, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center };
                Grid.SetColumn(size, 1); row.Children.Add(size);
                var statePanel = new StackPanel { Spacing = 4 };
                statePanel.Children.Add(new TextBlock { Text = state == "confirming" ? "Waiting for phone confirmation" : state == "cancelling" ? "Cancelling" : "Sending", FontSize = 11, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush") });
                statePanel.Children.Add(new ProgressBar { Minimum = 0, Maximum = 100, Value = percent, Height = 4 });
                Grid.SetColumn(statePanel, 2); row.Children.Add(statePanel);
                panel.Children.Add(new Border { BorderBrush = WinUIHelpers.Brush("CardStrokeColorSecondaryBrush"), BorderThickness = new Thickness(0, 0, 0, 1), Child = row });
            }
            var completed = history?.OfType<JsonObject>().Reverse().Take(100).ToList() ?? new List<JsonObject>();
            foreach (var item in completed)
            {
                var name = ReadText(item, "name", "File");
                var destination = ReadText(item, "destination", "Phone storage");
                var size = ReadLong(item, "size");
                var row = new Grid { ColumnSpacing = 18, Padding = new Thickness(12, 10, 12, 10), Background = WinUIHelpers.Brush("CardBackgroundFillColorDefaultBrush") };
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(180) });
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
                row.Children.Add(new TextBlock { Text = name, FontSize = 12, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis });
                var sizeText = new TextBlock { Text = FormatByteCount(size), FontSize = 11, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), VerticalAlignment = VerticalAlignment.Center };
                Grid.SetColumn(sizeText, 1); row.Children.Add(sizeText);
                var completedText = new TextBlock { Text = $"Completed · {destination}", FontSize = 11, Foreground = WinUIHelpers.Brush("SystemFillColorSuccessBrush"), VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis };
                Grid.SetColumn(completedText, 2); row.Children.Add(completedText);
                panel.Children.Add(new Border { BorderBrush = WinUIHelpers.Brush("CardStrokeColorSecondaryBrush"), BorderThickness = new Thickness(0, 0, 0, 1), Child = row });
            }
            if (uploads.Count == 0 && completed.Count == 0)
                panel.Children.Add(new TextBlock { Text = "No transfers yet.", FontSize = 13, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), Margin = new Thickness(0, 10, 0, 0) });
            _content.Children.Add(panel);
            _status.Text = $"{uploads.Count} active upload(s) · {completed.Count} recent completed transfer(s).";
        }
        catch (Exception ex)
        {
            if (_selected == "transfer")
            {
                ShowEmpty("Transfer status unavailable", ex.Message);
                _status.Text = ex.Message;
            }
        }
        finally { _transferLoadInProgress = false; }
    }

    private void ShowFileBrowser(string path = "")
    {
        try
        {
            var browser = new PhoneFilesPage(_bridge, new PhoneFilesPageState(path, "", "name", null, false));
            _content.Children.Clear();
            _content.Children.Add(browser);
            _pageTitle.Text = WinUIStrings.Translate("ملفات الهاتف", "Phone files");
            _pageCount.Text = WinUIStrings.Translate("استعراض ومعاينة وتنزيل ورفع", "Browse, preview, download, and upload");
        }
        catch (Exception ex)
        {
            _content.Children.Clear();
            ShowEmpty("Phone file browser could not open", ex.Message);
            _status.Text = ex.Message;
        }
    }

    private async Task UploadToPhoneAsync()
    {
        try
        {
            var picker = new FileOpenPicker();
            picker.FileTypeFilter.Add("*");
            InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(App.MainWindowInstance!));
            var files = await picker.PickMultipleFilesAsync();
            if (files.Count == 0) return;
            var roots = await _bridge.CallAsync("phone_files_action", new { operation = "roots" }) as JsonObject;
            var destination = roots?["roots"] is JsonArray rootEntries
                ? rootEntries.OfType<JsonObject>().FirstOrDefault()?["path"]?.GetValue<string>() ?? ""
                : "";
            var settings = await _bridge.CallAsync("get_settings") as JsonObject;
            var preferredDestination = ReadText(settings ?? new JsonObject(), "phone_upload_directory", "");
            if (!string.IsNullOrWhiteSpace(preferredDestination)) destination = preferredDestination;
            var destinationCheck = await _bridge.CallAsync("phone_files_list", new { path = destination, offset = 0, limit = 1, sort = "name" }) as JsonObject;
            if (destinationCheck?["success"]?.GetValue<bool>() != true)
                throw new InvalidOperationException(destinationCheck?["error"]?.GetValue<string>() ?? "The configured phone destination is unavailable.");
            var uploaded = 0;
            foreach (var file in files)
            {
                _status.Text = $"Uploading {file.Name} ({uploaded + 1}/{files.Count})…";
                var result = await _bridge.CallAsync("phone_files_upload", new
                {
                    local_path = file.Path,
                    destination,
                }, requestTimeoutOverride: TimeSpan.FromMinutes(5)) as JsonObject;
                if (result?["success"]?.GetValue<bool>() != true)
                    throw new InvalidOperationException(result?["error"]?.GetValue<string>() ?? $"Could not upload {file.Name}.");
                uploaded++;
            }
            _selected = "transfer";
            _pageTitle.Text = LocalizedCategoryName("transfer");
            await LoadTransfersAsync();
            _status.Text = $"Uploaded {uploaded} file(s) to the phone.";
        }
        catch (Exception ex)
        {
            _status.Text = $"Could not upload to phone: {ex.Message}";
        }
    }

    private void ShowUnsupported(string title, string message)
    {
        _selected = "apps";
        _pageTitle.Text = title;
        ShowEmpty(title, message);
    }

    private void ShowEmpty(string title, string message)
    {
        var panel = new StackPanel { Spacing = 8, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, MaxWidth = 520 };
        panel.Children.Add(new TextBlock { Text = title, FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold, Foreground = WinUIHelpers.Brush("TextFillColorPrimaryBrush"), TextAlignment = TextAlignment.Center });
        panel.Children.Add(new TextBlock { Text = message, FontSize = 13, Foreground = WinUIHelpers.Brush("TextFillColorSecondaryBrush"), TextWrapping = TextWrapping.Wrap, TextAlignment = TextAlignment.Center });
        _content.Children.Add(panel);
    }

    private static string FormatSize(JsonObject entry)
    {
        try
        {
            var bytes = entry["size"]?.GetValue<long>() ?? 0;
            return bytes >= 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024 * 1024):0.0} GB" : bytes >= 1024L * 1024 ? $"{bytes / (1024d * 1024):0.0} MB" : bytes >= 1024 ? $"{bytes / 1024d:0} KB" : $"{bytes} B";
        }
        catch { return "—"; }
    }

    private static string ExplainPhoneFileError(string error)
    {
        var explanation = error switch
        {
            var value when value.Contains("انتهت مهلة رد الهاتف", StringComparison.Ordinal) => "Phone did not answer before timeout. Check the file-channel connection and whether the phone's document provider is still responding.",
            var value when value.Contains("لم يتم اختيار مجلد للهاتف", StringComparison.Ordinal) || value.Contains("لم يتم اختيار مجلد الهاتف", StringComparison.Ordinal) => "Phone storage access was not granted, or its saved folder permission was revoked. Re-select the folder on the phone.",
            var value when value.Contains("المسار غير موجود", StringComparison.Ordinal) || value.Contains("الملف غير موجود", StringComparison.Ordinal) => "The item may have been moved, renamed, deleted, or become unavailable through its document provider. Refresh the listing.",
            var value when value.Contains("المسار يتجاوز مجلد الهاتف", StringComparison.Ordinal) => "The request was rejected because it tried to leave the phone's authorized folder. This is a security boundary.",
            var value when value.Contains("معاينة الفيديو تتطلب Android 10 أو أحدث", StringComparison.Ordinal) => "This phone cannot create a video thumbnail through the current API level. The original video was not downloaded.",
            var value when value.Contains("المعاينة متاحة للصور والفيديو فقط", StringComparison.Ordinal) => "This file type has no thumbnail preview. Open or download it instead.",
            var value when value.Contains("مزود ملفات الهاتف لا يدعم", StringComparison.Ordinal) => "The selected document provider does not support this file operation.",
            _ => "Phone-file request failed. Check the phone connection, its folder permission, and the document provider.",
        };
        return $"{explanation}\nDetails: {error}";
    }

    private static string FormatByteCount(long bytes) => bytes >= 1024L * 1024 * 1024
        ? $"{bytes / (1024d * 1024 * 1024):0.0} GB"
        : bytes >= 1024L * 1024 ? $"{bytes / (1024d * 1024):0.0} MB"
        : bytes >= 1024 ? $"{bytes / 1024d:0} KB" : $"{bytes} B";

    private static long ReadLong(JsonObject entry, string key)
    {
        try { return entry[key]?.GetValue<long>() ?? 0; }
        catch { return 0; }
    }

    private static int ReadInt(JsonObject entry, string key, int fallback)
    {
        try { return entry[key]?.GetValue<int>() ?? fallback; }
        catch { return fallback; }
    }

    private static bool ReadBool(JsonObject entry, string key)
    {
        try { return entry[key]?.GetValue<bool>() ?? false; }
        catch { return false; }
    }

    private static string FormatDate(JsonObject entry)
    {
        try
        {
            var value = entry["modified"]?.GetValue<long>() ?? 0;
            if (value <= 0) return "";
            return DateTimeOffset.FromUnixTimeMilliseconds(ModifiedTimestamp(entry)).LocalDateTime.ToString("MMM d");
        }
        catch { return ""; }
    }

    private static long ModifiedTimestamp(JsonObject entry)
    {
        try
        {
            var value = entry["modified"]?.GetValue<long>() ?? 0;
            if (value <= 0) return 0;
            return value > 10_000_000_000 ? value : value * 1000;
        }
        catch { return 0; }
    }

    private static string ReadText(JsonObject entry, string key, string fallback)
    {
        try { return entry[key]?.GetValue<string>() is { Length: > 0 } value ? value : fallback; }
        catch { return fallback; }
    }

    private static string FormatExtension(JsonObject entry)
    {
        var extension = Path.GetExtension(entry["name"]?.GetValue<string>() ?? "").TrimStart('.').ToUpperInvariant();
        return extension.Length == 0 ? "—" : extension;
    }

    private static string ReadDuration(JsonObject entry)
    {
        var duration = ReadDurationValue(entry);
        if (duration is null) return "—";
        return duration.Value.TotalHours >= 1 ? duration.Value.ToString(@"h\:mm\:ss") : $"{(int)duration.Value.TotalMinutes}:{duration.Value.Seconds:00}";
    }

    private static TimeSpan? ReadDurationValue(JsonObject entry)
    {
        try
        {
            var milliseconds = entry["duration"]?.GetValue<long>() ?? 0;
            return milliseconds > 0 ? TimeSpan.FromMilliseconds(milliseconds) : null;
        }
        catch { return null; }
    }

    private static SolidColorBrush Brush(string hex)
    {
        var value = hex.TrimStart('#');
        byte a = 255;
        if (value.Length == 8)
        {
            a = Convert.ToByte(value[..2], 16);
            value = value[2..];
        }
        var color = ColorHelper.FromArgb(a, Convert.ToByte(value[..2], 16), Convert.ToByte(value.Substring(2, 2), 16), Convert.ToByte(value.Substring(4, 2), 16));
        return new SolidColorBrush(color);
    }
}
