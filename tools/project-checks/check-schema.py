"""Host SQLite checks against the production DDL; not Android runtime or UI tests."""
from pathlib import Path
import re
import sqlite3
s=(Path(__file__).resolve().parents[2] / 'android/app/src/main/java/com/tapscene/data/ProjectStore.kt').read_text()
# The original schema is an explicit fixture; current v6 rebuild is checked separately.
legacy_states = (Path(__file__).with_name('legacy-states-v5.sql')).read_text().strip().rstrip(';')
s += '\ndb.execSQL(\"\"\"' + legacy_states + '\"\"\")'
# State indexes appear in create and migration; run the same definitions once.
s = s.replace('db.execSQL(\"CREATE INDEX states_order ON states(project_id,sort_order)\")', '', 1).replace('db.execSQL(\"CREATE INDEX states_source ON states(source_id)\")', '', 1)
c=sqlite3.connect(':memory:'); c.execute('PRAGMA foreign_keys=ON')
for sql in re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)',s,re.S): c.execute(sql)
for sql in re.findall(r'db.execSQL\("(CREATE (?:INDEX|TABLE).*?)"\)',s): c.execute(sql)
def project(p):
 c.execute('INSERT INTO projects VALUES(?,?,?,?,?,?,?)',(p,p,'goal',1,1,1,None))
 c.execute('INSERT INTO sources VALUES(?,?,?)',(p,'source','{}'))
def step(p,s):
 c.execute('INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)',(s,p,p+'/'+s+'.png','sha',4,1,1))
 c.execute('INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',(p,s,s,0,s,'desc',0,'source',s,123456,1000,'[]'))
def hotspot(p,h,frm,to=None,end=None):
 c.execute('INSERT INTO hotspots VALUES(?,?,?,?,?,?,?,?)',(p,h,frm,h,0,0,1,1))
 c.execute('INSERT INTO edges VALUES(?,?,?,?,?,?)',(p,h,h,frm,to,end))
project('p');step('p','a');step('p','b');project('other');step('other','c')
c.execute("UPDATE projects SET start_state_id='a' WHERE project_id='p'")
hotspot('p','a-to-b','a','b');hotspot('p','b-to-a','b','a');hotspot('p','a-self','a','a');hotspot('p','finish','b',end='done')
c.commit(); assert not list(c.execute('PRAGMA foreign_key_check'))
# The actual project cascade leaves other projects and the queued paths alone.
c.execute("INSERT INTO asset_cleanup SELECT project_id,relative_path FROM local_assets WHERE project_id='p'")
c.execute("INSERT INTO asset_imports VALUES('p','pending-import')")
c.execute("UPDATE projects SET start_state_id=NULL WHERE project_id='p'")
c.execute("DELETE FROM projects WHERE project_id='p'");c.commit()
assert c.execute('SELECT COUNT(*) FROM states').fetchone()[0]==1
assert c.execute('SELECT COUNT(*) FROM hotspots').fetchone()[0]==0
assert c.execute('SELECT COUNT(*) FROM edges').fetchone()[0]==0
assert c.execute('SELECT COUNT(*) FROM asset_cleanup').fetchone()[0]==2
assert list(c.execute('SELECT project_id,asset_id FROM asset_imports'))==[('p','pending-import')]
assert not list(c.execute('PRAGMA foreign_key_check'))
print('PASS project cascade: own graph/assets only; cleanup journal survives; other project intact')
project('p');step('p','a');step('p','b');c.commit()
# Cross-project target is rejected at commit; empty destinations rejected immediately.
try:
 hotspot('p','bad','a','c');c.commit();raise AssertionError('cross-project target accepted')
except sqlite3.IntegrityError:c.rollback()
try:
 hotspot('p','bad','a');raise AssertionError('missing destination accepted')
except sqlite3.IntegrityError:c.rollback()
try:
 hotspot('p','bad','a','b','end');raise AssertionError('dual destination accepted')
