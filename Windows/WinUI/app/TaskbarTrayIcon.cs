using System.ComponentModel;
using System.Runtime.InteropServices;

namespace DeskTwin.WinUI;

/// <summary>Small native notification-area icon for the unpackaged WinUI window.</summary>
internal sealed class TaskbarTrayIcon : IDisposable
{
    private const uint NotifyAdd = 0x00000000;
    private const uint NotifyDelete = 0x00000002;
    private const uint NotifyMessage = 0x00000001;
    private const uint NotifyIcon = 0x00000002;
    private const uint NotifyTip = 0x00000004;
    private const uint CallbackMessage = 0x8000 + 42;
    private const uint LeftButtonUp = 0x0202;
    private const uint LeftButtonDoubleClick = 0x0203;
    private const uint RightButtonUp = 0x0205;
    private const uint MenuString = 0x00000000;
    private const uint MenuSeparator = 0x00000800;
    private const uint TrackRightButton = 0x0002;
    private const uint TrackReturnCommand = 0x0100;
    private const uint IconId = 1;
    private const uint SubclassId = 1;

    private readonly IntPtr _windowHandle;
    private readonly string _openLabel;
    private readonly string _exitLabel;
    private readonly Action _open;
    private readonly Action _exit;
    private readonly SubclassProc _subclassProc;
    private readonly GCHandle _selfHandle;
    private readonly uint _taskbarCreatedMessage;
    private bool _disposed;

    public TaskbarTrayIcon(IntPtr windowHandle, string openLabel, string exitLabel, Action open, Action exit)
    {
        _windowHandle = windowHandle;
        _openLabel = openLabel;
        _exitLabel = exitLabel;
        _open = open;
        _exit = exit;
        _taskbarCreatedMessage = RegisterWindowMessage("TaskbarCreated");
        _subclassProc = WindowProc;
        _selfHandle = GCHandle.Alloc(this);

        if (!SetWindowSubclass(_windowHandle, _subclassProc, new UIntPtr(SubclassId), new UIntPtr(unchecked((ulong)GCHandle.ToIntPtr(_selfHandle).ToInt64()))))
        {
            _selfHandle.Free();
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not connect the DeskTwin tray icon to its window.");
        }
        AddIcon();
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        DeleteIcon();
        RemoveWindowSubclass(_windowHandle, _subclassProc, new UIntPtr(SubclassId));
        if (_selfHandle.IsAllocated) _selfHandle.Free();
    }

    private void AddIcon()
    {
        var data = new NotifyIconData
        {
            Size = (uint)Marshal.SizeOf<NotifyIconData>(),
            WindowHandle = _windowHandle,
            Id = IconId,
            Flags = NotifyMessage | NotifyIcon | NotifyTip,
            CallbackMessage = CallbackMessage,
            IconHandle = LoadIcon(IntPtr.Zero, new IntPtr(32512)), // IDI_APPLICATION
            Tip = "DeskTwin",
        };
        if (!Shell_NotifyIcon(NotifyAdd, ref data))
            System.Diagnostics.Debug.WriteLine($"DeskTwin tray icon could not be added: {Marshal.GetLastWin32Error()}");
    }

    private void DeleteIcon()
    {
        var data = new NotifyIconData
        {
            Size = (uint)Marshal.SizeOf<NotifyIconData>(),
            WindowHandle = _windowHandle,
            Id = IconId,
        };
        Shell_NotifyIcon(NotifyDelete, ref data);
    }

    private IntPtr WindowProc(IntPtr hwnd, uint message, UIntPtr wParam, IntPtr lParam, UIntPtr subclassId, UIntPtr referenceData)
    {
        if (message == _taskbarCreatedMessage)
        {
            AddIcon();
            return IntPtr.Zero;
        }
        if (message == CallbackMessage)
        {
            var mouseMessage = unchecked((uint)lParam.ToInt64());
            if (mouseMessage is LeftButtonUp or LeftButtonDoubleClick)
                _open();
            else if (mouseMessage == RightButtonUp)
                ShowMenu();
            return IntPtr.Zero;
        }
        return DefSubclassProc(hwnd, message, wParam, lParam);
    }

    private void ShowMenu()
    {
        var menu = CreatePopupMenu();
        if (menu == IntPtr.Zero) return;
        try
        {
            AppendMenu(menu, MenuString, new UIntPtr(1), _openLabel);
            AppendMenu(menu, MenuSeparator, UIntPtr.Zero, null);
            AppendMenu(menu, MenuString, new UIntPtr(2), _exitLabel);
            GetCursorPos(out var point);
            SetForegroundWindow(_windowHandle);
            var command = TrackPopupMenu(menu, TrackRightButton | TrackReturnCommand, point.X, point.Y, 0, _windowHandle, IntPtr.Zero);
            PostMessage(_windowHandle, 0, UIntPtr.Zero, IntPtr.Zero);
            if (command == 1) _open();
            else if (command == 2) _exit();
        }
        finally
        {
            DestroyMenu(menu);
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NotifyIconData
    {
        public uint Size;
        public IntPtr WindowHandle;
        public uint Id;
        public uint Flags;
        public uint CallbackMessage;
        public IntPtr IconHandle;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string? Tip;
        public uint State;
        public uint StateMask;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)] public string? Info;
        public uint TimeoutOrVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)] public string? InfoTitle;
        public uint InfoFlags;
        public Guid ItemGuid;
        public IntPtr BalloonIcon;
    }

    private delegate IntPtr SubclassProc(IntPtr hwnd, uint message, UIntPtr wParam, IntPtr lParam, UIntPtr subclassId, UIntPtr referenceData);

    [StructLayout(LayoutKind.Sequential)]
    private struct Point { public int X; public int Y; }

    [DllImport("shell32.dll", EntryPoint = "Shell_NotifyIconW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool Shell_NotifyIcon(uint message, ref NotifyIconData data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadIcon(IntPtr instance, IntPtr iconName);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint RegisterWindowMessage(string message);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(IntPtr hwnd, SubclassProc callback, UIntPtr subclassId, UIntPtr referenceData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(IntPtr hwnd, SubclassProc callback, UIntPtr subclassId);

    [DllImport("comctl32.dll", SetLastError = true)]
    private static extern IntPtr DefSubclassProc(IntPtr hwnd, uint message, UIntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AppendMenu(IntPtr menu, uint flags, UIntPtr item, string? text);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint TrackPopupMenu(IntPtr menu, uint flags, int x, int y, int reserved, IntPtr hwnd, IntPtr rect);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyMenu(IntPtr menu);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out Point point);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessage(IntPtr hwnd, uint message, UIntPtr wParam, IntPtr lParam);
}
