"""Read-only official public-page observation; no prediction, database or credentials.
Only a bounded JSON report is written. HTML is held in memory and discarded.
"""
import argparse
import datetime as dt
import email.utils
import hashlib
import json
import os
import pathlib
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

UTC = dt.timezone.utc
JST = dt.timezone(dt.timedelta(hours=9))
DATE = '2026-11-14'
VENUES = {'03': '福島', '05': '東京', '08': '京都'}
STAGES = [('T-20', 1200, 180), ('T-10', 600, 120), ('T-5', 300, 75), ('T-2', 120, 45)]
POLICY = dict(mode='VENUE_PROXY_DRY_RUN', data_status='READINESS_ONLY',
              venue_validation='VENUE_PROXY_ONLY', formal_cohort=False,
              formal_prediction=False, paper_stake=0, realMoney='BLOCKED')
ORIGIN = 'https://www.jra.go.jp'
PATHS = {'card': '/JRADB/accessD.html', 'result': '/JRADB/accessS.html'}


class Invalid(Exception):
    def __init__(self, code, category='parse'):
        super().__init__(code)
        self.code, self.category = code, category


def require(ok, code, category='parse'):
    if not ok:
        raise Invalid(code, category)


def stamp(value):
    return value.astimezone(UTC).isoformat(timespec='milliseconds')


def instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


class Element:
    def __init__(self, tag='', attrs=()):
        self.tag, self.attrs, self.children = tag, dict(attrs), []

    @property
    def text(self):
        return ''.join(x.text if isinstance(x, Element) else x for x in self.children)

    def all(self, tag=None, cls=None):
        for x in self.children:
            if isinstance(x, Element):
                if (tag is None or x.tag == tag) and (cls is None or cls in x.attrs.get('class', '').split()):
                    yield x
                yield from x.all(tag, cls)

    def first(self, tag=None, cls=None):
        return next(self.all(tag, cls), None)


class Document(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self, html):
        super().__init__(convert_charrefs=True)
        self.root = Element()
        self.stack = [self.root]
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        e = Element(tag, attrs)
        self.stack[-1].children.append(e)
        if tag not in self.VOID:
            self.stack.append(e)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        self.handle_endtag(tag)

    def handle_data(self, data):
        self.stack[-1].children.append(data)


def text(node):
    return re.sub(r'\s+', ' ', node.text).strip() if node else ''


def actions(html, path):
    """Follow only tokens actually present in an official page's links/actions."""
    out = []
    for e in Document(html).root.all():
        for route, token in re.findall(r"doAction\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]", e.attrs.get('onclick', '')):
            if route == path and token not in out:
                out.append(token)
        href = urllib.parse.urljoin(ORIGIN, e.attrs.get('href', ''))
        u = urllib.parse.urlparse(href)
        if u.scheme == 'https' and u.netloc == 'www.jra.go.jp' and u.path == path:
            for token in urllib.parse.parse_qs(u.query).get('CNAME', []):
                if token not in out:
                    out.append(token)
    return out


def identity(cname, kind):
    prefixes = {'meeting': r'pw01drl(?:00|10)', 'catalog': r'pw01des(?:01|10)',
                'result_meeting': r'pw01srl10', 'result': r'pw01sde(?:01|10)'}
    require(kind in prefixes, 'TOKEN_KIND', 'identity')
    pattern = prefixes[kind] + r'(\d{2})(\d{4})(\d{2})(\d{2})' + (r'(\d{2})' if kind == 'result' else '') + r'(\d{8})/[0-9A-Za-z]{2}'
    m = re.fullmatch(pattern, cname)
    require(m is not None, 'UNSUPPORTED_SOURCE_TOKEN', 'JRA source change')
    parts = list(m.groups())
    venue, year, meeting, day = parts[:4]
    require(venue in VENUES, 'VENUE_NOT_ALLOWED', 'identity')
    date = dt.datetime.strptime(parts[-1], '%Y%m%d').date().isoformat()
    require(date.startswith(year) and int(meeting) > 0 and int(day) > 0, 'TOKEN_DATE_MISMATCH', 'identity')
    out = dict(date=date, venue_code=venue, venue=VENUES[venue], meeting=int(meeting), day=int(day))
    if kind == 'result':
        out['race_no'] = int(parts[4])
        require(1 <= out['race_no'] <= 12, 'TOKEN_RACE_NUMBER', 'identity')
    return out