except sqlite3.IntegrityError:c.rollback()
print('PASS graph FK and XOR constraints')
# Transaction failure restores all old text/edges; duplicate capture is rejected.
hotspot('p','a-to-b','a','b');c.commit()
try:
 c.execute("UPDATE states SET title='new' WHERE project_id='p' AND state_id='a'")
 c.execute("DELETE FROM hotspots WHERE project_id='p' AND state_id='a'")
 hotspot('p','bad','a','c');c.commit();raise AssertionError('invalid form accepted')
except sqlite3.IntegrityError:c.rollback()
assert c.execute("SELECT title FROM states WHERE project_id='p' AND state_id='a'").fetchone()[0]=='a'
assert c.execute("SELECT COUNT(*) FROM edges WHERE project_id='p'").fetchone()[0]==1
try:
 c.execute("UPDATE states SET capture_id='a' WHERE project_id='p' AND state_id='b'");raise AssertionError('duplicate capture accepted')
except sqlite3.IntegrityError:c.rollback()
print('PASS atomic draft rollback and unique per-project capture')
# Delete an incoming link's hotspot as well as outgoing hotspots, then delete the step.
hotspot('p','b-to-a','b','a');hotspot('p','a-self','a','a');hotspot('p','finish','b',end='done');c.commit()
c.execute("UPDATE projects SET start_state_id='a' WHERE project_id='p'")
c.execute("DELETE FROM hotspots WHERE project_id='p' AND (state_id='a' OR hotspot_id IN (SELECT hotspot_id FROM edges WHERE project_id='p' AND to_state_id='a'))")
c.execute("UPDATE projects SET start_state_id='b' WHERE project_id='p'")
c.execute("DELETE FROM states WHERE project_id='p' AND state_id='a'")
c.execute("INSERT INTO asset_cleanup VALUES('p','p/a2.png')")
c.execute("DELETE FROM local_assets WHERE project_id='p' AND asset_id='a'");c.commit()
assert list(c.execute("SELECT hotspot_id FROM hotspots WHERE project_id='p'"))==[('finish',)]
assert not list(c.execute('PRAGMA foreign_key_check'))
print('PASS step deletion removes incoming/outgoing/self links, keeps independent end action')

# Next actions are independent authored buttons. Migration is additive and leaves every old
# table byte-for-byte equivalent at the row level; no hotspots or assets are synthesized.
tables = re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', s, re.S)
single_line = re.findall(r'db.execSQL\("(CREATE (?:INDEX|TABLE).*?)"\)', s)
next_sql = [sql for sql in tables + single_line if re.match(r'CREATE (?:TABLE|INDEX) next_actions', sql)]
transition_sql = [sql for sql in tables + single_line if re.match(r'CREATE (?:TABLE|INDEX) (?:edge_transitions|transition_imports)', sql)]
region_sql = [sql for sql in tables + single_line if re.match(r'CREATE (?:TABLE|INDEX) regions', sql)]
editor_sql = [sql for sql in tables + single_line if re.match(r'CREATE TABLE editor_draft(?:s|_sessions)', sql)]
assert len(editor_sql) == 2
assert len(region_sql) == 2
assert len(transition_sql) == 3
assert len(next_sql) == 2
legacy = sqlite3.connect(':memory:')
legacy.execute('PRAGMA foreign_keys=ON')
for sql in tables + single_line:
 if sql not in next_sql + transition_sql + region_sql + editor_sql: legacy.execute(sql)
legacy.execute('PRAGMA user_version=1')
legacy.execute("INSERT INTO projects VALUES('legacy','Title','Goal',11,12,9,'a')")
legacy.execute("INSERT INTO sources VALUES('legacy','source','{\"kept\":true}')")
for state in ('a', 'b'):
 legacy.execute('INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)',(state,'legacy','private/'+state+'.png','digest-'+state,42,3,7))
 legacy.execute('INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',('legacy',state,'capture-'+state,0,state,'legacy text',0,'source',state,123000,1000,'[]'))
