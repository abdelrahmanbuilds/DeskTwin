"""
DeskTwin Theme
---------------
تعريف مركزي لكل الألوان المستخدمة في الواجهة، بوضعين: Dark و Light.
أي شاشة في الواجهة لازم تستورد الألوان من هنا، مش تكتب hex codes مباشرة -
عشان تبديل الثيم يطبّق على كل حاجة فورًا من مكان واحد.
"""

from dataclasses import dataclass


@dataclass
class ThemeColors:
    # خلفيات
    bg_primary: str
    bg_secondary: str
    bg_card: str
    bg_sidebar: str

    # نصوص
    text_primary: str
    text_secondary: str
    text_disabled: str

    # الألوان الأساسية
    accent: str
    accent_hover: str
    on_accent: str
    on_accent_muted: str

    # ألوان دلالية (حالة)
    success: str
    success_hover: str
    warning: str
    warning_hover: str
    error: str
    error_hover: str
    info: str

    # حدود وفواصل
    border: str

    # حالة الاتصال
    online_dot: str
    offline_dot: str

    # Sidebar has a stable contrast contract independent of page theme.
    sidebar_text: str
    sidebar_muted: str
    sidebar_hover: str

    # Glassmorphism tokens: ألوان ثابتة وخفيفة، بدون blur أو طبقات متحركة.
    glass_overlay: str
    glass_border: str
    glow_cyan: str
    glow_violet: str


LIGHT_THEME = ThemeColors(
    bg_primary="#F5F7FB",
    bg_secondary="#F8FAFC",
    bg_card="#FFFFFF",
    bg_sidebar="#172033",
    text_primary="#172033",
    text_secondary="#667085",
    text_disabled="#9CA3AF",
    accent="#2563EB",
    accent_hover="#1D4ED8",
    on_accent="#FFFFFF",
    on_accent_muted="#DCEAFF",
    success="#16A34A",
    success_hover="#15803D",
    warning="#F59E0B",
    warning_hover="#D97706",
    error="#DC2626",
    error_hover="#B91C1C",
    info="#0EA5E9",
    border="#E2E8F0",
    online_dot="#16A34A",
    offline_dot="#9CA3AF",
    sidebar_text="#E8F0FE",
    sidebar_muted="#A9B8CC",
    sidebar_hover="#243551",
    glass_overlay="#FFFFFF",
    glass_border="#D7E3F5",
    glow_cyan="#06B6D4",
    glow_violet="#8B5CF6",
)

DARK_THEME = ThemeColors(
    bg_primary="#101827",
    bg_secondary="#182235",
    bg_card="#182235",
    bg_sidebar="#0B1220",
    text_primary="#F1F5F9",
    text_secondary="#B6C2D4",
    text_disabled="#6B7280",
    accent="#9DBDFF",
    accent_hover="#BED0FF",
    on_accent="#0B1220",
    on_accent_muted="#23324A",
    success="#34D399",
    success_hover="#10B981",
    warning="#FBBF24",
    warning_hover="#D97706",
    error="#FB7185",
    error_hover="#E11D48",
    info="#60A5FA",
    border="#334155",
    online_dot="#34D399",
    offline_dot="#6B7280",
    sidebar_text="#E8F0FE",
    sidebar_muted="#A9B8CC",
    sidebar_hover="#1B2A43",
    glass_overlay="#142238",
    glass_border="#2B466B",
    glow_cyan="#22D3EE",
    glow_violet="#A78BFA",
)


def get_theme(mode: str) -> ThemeColors:
    """mode: 'dark' أو 'light'."""
    return DARK_THEME if mode == "dark" else LIGHT_THEME