def same_meeting(a, b):
    return all(a.get(k) == b.get(k) for k in ['date', 'venue_code', 'meeting', 'day'])


def body_identity(unit, meeting):
    y, m, d = map(int, meeting['date'].split('-'))
    line = text(unit.first(cls='date_line'))
    require(re.search(rf'{y}年\s*{m}月\s*{d}日.*?{meeting["meeting"]}回{meeting["venue"]}{meeting["day"]}日', line), 'BODY_MEETING_MISMATCH', 'identity')


def number_cell(row, cls):
    s = text(row.first('td', cls))
    return int(s) if re.fullmatch(r'\d+', s) else None


def parse_catalog(html, meeting, cname):
    token = identity(cname, 'catalog')
    require(same_meeting(token, meeting), 'CATALOG_TOKEN_MISMATCH', 'identity')
    root = Document(html).root
    races, seen = [], set()
    for unit in root.all('li'):
        m = re.fullmatch(r'syutsuba_(\d{1,2})R', unit.attrs.get('id', ''))
        if not m:
            continue
        no = int(m[1])
        require(1 <= no <= 12 and no not in seen, 'DUPLICATE_OR_INVALID_RACE', 'identity')
        seen.add(no)
        body_identity(unit, meeting)
        image = unit.first(cls='race_number')
        image = image.first('img') if image else None
        require(image is not None and re.fullmatch(rf'{no}レース', image.attrs.get('alt', '')), 'BODY_RACE_NUMBER_MISMATCH', 'identity')
        start = re.search(r'発走時刻[：:]\s*(\d{1,2})時(\d{2})分', text(unit.first(cls='date_line')))
        require(start is not None, 'START_TIME_MISSING')
        h, minute = map(int, start.groups())
        require(h < 24 and minute < 60, 'START_TIME_INVALID')
        scheduled = dt.datetime.combine(dt.date.fromisoformat(meeting['date']), dt.time(h, minute), JST)
        course = text(unit.first(cls='course'))
        name = text(unit.first(cls='race_name'))
        require(bool(course), 'COURSE_MISSING')
        obstacle = bool(re.search('障害|ジャンプ', course + ' ' + name))
        runners, numbers = [], set()
        for row in unit.all('tr'):
            cell = row.first('td', 'num')
            if cell is None:
                continue
            n = number_cell(row, 'num')
            require(n is not None and 1 <= n <= 18 and n not in numbers, 'RUNNER_NUMBER_INVALID')
            numbers.add(n)
            if re.search('取消|除外', text(row)):
                continue
            require(row.first('td', 'horse') is not None, 'RUNNER_ROSTER_UNSUPPORTED')
            odds_text = text(row.first('td', 'odds')).replace(',', '')
            odds = float(odds_text) if re.fullmatch(r'[1-9]\d{0,3}\.\d', odds_text) else None
            runners.append(dict(horse_no=n, odds=odds))
        require(bool(numbers), 'ROSTER_EMPTY')
        base = {k: meeting[k] for k in ['date', 'venue_code', 'venue', 'meeting', 'day']}
        races.append(dict(**base, race_no=no, raceKey=f'{meeting["date"]}|{meeting["venue"]}|{no}',
                          scheduled_start=stamp(scheduled), obstacle=obstacle, runners=runners, source_cname=cname))
    require(bool(races), 'CATALOG_STRUCTURE_UNSUPPORTED', 'JRA source change')
    return sorted(races, key=lambda r: r['race_no'])


