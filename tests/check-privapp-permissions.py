#!/usr/bin/env python3
"""Check requested privileged platform permissions and same-partition packaging."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

app = Path(__file__).resolve().parent.parent
framework = Path('/home/meinte/android/bengal/platform/upstream/lineage-24-src/frameworks/base/core/res/AndroidManifest.xml')
name = '{http://schemas.android.com/apk/res/android}name'
level = '{http://schemas.android.com/apk/res/android}protectionLevel'
requested = {p.get(name) for p in ET.parse(app / 'AndroidManifest.xml').getroot().findall('uses-permission')}
required = {p.get(name) for p in ET.parse(framework).getroot().findall('permission')
            if p.get(name) in requested and 'privileged' in p.get(level, '').split('|')}
xml = Path(sys.argv[1]) if len(sys.argv) > 1 else app / 'privapp-permissions-com.bengal.power.xml'
root = ET.parse(xml).getroot()
assert root.tag == 'permissions' and len(root) == 1
entry = root[0]
assert entry.tag == 'privapp-permissions' and entry.get('package') == 'com.bengal.power'
granted = {p.get('name') for p in entry.findall('permission')}
assert granted == required == {'android.permission.MOUNT_UNMOUNT_FILESYSTEMS',
                               'android.permission.BLUETOOTH_PRIVILEGED'}, (required, granted)
bp = (app / 'Android.bp').read_text()
assert 'required: ["privapp-permissions-com.bengal.power", "default-permissions-com.bengal.power"]' in bp
prebuilt = re.search(r'prebuilt_etc\s*\{([^}]+)\}', bp).group(1)
for token in ('name: "privapp-permissions-com.bengal.power"', 'sub_dir: "permissions"',
              'system_ext_specific: true', 'src: "privapp-permissions-com.bengal.power.xml"'):
    assert token in prebuilt, token
defaults = ET.parse(app / 'default-permissions-com.bengal.power.xml').getroot()
assert defaults.tag == 'exceptions' and len(defaults) == 1
assert defaults[0].tag == 'exception' and defaults[0].get('package') == 'com.bengal.power'
assert len(defaults[0]) == 1
assert defaults[0][0].attrib == {'name': 'android.permission.BLUETOOTH_CONNECT', 'fixed': 'true'}
assert 'android.permission.BLUETOOTH_CONNECT' in requested
default_prebuilt = re.findall(r'prebuilt_etc\s*\{([^}]+)\}', bp)[1]
for token in ('name: "default-permissions-com.bengal.power"', 'sub_dir: "default-permissions"',
              'system_ext_specific: true', 'src: "default-permissions-com.bengal.power.xml"'):
    assert token in default_prebuilt, token
print('PASS exact platform privileged-permission coverage and required system_ext packaging')
