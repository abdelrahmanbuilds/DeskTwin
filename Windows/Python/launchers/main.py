"""
DeskTwin Core Runner
---------------------
ده اللي بيمثل "قلب" الـ Windows Service بعدين.
التسلسل المنطقي:
    1. Scanner يشتغل مرة عند البدء -> يلحق أي تغيير حصل والجهاز كان مقفول.
    2. Watcher يشتغل بعد كده باستمرار -> يمسك أي تغيير لحظي من هنا وبعدين.

الفكرة: من غير الخطوة (1)، لو قفلت الجهاز يوم كامل وشغلته تاني،
الـ DB هيفضل يفتكر آخر حالة قبل الإغلاق وكأنها لسه صح - وده غلط.
"""

import os
import sys
import time
import signal
import threading

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, PROJECT_ROOT)
sys.path.insert(0, os.path.join(PROJECT_ROOT, "Python", "service"))

from config.settings import DeskTwinConfig
from database.db import DeskTwinDB
from scanner.scanner import scan_all
from watcher.watcher import start_watching
from discovery.discovery import DeskTwinAdvertiser

API_PORT = 8420


def run(config_path: str = None, db_path: str = None, verbose: bool = True):
    cfg = DeskTwinConfig.load(config_path) if config_path else DeskTwinConfig.load()

    if not cfg.targets:
        print("مفيش targets متحددة. لازم تضيف مسار واحد على الأقل في الكونفج.")
        sys.exit(1)

    db = DeskTwinDB(db_path=db_path) if db_path else DeskTwinDB()

    print("=" * 50)
    print("DeskTwin Core - بدء التشغيل")
    print("=" * 50)

    print("\n[1/3] تشغيل الـ Scanner (لحاق أي تغيير حصل والسيرفس كان مقفول)...")
    scan_all(cfg, db, verbose=verbose)

    print("\n[2/3] تشغيل الـ Watcher (مراقبة لحظية مستمرة)...")
    observer = start_watching(cfg, db, verbose=verbose)

    print("\n[3/3] تشغيل الـ Discovery (الإعلان عن الجهاز على الشبكة المحلية)...")
    advertiser = DeskTwinAdvertiser(port=API_PORT)
    advertiser.start()

    print("\nDeskTwin Online ● — بيراقب دلوقتي بشكل مستمر، وظاهر للموبايل على الشبكة.")
    print("ملاحظة: الـ Local API (uvicorn) بيتشغل منفصل عبر api/server.py")
    print("اضغط Ctrl+C للإيقاف.\n")

    def handle_shutdown(signum, frame):
        print("\nإيقاف DeskTwin...")
        observer.stop()
        observer.join()
        advertiser.stop()
        print("DeskTwin Offline.")
        sys.exit(0)

    signal.signal(signal.SIGINT, handle_shutdown)
    signal.signal(signal.SIGTERM, handle_shutdown)

    while True:
        time.sleep(1)


if __name__ == "__main__":
    run()