def parse_result(html, cname, race):
    token = identity(cname, 'result')
    require(same_meeting(token, race) and token['race_no'] == race['race_no'], 'RESULT_TOKEN_MISMATCH', 'identity')
    root = Document(html).root
    units = list(root.all(cls='race_result_unit'))
    require(len(units) == 1, 'RESULT_STRUCTURE_UNSUPPORTED', 'JRA source change')
    unit = units[0]
    body_identity(unit, race)
    n = unit.first(cls='race_number')
    image = n.first('img') if n else None
    require(image is not None and image.attrs.get('alt') == f'{race["race_no"]}レース', 'RESULT_BODY_RACE_MISMATCH', 'identity')
    finish, seen = [], set()
    for row in unit.all('tr'):
        n = number_cell(row, 'num')
        if n is None:
            continue
        require(1 <= n <= 18 and n not in seen, 'RESULT_ROSTER_INVALID')
        seen.add(n)
        place = number_cell(row, 'place')
        finish.append(dict(horse_no=n, finish_position=place, finish_status=text(row.first('td', 'place'))))
    require(any(r['finish_position'] == 1 for r in finish), 'RESULT_NOT_PUBLISHED')
    payouts = []
    for win in root.all('li', 'win'):
        for line in win.all(cls='line'):
            n, yen = text(line.first(cls='num')), text(line.first(cls='yen')).replace(',', '')
            if re.fullmatch(r'\d{1,2}', n) and re.fullmatch(r'\d+\s*円', yen):
                payouts.append(dict(horse_no=int(n), payout_per_100=int(re.search(r'\d+', yen)[0])))
    winners = {r['horse_no'] for r in finish if r['finish_position'] == 1}
    require(payouts and {x['horse_no'] for x in payouts} == winners, 'WIN_PAYOUT_MISSING_OR_MISMATCH', 'identity')
    return dict(raceKey=race['raceKey'], race_identity=token, official_finish=finish, win_payout=payouts)


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u = urllib.parse.urlparse(newurl)
        if u.scheme != 'https' or u.netloc != 'www.jra.go.jp' or u.path not in PATHS.values():
            raise Invalid('SOURCE_REDIRECT_REJECTED', 'HTTP')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Clock:
    def now(self):
        return dt.datetime.now(UTC)

    def sleep(self, seconds):
        time.sleep(seconds)


def accepted(record, race, stage):
    """Independent fail-closed summary gate; never combine different race keys."""
    try:
        name, before, tolerance = stage
        target = instant(race['scheduled_start']) - dt.timedelta(seconds=before)
        server = email.utils.parsedate_to_datetime(record['server_date'])
        skew = (instant(record['received_at']) - server).total_seconds() * 1000
        offset = (instant(record['parsed_at']) - target).total_seconds() * 1000
        runners = record['runners']
        return (record['result_status'] == 'SUCCESS' and record['raceKey'] == race['raceKey']
                and record['milestone'] == name and record['scheduled_start'] == race['scheduled_start']
                and instant(record['target_time']) == target and record['http_status'] == 200
                and re.fullmatch('[a-f0-9]{64}', record['raw_sha256']) is not None
                and record['race_identity_verified'] is True and record['clock_verified'] is True
                and server.tzinfo is not None and skew == record['clock_skew_ms'] and abs(skew) <= 10000
                and record['runner_count'] == record['odds_count'] == len(runners) >= 2
                and len({r['horse_no'] for r in runners}) == len(runners)
                and all(isinstance(r['odds'], (float, int)) and 1 <= r['odds'] < 10000 for r in runners)
                and record['completeness'] == 1.0 and abs(offset) <= tolerance * 1000
                and record['target_offset_ms'] == offset
                and instant(record['attempted_at']) <= instant(record['received_at']) <= instant(record['parsed_at'])
                and all(record.get(k) == v for k, v in POLICY.items()))
    except (KeyError, TypeError, ValueError):
        return False


