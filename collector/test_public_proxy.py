"""Synthetic only. Virtual clock advances immediately; never waits in tests."""
import copy
import datetime as dt
import email.utils
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import public_proxy as p

MEETING = dict(date=p.DATE, venue_code='03', venue='福島', meeting=99, day=99)
TOKEN = 'pw01des01032026999920261114/AA'  # TEST_ONLY synthetic source identity
START = dt.datetime(2026, 11, 14, 0, 40, tzinfo=p.UTC)


def html(odds='2.0', date='2026年11月14日（土曜）99回福島99日', scratched=False):
    second = '<td class="horse">TEST_ONLY</td><td class="odds">3.0</td>'
    if scratched:
        second = '<td class="horse">TEST_ONLY</td><td>取消</td>'
    return f'''<li id="syutsuba_1R"><div class="date_line">{date} 発走時刻：9時40分</div>
    <div class="race_number"><img alt="1レース"></div><div class="course">ダート1,400メートル</div>
    <table><tr><td class="num">1</td><td class="horse">TEST_ONLY</td><td class="odds">{odds}</td></tr>
    <tr><td class="num">2</td>{second}</tr></table></li>'''


class FakeClock:
    def __init__(self, now):
        self.value, self.waits = now, []
    def now(self):
        return self.value
    def sleep(self, seconds):
        self.waits.append(seconds)
        self.value += dt.timedelta(seconds=seconds)


