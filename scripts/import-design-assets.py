"""Import pinned, licensed Tabler vectors and Barlow Condensed font resources.

Vector conversion is mechanical; all icon paths are unchanged upstream artwork.
"""
from pathlib import Path
import concurrent.futures
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
ICONS = 'arrow-left arrow-right chevron-down chevron-right refresh search cloud-sun notebook bookmark fish fish-hook map-pin tent alert-triangle bluetooth current-location compass plus minus layers navigation map ruler download upload microphone player-pause player-stop check circle-check cloud cloud-download cloud-upload settings-adjust user world sun gauge shield-check help bell lock mail camera eye eye-off logout edit trash calendar clock route wind droplet moon temperature waves battery menu dots filter share list-check volume sun-rise sun-set snowflake cloud-rain cloud-storm cloud-fog'.split()

def download(url):
    return urllib.request.urlopen(url, timeout=30).read()

def icon(name):
    target = RES / 'drawable' / ('ic_' + name.replace('-', '_') + '.xml')
    if target.exists():
        return
    upstream = {'cloud-sun': 'sun', 'layers': 'stack-2', 'settings-adjust': 'adjustments', 'sun-rise': 'sunrise', 'sun-set': 'sunset', 'cloud-fog': 'mist', 'waves': 'ripple'}.get(name, name)
    try:
        data = download(f'https://raw.githubusercontent.com/tabler/tabler-icons/v3.46.0/icons/outline/{upstream}.svg')
    except Exception as error:
        raise RuntimeError(f'Icon {name} ({upstream}): {error}') from error
    source = ET.fromstring(data)
    paths = []
    for node in source:
        if node.tag.split('}')[-1] != 'path':
            raise ValueError(f'Unsupported element in {name}: {node.tag}')
        if node.attrib.get('stroke') == 'none':
            continue
        paths.append(node.attrib['d'])
    vector = ['<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">']
    vector += [f'    <path android:pathData="{p}" android:fillColor="@android:color/transparent" android:strokeColor="#FFE8DFDA" android:strokeWidth="1.5" android:strokeLineCap="round" android:strokeLineJoin="round" />' for p in paths]
    vector.append('</vector>')
    target.write_text('\n'.join(vector) + '\n', encoding='utf-8')

if __name__ == '__main__':
    (RES / 'font').mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(icon, ICONS))
    for weight in ['Regular', 'Medium', 'SemiBold']:
        (RES / 'font' / f'barlow_condensed_{weight.lower()}.ttf').write_bytes(download(f'https://raw.githubusercontent.com/google/fonts/main/ofl/barlowcondensed/BarlowCondensed-{weight}.ttf'))
    notices = ROOT / 'third-party'
    notices.mkdir(exist_ok=True)
    (notices / 'Tabler-LICENSE.txt').write_bytes(download('https://raw.githubusercontent.com/tabler/tabler-icons/v3.46.0/LICENSE'))
    (notices / 'Barlow-OFL.txt').write_bytes(download('https://raw.githubusercontent.com/google/fonts/main/ofl/barlowcondensed/OFL.txt'))
    print(f'Imported {len(ICONS)} icons and 3 font weights.')
