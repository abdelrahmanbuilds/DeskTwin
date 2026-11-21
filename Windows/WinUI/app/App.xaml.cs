using Microsoft.UI.Xaml;
using System.Runtime.InteropServices;

namespace DeskTwin.WinUI;

public partial class App : Application
{
    private Window? _window;
    private Mutex? _instanceMutex;
    public static Window? MainWindowInstance { get; private set; }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);

    public App()
    {
        InitializeComponent();
    }

    protected override async void OnLaunched(LaunchActivatedEventArgs args)
    {
        _instanceMutex = new Mutex(initiallyOwned: true, name: @"Local\DeskTwin.WinUI.SingleInstance", createdNew: out var isFirstInstance);
        if (!isFirstInstance)
        {
            MessageBoxW(IntPtr.Zero, WinUIStrings.Translate("DeskTwin مفتوح بالفعل. استخدم النافذة الحالية لتجنب تشغيل خدمتين على المنفذ نفسه.", "DeskTwin is already open. Use the current window to avoid starting a second service on the same port."), "DeskTwin", 0x40);
            Exit();
            return;
        }

        PhoneAssistantWorkspacePage.ClearTemporaryMediaCache();
        var mainWindow = new MainWindow();
        _window = mainWindow;
        MainWindowInstance = _window;
        await mainWindow.InitializeAsync();
        _window.Activate();
    }
}