class CollectorTests(unittest.TestCase):
    def make(self, directory, fail_first=False, bad_odds=False, skew=False):
        clock = FakeClock(START - dt.timedelta(minutes=30))
        calls = []
        def fetch(path, cname):
            calls.append(clock.now())
            clock.value += dt.timedelta(milliseconds=50)
            body = html(odds='---' if bad_odds else '2.0').encode('cp932')
            status = 503 if fail_first and len(calls) == 1 else 200
            server = clock.now() - dt.timedelta(seconds=20 if skew else 0)
            return status, {'Date': email.utils.format_datetime(server, usegmt=True)}, body
        c = p.Collector(pathlib.Path(directory)/'report.json', clock, fetch, run_sha='TEST_ONLY')
        race = p.parse_catalog(html(), MEETING, TOKEN)[0]
        c.report['target_race'] = {k: v for k, v in race.items() if k != 'runners'}
        return c, clock, calls, race

    def test_all_four_same_race_and_fixed_targets(self):
        self.assertEqual(p.STAGES, [('T-20',1200,180),('T-10',600,120),('T-5',300,75),('T-2',120,45)])
        with tempfile.TemporaryDirectory() as d:
            c, clock, calls, race = self.make(d)
            for stage in p.STAGES:
                c.observe_stage(race, stage)
            self.assertTrue(c.report['four_milestone_complete'])
            self.assertEqual(c.report['FOUR_MILESTONE_COMPLETE'], 'PASS')
            c.observe_stage(race, p.STAGES[-1])
            self.assertEqual(len(calls), 4)
            with self.assertRaisesRegex(p.Invalid, 'OUTPUT_ALREADY_EXISTS'):
                p.Collector(c.output, clock)
            self.assertTrue(all(w <= 30 for w in clock.waits))
            self.assertTrue(all(r['target_offset_ms'] == 50 for r in c.report['attempts']))
            q = json.loads(c.output.read_text())
            self.assertEqual(q['REAL_prospective_count'], 0)
            for r in q['attempts']:
                for k, v in p.POLICY.items(): self.assertEqual(r[k], v)
                self.assertNotIn('TEST_ONLY', str(r['runners']))
            # Different race and a forged incomplete record cannot complete the summary.
            c.report['attempts'][-1]['raceKey'] = 'TEST_ONLY_OTHER_RACE'
            c.save(); self.assertFalse(c.report['four_milestone_complete'])
            c.report['attempts'][-1]['raceKey'] = race['raceKey']
            c.report['attempts'][-1]['clock_verified'] = False
            c.save(); self.assertFalse(c.report['four_milestone_complete'])

    def test_retry_spacing_failure_append_and_success_stops(self):
        with tempfile.TemporaryDirectory() as d:
            c, clock, calls, race = self.make(d, fail_first=True)
            c.observe_stage(race, p.STAGES[0])
            self.assertEqual(len(calls), 2)
            self.assertGreaterEqual((calls[1]-calls[0]).total_seconds(), 15)
            self.assertEqual([x['result_status'] for x in c.report['attempts']], ['FAILED', 'SUCCESS'])
            self.assertEqual(c.report['retries'], 1)

    def test_odds_and_clock_fail_closed_three_attempts(self):
        for kw, category in [({'bad_odds':True},'odds incomplete'),({'skew':True},'clock')]:
            with self.subTest(category=category), tempfile.TemporaryDirectory() as d:
                c, clock, calls, race = self.make(d, **kw)
                c.observe_stage(race, p.STAGES[0])
                self.assertEqual(len(calls), 3)
                self.assertEqual(c.report['miss_count'], 1)
                self.assertTrue(all(x['failure_category']==category for x in c.report['attempts']))
                self.assertFalse(c.report['four_milestone_complete'])

    def test_late_start_no_fake_attempt_and_parse_deadline(self):
        with tempfile.TemporaryDirectory() as d:
            c, clock, calls, race = self.make(d)
            clock.value = START-dt.timedelta(seconds=1200-181)
            c.started = clock.now()
            c.observe_stage(race, p.STAGES[0])
            self.assertEqual(calls, [])
            self.assertEqual(c.report['misses'][0]['failure_category'],'workflow late start')
        with tempfile.TemporaryDirectory() as d:
            c, clock, calls, race = self.make(d)
            def slow(path,cname):
                clock.value += dt.timedelta(seconds=181)
                return 200, {'Date':email.utils.format_datetime(clock.now(),usegmt=True)},html().encode('cp932')
            c.fetcher=slow
            c.observe_stage(race,p.STAGES[0])
            self.assertEqual(c.report['attempts'][0]['error_code'],'PARSED_OUTSIDE_TOLERANCE')
            self.assertFalse(c.report['four_milestone_complete'])

    def test_identity_roster_and_observed_links(self):
        with self.assertRaisesRegex(p.Invalid,'BODY_MEETING'):
            p.parse_catalog(html(date='2026年11月14日 99回東京99日'), MEETING, TOKEN)
        with self.assertRaisesRegex(p.Invalid,'DUPLICATE_OR_INVALID_RACE'):
            p.parse_catalog(html()+html(),MEETING,TOKEN)
        self.assertEqual(len(p.parse_catalog(html(scratched=True),MEETING,TOKEN)[0]['runners']),1)
        linked=f'<a href="/JRADB/accessD.html?CNAME={TOKEN}">x</a><a href="https://evil.example/JRADB/accessD.html?CNAME=BAD">x</a>'
        self.assertEqual(p.actions(linked,p.PATHS['card']),[TOKEN])

    def test_result_identity_payout_and_no_prediction(self):
        race=p.parse_catalog(html(),MEETING,TOKEN)[0]
        token='pw01sde1003202699990120261114/AA'
        result='''<div class="race_result_unit"><div class="date_line">2026年11月14日 99回福島99日</div><div class="race_number"><img alt="1レース"></div><table><tr><td class="num">1</td><td class="place">1</td></tr><tr><td class="num">2</td><td class="place">2</td></tr></table></div><li class="win"><div class="line"><div class="num">1</div><div class="yen">200円</div></div></li>'''
        q=p.parse_result(result,token,race)
        self.assertEqual(q['win_payout'],[{'horse_no':1,'payout_per_100':200}])
        with self.assertRaisesRegex(p.Invalid,'RESULT_TOKEN'):
            p.parse_result(result,token,dict(race,race_no=2))
        with self.assertRaisesRegex(p.Invalid,'WIN_PAYOUT'):
            p.parse_result(result.replace('200円','---'),token,race)

if __name__=='__main__': unittest.main()