legacy.execute("INSERT INTO hotspots VALUES('legacy','hotspot','a','Manual',0.1,0.2,0.6,0.8)")
legacy.execute("INSERT INTO edges VALUES('legacy','edge','hotspot','a','b',NULL)")
legacy.execute("INSERT INTO asset_cleanup VALUES('deleted','private/queued.png')")
legacy.execute("INSERT INTO asset_imports VALUES('legacy','interrupted')")
legacy.commit()
old_names = [row[0] for row in legacy.execute("SELECT name FROM sqlite_master WHERE type='table'")]
before = {name: list(legacy.execute('SELECT * FROM ' + name)) for name in old_names}
with legacy:
 for sql in next_sql: legacy.execute(sql)
 legacy.execute('PRAGMA user_version=2')
assert before == {name: list(legacy.execute('SELECT * FROM ' + name)) for name in old_names}
assert legacy.execute('SELECT COUNT(*) FROM next_actions').fetchone()[0] == 0
assert not list(legacy.execute('PRAGMA foreign_key_check'))
print('PASS v1-to-v2 additive DDL: old IDs, revision, hotspots, assets, sources and recovery journals unchanged')

# Use the shared schema above to exercise both composite foreign keys and per-source cardinality.
def next_action(p, action, frm, to, label='Next'):
 c.execute('INSERT INTO next_actions VALUES(?,?,?,?,?)', (p, action, frm, label, to))
step('p', 'new-a'); c.commit()
next_action('p', 'next-b', 'b', 'new-a'); c.commit()
for action, frm, to in [('bad-target', 'new-a', 'c'), ('bad-source', 'c', 'new-a'), ('duplicate-source', 'b', 'new-a')]:
 try:
  next_action('p', action, frm, to); c.commit(); raise AssertionError('invalid next action accepted')
 except sqlite3.IntegrityError: c.rollback()
try:
 next_action('p', 'blank-label', 'new-a', 'b', ' '); raise AssertionError('blank label accepted')
except sqlite3.IntegrityError: c.rollback()
print('PASS next action source/target same-project FKs, one action per source and nonblank label')

# Reordering does not alter any graph action. A rejected transaction restores both text and
# actions, even if it first wrote a valid authored target and then hit another bad target.
old_next = list(c.execute('SELECT * FROM next_actions ORDER BY action_id'))
c.execute("UPDATE states SET sort_order=7 WHERE project_id='p' AND state_id='b'"); c.commit()
assert old_next == list(c.execute('SELECT * FROM next_actions ORDER BY action_id'))
try:
 c.execute("UPDATE next_actions SET label='changed',to_state_id=NULL WHERE project_id='p' AND action_id='next-b'")
 c.execute("UPDATE states SET title='changed' WHERE project_id='p' AND state_id='b'")
 next_action('p', 'bad-end', 'new-a', 'c'); c.commit(); raise AssertionError('partial graph committed')
except sqlite3.IntegrityError: c.rollback()
assert old_next == list(c.execute('SELECT * FROM next_actions ORDER BY action_id'))
assert c.execute("SELECT title FROM states WHERE project_id='p' AND state_id='b'").fetchone()[0] == 'b'
print('PASS authored action reorder invariance and full transaction rollback')

# The store clears only target columns before deleting a destination. Broken actions remain
# countable and repairable. Deleting their source cascades the action, never another project.
c.execute("UPDATE next_actions SET to_state_id=NULL WHERE project_id='p' AND to_state_id='new-a'")
c.execute("DELETE FROM states WHERE project_id='p' AND state_id='new-a'"); c.commit()
assert list(c.execute('SELECT action_id,label,to_state_id FROM next_actions')) == [('next-b', 'Next', None)]
assert c.execute("SELECT COUNT(*) FROM next_actions WHERE project_id='p'").fetchone()[0] == 1
c.execute("UPDATE projects SET start_state_id=NULL WHERE project_id='p'")
c.execute("DELETE FROM states WHERE project_id='p' AND state_id='b'"); c.commit()
assert c.execute('SELECT COUNT(*) FROM next_actions').fetchone()[0] == 0
assert not list(c.execute('PRAGMA foreign_key_check'))
print('PASS deleted target preserves unresolved button; deleted source cascades its button')
print('NOTE capacity, explicit rebuild and revision policy are covered by AuthoredPathChecks on Android; host DDL is not runtime proof')
step('p', 'cascade-a'); step('p', 'cascade-b')
next_action('p', 'cascade-next', 'cascade-a', 'cascade-b'); c.commit()
c.execute("DELETE FROM projects WHERE project_id='p'"); c.commit()
assert c.execute('SELECT COUNT(*) FROM next_actions').fetchone()[0] == 0
assert c.execute("SELECT COUNT(*) FROM states WHERE project_id='other'").fetchone()[0] == 1
assert not list(c.execute('PRAGMA foreign_key_check'))
print('PASS project deletion cascades authored actions while another project remains intact')


