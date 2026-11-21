"""PyInstaller spec for the Python/CustomTkinter desktop client only."""
import os

from PyInstaller.utils.hooks import collect_all

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(SPECPATH))))
SERVICE_ROOT = os.path.join(PROJECT_ROOT, 'Python', 'service')
datas = [
    (os.path.join(PROJECT_ROOT, 'config'), 'config'),
    (os.path.join(PROJECT_ROOT, 'Python', 'ui', 'gui'), 'gui'),
    (os.path.join(SERVICE_ROOT, 'database', 'schema.sql'), 'Python/service/database'),
]
binaries = []
hiddenimports = ['api.server', 'auth.pairing', 'sync.ws_manager', 'storage.trash', 'Python.core.desktwin_service', 'Python.core.diagnostics']
tmp_ret = collect_all('customtkinter')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('uvicorn')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('zeroconf')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('cryptography')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('qrcode')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('PIL')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]
tmp_ret = collect_all('imageio_ffmpeg')
datas += tmp_ret[0]; binaries += tmp_ret[1]; hiddenimports += tmp_ret[2]


a = Analysis(
    [os.path.join(PROJECT_ROOT, 'Python/ui/gui.py')],
    pathex=[PROJECT_ROOT, SERVICE_ROOT],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    optimize=0,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='DeskTwin-PythonGUI',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='DeskTwin-PythonGUI',
)

