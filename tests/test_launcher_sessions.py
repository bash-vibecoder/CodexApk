import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / 'runtime/share/codex-terminal/launcher.py'
SPEC = importlib.util.spec_from_file_location('acodext_launcher', MODULE_PATH)
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)


def write_session(home, name, session_id, cwd, modified):
    path = home / 'sessions/2026/11/18' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        'type': 'session_meta',
        'payload': {'id': session_id, 'cwd': str(cwd)},
    }) + '\n')
    os.utime(path, (modified, modified))
    return path


class ProjectSessionMemoryTest(unittest.TestCase):
    def test_picks_latest_session_for_matching_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / 'projects/Test'
            other = root / 'projects/Other'
            sessions = root / 'codex/sessions'
            first_id = '01a1eea3-16d6-71a3-ac20-14be7f1447f3'
            latest_id = '01a1ef3d-4945-7b70-9264-3593b30ea318'
            other_id = '01a1ef40-4945-7b70-9264-3593b30ea318'
            write_session(sessions, 'rollout-first.jsonl', first_id, project, 100)
            write_session(sessions, 'rollout-other.jsonl', other_id, other, 300)
            write_session(sessions, 'rollout-latest.jsonl', latest_id, project, 200)

            self.assertEqual(latest_id, LAUNCHER.latest_session_id(root / 'codex', project))

    def test_remembers_distinct_session_ids_per_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'codex'
            home = Path(directory) / 'home'
            project = Path(directory) / 'projects/Test'
            other = Path(directory) / 'projects/Other'
            test_id = '01a1ef3d-4945-7b70-9264-3593b30ea318'
            other_id = '01a1ef40-4945-7b70-9264-3593b30ea318'

            LAUNCHER.remember_session_id(root, project, test_id, home)
            LAUNCHER.remember_session_id(root, other, other_id, home)

            self.assertEqual(test_id, LAUNCHER.remembered_session_id(root, project, home))
            self.assertEqual(other_id, LAUNCHER.remembered_session_id(root, other, home))

    def test_resume_selection_seeds_from_latest_matching_project(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'codex'
            home = Path(directory) / 'home'
            project = Path(directory) / 'projects/Test'
            session_id = '01a1ef3d-4945-7b70-9264-3593b30ea318'
            write_session(root, 'rollout-test.jsonl', session_id, project, 200)

            self.assertEqual(session_id, LAUNCHER.session_id_for_resume(root, project, home))
            self.assertEqual(session_id, LAUNCHER.remembered_session_id(root, project, home))


if __name__ == '__main__':
    unittest.main()
