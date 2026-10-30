#!/usr/bin/env python3
"""Check the buildless landing page and the release files it shares a deployment with."""
from html.parser import HTMLParser
from pathlib import Path
import hashlib
import json
import struct

root = Path(__file__).resolve().parents[1] / 'update-service/public'

class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.links = []
        self.downloads = 0
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            assert attrs['id'] not in self.ids, 'Duplicate ID'
            self.ids.add(attrs['id'])
        for key in ('href', 'src'):
            if key in attrs:
                self.links.append(attrs[key])
        if tag == 'img':
            assert 'alt' in attrs, 'Missing image alternative'
        if 'download' in attrs:
            assert attrs['href'] == '/crosspoint.apk'
            self.downloads += 1

page = Page()
page.feed((root / 'index.html').read_text())
assert page.downloads >= 2
for link in page.links:
    if link.startswith('#'):
        assert link[1:] in page.ids, f'Missing section: {link}'
    elif link.startswith('/') and link != '/':
        assert (root / link[1:]).is_file(), f'Missing asset: {link}'
for name in ('home.png', 'reader.png', 'settings.png'):
    data = (root / 'assets' / name).read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II', data[16:24]) == (480, 720), f'Unexpected screen size: {name}'
feed = json.loads((root / 'latest.json').read_text())
for name in ('crosspoint.apk', feed['apk']):
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == feed['sha256'], f'Release mismatch: {name}'
print('Landing links, image alternatives, download targets, and release hashes passed.')
