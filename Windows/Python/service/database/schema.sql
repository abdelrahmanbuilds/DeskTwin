-- DeskTwin Database Schema v0.1
-- ------------------------------------------------------------
-- الفكرة: كل صف = ملف واحد بكل الـ metadata بتاعته + حالة المزامنة.
-- الملف الأصلي فضل على الكمبيوتر - هنا بنخزن المعرفة عنه بس.

CREATE TABLE IF NOT EXISTS files (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    path            TEXT NOT NULL UNIQUE,   -- المسار الكامل (المفتاح المنطقي)
    name            TEXT NOT NULL,
    extension       TEXT,
    parent_folder   TEXT,
    size_bytes      INTEGER NOT NULL DEFAULT 0,
    created_at      TEXT,                   -- ISO 8601, وقت إنشاء الملف على القرص
    modified_at     TEXT,                   -- ISO 8601, آخر تعديل على القرص
    hash            TEXT,                   -- SHA-256، NULL لو الملف أكبر من الحد المسموح
    version         INTEGER NOT NULL DEFAULT 1,

    -- تصنيف / سياق
    project         TEXT,                   -- اسم المشروع (من الـ target label)
    tags            TEXT,                   -- JSON array كـ نص، مؤقتًا

    -- أصل الملف: هل هو ملف كمبيوتر أصلي، ولا اتضاف من التليفون
    origin          TEXT NOT NULL DEFAULT 'pc' CHECK (origin IN ('pc','phone')),

    -- سياسة الملفات المضافة من التليفون (بند: رفع ملف جديد من الموبايل)
    -- null لو الملف origin='pc'
    phone_sync_policy TEXT CHECK (phone_sync_policy IN ('copy_and_keep','copy_and_delete','metadata_only',NULL)),

    -- آخر hash معروف عند آخر مزامنة ناجحة بين الجهازين (مرجع لاكتشاف التعارض)
    last_synced_hash TEXT,
    last_synced_at   TEXT,

    -- حالة المزامنة
    sync_state      TEXT NOT NULL DEFAULT 'NEW'
                    CHECK (sync_state IN ('SYNCED','MODIFIED','PENDING','CONFLICT','MISSING','NEW','DELETED')),
    last_seen_at    TEXT NOT NULL,          -- آخر مرة الـ scanner/watcher شاف الملف موجود

    -- القاعدة الذهبية: افتراضيًا التليفون بياخد الـ metadata بس، مش الملف الفعلي.
    -- الملف ميبقاش LINKED (نسخة حقيقية على الجهازين) إلا لو المستخدم قرر بنفسه.
    sync_mode       TEXT NOT NULL DEFAULT 'METADATA_ONLY'
                    CHECK (sync_mode IN ('METADATA_ONLY','LINKED','PHONE_ONLY','PC_ONLY')),

    -- تتبع التغييرات
    first_indexed_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_files_project ON files(project);
CREATE INDEX IF NOT EXISTS idx_files_parent ON files(parent_folder);
CREATE INDEX IF NOT EXISTS idx_files_sync_state ON files(sync_state);
CREATE INDEX IF NOT EXISTS idx_files_extension ON files(extension);

CREATE TABLE IF NOT EXISTS scan_manifests (
    target_path TEXT PRIMARY KEY,
    last_scan_at TEXT NOT NULL,
    dirty INTEGER NOT NULL DEFAULT 1,
    root_mtime REAL
);

-- ------------------------------------------------------------
-- Version History: نسخ احتياطية من الإصدارات القديمة.
-- بتتحفظ في حالتين: (1) تعارض حقيقي بين الكمبيوتر والتليفون،
-- (2) نقطة حفظ عادية قبل ابديت مهم (لو المستخدم فعّلها).
-- المستخدم بيتحكم في وقت مسحها من الإعدادات (بند: امتى يمسح النسخ).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS file_versions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path       TEXT NOT NULL,          -- بيشاور على files.path
    version_number  INTEGER NOT NULL,
    stored_copy_path TEXT NOT NULL,         -- مكان تخزين النسخة الفعلية على القرص
    hash            TEXT,
    size_bytes      INTEGER,
    source_device   TEXT NOT NULL CHECK (source_device IN ('pc','phone')),
    reason          TEXT NOT NULL CHECK (reason IN ('conflict_backup','manual_savepoint','pre_sync_backup')),
    created_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_versions_path ON file_versions(file_path);
CREATE INDEX IF NOT EXISTS idx_versions_created ON file_versions(created_at);

-- ------------------------------------------------------------
-- Conflicts: تعارض حقيقي بين نسخة الكمبيوتر ونسخة التليفون.
-- الملف يفضل في حالة CONFLICT وممنوع أي استبدال تلقائي
-- لحد ما المستخدم يحل التعارض من التطبيق (بند: يوقف ويطلب تأكيد).
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conflicts (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path           TEXT NOT NULL,
    pc_hash             TEXT,
    pc_modified_at      TEXT,
    pc_backup_version_id INTEGER,           -- يشاور على file_versions.id
    phone_hash          TEXT,
    phone_modified_at   TEXT,
    phone_backup_version_id INTEGER,        -- يشاور على file_versions.id
    detected_at         TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending','resolved_keep_pc','resolved_keep_phone','resolved_manual')),
    resolved_at         TEXT,
    FOREIGN KEY (pc_backup_version_id) REFERENCES file_versions(id),
    FOREIGN KEY (phone_backup_version_id) REFERENCES file_versions(id)
);

CREATE INDEX IF NOT EXISTS idx_conflicts_path ON conflicts(file_path);
CREATE INDEX IF NOT EXISTS idx_conflicts_status ON conflicts(status);

-- إعدادات مسح النسخ القديمة - المستخدم بيتحكم فيها من التطبيق
CREATE TABLE IF NOT EXISTS version_retention_settings (
    id                  INTEGER PRIMARY KEY CHECK (id = 1),  -- صف واحد بس
    max_versions_per_file TEXT NOT NULL DEFAULT '5',
    auto_delete_after_days INTEGER,          -- NULL = بلا حذف تلقائي بالوقت
    cleanup_mode        TEXT NOT NULL DEFAULT 'manual'
                        CHECK (cleanup_mode IN ('manual','by_count','by_age','both'))
);

INSERT OR IGNORE INTO version_retention_settings (id, max_versions_per_file, cleanup_mode)
VALUES (1, '5', 'manual');

-- سجل نشاط التليفون اللحظي (بند: "التليفون داخل على مجلد إيه دلوقتي")
-- منفصل عن change_log لأنه بيسجل "تصفح" مش "تعديل فعلي على الملفات"
CREATE TABLE IF NOT EXISTS activity_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_token    TEXT,                    -- مين عمل النشاط
      activity_type   TEXT NOT NULL,
    detail          TEXT,                    -- مثلاً: اسم الفولدر أو الملف
    created_at      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_activity_log_time ON activity_log(created_at);

-- ------------------------------------------------------------
-- طلبات تحتاج موافقة (بند: العمليات الحساسة زي الحذف والتسمية)
-- التليفون بيطلب، الكمبيوتر بيوافق أو يرفض من الواجهة الرسومية.
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS pending_approvals (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_token    TEXT NOT NULL,
    action_type     TEXT NOT NULL CHECK (action_type IN (
                        'delete_file', 'rename_file', 'create_file',
                        'make_linked', 'overwrite_file', 'command_draft',
                        'command_execute'
                    )),
    file_id         INTEGER,                 -- NULL لو ملف جديد لسه مش موجود
    file_path       TEXT,
    new_value       TEXT,                    -- الاسم الجديد لو rename، أو مسار مؤقت لو create
    status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
    requested_at    TEXT NOT NULL,
    resolved_at     TEXT
);

CREATE INDEX IF NOT EXISTS idx_pending_approvals_status ON pending_approvals(status);

-- ------------------------------------------------------------
-- طابور الأوامر المؤجلة (offline deferred command queue)
-- بند: التليفون بيسجل أوامر (حذف/تسمية/تحميل/رفع) وهو أوفلاين، وبيبعتها
-- كلها كدفعة واحدة لحظة ما يرجع يتصل. مختلف تمامًا عن pending_approvals:
-- هنا الموافقة بتحصل على التليفون نفسه (المستخدم وافق عليها هناك قبل ما
-- يبعتها)، مش على واجهة الكمبيوتر - فمفيش داعي تظهر في شاشة الموافقات
-- العادية. الكمبيوتر دوره هنا تنفيذ فقط، بالترتيب، مع إيقاف فوري عند
-- أول تعارض.
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS offline_command_batches (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_token    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'processing'
                    CHECK (status IN ('processing', 'completed', 'stopped_on_conflict', 'cancelled')),
    created_at      TEXT NOT NULL,
    completed_at    TEXT
);

CREATE TABLE IF NOT EXISTS offline_commands (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id        INTEGER NOT NULL REFERENCES offline_command_batches(id),
    sequence_index  INTEGER NOT NULL,       -- ترتيب التنفيذ - نفس ترتيب الإنشاء وقت الأوفلاين بالظبط
    command_type    TEXT NOT NULL CHECK (command_type IN (
                        'delete', 'rename', 'download', 'upload'
                    )),
    file_path       TEXT NOT NULL,          -- المسار وقت إنشاء الأمر (على الكمبيوتر)
    new_value       TEXT,                   -- الاسم الجديد لو rename، أو target_folder لو upload
    status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'executed', 'conflict', 'skipped', 'cancelled')),
    conflict_reason TEXT,                   -- سبب التعارض لو status='conflict' (يُعرض للمستخدم حرفيًا)
    executed_at     TEXT
);

