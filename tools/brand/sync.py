#!/usr/bin/env python3
"""Sync published brand assets, or check local copies and latest published hashes.

APK builds use the committed copies offline. CI runs `check`; `check --offline` verifies only the
committed inputs and their deterministic Android/web derivatives. `sync` refreshes every input.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('tools/brand/manifest.json')
NS = 'http://www.w3.org/2000/svg'
BASE_URL = 'https://panel-assistant.io/brand/'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url):
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Panel-Assistant-Brand-Sync/1.0', 'Cache-Control': 'no-cache',
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def name_svg(wordmark):
    root = ET.fromstring(wordmark)
    paths = root.findall(f'{{{NS}}}path')
    if len(paths) != 1:
        raise ValueError('canonical wordmark must contain one outlined name')
    path = paths[0]
    transform = re.fullmatch(r'translate\(([\d.]+) 0\)', path.attrib['transform'])
    if transform is None:
        raise ValueError('unsupported canonical name placement')
    width = float(root.attrib['viewBox'].split()[2]) - float(transform[1])
    return (f'<svg xmlns="{NS}" width="{width:.2f}" height="30" '
            f'viewBox="0 -30 {width:.2f} 30">\n'
            f'<path fill="currentColor" d={quoteattr(path.attrib["d"])}/>\n'
            '</svg>\n').encode()


def android_vector(icon, size, crop):
    root = ET.fromstring(icon)
    x, y, width, height = map(float, root.attrib['viewBox'].split())
    def node(element, indent):
        tag = element.tag.removeprefix(f'{{{NS}}}')
        if tag == 'path':
            attrs = {'fillColor': element.attrib.get('fill', '#000000'),
                     'pathData': element.attrib['d']}
            for source, target in [('stroke', 'strokeColor'), ('stroke-width', 'strokeWidth')]:
                if source in element.attrib:
                    attrs[target] = element.attrib[source]
            supported = {'d', 'fill', 'stroke', 'stroke-width'}
            if set(element.attrib) - supported:
                raise ValueError('unsupported canonical path attributes')
            return indent + '<path ' + ' '.join(f'android:{k}={quoteattr(v)}' for k, v in attrs.items()) + ' />\n'
        if tag == 'g':
            match = re.fullmatch(r'translate\(([\d.]+),([\d.]+)\) scale\(([\d.]+)\)', element.attrib.get('transform', ''))
            if match is None or set(element.attrib) != {'transform'}:
                raise ValueError('unsupported canonical group transform')
            attrs = dict(zip(['translateX', 'translateY', 'scaleX', 'scaleY'], [match[1], match[2], match[3], match[3]]))
            return (indent + '<group ' + ' '.join(f'android:{k}={quoteattr(v)}' for k, v in attrs.items()) + '>\n'
                    + ''.join(node(child, indent + '    ') for child in element) + indent + '</group>\n')
        raise ValueError(f'unsupported canonical SVG element: {tag}')
    body = ''.join(node(child, '        ' if crop else '    ') for child in root)
    if crop:
        # A tight mark centred in the existing 60-unit native square.
        body = f'    <group android:translateX="{-x:g}" android:translateY="{(width-height)/2-y:g}">\n' + body + '    </group>\n'
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp" android:height="{size}dp"\n'
            f'    android:viewportWidth="{size}" android:viewportHeight="{size}">\n'
            '    <!-- Generated from the published icon by tools/brand/sync.py; do not edit. -->\n'
            + body + '</vector>\n').encode()


def generated(inputs):
    return {
        'app/src/main/assets/name.svg': name_svg(inputs['tools/brand/wordmark-on-light.svg']),
        'app/src/main/res/drawable/ic_launcher_foreground.xml': android_vector(inputs['app/src/main/assets/icon.svg'], 108, False),
        'app/src/main/res/drawable/ic_pa_mark.xml': android_vector(inputs['app/src/main/assets/icon.svg'], 60, True),
    }


def run(command, root=ROOT, offline=False, source_dir=None):
    manifest = json.loads((root / MANIFEST).read_text())
    inputs = {}
    problems = []
    for item in manifest['assets']:
        path = item['path']
        filename = item['url'].removeprefix(BASE_URL)
        if not item['url'].startswith(BASE_URL) or not re.fullmatch(r'[a-z0-9-]+\.(svg|png)', filename):
            raise ValueError(f'noncanonical brand URL: {item["url"]}')
        if Path(path).is_absolute() or '..' in Path(path).parts:
            raise ValueError(f'unsafe local path: {path}')
        if command == 'sync':
            data = (source_dir / item['url'].rsplit('/', 1)[1]).read_bytes() if source_dir else fetch(item['url'])
            item['sha256'] = digest(data)
        else:
            data = (root / path).read_bytes()
            if digest(data) != item['sha256']:
                problems.append(f'local copy differs: {path}')
            if not offline and digest(fetch(item['url'])) != item['sha256']:
                problems.append(f'manifest lags published asset: {item["url"]}')
        inputs[path] = data
    outputs = generated(inputs)
    declarations = manifest['derivatives']
    if len(declarations) != len(outputs) or {item['path'] for item in declarations} != set(outputs):
        raise ValueError('manifest must declare every generated derivative exactly once')
    if any(item['source'] not in inputs for item in declarations):
        raise ValueError('derivative source must be a canonical manifest input')
    if command == 'sync':
        # Fetch and validate all inputs before changing any committed copy.
        for path, data in {**inputs, **outputs}.items():
            destination = root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        (root / MANIFEST).write_text(json.dumps(manifest, indent=2) + '\n')
    else:
        for path, expected in outputs.items():
            if (root / path).read_bytes() != expected:
                problems.append(f'generated copy differs: {path}')
    for problem in problems:
        print('FAIL ' + problem, file=sys.stderr)
    if problems:
        return 1
    print(f'brand: {command} passed ({len(inputs)} canonical assets, {len(outputs)} derivatives)')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['sync', 'check'])
    parser.add_argument('--offline', action='store_true', help='check committed copies without the publication freshness check')
    parser.add_argument('--source-dir', type=Path, help='sync from a generated brand directory instead of the published service')
    args = parser.parse_args()
    if args.offline and args.command != 'check' or args.source_dir and args.command != 'sync':
        parser.error('--offline is for check; --source-dir is for sync')
    try:
        return run(args.command, offline=args.offline, source_dir=args.source_dir)
    except (OSError, ValueError, KeyError, ET.ParseError) as error:
        print(f'FAIL brand: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
