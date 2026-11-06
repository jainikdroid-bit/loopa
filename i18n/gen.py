"""Собрать app/src/main/java/app/loopa/I18nTables.kt из i18n/strings.json и проверить переводы.

Проверки: у каждого языка столько же строк, сколько ключей; совпадают подстановки %s/%d; каждый tr("…") в коде есть в ключах.
Запуск: python i18n/gen.py
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'app', 'loopa')
data = json.load(open(os.path.join(ROOT, 'i18n', 'strings.json'), encoding='utf-8'))
keys = data['keys']
langs = {k: v for k, v in data.items() if not k.startswith('_') and k != 'keys'}
errors = []

if len(set(keys)) != len(keys):
    errors.append('повторяющиеся ключи')
for lang, vals in langs.items():
    if len(vals) != len(keys):
        errors.append(f'{lang}: {len(vals)} строк, а ключей {len(keys)}')
        continue
    for k, v in zip(keys, vals):
        if re.findall(r'%[sd]', k) != re.findall(r'%[sd]', v):
            errors.append(f'{lang}: подстановки не совпадают: {k!r} -> {v!r}')

# все строки из кода переведены
used = set()
for f in os.listdir(SRC):
    if f.endswith('.kt') and not f.startswith('I18n'):
        s = open(os.path.join(SRC, f), encoding='utf-8').read()
        used.update(m.group(1) for m in re.finditer(r'\btr\("((?:[^"\\]|\\.)*)"', s))
        for m in re.finditer(r'\bR?Sort\("[^"]*", "([^"]*)", "([^"]*)"', s):
            used.update(m.groups())
missing = sorted(u for u in used if u not in keys)
if missing:
    errors.append('нет в strings.json: ' + '; '.join(missing))

if errors:
    print('\n'.join(errors))
    sys.exit(1)


def q(s):
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$') + '"'


out = ['package app.loopa', '',
       '// Сгенерировано i18n/gen.py из i18n/strings.json — не править вручную.', '',
       'object I18nTables {']
for lang, vals in langs.items():
    out.append(f'    private val {lang.upper()} by lazy {{ mapOf(')
    out += [f'        {q(k)} to {q(v)},' for k, v in zip(keys, vals)]
    out.append('    ) }')
out.append('')
out.append('    fun of(code: String): Map<String, String> = when (code) {')
for lang in langs:
    out.append(f'        "{lang}" -> {lang.upper()}')
out.append('        else -> emptyMap()')
out.append('    }')
out.append('}')
open(os.path.join(SRC, 'I18nTables.kt'), 'w', encoding='utf-8', newline='\n').write('\n'.join(out) + '\n')
print(f'ok: {len(keys)} строк, языки: {", ".join(langs)}')