# Production v3 is wholly additive from either installed version. In-progress deletion/import
# journals must survive alongside original IDs, revision, sources, images and authored edges.
for previous_version in (1, 2):
 migrated = sqlite3.connect(':memory:')
 migrated.execute('PRAGMA foreign_keys=ON')
 for sql in tables + single_line:
  if sql not in transition_sql + region_sql + editor_sql and (previous_version == 2 or sql not in next_sql): migrated.execute(sql)
 migrated.execute('PRAGMA user_version=' + str(previous_version))
 migrated.execute("INSERT INTO projects VALUES('migration','Kept','Goal',11,12,7,'a')")
 migrated.execute("INSERT INTO sources VALUES('migration','source','source-private-json')")
 for state in ('a', 'b'):
  migrated.execute('INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)',(state,'migration','private/'+state+'.png','hash',42,3,7))
  migrated.execute('INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',('migration',state,state,0,state,'saved',0,'source',state,1000,1000,'[]'))
 migrated.execute("INSERT INTO hotspots VALUES('migration','hotspot','a','Manual',0,0,1,1)")
 migrated.execute("INSERT INTO edges VALUES('migration','edge','hotspot','a','b',NULL)")
 if previous_version == 2: migrated.execute("INSERT INTO next_actions VALUES('migration','next','b','Next','a')")
 migrated.execute("INSERT INTO asset_cleanup VALUES('deleted','queued.png')")
 migrated.execute("INSERT INTO asset_imports VALUES('deleted','interrupted')")
 migrated.commit()
 original_tables = [row[0] for row in migrated.execute("SELECT name FROM sqlite_master WHERE type='table'")]
 original_rows = {name: list(migrated.execute('SELECT * FROM '+name)) for name in original_tables}
 with migrated:
  if previous_version < 2:
   for sql in next_sql: migrated.execute(sql)
  for sql in transition_sql: migrated.execute(sql)
  migrated.execute('PRAGMA user_version=3')
 assert original_rows == {name: list(migrated.execute('SELECT * FROM '+name)) for name in original_tables}
 assert migrated.execute('SELECT COUNT(*) FROM edge_transitions').fetchone()[0] == 0
 assert not list(migrated.execute('PRAGMA foreign_key_check'))
 # A transition-only source survives removal of unrelated states and cannot be deleted while
 # its video is referenced. Its private metadata is separate from the controlled asset row.
 migrated.execute("INSERT INTO sources VALUES('migration','video-source','private-trim-provenance')")
 migrated.execute("INSERT INTO local_assets VALUES('video','migration','project-assets/migration/video.mp4','video-hash',128,160,288)")
 migrated.execute("INSERT INTO edge_transitions VALUES('migration','edge','video','video-source',0,10000000,10000000,'[]','review')")
 migrated.commit()
 for invalid in (0, -1, 10000001):
  try:
   migrated.execute('UPDATE edge_transitions SET duration_us=?', (invalid,))
   migrated.commit(); raise AssertionError('invalid actual transition duration accepted')
  except sqlite3.IntegrityError: migrated.rollback()
 try:
  migrated.execute("DELETE FROM sources WHERE source_id='video-source'")
  migrated.commit(); raise AssertionError('referenced video source deleted')
 except sqlite3.IntegrityError: migrated.rollback()
 # A failed replacement transaction must retain old reviewed binding and no queued deletion.
 try:
  migrated.execute("INSERT INTO asset_cleanup VALUES('migration','project-assets/migration/video.mp4')")
  migrated.execute("DELETE FROM edge_transitions WHERE edge_id='edge'")
  migrated.execute("DELETE FROM local_assets WHERE asset_id='video'")
  migrated.execute("INSERT INTO edge_transitions VALUES('migration','edge','missing','video-source',0,1000000,1000000,'[]','new-review')")
  migrated.commit(); raise AssertionError('partial video replacement committed')
 except sqlite3.IntegrityError: migrated.rollback()
 assert migrated.execute("SELECT asset_id FROM edge_transitions WHERE edge_id='edge'").fetchone() == ('video',)
 assert migrated.execute("SELECT COUNT(*) FROM asset_cleanup WHERE project_id='migration'").fetchone()[0] == 0
 # Project cascade queues images plus video, preserves both kinds of interrupted import journal.
 migrated.execute("INSERT INTO transition_imports VALUES('migration','interrupted-video')")
 migrated.execute("INSERT INTO asset_cleanup SELECT project_id,relative_path FROM local_assets WHERE project_id='migration'")
 migrated.execute("UPDATE projects SET start_state_id=NULL WHERE project_id='migration'")
 migrated.execute("DELETE FROM projects WHERE project_id='migration'")
 migrated.commit()
 assert migrated.execute('SELECT COUNT(*) FROM edge_transitions').fetchone()[0] == 0
 assert list(migrated.execute('SELECT * FROM transition_imports')) == [('migration', 'interrupted-video')]
 assert list(migrated.execute('SELECT * FROM asset_imports')) == [('deleted', 'interrupted')]
 assert migrated.execute('SELECT COUNT(*) FROM asset_cleanup').fetchone()[0] == 4
 assert not list(migrated.execute('PRAGMA foreign_key_check'))
 print('PASS v%d-to-v3 preserved rows/journals, exact duration limits, transition source FK, atomic replacement rollback and video cascade' % previous_version)

