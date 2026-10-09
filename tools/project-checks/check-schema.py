"""Host SQLite checks against the production DDL; not Android runtime or UI tests."""
from pathlib import Path
import re
import sqlite3
s=(Path(__file__).resolve().parents[2] / 'android/app/src/main/java/com/tapscene/data/ProjectStore.kt').read_text()
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
c.execute("UPDATE projects SET start_state_id=NULL WHERE project_id='p'")
c.execute("DELETE FROM projects WHERE project_id='p'");c.commit()
assert c.execute('SELECT COUNT(*) FROM states').fetchone()[0]==1
assert c.execute('SELECT COUNT(*) FROM hotspots').fetchone()[0]==0
assert c.execute('SELECT COUNT(*) FROM edges').fetchone()[0]==0
assert c.execute('SELECT COUNT(*) FROM asset_cleanup').fetchone()[0]==2
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
