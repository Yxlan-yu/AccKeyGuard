import os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))
bad = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    if '.git' in dirpath:
        continue
    for fn in filenames:
        if not fn.endswith(('.java', '.xml', '.gradle', '.properties', '.txt', '.md', '.yml', '.py')):
            continue
        p = os.path.join(dirpath, fn)
        raw = open(p, 'rb').read()
        rel = os.path.relpath(p, ROOT)
        if raw.startswith(b'\xef\xbb\xbf'):
            bad.append((rel, 'BOM'))
        try:
            t = raw.decode('utf-8')
        except UnicodeDecodeError as e:
            bad.append((rel, 'NOT-UTF8 %s' % e))
            continue
        for i, line in enumerate(t.splitlines(), 1):
            if '\ufffd' in line:
                bad.append((rel, 'U+FFFD line %d' % i))
            if line.rstrip().endswith('\r'):
                bad.append((rel, 'CRLF line %d' % i))
        if '\r\n' in t:
            bad.append((rel, 'CRLF file'))
        open(p, 'wb').write(raw)

print('suspicious files:', len(bad))
for b in bad:
    print(' -', b)

print('--- Status.java literal lines ---')
p = os.path.join(ROOT, 'app/src/main/java/com/yxlanyu/acckeyguard/data/Status.java')
b = open(p, 'rb').read()
t = b.decode('utf-8')
lines = t.splitlines()
for i, line in enumerate(lines, 1):
    if 'error =' in line or '未响应' in line:
        print(i, ascii(line))
        print('   quotes:', line.count('"'))

print('--- strings.xml ---')
p = os.path.join(ROOT, 'app/src/main/res/values/strings.xml')
t = open(p, 'rb').read().decode('utf-8')
for i, line in enumerate(t.splitlines(), 1):
    print(i, ascii(line))

print('--- quote balance per java file ---')
query = os.path.join(ROOT, 'app/src/main/java')
for dirpath, dirnames, filenames in os.walk(query):
    for fn in sorted(filenames):
        if not fn.endswith('.java'):
            continue
        p = os.path.join(dirpath, fn)
        t = open(p, 'rb').read().decode('utf-8')
        n = 0
        stack = []
        i = 0
        ok = True
        while i < len(t):
            ch = t[i]
            if ch == '\\':
                i += 2
                continue
            if ch == '"':
                n += 1
            i += 1
        if n % 2:
            print('ODD QUOTES', os.path.relpath(p, ROOT), n)
print('done')
