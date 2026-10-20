"""Generate Android-only assets. Never write to the website sources."""
from pathlib import Path
import shutil
root = Path(__file__).resolve().parents[2]
out = root / 'android/app/build/generated/webAssets'
out.mkdir(parents=True, exist_ok=True)
html = (root / 'index.html').read_text()
# Fail if upstream moves the audited integration points rather than silently patching wrongly.
def replace_once(old, new):
    global html
    assert html.count(old) == 1, f'upstream integration changed: {old[:80]}'
    html = html.replace(old, new)
replace_once('function render(){\n const c=replay();', 'function render(){\n const c=androidProjection(replay());')
replace_once('localStorage.setItem(KEY,JSON.stringify(data));return true', 'localStorage.setItem(KEY,JSON.stringify(data));androidSaved();return true')
# Native Storage Access Framework handles exports; original backup schema is retained.
a = html.index('function exportBackup(){')
b = html.index("$('fileInput').addEventListener", a)
html = html[:a] + "function exportBackup(){androidSend({type:'export',fuel:{...data,exportedAt:new Date().toISOString()}})}\n" + html[b:]
replace_once("data=normalize(obj);save();render();close();toast('备份已恢复", "androidSend({type:'beforeImport'});data=normalize(obj);save();render();close();toast('备份已恢复")
replace_once("Math.round(c.remaining/s.capacity*100)", "Number.isFinite(c.remaining)?Math.round(c.remaining/s.capacity*100):'--'")
replace_once("render();\nupdatePrice();", (root/'android/bridge.js').read_text() + '\nrender();\nandroidSaved();\nupdatePrice();')
replace_once("if('serviceWorker' in navigator&&location.protocol==='https:')navigator.serviceWorker.register('./sw.js').catch(()=>{});", '// APK assets provide offline availability; do not cache stale adapters with a service worker.')
(out/'index.html').write_text(html)
for name in ['manifest.webmanifest','icon.svg']:
    shutil.copyfile(root/name, out/name)
