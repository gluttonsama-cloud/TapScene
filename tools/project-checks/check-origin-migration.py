"""Host SQLite v1–v7 -> v8 safety checks. Not Android connection-pool/device proof."""
from pathlib import Path
import re
import sqlite3

root = Path(__file__).resolve().parents[2]
source = (root / 'android/app/src/main/java/com/tapscene/data/ProjectStore.kt').read_text()
legacy_text = '\n'.join(line for line in Path(__file__).with_name('legacy-schema-v5.sql').read_text().splitlines() if not line.startswith('--'))
legacy = [s.strip() for s in legacy_text.split(';') if s.strip()]
new_state = re.search(r'internal val STATES_SQL = """(.*?)"""', source, re.S).group(1)
columns = re.search(r'LEGACY_STATE_COLUMNS = "(.*?)"', source).group(1)
v6_columns = columns + ',origin_kind,base_asset_id,base_sha256,base_revision,base_width,base_height'
def fixture_sql(filename):
    text = '\n'.join(line for line in Path(__file__).with_name(filename).read_text().splitlines() if not line.startswith('--'))
    return [s.strip() for s in text.split(';') if s.strip()]

v6_state = fixture_sql('legacy-states-v6.sql')[0]
v7_state = fixture_sql('legacy-states-v7.sql')[0]
v7_columns = v6_columns + ',image_source_id,evidence_kind'
v7_images = fixture_sql('legacy-images-v7.sql')
image_tables = [sql for sql in re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', source, re.S) + re.findall(r'db.execSQL\("(CREATE TABLE.*?)"\)',source) if sql.startswith(('CREATE TABLE image_sources ', 'CREATE TABLE image_source_imports(', 'CREATE TABLE image_source_cleanup('))]
assert len(image_tables) == 3
assert image_tables == v7_images, 'Shipped v7 image DDL changed; preserve its frozen fixture'
ai_tables = [sql for sql in re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', source, re.S) if sql.startswith(('CREATE TABLE package_step_origins ', 'CREATE TABLE ai_import_sessions ', 'CREATE TABLE draft_ai_configs '))]
assert len(ai_tables) == 3
assert 'db.setForeignKeyConstraintsEnabled(db.version !in 1..7)' in source
assert 'oldVersion in 1..7 && newVersion == 8 && foreignKeys(db) == 0' in source

def introduced(sql):
    if 'editor_draft' in sql: return 5
    if 'regions' in sql: return 4
    if 'edge_transitions' in sql or 'transition_imports' in sql: return 3
    if 'next_actions' in sql: return 2
    return 1

def tables(db):
    return [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]

def rows(db, name, projection='*'):
    cur = db.execute(f'SELECT {projection} FROM "{name}"')
    return sorted(cur.fetchall(), key=repr)

def typed_rows(db, name, projection='*'):
    # Preserve SQLite storage classes too (Python otherwise treats 1 == 1.0).
    columns = [d[0] for d in db.execute(f'SELECT {projection} FROM "{name}" LIMIT 0').description]
    expressions = ','.join(f'typeof("{c}"),"{c}"' for c in columns)
    return rows(db, name, expressions)

def snapshot(db):
    return ({name: typed_rows(db,name) for name in tables(db)},
        list(db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name')),
        db.execute('PRAGMA user_version').fetchone()[0])

def fixture(version):
    db = sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
    for sql in legacy:
        if introduced(sql) <= version:
            db.execute((v7_state if version == 7 else v6_state) if version >= 6 and sql.startswith('CREATE TABLE states (') else sql)
    if version == 7:
        for sql in v7_images: db.execute(sql)
    db.execute(f'PRAGMA user_version={version}')
    for p in ('p', 'other'):
        db.execute('INSERT INTO projects VALUES(?,?,?,?,?,?,?)',(p,p,'Goal',11,12,9,'a'))
        db.execute('INSERT INTO sources VALUES(?,?,?)',(p,'src','source retained even if file missing'))
        for asset in ('a','b','t','r'):
            db.execute('INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)',(p+asset,p,p+'/'+asset+'.png','digest',42,20,30))
        for state in ('a','b'):
            values=(p,state,'capture-'+state,0,state,'Text',0,'src',p+state,123,1000,'[]')
            if version >= 6:
                db.execute(f"INSERT INTO states ({columns},origin_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'videoFrame')",values)
            else: db.execute('INSERT INTO states VALUES(?,?,?,?,?,?,?,?,?,?,?,?)',values)
        db.execute("INSERT INTO hotspots VALUES(?,?,?,'Manual',.1,.2,.6,.8)",(p,'h','a'))
        db.execute('INSERT INTO edges VALUES(?,?,?,?,?,NULL)',(p,'e','h','a','b'))
        if version >= 2: db.execute("INSERT INTO next_actions VALUES(?,?,?,'Back',?)",(p,'n','b','a'))
        if version >= 3:
            db.execute('INSERT INTO edge_transitions VALUES(?,?,?,?,0,1000,1000,\'[]\',?)',(p,'e',p+'t','src','review'))
            db.execute('INSERT INTO transition_imports VALUES(?,?)',(p,p+'-pending-video'))
        if version >= 4: db.execute("INSERT INTO regions VALUES(?,?,?,?,?,'Region',NULL,1,2,3,4,20,30,0,.5,.5,?,123)",(p,'r','a',p+'a','digest',p+'r'))
        if version >= 5:
            db.execute('INSERT INTO editor_drafts VALUES(?,?,?)',(p,'a','{"pendingForm":{"label":"  not applied  ","left":"invalid"}}'))
            db.execute('INSERT INTO editor_draft_sessions VALUES(?,7)',(p,))
    if version >= 6:
        db.execute("UPDATE states SET origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,base_asset_id='historical',base_sha256=?,base_revision=8,base_width=20,base_height=30 WHERE state_id='b'",('a'*64,))
    if version == 7:
        for p in ('p', 'other'):
            db.execute("INSERT INTO image_sources VALUES(?,?,?,?)", (p, p+'-raw','image/png','original private source'))
            db.execute("INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)",(p+'c',p,p+'/c.png','digest',42,20,30))
            db.execute("INSERT INTO states ("+columns+",origin_kind,image_source_id,evidence_kind) VALUES(?,?,?,0,'Screenshot','Kept authored',0,NULL,?,NULL,NULL,'[]','image',?,'authored')", (p,'c','capture-c',p+'c',p+'-raw'))
            db.execute("UPDATE states SET evidence_kind='imported' WHERE project_id=? AND state_id='b'", (p,))
        db.execute("INSERT INTO image_source_imports VALUES('pending-image','pending-source','image/jpeg')")
        db.execute("INSERT INTO image_source_cleanup VALUES('deleted','deleted-source','image/png')")
    db.execute("INSERT INTO asset_cleanup VALUES('deleted','orphan.png')")
    db.execute("INSERT INTO asset_imports VALUES('p','pending-image')")
    db.commit(); return db

def migrate(db, version, fail=None):
    assert db.execute('PRAGMA foreign_keys').fetchone()[0] == 0
    for sql in legacy:
        if introduced(sql)>version: db.execute(sql)
    if version < 7:
        for sql in image_tables: db.execute(sql)
    for sql in ai_tables: db.execute(sql)
    preserved = columns if version < 6 else v6_columns if version < 7 else v7_columns
    # Mirror production's fail-closed schema object gate.
    for kind,name,table,sql in db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND sql IS NOT NULL"):
        assert kind == 'index' and (table != 'states' or sql in ('CREATE INDEX states_order ON states(project_id,sort_order)','CREATE INDEX states_source ON states(source_id)'))
    before = {t:typed_rows(db,t) for t in tables(db) if t!='states'}
    old = typed_rows(db,'states',preserved)
    unchanged_schema = list(db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name!='states' AND name NOT LIKE 'sqlite_%' ORDER BY type,name"))
    db.execute(new_state.replace('CREATE TABLE states (','CREATE TABLE states_v8 ('))
    if version < 6: db.execute(f"INSERT INTO states_v8 ({columns},origin_kind) SELECT {columns},'videoFrame' FROM states")
    else: db.execute(f"INSERT INTO states_v8 ({preserved}) SELECT {preserved} FROM states")
    checkpoint(fail,'copy')
    assert typed_rows(db,'states_v8',preserved)==old
    db.execute('DROP TABLE states'); checkpoint(fail,'drop')
    db.execute('ALTER TABLE states_v8 RENAME TO states'); checkpoint(fail,'rename')
    for sql in legacy:
        if sql.startswith('CREATE INDEX states_'): db.execute(sql)
    checkpoint(fail,'indexes')
    assert typed_rows(db,'states',preserved)==old
    assert before=={t:typed_rows(db,t) for t in before}
    assert unchanged_schema == list(db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name!='states' AND name NOT LIKE 'sqlite_%' ORDER BY type,name"))
    assert not list(db.execute('PRAGMA foreign_key_check'))
    assert list(db.execute('PRAGMA integrity_check'))==[('ok',)]
    db.execute('PRAGMA user_version=8'); checkpoint(fail,'version')

def checkpoint(wanted,stage):
    if wanted==stage: raise RuntimeError('Injected failure after '+stage)

for version in range(1,8):
    for stage in ('copy','drop','rename','indexes','version',None):
        db=fixture(version); before=snapshot(db)
        db.execute('PRAGMA foreign_keys=OFF'); db.execute('BEGIN EXCLUSIVE')
        try:
            migrate(db,version,stage); db.commit()
        except RuntimeError:
            db.rollback(); assert snapshot(db)==before
        db.execute('PRAGMA foreign_keys=ON')
        assert db.execute('PRAGMA foreign_keys').fetchone()[0]==1
        assert not list(db.execute('PRAGMA foreign_key_check'))
        if stage is None:
            assert db.execute('PRAGMA user_version').fetchone()[0]==8
            if version < 6: assert list(db.execute('SELECT DISTINCT origin_kind,base_asset_id,base_revision FROM states'))==[('videoFrame',None,None)]
            else: assert list(db.execute("SELECT COUNT(*) FROM states WHERE origin_kind='image' AND base_asset_id='historical'"))==[(2,)]
            if version < 7: assert list(db.execute('SELECT DISTINCT evidence_kind,image_source_id FROM states')) == [('recorded',None)]
            else:
                assert list(db.execute("SELECT DISTINCT evidence_kind FROM states WHERE state_id='b'")) == [('imported',)]
                assert db.execute("SELECT COUNT(*) FROM states WHERE image_source_id IS NOT NULL AND evidence_kind='authored'").fetchone() == (2,)
                assert db.execute('SELECT COUNT(*) FROM image_source_imports').fetchone() == (1,)
                assert db.execute('SELECT COUNT(*) FROM image_source_cleanup').fetchone() == (1,)
            assert db.execute('SELECT COUNT(*) FROM states WHERE package_import_id IS NOT NULL').fetchone() == (0,)
            assert all(db.execute('SELECT COUNT(*) FROM '+t).fetchone()==(0,) for t in ('ai_import_sessions','package_step_origins','draft_ai_configs'))
        db.close()
    print(f'PASS v{version}->v8 exact graph/assets/drafts/journals, composite IDs, all five DDL/version rollback points')

# Gates reject unexpected schema and pre-existing orphan relationships without changing anything.
for bad in ('index','changed-index','trigger','view','orphan'):
    db=fixture(5)
    if bad=='index': db.execute('CREATE INDEX custom_states ON states(title)')
    elif bad=='changed-index':
        db.execute('DROP INDEX states_source'); db.execute('CREATE INDEX states_source ON states(title)')
    elif bad=='trigger': db.execute('CREATE TRIGGER custom_states AFTER UPDATE ON states BEGIN SELECT 1; END')
    elif bad=='view': db.execute('CREATE VIEW custom_states AS SELECT * FROM states')
    else:
        db.execute('PRAGMA foreign_keys=OFF');db.execute("UPDATE states SET source_id='missing' WHERE project_id='p'");db.commit()
    before=snapshot(db);db.execute('PRAGMA foreign_keys=OFF');db.execute('BEGIN EXCLUSIVE')
    try:
        migrate(db,5);raise RuntimeError('Bad migration accepted')
    except AssertionError: db.rollback()
    assert snapshot(db)==before
    db.close()
print('PASS unknown index/trigger/view and orphan source reject with original schema/data/version retained')

db=fixture(5);db.execute('PRAGMA foreign_keys=OFF');db.execute('BEGIN EXCLUSIVE');migrate(db,5);db.commit();db.execute('PRAGMA foreign_keys=ON')
# Valid image records have no fake source or timing; old asset ID is provenance, not an FK.
base="origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,base_asset_id='cleaned-history',base_sha256='"+'a'*64+"',base_revision=9,base_width=20,base_height=30"
db.execute("UPDATE states SET "+base+" WHERE project_id='p' AND state_id='a'");db.commit()
assert not list(db.execute('PRAGMA foreign_key_check'))
for update in ("source_id='src'","frame_pts_us=0","time_precision_us=1000","base_sha256=NULL","base_revision=NULL","base_width=NULL","base_height=0","base_sha256='INVALID'","origin_kind='videoFrame'","origin_kind='imported'","package_import_id='not-package'"):
    try:
        db.execute("UPDATE states SET "+update+" WHERE project_id='p' AND state_id='a'");db.commit();raise AssertionError('Illegal mixed origin accepted: '+update)
    except sqlite3.IntegrityError:db.rollback()
for update in ("source_id=NULL","frame_pts_us=NULL","time_precision_us=NULL","base_asset_id='hidden'","package_import_id='not-package'"):
    try:
        db.execute("UPDATE states SET "+update+" WHERE project_id='p' AND state_id='b'");db.commit();raise AssertionError('Illegal video accepted: '+update)
    except sqlite3.IntegrityError:db.rollback()
print('PASS image/videoFrame XOR, required metadata and SQL NULL rejection; history is not a live asset FK')
# External image is a separate private source, never a safe-base binding or video.
db.execute("INSERT INTO image_sources VALUES('p','raw','image/png','private original')")
db.execute("UPDATE states SET image_source_id='raw',evidence_kind='authored',base_asset_id=NULL,base_sha256=NULL,base_revision=NULL,base_width=NULL,base_height=NULL WHERE project_id='p' AND state_id='a'");db.commit()
for update in ("source_id='src'","frame_pts_us=0","time_precision_us=1","base_asset_id='safe'","evidence_kind='recorded'","image_source_id='absent'","package_import_id='not-package'"):
    try:
        db.execute("UPDATE states SET "+update+" WHERE project_id='p' AND state_id='a'");db.commit();raise AssertionError('Illegal external image accepted: '+update)
    except sqlite3.IntegrityError:db.rollback()
# Same external source cannot be cross-project or duplicated; journals survive a deleted project.
try:
    db.execute("UPDATE states SET origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,image_source_id='raw',evidence_kind='authored' WHERE project_id='other' AND state_id='b'");db.commit();raise AssertionError('Cross-project raw source accepted')
except sqlite3.IntegrityError:db.rollback()
db.execute("INSERT INTO image_source_cleanup VALUES('p','raw','image/png')")
db.execute("UPDATE projects SET start_state_id=NULL WHERE project_id='p'")
db.execute("DELETE FROM projects WHERE project_id='p'");db.commit()
assert not list(db.execute("SELECT * FROM image_sources WHERE project_id='p'"))
assert list(db.execute('SELECT * FROM image_source_cleanup'))==[('p','raw','image/png')]
assert not list(db.execute('PRAGMA foreign_key_check'))
print('PASS explicit external-image/safe-base/video XOR, evidence identity, source isolation and post-delete raw cleanup journal')

# Exercise the actual v8 DDL too. A valid import has two deferred references, so
# either insertion order succeeds, but either orphan or mismatched import fails at commit.
def current_database():
    result = sqlite3.connect(':memory:'); result.execute('PRAGMA foreign_keys=ON')
    statements = re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', source, re.S) + [new_state]
    statements += list(dict.fromkeys(re.findall(r'db.execSQL\("(CREATE (?:INDEX|TABLE).*?)"\)', source)))
    for sql in statements: result.execute(sql)
    return result

def reject(db, operation):
    before = snapshot(db)
    try:
        operation(); db.commit()
    except sqlite3.IntegrityError:
        db.rollback()
    else: raise AssertionError('Invalid v8 write was accepted')
    assert snapshot(db) == before, 'Rejected write changed persisted data'

def insert_package_state(db, project='p', state='s', import_id='import-1'):
    db.execute("INSERT INTO states (project_id,state_id,capture_id,sort_order,title,description,is_terminal,input_asset_id,masks_json,origin_kind,evidence_kind,package_import_id) VALUES(?,?,?,0,'Imported','Text',1,?,'[]','packageImage','imported',?)", (project,state,state,project+'-asset',import_id))

def insert_origin(db, project='p', state='s', import_id='import-1'):
    # The old source state/asset identifiers are history, not live source/asset FKs.
    db.execute('INSERT INTO package_step_origins VALUES(?,?,?,?,?,?,?)', (project,state,import_id,'external-state','external-asset','a'*64,'recorded'))

for reverse in (False,True):
    db=current_database()
    for p in ('p','other'):
        db.execute('INSERT INTO projects VALUES(?,?,?,1,1,1,NULL)',(p,p,'Goal'))
        db.execute('INSERT INTO local_assets VALUES(?,?,?,?,4,1,1)',(p+'-asset',p,p+'/asset.png','a'*64))
    db.commit()
    reject(db, lambda: insert_package_state(db))
    reject(db, lambda: insert_origin(db))
    reject(db, lambda: (insert_package_state(db),insert_origin(db,import_id='different-import')))
    reject(db, lambda: (insert_package_state(db),insert_origin(db,project='other')))
    reject(db, lambda: (insert_package_state(db),insert_origin(db,state='wrong-state')))
    with db:
        operations = (insert_origin,insert_package_state) if reverse else (insert_package_state,insert_origin)
        for operation in operations: operation(db)
    assert not list(db.execute('PRAGMA foreign_key_check'))
    for update in ("package_import_id=NULL", "package_import_id=''", "package_import_id='different-import'",
            "evidence_kind='recorded'", "evidence_kind='authored'", "source_id='fake-video'", "frame_pts_us=0",
            "time_precision_us=1", "image_source_id='fake-raw'", "base_asset_id='fake-base'",
            "base_sha256='"+'a'*64+"'", "base_revision=1", "base_width=1", "base_height=1",
            "origin_kind='videoFrame'", "origin_kind='image'"):
        reject(db, lambda update=update: db.execute('UPDATE states SET '+update+" WHERE project_id='p'"))
    for update in ("source_sha256=NULL", "source_sha256='"+'g'*64+"'", "source_sha256='"+'a'*63+"'",
            "declared_kind='videoFrame'", "declared_kind=NULL", "source_state_id=''", "source_asset_id=''",
            "import_id='different-import'", "project_id='other'", "state_id='wrong-state'"):
        reject(db, lambda update=update: db.execute('UPDATE package_step_origins SET '+update+" WHERE project_id='p'"))
    reject(db, lambda: db.execute("UPDATE package_step_origins SET source_sha256=? WHERE project_id='p'", ('a'*64+'\0suffix',)))
    reject(db, lambda: db.execute("DELETE FROM package_step_origins WHERE project_id='p'"))
    # Same state ID in another project is permitted only with its own exact binding.
    with db:
        insert_package_state(db,project='other'); insert_origin(db,project='other')
    # A safe-image replacement clears package_import_id while preserving evidence and history.
    before_origin=rows(db,'package_step_origins')
    with db:
        db.execute("UPDATE states SET origin_kind='image',package_import_id=NULL,base_asset_id='old-safe-asset',base_sha256=?,base_revision=1,base_width=1,base_height=1 WHERE project_id='p'",('b'*64,))
    assert rows(db,'package_step_origins')==before_origin
    assert db.execute("SELECT evidence_kind FROM states WHERE project_id='p'").fetchone()==('imported',)
    reject(db, lambda: db.execute("UPDATE states SET package_import_id='import-1' WHERE project_id='p'"))
    # Receipts bind preallocated project IDs even before there is a project.
    db.execute("INSERT INTO ai_import_sessions VALUES('waiting','preparing','not-yet-created',NULL,NULL,NULL,123)")
    db.execute("INSERT INTO ai_import_sessions VALUES('session','committed','p',?,?,?,123)",('c'*64,'d'*64,'{}'))
    db.execute("INSERT INTO draft_ai_configs VALUES('p',1,0,'{}')"); db.commit()
    reject(db, lambda: db.execute("INSERT INTO ai_import_sessions VALUES('duplicate','preparing','p',NULL,NULL,NULL,123)"))
    for state in ('ready','committed'):
        reject(db, lambda state=state: db.execute("UPDATE ai_import_sessions SET state=? WHERE session_id='waiting'",(state,)))
    for update in ("input_sha=NULL", "preview_digest=NULL", "prepared_json=NULL", "prepared_json=''", "state='unknown'",
            "input_sha='INVALID'", "preview_digest='INVALID'"):
        reject(db, lambda update=update: db.execute('UPDATE ai_import_sessions SET '+update+" WHERE session_id='session'"))
    for field in ('input_sha','preview_digest'):
        reject(db, lambda field=field: db.execute('UPDATE ai_import_sessions SET '+field+"=? WHERE session_id='session'",('a'*64+'\0suffix',)))
    for text in ('x'*2097153, '界'*699051):
        reject(db, lambda text=text: db.execute("UPDATE ai_import_sessions SET prepared_json=? WHERE session_id='session'",(text,)))
    with db: db.execute("UPDATE ai_import_sessions SET prepared_json=? WHERE session_id='session'",('x'*2097152,))
    for update in ("bound_revision=0", "bound_revision=NULL", "needs_repair=2", "needs_repair=NULL", "config_json=''", "config_json=NULL", "project_id='missing'"):
        reject(db, lambda update=update: db.execute('UPDATE draft_ai_configs SET '+update))
    for text in ('x'*524289,'界'*174763):
        reject(db, lambda text=text: db.execute('UPDATE draft_ai_configs SET config_json=?',(text,)))
    with db: db.execute('UPDATE draft_ai_configs SET config_json=?',('x'*524288,))
    with db: db.execute("DELETE FROM projects WHERE project_id='p'")
    assert db.execute("SELECT COUNT(*) FROM package_step_origins WHERE project_id='p'").fetchone()==(0,)
    assert db.execute('SELECT COUNT(*) FROM draft_ai_configs').fetchone()==(0,)
    assert db.execute("SELECT state FROM ai_import_sessions WHERE project_id='p'").fetchone()==('committed',)
    assert db.execute("SELECT COUNT(*) FROM package_step_origins WHERE project_id='other'").fetchone()==(1,)
    assert not list(db.execute('PRAGMA foreign_key_check'))
    assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
    # Direct state deletion also cascades its provenance despite the circular reference.
    with db: db.execute("DELETE FROM states WHERE project_id='other'")
    assert db.execute('SELECT COUNT(*) FROM package_step_origins').fetchone()==(0,)
    db.close()
print('PASS v8 deferred circular provenance FK, both insertion orders, orphan/mismatch/mixed-source rejection, imported evidence and retained history')
print('PASS v8 durable import receipts, unique project reservation, ready/commit completeness, UTF-8 JSON limits and config/provenance cascades')
print('NOT_RUN Android SQLiteOpenHelper connection-pool/lifecycle and device migration; host SQLite '+sqlite3.sqlite_version)