def report_policy():
    return dict(**POLICY, REAL_prospective_count=0, PROSPECTIVE_READY=False,
                HANSHIN_REAL_TIMECHAIN='NOT_YET_VERIFIED')


class Collector:
    def __init__(self, output, clock=None, fetcher=None, run_sha=None):
        self.output, self.clock = pathlib.Path(output), clock or Clock()
        require(not self.output.exists(), 'OUTPUT_ALREADY_EXISTS_USE_NEW_PATH', 'process interruption')
        self.fetcher = fetcher
        self.started = self.clock.now()
        self.report = dict(**report_policy(), schema='PUBLIC-PROXY-TIMECHAIN-v1',
                           public_run_sha=run_sha or os.environ.get('GITHUB_SHA', 'LOCAL_UNCOMMITTED'),
                           started_at=stamp(self.started), target_date=DATE, target_race=None,
                           phase='INITIALIZING', source_attempts=[], attempts=[], misses=[], errors=[],
                           milestones={s[0]: dict(status='PENDING', **POLICY) for s in STAGES},
                           four_milestone_complete=False, FOUR_MILESTONE_COMPLETE='NOT_YET_COMPLETE',
                           proxy_status='NOT_YET_VERIFIED', result_path=dict(status='NOT_YET_MEASURED'))
        self.save()

    def save(self):
        records = self.report['attempts']
        race = self.report['target_race']
        successful = [x for x in records if race and any(accepted(x, race, s) for s in STAGES)]
        complete = bool(race) and all(any(x.get('raceKey') == race['raceKey'] and x.get('milestone') == s[0] for x in successful) for s in STAGES)
        self.report.update(updated_at=stamp(self.clock.now()), four_milestone_complete=complete,
                           FOUR_MILESTONE_COMPLETE='PASS' if complete else 'NOT_YET_COMPLETE',
                           proxy_status='REAL_SOURCE_PROXY_TIMECHAIN_VERIFIED' if complete else 'NOT_YET_VERIFIED',
                           raw_sha_availability=dict(successful=len(successful), with_sha=sum(bool(x.get('raw_sha256')) for x in successful), attempts_total=len(records), attempts_with_sha=sum(bool(x.get('raw_sha256')) for x in records)),
                           mean_processing_latency_ms=(sum(x['processing_latency_ms'] for x in successful) / len(successful) if successful else None),
                           retries=sum(x.get('retry_number', 0) > 0 for x in records), miss_count=len(self.report['misses']))
        self.output.parent.mkdir(parents=True, exist_ok=True)
        temp = self.output.with_suffix('.tmp')
        temp.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + '\n')
        temp.replace(self.output)

    def wait_until(self, target):
        while self.clock.now() < target:
            # Observation scheduling, never a retry intended to hide a test failure.
            self.clock.sleep(min(30, max(0, (target - self.clock.now()).total_seconds())))

    def request(self, path, cname, parser):
        require(path in PATHS.values() and re.fullmatch(r'pw01[a-zA-Z0-9]+/[a-zA-Z0-9]{2}', cname), 'REQUEST_NOT_ALLOWED', 'identity')
        rec = dict(**POLICY, source_url=ORIGIN + path, source_cname=cname,
                   attempted_at=stamp(self.clock.now()), received_at=None, parsed_at=None,
                   http_status=None, raw_sha256=None, server_date=None, clock_skew_ms=None, clock_verified=False,
                   processing_latency_ms=None, result_status='FAILED', error_code=None, failure_category=None)
        value = None
        try:
            if self.fetcher:
                status, headers, body = self.fetcher(path, cname)
            else:
                req = urllib.request.Request(ORIGIN + path, data=urllib.parse.urlencode({'CNAME': cname}).encode(), headers={
                    'User-Agent': 'Mozilla/5.0 PUBLIC-READINESS-ONLY-PROXY/1.0',
                    'Referer': ORIGIN + '/', 'Accept-Language': 'ja',
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'Cache-Control': 'no-cache', 'Pragma': 'no-cache'})
                with urllib.request.build_opener(OfficialRedirects()).open(req, timeout=20) as response:
                    status, headers = response.status, response.headers
                    body = response.read(2 * 1024 * 1024 + 1)
            rec.update(received_at=stamp(self.clock.now()), http_status=status,
                       raw_sha256=hashlib.sha256(body).hexdigest())
            require(status == 200, f'HTTP_{status}', 'HTTP')
            require(0 < len(body) <= 2 * 1024 * 1024, 'RESPONSE_SIZE_INVALID', 'HTTP')
            try:
                rec['server_date'] = headers.get('Date')
                server = email.utils.parsedate_to_datetime(rec['server_date'] or '')
                require(server.tzinfo is not None, 'CLOCK_DATE_MISSING', 'clock')
                rec['clock_skew_ms'] = (instant(rec['received_at']) - server).total_seconds() * 1000
                rec['clock_verified'] = abs(rec['clock_skew_ms']) <= 10000
            except (ValueError, TypeError):
                rec['clock_verified'] = False
            value = parser(body.decode('cp932', errors='strict'))
            rec['result_status'] = 'SUCCESS'
        except urllib.error.HTTPError as e:
            rec.update(http_status=e.code, received_at=stamp(self.clock.now()), error_code=f'HTTP_{e.code}', failure_category='HTTP')
        except Invalid as e:
            rec.update(error_code=e.code, failure_category=e.category)
        except (urllib.error.URLError, TimeoutError, OSError):
            rec.update(error_code='NETWORK_FAILURE', failure_category='network')
        except UnicodeError:
            rec.update(error_code='SOURCE_ENCODING_UNSUPPORTED', failure_category='JRA source change')
        except Exception:
            rec.update(error_code='UNEXPECTED_PARSER_FAILURE', failure_category='parse')
        rec['parsed_at'] = stamp(self.clock.now())
        if rec['received_at']:
            rec['processing_latency_ms'] = (instant(rec['parsed_at']) - instant(rec['received_at'])).total_seconds() * 1000
        return rec, value

    def source(self, path, cname, phase, parser):
        rec, value = self.request(path, cname, parser)
        rec['phase'] = phase
        self.report['source_attempts'].append(rec)
        self.save()
        return value if rec['result_status'] == 'SUCCESS' else None

    def discover_target(self):
        def meetings(html):
            out = []
            for token in actions(html, PATHS['card']):
                try:
                    m = identity(token, 'meeting')
                except (Invalid, ValueError):
                    continue
                if m['date'] == DATE:
                    out.append(dict(m, source_cname=token))
            require(out, 'TARGET_MEETINGS_NOT_OBSERVED', 'JRA source change')
            return out
        meetings = self.source(PATHS['card'], 'pw01dli00/F3', 'DISCOVERY', meetings)
        require(meetings, 'DISCOVERY_FAILED', 'network')
        races = []
        for meeting in meetings:
            def detail(html):
                for token in actions(html, PATHS['card']):
                    try:
                        if same_meeting(identity(token, 'catalog'), meeting):
                            return token
                    except (Invalid, ValueError):
                        continue
                raise Invalid('DETAIL_LINK_NOT_OBSERVED', 'JRA source change')
            cname = self.source(PATHS['card'], meeting['source_cname'], 'MEETING', detail)
            if cname:
                parsed = self.source(PATHS['card'], cname, 'CATALOG', lambda html: parse_catalog(html, meeting, cname))
                races.extend(parsed or [])
        candidates = [r for r in races if not r['obstacle'] and len(r['runners']) >= 2 and all(x['odds'] is not None for x in r['runners'])
                      and instant(r['scheduled_start']) - dt.timedelta(seconds=1200) > self.clock.now()]
        require(candidates, 'NO_FUTURE_COMPLETE_TARGET', 'workflow late start')
        race = min(candidates, key=lambda r: (r['scheduled_start'], r['venue_code'], r['race_no']))
        self.report['target_race'] = {k: v for k, v in race.items() if k != 'runners'}
        self.report['target_selection'] = dict(active_runner_count=len(race['runners']), odds_count=len(race['runners']),
                                                identity_verified=True, source='OBSERVED_OFFICIAL_CATALOG',
                                                recalculated_T20=stamp(instant(race['scheduled_start']) - dt.timedelta(seconds=1200)))
        self.save()
        return race

    def observe_stage(self, race, stage):
        name, before, tolerance = stage
        target = instant(race['scheduled_start']) - dt.timedelta(seconds=before)
        end = target + dt.timedelta(seconds=tolerance)
        prior = [x for x in self.report['attempts'] if x.get('raceKey') == race['raceKey'] and x.get('milestone') == name]
        if any(accepted(x, race, stage) for x in prior):
            return
        self.wait_until(target)
        if prior:
            self.wait_until(instant(prior[-1]['attempted_at']) + dt.timedelta(seconds=15))
        for retry in range(len(prior), 3):
            if self.clock.now() > end:
                break
            rec, races = self.request(PATHS['card'], race['source_cname'], lambda html: parse_catalog(html, race, race['source_cname']))
            rec.update(raceKey=race['raceKey'], venue=race['venue'], race_no=race['race_no'],
                       scheduled_start=race['scheduled_start'], milestone=name, target_time=stamp(target),
                       retry_number=retry, runner_count=None, odds_count=None, completeness=None,
                       race_identity_verified=False, runners=[])
            offset = (instant(rec['parsed_at']) - target).total_seconds() * 1000
            rec['target_offset_ms'] = offset
            if rec['result_status'] == 'SUCCESS':
                try:
                    matching = [r for r in races if same_meeting(r, race) and r['race_no'] == race['race_no'] and r['raceKey'] == race['raceKey']]
                    require(len(matching) == 1 and matching[0]['scheduled_start'] == race['scheduled_start'], 'RACE_IDENTITY_OR_START_CHANGED', 'identity')
                    roster = matching[0]['runners']
                    count = sum(x['odds'] is not None for x in roster)
                    rec.update(race_identity_verified=True, runners=roster, runner_count=len(roster), odds_count=count,
                               completeness=count / len(roster) if roster else 0)
                    require(len(roster) >= 2 and count == len(roster), 'ODDS_INCOMPLETE', 'odds incomplete')
                    require(rec['clock_verified'], 'CLOCK_UNVERIFIED', 'clock')
                    require(abs(offset) <= tolerance * 1000, 'PARSED_OUTSIDE_TOLERANCE', 'scheduler')
                    require(instant(rec['attempted_at']) <= instant(rec['received_at']) <= instant(rec['parsed_at']), 'CLOCK_SEQUENCE_INVALID', 'clock')
                except Invalid as e:
                    rec.update(result_status='FAILED', error_code=e.code, failure_category=e.category)
            self.report['attempts'].append(rec)
            self.save()
            if rec['result_status'] == 'SUCCESS':
                self.report['milestones'][name] = dict(**POLICY, status='SUCCESS', parsed_at=rec['parsed_at'],
                                                       target_offset_ms=offset, raw_sha256=rec['raw_sha256'])
                self.save()
                return
            next_try = instant(rec['attempted_at']) + dt.timedelta(seconds=15)
            if next_try > end:
                break
            self.wait_until(next_try)
        attempted = [x for x in self.report['attempts'] if x['milestone'] == name]
        category = attempted[-1]['failure_category'] if attempted else ('workflow late start' if self.started > end else 'scheduler')
        reason = attempted[-1]['error_code'] if attempted else 'NO_ATTEMPT_IN_WINDOW'
        miss = dict(**POLICY, raceKey=race['raceKey'], milestone=name, target_time=stamp(target),
                    recorded_at=stamp(self.clock.now()), failure_category=category, error_code=reason)
        self.report['misses'].append(miss)
        self.report['milestones'][name] = dict(**POLICY, status='FAILED', error_code=reason, failure_category=category)
        self.save()

    def result_path(self, race):
        self.wait_until(instant(race['scheduled_start']) + dt.timedelta(minutes=5))
        tries = []
        for retry in range(5):
            attempt = dict(**POLICY, attempted_at=stamp(self.clock.now()), retry_number=retry, status='FAILED')
            try:
                def meeting_link(html):
                    for token in actions(html, PATHS['result']):
                        try:
                            if same_meeting(identity(token, 'result_meeting'), race):
                                return token
                        except (Invalid, ValueError):
                            continue
                    raise Invalid('RESULT_MEETING_NOT_YET_OBSERVED', 'JRA source change')
                meeting = self.source(PATHS['result'], 'pw01sli00/AF', 'RESULT_MENU', meeting_link)
                require(meeting, 'RESULT_MEETING_UNAVAILABLE')
                def race_link(html):
                    for token in actions(html, PATHS['result']):
                        try:
                            r = identity(token, 'result')
                            if same_meeting(r, race) and r['race_no'] == race['race_no']:
                                return token
                        except (Invalid, ValueError):
                            continue
                    raise Invalid('RESULT_RACE_LINK_NOT_YET_OBSERVED', 'JRA source change')
                cname = self.source(PATHS['result'], meeting, 'RESULT_MEETING', race_link)
                require(cname, 'RESULT_LINK_UNAVAILABLE')
                rec, result = self.request(PATHS['result'], cname, lambda html: parse_result(html, cname, race))
                self.report['source_attempts'].append(dict(rec, phase='RESULT'))
                if rec['result_status'] != 'SUCCESS':
                    raise Invalid(rec['error_code'], rec['failure_category'])
                attempt.update(status='PASS', result_fetched_at=rec['received_at'], raw_sha256=rec['raw_sha256'], **result)
                tries.append(attempt)
                self.report['result_path'] = dict(**POLICY, status='PASS', PROXY_RESULT_PATH='PASS', attempts=tries)
                self.save()
                return
            except Invalid as e:
                attempt.update(error_code=e.code, failure_category=e.category)
            tries.append(attempt)
            self.report['result_path'] = dict(**POLICY, status='FAILED', attempts=tries)
            self.save()
            if retry < 4:
                self.wait_until(instant(attempt['attempted_at']) + dt.timedelta(seconds=60))

    def run(self, preflight=False):
        try:
            require(self.clock.now().astimezone(JST).date().isoformat() == DATE, 'OBSERVATION_DATE_NOT_CURRENT', 'scheduler')
            race = self.discover_target()
            if preflight:
                self.report['phase'] = 'PREFLIGHT_ONLY_NOT_TIMECHAIN'
                self.save()
                return 0
            require(instant(race['scheduled_start']) + dt.timedelta(minutes=10) <= self.started + dt.timedelta(minutes=110), 'WORKFLOW_STARTED_TOO_EARLY_FOR_JOB_BUDGET', 'scheduler')
            self.report['phase'] = 'OBSERVING_REAL_TIMECHAIN'
            self.save()
            for stage in STAGES:
                self.observe_stage(race, stage)
            self.result_path(race)
            self.report['phase'] = 'OBSERVATION_FINISHED'
        except Invalid as e:
            self.report['errors'].append(dict(error_code=e.code, failure_category=e.category, observed_at=stamp(self.clock.now())))
        except KeyboardInterrupt:
            self.report['errors'].append(dict(error_code='PROCESS_INTERRUPTED', failure_category='process interruption'))
        except Exception:
            self.report['errors'].append(dict(error_code='UNEXPECTED_COLLECTOR_FAILURE', failure_category='scheduler'))
        self.save()
        return 0 if self.report['four_milestone_complete'] else 1


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--output', default='proxy-timechain-report.json')
    ap.add_argument('--preflight', action='store_true')
    args = ap.parse_args()
    raise SystemExit(Collector(args.output).run(args.preflight))
