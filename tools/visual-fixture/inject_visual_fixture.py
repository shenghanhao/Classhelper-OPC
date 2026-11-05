#!/usr/bin/env python3
"""Inject synthetic rows into a LOCAL COPY of an initialized Room v6 database.

Never invokes adb, changes a package, copies credentials, or touches real classes.
The caller copies fixture/files separately to an isolated test application's files/.
"""
import argparse
import json
from pathlib import Path
import sqlite3

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--fixture', required=True, type=Path)
parser.add_argument('--database', required=True, type=Path, help='Local initialized test database, not a phone path')
args = parser.parse_args()
seed = json.loads((args.fixture / 'room-seed.json').read_text())
assert seed['sessionId'].startswith('visual-acceptance-')
assert args.database.is_file(), 'Initialize the test app database first; this script does not create its schema.'
allowed = ('sessions', 'transcript_segments', 'board_photos', 'classroom_notes', 'course_types')
assert set(seed['tables']) == set(allowed)
with sqlite3.connect(args.database) as db:
    assert db.execute('PRAGMA user_version').fetchone()[0] == seed['roomVersion'] == 6
    for table in allowed:
        columns = {row[1] for row in db.execute(f'PRAGMA table_info({table})')}
        for row in seed['tables'][table]:
            assert set(row) <= columns, (table, set(row) - columns)
            if table != 'course_types':
                assert row.get('sessionId', row.get('id')) == seed['sessionId']
                assert row['id'].startswith('visual-')
            else:
                assert '视觉验收样例' in row['name']
            keys = list(row)
            sql = f'INSERT OR REPLACE INTO {table} ({",".join(keys)}) VALUES ({",".join("?" for _ in keys)})'
            db.execute(sql, [row[key] for key in keys])
    assert db.execute('SELECT COUNT(*) FROM transcript_segments WHERE sessionId = ?', (seed['sessionId'],)).fetchone()[0] == 12
print('Injected only synthetic visual acceptance rows into local Room v6 copy.')
print('Session:', seed['sessionId'])
print('Expected sourceHash:', seed['sourceHash'])
