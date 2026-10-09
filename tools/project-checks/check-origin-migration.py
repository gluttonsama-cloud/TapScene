"""Host SQLite v1–v6 -> v7 safety checks. Not Android connection-pool/device proof."""
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
v6_state = Path(__file__).with_name('legacy-states-v6.sql').read_text().strip().rstrip(';')
image_tables = [sql for sql in re.findall(r'db.execSQL\("""(CREATE TABLE.*?)"""\)', source, re.S) + re.findall(r'db.execSQL\("(CREATE TABLE.*?)"\)',source) if sql.startswith(('CREATE TABLE image_sources ', 'CREATE TABLE image_source_imports(', 'CREATE TABLE image_source_cleanup('))]
assert len(image_tables) == 3

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

def snapshot(db):
    return ({name: rows(db,name) for name in tables(db)},
        list(db.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name')),
        db.execute('PRAGMA user_version').fetchone()[0])

def fixture(version):
    db = sqlite3.connect(':memory:'); db.execute('PRAGMA foreign_keys=ON')
    for sql in legacy:
        if introduced(sql) <= version: db.execute(v6_state if version == 6 and sql.startswith('CREATE TABLE states (') else sql)
    db.execute(f'PRAGMA user_version={version}')
    for p in ('p', 'other'):
        db.execute('INSERT INTO projects VALUES(?,?,?,?,?,?,?)',(p,p,'Goal',11,12,9,'a'))
        db.execute('INSERT INTO sources VALUES(?,?,?)',(p,'src','source retained even if file missing'))
        for asset in ('a','b','t','r'):
            db.execute('INSERT INTO local_assets VALUES(?,?,?,?,?,?,?)',(p+asset,p,p+'/'+asset+'.png','digest',42,20,30))
        for state in ('a','b'):
            values=(p,state,'capture-'+state,0,state,'Text',0,'src',p+state,123,1000,'[]')
            if version == 6:
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
    if version == 6:
        db.execute("UPDATE states SET origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,base_asset_id='historical',base_sha256=?,base_revision=8,base_width=20,base_height=30 WHERE state_id='b'",('a'*64,))
    db.execute("INSERT INTO asset_cleanup VALUES('deleted','orphan.png')")
    db.execute("INSERT INTO asset_imports VALUES('p','pending-image')")
    db.commit(); return db

def migrate(db, version, fail=None):
    assert db.execute('PRAGMA foreign_keys').fetchone()[0] == 0
    for sql in legacy:
        if introduced(sql)>version: db.execute(sql)
    for sql in image_tables: db.execute(sql)
    preserved = columns if version < 6 else v6_columns
    # Mirror production's fail-closed schema object gate.
    for kind,name,table,sql in db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE type IN ('index','trigger','view') AND sql IS NOT NULL"):
        assert kind == 'index' and (table != 'states' or sql in ('CREATE INDEX states_order ON states(project_id,sort_order)','CREATE INDEX states_source ON states(source_id)'))
    before = {t:rows(db,t) for t in tables(db) if t!='states'}
    old = rows(db,'states',preserved)
    unchanged_schema = list(db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name!='states' AND name NOT LIKE 'sqlite_%' ORDER BY type,name"))
    db.execute(new_state.replace('CREATE TABLE states (','CREATE TABLE states_v7 ('))
    if version < 6: db.execute(f"INSERT INTO states_v7 ({columns},origin_kind) SELECT {columns},'videoFrame' FROM states")
    else: db.execute(f"INSERT INTO states_v7 ({v6_columns}) SELECT {v6_columns} FROM states")
    checkpoint(fail,'copy')
    assert rows(db,'states_v7',preserved)==old
    db.execute('DROP TABLE states'); checkpoint(fail,'drop')
    db.execute('ALTER TABLE states_v7 RENAME TO states'); checkpoint(fail,'rename')
    for sql in legacy:
        if sql.startswith('CREATE INDEX states_'): db.execute(sql)
    checkpoint(fail,'indexes')
    assert rows(db,'states',preserved)==old
    assert before=={t:rows(db,t) for t in before}
    assert unchanged_schema == list(db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE tbl_name!='states' AND name NOT LIKE 'sqlite_%' ORDER BY type,name"))
    assert not list(db.execute('PRAGMA foreign_key_check'))
    assert list(db.execute('PRAGMA integrity_check'))==[('ok',)]
    db.execute('PRAGMA user_version=7'); checkpoint(fail,'version')

def checkpoint(wanted,stage):
    if wanted==stage: raise RuntimeError('Injected failure after '+stage)

for version in range(1,7):
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
            assert db.execute('PRAGMA user_version').fetchone()[0]==7
            if version < 6: assert list(db.execute('SELECT DISTINCT origin_kind,base_asset_id,base_revision FROM states'))==[('videoFrame',None,None)]
            else: assert list(db.execute("SELECT COUNT(*) FROM states WHERE origin_kind='image' AND base_asset_id='historical'"))==[(2,)]
            assert list(db.execute('SELECT DISTINCT evidence_kind,image_source_id FROM states')) == [('recorded',None)]
        db.close()
    print(f'PASS v{version}->v7 exact graph/assets/drafts/journals, composite IDs, all five DDL/version rollback points')

# Gates reject unexpected schema and pre-existing orphan relationships without changing anything.
for bad in ('index','changed-index','trigger','orphan'):
    db=fixture(5)
    if bad=='index': db.execute('CREATE INDEX custom_states ON states(title)')
    elif bad=='changed-index':
        db.execute('DROP INDEX states_source'); db.execute('CREATE INDEX states_source ON states(title)')
    elif bad=='trigger': db.execute('CREATE TRIGGER custom_states AFTER UPDATE ON states BEGIN SELECT 1; END')
    else:
        db.execute('PRAGMA foreign_keys=OFF');db.execute("UPDATE states SET source_id='missing' WHERE project_id='p'");db.commit()
    before=snapshot(db);db.execute('PRAGMA foreign_keys=OFF');db.execute('BEGIN EXCLUSIVE')
    try:
        migrate(db,5);raise RuntimeError('Bad migration accepted')
    except AssertionError: db.rollback()
    assert snapshot(db)==before
    db.close()
print('PASS unknown index/trigger and orphan source reject with original schema/data/version retained')

db=fixture(5);db.execute('PRAGMA foreign_keys=OFF');db.execute('BEGIN EXCLUSIVE');migrate(db,5);db.commit();db.execute('PRAGMA foreign_keys=ON')
# Valid image records have no fake source or timing; old asset ID is provenance, not an FK.
base="origin_kind='image',source_id=NULL,frame_pts_us=NULL,time_precision_us=NULL,base_asset_id='cleaned-history',base_sha256='"+'a'*64+"',base_revision=9,base_width=20,base_height=30"
db.execute("UPDATE states SET "+base+" WHERE project_id='p' AND state_id='a'");db.commit()
assert not list(db.execute('PRAGMA foreign_key_check'))
for update in ("source_id='src'","frame_pts_us=0","time_precision_us=1000","base_sha256=NULL","base_revision=NULL","base_width=NULL","base_height=0","base_sha256='INVALID'","origin_kind='videoFrame'","origin_kind='imported'"):
    try:
        db.execute("UPDATE states SET "+update+" WHERE project_id='p' AND state_id='a'");db.commit();raise AssertionError('Illegal mixed origin accepted: '+update)
    except sqlite3.IntegrityError:db.rollback()
for update in ("source_id=NULL","frame_pts_us=NULL","time_precision_us=NULL","base_asset_id='hidden'"):
    try:
        db.execute("UPDATE states SET "+update+" WHERE project_id='p' AND state_id='b'");db.commit();raise AssertionError('Illegal video accepted: '+update)
    except sqlite3.IntegrityError:db.rollback()
print('PASS image/videoFrame XOR, required metadata and SQL NULL rejection; history is not a live asset FK')
# External image is a separate private source, never a safe-base binding or video.
db.execute("INSERT INTO image_sources VALUES('p','raw','image/png','private original')")
db.execute("UPDATE states SET image_source_id='raw',evidence_kind='authored',base_asset_id=NULL,base_sha256=NULL,base_revision=NULL,base_width=NULL,base_height=NULL WHERE project_id='p' AND state_id='a'");db.commit()
for update in ("source_id='src'","frame_pts_us=0","time_precision_us=1","base_asset_id='safe'","evidence_kind='recorded'","image_source_id='absent'"):
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
print('NOT_RUN Android SQLiteOpenHelper connection-pool/lifecycle and device migration; host SQLite '+sqlite3.sqlite_version)