# v4 regions are wholly additive. The dependency ID/hash intentionally survive base replacement;
# nullable crop/review is cleared by the production transaction before old files are queued.
for previous_version in (1, 2, 3):
 m = sqlite3.connect(':memory:'); m.execute('PRAGMA foreign_keys=ON')
 for sql in tables + single_line:
  if sql in region_sql + editor_sql or (previous_version < 2 and sql in next_sql) or (previous_version < 3 and sql in transition_sql): continue
  m.execute(sql)
 m.execute("INSERT INTO projects VALUES('p','Kept','Goal',1,2,7,'s')")
 m.execute("INSERT INTO sources VALUES('p','src','private')")
 m.execute("INSERT INTO local_assets VALUES('base','p','base.png','base-sha',50,20,30)")
 m.execute("INSERT INTO states VALUES('p','s','capture',0,'Step','Text',1,'src','base',0,1000,'[]')")
 m.execute("INSERT INTO asset_imports VALUES('p','pending')"); m.commit()
 old_tables=[r[0] for r in m.execute("SELECT name FROM sqlite_master WHERE type='table'")]
 old={t:list(m.execute('SELECT * FROM '+t)) for t in old_tables}
 with m:
  for sql in (next_sql if previous_version < 2 else []) + (transition_sql if previous_version < 3 else []) + region_sql: m.execute(sql)
  m.execute('PRAGMA user_version=4')
 assert old == {t:list(m.execute('SELECT * FROM '+t)) for t in old_tables}
 m.execute("INSERT INTO local_assets VALUES('crop','p','crop.png','crop-sha',30,4,5)")
 row=('p','r','s','base','base-sha','Visible','Group',2,3,4,5,20,30,-1,.25,.75,'crop',123)
 m.execute('INSERT INTO regions VALUES('+','.join('?'*18)+')',row);m.commit()
 for sql in ("UPDATE regions SET x_px=19", "UPDATE regions SET anchor_x=1.1", "UPDATE regions SET width_px=0", "UPDATE regions SET z_index=10001", "UPDATE regions SET state_id='foreign'", "UPDATE regions SET asset_id='missing'"):
  try: m.execute(sql);m.commit();raise AssertionError('invalid region row accepted')
  except sqlite3.IntegrityError:m.rollback()
 with m:
  m.execute("INSERT INTO asset_cleanup VALUES('p','crop.png')")
  m.execute("UPDATE regions SET asset_id=NULL,reviewed_at=NULL")
  m.execute("DELETE FROM local_assets WHERE asset_id='crop'")
 assert m.execute('SELECT base_asset_id,base_sha256,asset_id,reviewed_at FROM regions').fetchone()==('base','base-sha',None,None)
 m.execute("UPDATE projects SET start_state_id=NULL")
 m.execute("DELETE FROM states WHERE state_id='s'");m.commit()
 assert m.execute('SELECT COUNT(*) FROM regions').fetchone()[0]==0
 assert m.execute('SELECT COUNT(*) FROM asset_cleanup').fetchone()[0]==1
 assert not list(m.execute('PRAGMA foreign_key_check'))
 print('PASS v%d-to-v4 additive region migration, pixel/anchor/layer/FK constraints, stale dependency and cascade' % previous_version)