CREATE INDEX IF NOT EXISTS idx_offline_commands_batch ON offline_commands(batch_id, sequence_index);


-- ------------------------------------------------------------
-- ربط جهاز بمستخدم واحد (Single-user binding)
-- المستخدم بيحدد اسمه مرة واحدة، وبعدها التطبيق مربوط بيه.
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS bound_user (
    id              INTEGER PRIMARY KEY CHECK (id = 1),  -- صف واحد بس - مستخدم واحد
    user_name       TEXT NOT NULL,
    bound_at        TEXT NOT NULL
);

-- ------------------------------------------------------------
-- كود مؤقت لأول اتصال، وبعدين token دائم للجهاز المعتمد.
-- ------------------------------------------------------------

-- الأكواد المؤقتة اللي بتظهر على شاشة الكمبيوتر لحظة الـ pairing
CREATE TABLE IF NOT EXISTS pairing_codes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    code            TEXT NOT NULL UNIQUE,     -- HMAC-SHA256 للكود؛ لا تخزن الأرقام نفسها
    created_at      TEXT NOT NULL,
    expires_at      TEXT NOT NULL,            -- صلاحية قصيرة (دقيقتين مثلاً)
    used            INTEGER NOT NULL DEFAULT 0  -- 0/1، الكود يُستهلك مرة واحدة بس
);

