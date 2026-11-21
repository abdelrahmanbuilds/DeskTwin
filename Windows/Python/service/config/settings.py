"""
DeskTwin Config
----------------
المستخدم بيحدد هنا إيه اللي عايز DeskTwin يفحصه:
ممكن يكون فولدر واحد، أو عدة فولدرات، أو بارتشن كامل.

مفيش أي هاردكود لمسار معين - كل حاجة بتتحدد من هنا.
"""

import json
import os
from dataclasses import dataclass, field, asdict


PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DEFAULT_CONFIG_PATH = os.path.join(PROJECT_ROOT, "config", "config.json")

# امتدادات هيتجاهلها الـ scanner افتراضيًا (ملفات نظام / مؤقتة)
DEFAULT_IGNORED_EXTENSIONS = [".tmp", ".log", ".cache"]

# مجلدات هيتجاهلها دايمًا (تكلفة عالية بلا فايدة)
DEFAULT_IGNORED_FOLDERS = [
    "node_modules", ".git", "__pycache__", "venv", ".venv",
    "$RECYCLE.BIN", "System Volume Information",
]


@dataclass
class ScanTarget:
    """مسار واحد المستخدم عايز يفحصه (ملف / فولدر / بارتشن)."""
    path: str
    label: str = ""          # اسم مستعار زي "Projects" أو "Accounting"
    recursive: bool = True   # يفحص المجلدات الفرعية ولا لأ
    enabled: bool = True
    is_file: bool = False    # True لو ده ملف منفرد (مش فولدر) - القاعدة:
                              # "الملف المنفرد لا يشارك جيرانه" (PROJECT_STATUS.md بند 6.5)

    def normalized_path(self) -> str:
        return os.path.normpath(os.path.abspath(self.path))


@dataclass
class DeskTwinConfig:
    targets: list[ScanTarget] = field(default_factory=list)
    ignored_extensions: list[str] = field(default_factory=lambda: DEFAULT_IGNORED_EXTENSIONS.copy())
    ignored_folders: list[str] = field(default_factory=lambda: DEFAULT_IGNORED_FOLDERS.copy())
    hash_files_larger_than_mb: int = 0   # 0 = يعمل hash لكل الملفات بغض النظر عن الحجم
    hash_size_limit_mb: int = 500        # فوق الحجم ده، ميعملش hash (تكلفة عالية)

    # النطاق الآمن الافتراضي: الموبايل يشوف بس الـ targets المحددة صراحة.
    # لو المستخدم فعّلها بنفسه (تأكيد صريح من واجهة الكمبيوتر) - الموبايل
    # يقدر يتصفح الجهاز كامل (كل الـ drives)، مش بس الـ targets.
    # مشاركة الجهاز بالكامل اختيار صريح من واجهة الكمبيوتر.
    share_entire_device: bool = False

    def add_target(self, path: str, label: str = "", recursive: bool = True, is_file: bool = False):
        self.targets.append(ScanTarget(path=path, label=label, recursive=recursive, is_file=is_file))

    def is_path_allowed(self, path: str) -> bool:
        """
        النطاق الآمن الفعلي: هل المسار ده مسموح للموبايل يشوفه؟
        - لو share_entire_device مفعّلة: كل حاجة مسموحة.
        - غير كده: لازم يكون المسار جوه واحد من الـ targets المفعّلة
          (نفس المسار بالظبط، أو فرع منه).

        ملحوظة مهمة عن targets من نوع ملف منفرد (is_file=True): المسار
        المسموح هنا هو *الملف نفسه بالظبط*، مش الفولدر اللي هو جواه.
        لو target هو ملف منفرد، normalized_path() بترجع مسار الملف، والفحص
        `normalized == target_norm` بيغطي الحالة دي صح من غير أي تغيير إضافي.
        لكن لازم ننتبه: الفحص التاني `startswith(target_norm + os.sep)`
        متوقّع يفضل False دايمًا لملف منفرد (مفيش حاجة "جوه" ملف)، وده
        صحيح ومطلوب - نفس قاعدة "الملف المنفرد لا يشارك جيرانه".
        """
        if self.share_entire_device:
            return True
        # Windows paths قد تصل من Android بشرطات عكسية/أمامية أو بحالة
        # حروف مختلفة. نوحّدها قبل المقارنة حتى لا يفشل المسار الصحيح.
        # استخدم realpath قبل المقارنة حتى لا يخرج symlink/junction من target
        # المسموح إلى مجلد شقيق أو إلى جذر القرص.
        normalized = os.path.normcase(os.path.normpath(os.path.realpath(str(path).strip().strip('"'))))
        for target in self.targets:
            if not target.enabled:
                continue
            target_norm = os.path.normcase(os.path.normpath(os.path.realpath(target.normalized_path())))
            if normalized == target_norm:
                return True
            if not target.is_file:
                # Prefix matching breaks when the permitted directory is a
                # drive root: adding another separator produces a non-matching
                # spelling. commonpath preserves the component boundary.
                try:
                    if os.path.commonpath((target_norm, normalized)) == target_norm:
                        return True
                except ValueError:
                    # Different drives/UNC shares cannot be descendants.
                    continue
        return False

    def to_dict(self):
        return {
            "targets": [asdict(t) for t in self.targets],
            "ignored_extensions": self.ignored_extensions,
            "ignored_folders": self.ignored_folders,
            "hash_files_larger_than_mb": self.hash_files_larger_than_mb,
            "hash_size_limit_mb": self.hash_size_limit_mb,
            "share_entire_device": self.share_entire_device,
        }

    def save(self, path: str = DEFAULT_CONFIG_PATH):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=2, ensure_ascii=False)

    @classmethod
    def load(cls, path: str = DEFAULT_CONFIG_PATH) -> "DeskTwinConfig":
        if not os.path.exists(path):
            return cls()  # config فاضي، المستخدم لسه محددش حاجة
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        cfg = cls(
            ignored_extensions=data.get("ignored_extensions", DEFAULT_IGNORED_EXTENSIONS.copy()),
            ignored_folders=data.get("ignored_folders", DEFAULT_IGNORED_FOLDERS.copy()),
            hash_files_larger_than_mb=data.get("hash_files_larger_than_mb", 0),
            hash_size_limit_mb=data.get("hash_size_limit_mb", 500),
            share_entire_device=data.get("share_entire_device", False),
        )
        cfg.targets = [ScanTarget(**t) for t in data.get("targets", [])]
        return cfg


if __name__ == "__main__":
    # مثال: المستخدم بيضيف فولدر المشاريع بتاعته
    cfg = DeskTwinConfig()
    cfg.add_target(r"C:\Projects", label="Projects", recursive=True)
    cfg.save()
    print(f"Config saved with {len(cfg.targets)} target(s).")
