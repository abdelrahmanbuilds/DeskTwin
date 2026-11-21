# Windows desktop app

The official desktop UI is a C# WinUI 3 application. It starts the Python host in WinUI/bridge as a child process and communicates with it through local JSON Lines over standard input and output.

Build and run instructions are in the Windows project README. Keep WinUI/app, WinUI/bridge, and Python in their repository locations so the app can locate its service.
