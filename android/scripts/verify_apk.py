"""Validate the delivered APK's real manifest/dex/assets before naming or publishing it."""
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
android = root / 'android'
apk = android / 'app/build/outputs/apk/debug/app-debug.apk'
sdk = Path(os.environ['ANDROID_HOME'])
aapt = sdk / 'build-tools/36.0.0/aapt2'
gradle = (android / 'app/build.gradle.kts').read_text()
version = re.search(r'versionName = "([0-9.]+)"', gradle)[1]
code = re.search(r'versionCode = (\d+)', gradle)[1]

def dump(*args):
    return subprocess.check_output([str(aapt), 'dump', *args], text=True)

badging = dump('badging', str(apk))
assert "name='io.github.bieshuowola.fuelmanager'" in badging
assert f"versionName='{version}'" in badging and f"versionCode='{code}'" in badging
manifest = dump('xmltree', str(apk), '--file', 'AndroidManifest.xml')
for name in ['.RideTileService', '.RideStartActivity', '.LocationTrackingService',
             'android.permission.BIND_QUICK_SETTINGS_TILE', 'android.service.quicksettings.action.QS_TILE',
             'android.service.quicksettings.action.QS_TILE_PREFERENCES']:
    assert name in manifest, f'Missing packaged Android component: {name}'
resources = dump('resources', str(apk))
label = resources.split('string/ride_tile_label', 1)[1].split('resource', 1)[0]
assert '"骑行"' in label
with zipfile.ZipFile(apk) as archive:
    dex = b''.join(archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n))
    for cls in ['RideTileService', 'RideStartActivity', 'RideControl', 'LocationTrackingService']:
        assert f'Lio/github/bieshuowola/fuelmanager/{cls};'.encode() in dex, f'Missing real code: {cls}'
    assert archive.read('assets/index.html') == (android / 'app/build/generated/webAssets/index.html').read_bytes()
    for name in ['icon.svg', 'manifest.webmanifest']:
        assert archive.read('assets/' + name) == (root / name).read_bytes()

subprocess.run([str(sdk / 'build-tools/36.0.0/apksigner'), 'verify', str(apk)], check=True)
subprocess.run([str(sdk / 'build-tools/36.0.0/zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
# ASCII name, exactly the verified version, not a copy of an older build.
filename = f'fuel-manager-{version}-debug.apk'
output = apk.with_name(filename)
shutil.copyfile(apk, output)
sha = hashlib.sha256(output.read_bytes()).hexdigest()
output.with_suffix('.apk.sha256').write_text(f'{sha}  {filename}\n')
print(f'Verified APK {filename}; versionCode={code}; native ride tile and current assets present; SHA256={sha}')
if os.environ.get('GITHUB_OUTPUT'):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'version={version}\nfilename={filename}\nsha256={sha}\n')