CREATE INDEX IF NOT EXISTS idx_pairing_codes_code ON pairing_codes(code);

-- الأجهزة المعتمدة بعد نجاح الـ pairing
CREATE TABLE IF NOT EXISTS trusted_devices (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    device_token    TEXT NOT NULL UNIQUE,     -- توكن دائم، بيتبعت في كل طلب بعد كده
    device_name     TEXT,                     -- اسم يظهر للمستخدم، زي "Ahmed's Phone"
    paired_at       TEXT NOT NULL,
    last_seen_at    TEXT,
    revoked         INTEGER NOT NULL DEFAULT 0,  -- المستخدم يقدر يلغي ثقة جهاز
    trust_level     TEXT NOT NULL DEFAULT 'standard', -- standard | trusted | full
    session_expires_at TEXT,
    -- الثقة العمياء مفعّلة تلقائيًا لأي جهاز اتعمله Pairing ناجح - التوكن
    -- نفسه هو دليل الثقة الدائمة. العمود ده بس بيسجل هل الجهاز اتصل تلقائيًا
    -- (auto-reconnect بالتوكن المحفوظ) قبل كده من غير ما يطلب كود جديد،
    -- عشان نعرضها للمستخدم في الواجهة كتأكيد إن السلوك شغال فعليًا.
    auto_reconnect_count INTEGER NOT NULL DEFAULT 0,
    last_auto_reconnect_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_trusted_devices_token ON trusted_devices(device_token);

-- سجل كل الأجهزة اللي وصلت (audit trail - مين اتصل وإمتى)
CREATE TABLE IF NOT EXISTS change_log (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path       TEXT NOT NULL,
    event_type      TEXT NOT NULL CHECK (event_type IN ('created','modified','deleted','moved','renamed')),
    old_path        TEXT,                   -- في حالة move/rename
    old_hash        TEXT,
    new_hash        TEXT,
    detected_by     TEXT NOT NULL CHECK (detected_by IN ('scanner','watcher','approval')),
    detected_at     TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_changelog_path ON change_log(file_path);
CREATE INDEX IF NOT EXISTS idx_changelog_time ON change_log(detected_at);

-- ------------------------------------------------------------
-- نقل الملفات الكبيرة على أجزاء (resumable/chunked transfer)
-- بند: ملف كبير (≥1GB) بيتقسم أجزاء، لو الاتصال اتقطع الملف الناقص
-- بيفضل في مكانه الحقيقي على القرص (مش أجزاء منفصلة)، ولما الاتصال
-- يرجع بنكمل من نفس النقطة بدل ما نبدأ من الصفر.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS resumable_transfers (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path           TEXT NOT NULL UNIQUE,   -- المسار الحقيقي والنهائي للملف على القرص
    direction           TEXT NOT NULL CHECK (direction IN ('upload', 'download')),
                        -- upload = جاي من التليفون للكمبيوتر
                        -- download = رايح من الكمبيوتر للتليفون (بيتسجل هنا
                        -- بس لأغراض العرض/التحذير، التنفيذ الفعلي على التليفون)
    total_size_bytes    INTEGER NOT NULL,        -- الحجم الكامل المتوقع للملف الأصلي
    bytes_received      INTEGER NOT NULL DEFAULT 0,  -- آخر حجم معروف فعليًا على القرص
    source_fingerprint  TEXT NOT NULL,           -- بصمة الملف الأصلي (راجع transfer/resumable.py)
    device_token        TEXT NOT NULL,           -- الجهاز المصدر/الوجهة
    status              TEXT NOT NULL DEFAULT 'in_progress'
                        CHECK (status IN ('in_progress', 'stalled', 'completed', 'abandoned')),
    created_at          TEXT NOT NULL,
    last_progress_at    TEXT NOT NULL,           -- آخر مرة اتكتب فيها جزء جديد فعليًا
    retention_hours     INTEGER NOT NULL DEFAULT 24  -- من إعدادات المستخدم (ساعة - أسبوع)
);

CREATE INDEX IF NOT EXISTS idx_resumable_status ON resumable_transfers(status);
CREATE INDEX IF NOT EXISTS idx_resumable_path ON resumable_transfers(file_path);

-- معلومات كل عملية مسح (لمعرفة آخر scan وحالته)
CREATE TABLE IF NOT EXISTS scan_runs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    target_path     TEXT NOT NULL,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    files_found     INTEGER DEFAULT 0,
    files_new       INTEGER DEFAULT 0,
    files_modified  INTEGER DEFAULT 0,
    files_deleted   INTEGER DEFAULT 0,
    status          TEXT DEFAULT 'running' CHECK (status IN ('running','completed','failed'))
);
