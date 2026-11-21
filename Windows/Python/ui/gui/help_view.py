import customtkinter as ctk


class HelpView(ctk.CTkFrame):
    """دليل استخدام مختصر ومتاح من داخل التطبيق."""
    def __init__(self, parent, main_window):
        super().__init__(parent, fg_color="transparent")
        colors = main_window.colors
        ctk.CTkLabel(self, text="دليل الاستخدام والمساعدة", font=ctk.CTkFont(size=22, weight="bold"), text_color=colors.text_primary).pack(anchor="w", pady=(0, 16))
        box = ctk.CTkTextbox(self, fg_color=colors.bg_card, text_color=colors.text_primary, corner_radius=12)
        box.pack(fill="both", expand=True)
        box.insert("end", "DeskTwin\n\n1) شغّل الخدمة من زر التشغيل.\n2) ولّد كود اقتران وأدخله في الهاتف.\n3) استخدم المجلدات لتصفح الملفات، وDeskTwin Chat للرسائل النصية فقط.\n4) راجع صفحة النشاط للسجل الكامل، وصفحة الأجهزة لإدارة الثقة.\n5) عند انقطاع الاتصال استخدم إعادة الاتصال؛ لا تحتاج لإعادة الاقتران إلا بعد إلغاء الثقة.\n6) لو ظهر خطأ، افتح فحص التوافق لقراءة السبب والحل المقترح.\n\nعن البرنامج\nDeskTwin يعمل محليًا على شبكة LAN، ولا يرفع الملفات إلى خدمة خارجية.\n")
        box.configure(state="disabled")
