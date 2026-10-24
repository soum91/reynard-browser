"""Add only the pinned, bundled companion to Gecko's existing built-in resource path."""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
source = root / 'addons/fetchdock'
patch_root = root / 'patches/mobile/ios/app/fetchdock'
patch_root.mkdir(parents=True, exist_ok=True)
for file in sorted(source.iterdir()):
    if not file.is_file() or file.name.endswith('.md'):
        continue
    content = file.read_text().splitlines()
    relative = 'mobile/ios/app/fetchdock/' + file.name
    patch = f'diff --git a/{relative} b/{relative}\nnew file mode 100644\n--- /dev/null\n+++ b/{relative}\n@@ -0,0 +1,{len(content)} @@\n'
    patch += ''.join('+' + line + '\n' for line in content)
    (patch_root / (file.name + '.patch')).write_text(patch)
moz_patch = root / 'patches/mobile/ios/app/moz.build.patch'
text = moz_patch.read_text()
if 'FINAL_TARGET_FILES.extensions.fetchdock' not in text:
    text += '+\n+FINAL_TARGET_FILES.extensions.fetchdock += [\n'
    text += ''.join('+    "fetchdock/' + p.name + '",\n' for p in sorted(source.iterdir()) if p.is_file() and not p.name.endswith('.md'))
    text += '+]\n'
    lines = text.splitlines()
    index = next(i for i, line in enumerate(lines) if line.startswith('@@'))
    old_count = sum(1 for line in lines[index+1:] if line.startswith((' ', '-')))
    new_count = sum(1 for line in lines[index+1:] if line.startswith((' ', '+')))
    lines[index] = re.sub(r'@@ -12,\d+ \+12,\d+ @@', f'@@ -12,{old_count} +12,{new_count} @@', lines[index])
    moz_patch.write_text('\n'.join(lines) + '\n')
print('Generated bundled companion patches')
