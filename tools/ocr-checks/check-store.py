#!/usr/bin/env python3
"""Validate the actual OCR schema with host SQLite. Not an Android/Kotlin runtime test."""
from pathlib import Path
import re
import sqlite3
import uuid

ROOT = Path(__file__).resolve().parents[2]
STORE = ROOT / "android/app/src/main/java/com/tapscene/data/CandidateOcrStore.kt"


def rejected(db, sql, values):
    try:
        db.execute(sql, values)
    except sqlite3.IntegrityError:
        return
    raise AssertionError("Invalid OCR database row was accepted")


def main():
    text = STORE.read_text(encoding="utf-8")
    schemas = re.findall(r'db\.execSQL\("""(.*?)"""\)', text, re.S)
    assert len(schemas) == 3, "Recheck schema extraction after store changes"
    db = sqlite3.connect(":memory:")
    for schema in schemas:
        db.execute(schema)
    project, source, candidate, run = [str(uuid.uuid4()) for _ in range(4)]
    identity = (project, source, "a" * 64, "engine-1", "model-1")
    result = identity + (candidate, 1_000, 1_000, 1_080, 2_400, '{"words":[],"truncated":false}')
    insert_result = "INSERT INTO results VALUES (?,?,?,?,?,?,?,?,?,?,?)"
    insert_job = "INSERT OR REPLACE INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?)"
    db.execute(insert_result, result)
    for column, other in (("project_id", str(uuid.uuid4())), ("source_id", str(uuid.uuid4())),
                          ("source_sha", "b" * 64), ("actual_pts_us", 2_000), ("width", 1_079),
                          ("height", 2_399), ("engine_version", "engine-2"), ("model_version", "model-2")):
        assert db.execute(f"SELECT count(*) FROM results WHERE {column}=?", (other,)).fetchone()[0] == 0
    for index, value in ((6, -1), (6, 1), (7, 1), (8, 0), (8, 2_400), (9, 2_401), (10, "x" * 524_289)):
        invalid = list(result)
        invalid[5] = str(uuid.uuid4())
        invalid[index] = value
        rejected(db, insert_result, invalid)
    db.execute(insert_job, identity + ("RUNNING", run, 1, 2, "[]"))
    for status, lease, completed, total in (("RUNNING", None, 0, 1), ("COMPLETED", run, 1, 1),
                                          ("COMPLETED", None, 0, 1), ("FAILED", None, 31, 30),
                                          ("FAILED", None, 0, 31), ("UNKNOWN", None, 0, 1)):
        rejected(db, insert_job, identity + (status, lease, completed, total, "[]"))
    db.commit()
    try:
        with db:
            db.execute("DELETE FROM results WHERE project_id=?", (project,))
            raise RuntimeError("Synthetic interrupted commit")
    except RuntimeError:
        pass
    assert db.execute("SELECT count(*) FROM results").fetchone()[0] == 1
    other = list(result)
    other[0] = str(uuid.uuid4())
    other[1] = str(uuid.uuid4())
    db.execute(insert_result, other)
    db.commit()
    with db:
        db.execute("INSERT INTO deletion_fences VALUES (?,?)", ("source", source))
        db.execute("DELETE FROM results WHERE source_id=?", (source,))
        db.execute("DELETE FROM jobs WHERE source_id=?", (source,))
    assert db.execute("SELECT count(*) FROM results WHERE source_id=?", (source,)).fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM jobs WHERE source_id=?", (source,)).fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM results WHERE project_id=?", (other[0],)).fetchone()[0] == 1
    assert db.execute("SELECT count(*) FROM deletion_fences WHERE object_id=?", (source,)).fetchone()[0] == 1
    # This is a source-level privacy tripwire, not a full data-flow or APK audit.
    assert "noBackupFilesDir" in text and "PRAGMA secure_delete=ON" in text
    assert "android.graphics" not in text and "android.util.Log" not in text
    db.close()
    print("PASS OCR host SQLite: binding keys, limits, rollback, scoped cleanup and deletion fence")
    print("NOT_RUN here: Android CandidateOcrChecks, coroutine cancellation, native OCR and device UI")


if __name__ == "__main__":
    main()