# v5 recovery is additive from every deployed schema. Existing rows, graph revision and recovery
# journals are unchanged; raw form strings live only in the new private whitelist JSON record.
for previous_version in (1, 2, 3, 4):
 m = sqlite3.connect(':memory:'); m.execute('PRAGMA foreign_keys=ON')
 for sql in tables + single_line:
  if sql in editor_sql or (previous_version < 2 and sql in next_sql) or (previous_version < 3 and sql in transition_sql) or (previous_version < 4 and sql in region_sql): continue
  m.execute(sql)
 m.execute('PRAGMA user_version=' + str(previous_version))
 m.execute("INSERT INTO projects VALUES('p','Kept','Goal',1,2,7,'s')")
 m.execute("INSERT INTO sources VALUES('p','src','private-source')")
 m.execute("INSERT INTO local_assets VALUES('base','p','base.png','sha',50,20,30)")
 m.execute("INSERT INTO states VALUES('p','s','capture',0,'Step','Text',0,'src','base',0,1000,'[]')")
 m.execute("INSERT INTO asset_cleanup VALUES('deleted','queued.png')")
 m.execute("INSERT INTO asset_imports VALUES('p','pending')")
 if previous_version >= 2: m.execute("INSERT INTO next_actions VALUES('p','next','s','Again','s')")
 if previous_version >= 3: m.execute("INSERT INTO transition_imports VALUES('p','pending-video')")
 if previous_version >= 4: m.execute("INSERT INTO regions VALUES('p','r','s','base','sha','Kept',NULL,1,2,3,4,20,30,0,.5,.5,NULL,NULL)")
 m.commit()
 old_tables=[r[0] for r in m.execute("SELECT name FROM sqlite_master WHERE type='table'")]
 old={t:list(m.execute('SELECT * FROM '+t)) for t in old_tables}
 with m:
  for sql in (next_sql if previous_version < 2 else []) + (transition_sql if previous_version < 3 else []) + (region_sql if previous_version < 4 else []) + editor_sql: m.execute(sql)
  m.execute('PRAGMA user_version=5')
 assert old == {t:list(m.execute('SELECT * FROM '+t)) for t in old_tables}
 assert m.execute('PRAGMA user_version').fetchone()[0] == 5
 assert m.execute('SELECT COUNT(*) FROM editor_drafts').fetchone()[0] == 0
 assert m.execute('SELECT COUNT(*) FROM editor_draft_sessions').fetchone()[0] == 0
 m.execute("INSERT INTO editor_draft_sessions VALUES('p',1)")
 m.execute("INSERT INTO editor_drafts VALUES('p','s',?)", ('{"pendingForm":{"left":"not-a-number","label":"  unfinished  "}}',))
 m.commit()
 assert old == {t:list(m.execute('SELECT * FROM '+t)) for t in old_tables}
 for payload in ('', 'x' * 262145, '界' * 87382):
  try: m.execute("UPDATE editor_drafts SET draft_json=?", (payload,));m.commit();raise AssertionError('invalid byte length accepted')
  except sqlite3.IntegrityError:m.rollback()
 try:
  m.execute("INSERT INTO editor_drafts VALUES('p','missing','{}')"); m.commit(); raise AssertionError('missing step draft accepted')
 except sqlite3.IntegrityError: m.rollback()
 # Deferred FK failure occurs after both text and recovery clear. SQLite restores both together.
 staged = list(m.execute('SELECT * FROM editor_drafts'))
 try:
  m.execute("UPDATE states SET title='Saved',source_id='missing' WHERE state_id='s'")
  m.execute("DELETE FROM editor_drafts WHERE project_id='p' AND state_id='s'")
  m.execute("UPDATE projects SET draft_revision=draft_revision+1")
  m.commit(); raise AssertionError('partial staged save accepted')
 except sqlite3.IntegrityError: m.rollback()
 assert staged == list(m.execute('SELECT * FROM editor_drafts'))
 assert m.execute('SELECT title FROM states').fetchone()[0] == 'Step'
 assert m.execute('SELECT draft_revision FROM projects').fetchone()[0] == 7
 # A successful formal save clears the recovery row in the same transaction.
 with m:
  m.execute("UPDATE states SET title='Saved' WHERE state_id='s'")
  m.execute("DELETE FROM editor_drafts WHERE project_id='p' AND state_id='s'")
  m.execute("UPDATE projects SET draft_revision=draft_revision+1")
 assert m.execute('SELECT COUNT(*) FROM editor_drafts').fetchone()[0] == 0
 assert m.execute('SELECT draft_revision FROM projects').fetchone()[0] == 8
 assert m.execute('SELECT generation FROM editor_draft_sessions').fetchone()[0] == 1
 m.execute("INSERT INTO editor_drafts VALUES('p','s','{}')")
 m.execute("UPDATE projects SET start_state_id=NULL")
 m.execute("DELETE FROM states WHERE state_id='s'");m.commit()
 assert m.execute('SELECT COUNT(*) FROM editor_drafts').fetchone()[0] == 0
 assert m.execute('SELECT COUNT(*) FROM editor_draft_sessions').fetchone()[0] == 1
 m.execute("DELETE FROM projects WHERE project_id='p'");m.commit()
 assert m.execute('SELECT COUNT(*) FROM editor_draft_sessions').fetchone()[0] == 0
 assert not list(m.execute('PRAGMA foreign_key_check'))
 print('PASS v%d-to-v5 additive editor recovery, unchanged revision/journals, UTF-8 byte bound, save-clear rollback and cascades' % previous_version)

# Project cascade removes editor rows and generations without touching a different project.
# Use the production schema initialized at the start of this file.
project('draft-p'); step('draft-p','draft-a'); step('draft-p','draft-b')
for p, state in [('draft-p','draft-a'), ('draft-p','draft-b'), ('other','c')]:
 c.execute('INSERT INTO editor_drafts VALUES(?,?,?)', (p,state,'{}'))
for p in ('draft-p', 'other'): c.execute('INSERT INTO editor_draft_sessions VALUES(?,1)', (p,))
c.commit()
c.execute("DELETE FROM projects WHERE project_id='draft-p'");c.commit()
assert list(c.execute('SELECT project_id,state_id FROM editor_drafts')) == [('other','c')]
assert list(c.execute('SELECT project_id,generation FROM editor_draft_sessions')) == [('other',1)]
assert not list(c.execute('PRAGMA foreign_key_check'))
print('PASS editor project cascade preserves other project draft/session')
print('NOTE codec, raw invalid form restoration and session writer races are covered by EditorDraftStoreChecks on Android; host DDL is not runtime proof')
