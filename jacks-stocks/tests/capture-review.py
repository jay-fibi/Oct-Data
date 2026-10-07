"""Capture actual Android screens from the explicitly seeded review emulator.

Requires the updated APK, androidTest APK, and opt-in ReviewSeedTest already run.
Never clears app data or loads invented market prices. Saves screenshots and UI XML.
"""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--out', required=True)
parser.add_argument('--offline-check', action='store_true', help='Temporarily disable review-emulator networking and verify cached restart/failed refresh')
args = parser.parse_args()
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=True)


def adb(*parts, binary=False, timeout=35):
    result = subprocess.run([args.adb, '-s', args.serial, *parts], capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def tree():
    adb('shell', 'uiautomator', 'dump', '/sdcard/jacks-review.xml')
    raw = adb('shell', 'cat', '/sdcard/jacks-review.xml')
    return ET.fromstring(raw), raw


def tap_text(text, exact=True):
    root, _ = tree()
    for node in root.iter('node'):
        value = node.get('text', '')
        if value == text if exact else text in value:
            coords = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
            if len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1]:
                adb('shell', 'input', 'tap', str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
                time.sleep(.8)
                return
    raise RuntimeError(f'Visible text not found: {text}')


def screenshot(name):
    time.sleep(1)
    root, raw = tree()
    blob = adb('exec-out', 'screencap', '-p', binary=True)
    if not blob.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('screencap did not return PNG')
    (out / (name + '.png')).write_bytes(blob)
    (out / (name + '.xml')).write_text(raw, encoding='utf-8')
    print('Captured', name, 'bytes', len(blob), flush=True)
    return root


def swipe_up():
    size = adb('shell', 'wm', 'size')
    w, h = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])
    adb('shell', 'input', 'swipe', str(w // 2), str(int(h * .75)), str(w // 2), str(int(h * .32)), '450')
    time.sleep(.8)


def align_caption(caption):
    """Bring a title to the top of the real scroll viewport, without cropping PNGs."""
    for _ in range(8):
        root, _ = tree()
        scroll = next(n for n in root.iter('node') if n.get('class') == 'android.widget.ScrollView')
        bounds = list(map(int, re.findall(r'\d+', scroll.get('bounds', ''))))
        node = next((n for n in root.iter('node') if n.get('text') == caption
                     or n.get('content-desc', '').startswith(caption + '.')), None)
        if node is None:
            swipe_up()
            continue
        coords = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        delta = coords[1] - (bounds[1] + 30)
        if abs(delta) < 45:
            return
        x = (bounds[0] + bounds[2]) // 2
        distance = min(abs(delta), (bounds[3] - bounds[1]) // 2)
        start = bounds[3] - 100 if delta > 0 else bounds[1] + 100
        end = start - distance if delta > 0 else start + distance
        adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '650')
        time.sleep(.8)
    raise RuntimeError('Could not align caption: ' + caption)


def select_theme(theme):
    tap_text('Settings')
    for _ in range(5):
        root, _ = tree()
        spinner = next((n for n in root.iter('node') if n.get('class') == 'android.widget.Spinner'), None)
        if spinner is not None:
            coords = list(map(int, re.findall(r'\d+', spinner.get('bounds', ''))))
            if coords[3] - coords[1] > 70:
                adb('shell', 'input', 'tap', str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
                time.sleep(.5)
                tap_text(theme)
                return
        swipe_up()
    raise RuntimeError('Theme spinner not found')


def wait_refresh():
    deadline = time.time() + 150
    while time.time() < deadline:
        time.sleep(2)
        root, _ = tree()
        refresh = next((n for n in root.iter('node') if n.get('text') == 'Refresh prices'), None)
        if refresh is not None and refresh.get('enabled') == 'true':
            return root
    raise RuntimeError('Manual refresh did not finish in time')


def value_and_stamp(root):
    texts = [n.get('text', '') for n in root.iter('node')]
    return texts[texts.index('PORTFOLIO VALUE') + 1], next(t for t in texts if t.startswith('Last refresh:'))


adb('shell', 'am', 'force-stop', 'com.jacks.stocks')
adb('shell', 'am', 'start', '-n', 'com.jacks.stocks/.MainActivity')
time.sleep(3)
root, _ = tree()
if not any('DEMO' in n.get('text', '') for n in root.iter('node')):
    raise RuntimeError('Refusing screenshots: fictional-portfolio demo label is absent')
select_theme('Dark')
tap_text('Overview')
tap_text('Settings')
tap_text('Download NSE stock list')
root = wait_refresh()
catalogue_status = next(n.get('text') for n in root.iter('node') if n.get('content-desc') == 'Refresh status. Tap for full details.')
print('Catalogue:', catalogue_status, flush=True)
tap_text('Overview')
tap_text('Refresh prices')
root = wait_refresh()
refresh_status = next(n.get('text') for n in root.iter('node') if n.get('content-desc') == 'Refresh status. Tap for full details.')
if not re.search(r'Yahoo Finance: [1-9]\d* quotes, [1-9]\d* closing prices saved', refresh_status):
    raise RuntimeError('Live refresh not confirmed: ' + refresh_status)
baseline = value_and_stamp(screenshot('01-overview-dark'))
align_caption('Your earnings')
screenshot('02-earnings-dark')
tap_text('Holdings')
screenshot('03-holdings-dark')
tap_text('Activity')
screenshot('04-transactions-dark')
tap_text('Charts')
time.sleep(3)
screenshot('05-charts-value-dark')
align_caption('Cumulative earnings / loss')
screenshot('06-charts-earnings-dark')
align_caption('Daily earnings / loss')
screenshot('07-charts-periods-dark')
align_caption('Allocation by current market value')
screenshot('08-allocation-dark')
tap_text('Settings')
screenshot('09-yahoo-settings-dark')
select_theme('Light')
tap_text('Overview')
screenshot('10-overview-light')
tap_text('Holdings')
tap_text('View RELIANCE')
screenshot('11-stock-details-light')
adb('shell', 'am', 'force-stop', 'com.jacks.stocks')
adb('shell', 'am', 'start', '-n', 'com.jacks.stocks/.MainActivity')
time.sleep(2)
final, raw = tree()
(out / 'final-ui.xml').write_text(raw, encoding='utf-8')
assert any('Jacks stocks' in n.get('text', '') for n in final.iter('node'))
assert value_and_stamp(final) == baseline, 'Cached value/refresh timestamp changed on restart'
if args.offline_check:
    # Guard above confirms the opt-in fictional ledger. Never target a personal device.
    if not args.serial.startswith('emulator-'):
        raise RuntimeError('Offline check is limited to the disposable emulator')
    wifi = adb('shell', 'settings', 'get', 'global', 'wifi_on')
    data = adb('shell', 'settings', 'get', 'global', 'mobile_data')
    try:
        adb('shell', 'svc', 'wifi', 'disable')
        adb('shell', 'svc', 'data', 'disable')
        time.sleep(3)
        adb('shell', 'am', 'force-stop', 'com.jacks.stocks')
        adb('shell', 'am', 'start', '-n', 'com.jacks.stocks/.MainActivity')
        time.sleep(3)
        root = screenshot('12-offline-restart-light')
        assert value_and_stamp(root) == baseline, 'Offline restart lost cached valuation'
        tap_text('Refresh prices')
        root = wait_refresh()
        assert any('0 quotes' in n.get('text', '') for n in root.iter('node')), 'Expected offline refresh failure'
        assert value_and_stamp(root) == baseline, 'Failed refresh replaced last good prices'
        screenshot('13-offline-refresh-light')
        print('Offline restart and failed-refresh cache preservation passed', flush=True)
    finally:
        adb('shell', 'svc', 'wifi', 'enable' if wifi in ('1', '2') else 'disable')
        adb('shell', 'svc', 'data', 'enable' if data == '1' else 'disable')
(out / 'capture-info.json').write_text(json.dumps({
    'serial': args.serial, 'screens': sorted(p.name for p in out.glob('*.png')),
    'device': adb('shell', 'getprop', 'ro.product.model'),
    'android': adb('shell', 'getprop', 'ro.build.version.release'),
    'refresh_status': refresh_status,
    'catalogue_status': catalogue_status,
    'offline_check': args.offline_check,
    'note': 'Actual emulator screenshots. Fictional transactions; prices requested by explicit Yahoo refresh. See refresh status in screenshots.'
}, indent=2), encoding='utf-8')
print('Capture complete', flush=True)