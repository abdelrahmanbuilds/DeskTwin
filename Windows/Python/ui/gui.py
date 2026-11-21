"""
DeskTwin GUI Entry Point
--------------------------
نقطة الدخول للواجهة الرسومية الجديدة (Sidebar + شاشات منفصلة).
هذا مدخل نسخة Python/CustomTkinter. نسخة WinUI لها مشروع ومدخل تشغيل منفصلان
تحت WinUI/app و WinUI/launchers/run-winui.ps1.
"""

import os
import sys

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJECT_ROOT)
from gui.main_window import DeskTwinMainWindow

if __name__ == "__main__":
    initial_view = "folders" if "--open-folders" in sys.argv else None
    app = DeskTwinMainWindow(initial_view=initial_view)
    app.mainloop()
