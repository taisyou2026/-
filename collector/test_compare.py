import unittest
from compare_snapshots import compare
class CompareTest(unittest.TestCase):
 def test_update_and_time_gap_are_recorded_not_automatic_failure(self):
  a=dict(raceKey='TEST_ONLY',milestone='T-20',result_status='SUCCESS',received_at='2026-11-14T00:20:00+00:00',raw_sha256='a'*64,runner_count=2,odds_count=2,completeness=1,runners=[dict(horse_no=1,odds=2),dict(horse_no=2,odds=3)])
  b=dict(a,received_at='2026-11-14T00:17:00+00:00',raw_sha256='b'*64,runners=[dict(horse_no=1,odds=2.1),dict(horse_no=2,odds=2.9)])
  q=compare({'attempts':[a]},[b]);s=q['stages'][0]
  self.assertEqual(s['status'],'SEPARATE_SNAPSHOTS');self.assertFalse(s['automatic_failure']);self.assertFalse(s['raw_sha_equal']);self.assertFalse(s['odds_equal'])
  q=compare({'attempts':[a]},[dict(b,raceKey='OTHER_TEST_ONLY')]);self.assertEqual(q['stages'][0]['status'],'PC_SNAPSHOT_UNAVAILABLE')
