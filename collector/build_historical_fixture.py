"""Build bounded structured historical JRA fixtures from official public pages.

No raw HTML is persisted. Facts are parsed from the official catalog/result DOM,
with source CNAME, retrieval timestamp, and raw SHA-256 receipts retained.
Historical replay is explicitly non-prospective and cannot certify live TIMECHAIN.
"""
import datetime as dt
import json
import pathlib
import re
import time
from collections import Counter, defaultdict

from public_proxy import (
    Collector, DATE, JST, ORIGIN, PATHS, actions, identity, same_meeting,
    text, Document, stamp, require, Invalid
)

OUT = pathlib.Path("jra-real-historical-fixture-20260919-20261114.json")
SUMMARY = pathlib.Path("jra-real-historical-fixture-summary.json")
TARGET_MIN_RACES = 20
TARGET_MAX_RACES = 25
TARGET_MIN_RUNNERS = 250
TARGET_MAX_RUNNERS = 350

SUPPLEMENTAL_CARDS = [
    dict(date="2026-09-19", venue_code="09", venue="阪神", meeting=4, day=5, race_no=1,
         card_cname="pw01dde1009202604050120260919/19"),
    dict(date="2026-09-19", venue_code="09", venue="阪神", meeting=4, day=5, race_no=4,
         card_cname="pw01dde0109202604050420260919/59"),
]




def historical_request(c, path, cname, parser):
    attempts = []
    last_rec, last_value = None, None
    for attempt in range(4):
        time.sleep(0.6 if attempt == 0 else [2, 5, 10][attempt - 1])
        rec, value = c.request(path, cname, parser)
        attempts.append(dict(
            attempt=attempt + 1,
            http_status=rec.get("http_status"),
            result_status=rec.get("result_status"),
            error_code=rec.get("error_code"),
            failure_category=rec.get("failure_category"),
        ))
        last_rec, last_value = rec, value
        if rec.get("result_status") == "SUCCESS":
            rec["historical_retry_count"] = attempt
            rec["historical_attempts"] = attempts
            return rec, value
        transient = rec.get("http_status") in {429, 503} or rec.get("failure_category") == "network"
        if not transient:
            break
    if last_rec is not None:
        last_rec["historical_retry_count"] = max(0, len(attempts) - 1)
        last_rec["historical_attempts"] = attempts
    return last_rec, last_value

def img_alt(node):
    if node is None:
        return ""
    img = node.first("img")
    return (img.attrs.get("alt", "") if img else "").strip()


def frame_no(row):
    alt = img_alt(row.first("td", "waku"))
    m = re.search(r"枠([1-8])", alt)
    return int(m.group(1)) if m else None


def parse_float_cell(row, cls):
    s = text(row.first("td", cls)).replace("kg", "").strip()
    return float(s) if re.fullmatch(r"\d+(?:\.\d+)?", s) else None


def grade_from(unit):
    alts = []
    for e in unit.all("img"):
        a = e.attrs.get("alt", "").strip()
        if a:
            alts.append(a)
    joined = " ".join(alts + [text(unit.first(cls="race_title"))]).replace("･", "・")
    for label in ["J・GⅠ", "J・GⅡ", "J・GⅢ", "GⅠ", "GⅡ", "GⅢ", "リステッド"]:
        if label in joined:
            return label
    return "NONE_OBSERVED"


def parse_course_and_condition(unit):
    title = text(unit.first(cls="race_title"))
    name = text(unit.first(cls="race_name")) or "UNKNOWN"
    course = text(unit.first(cls="course"))
    if not course:
        m = re.search(r"コース[：:]\s*([^本]+?)(?:本賞金|$)", title)
        course = m.group(1).strip() if m else ""
    m = re.search(r"([0-9,]+)メートル（([^）]+)）", course or title)
    distance = int(m.group(1).replace(",", "")) if m else None
    course_detail = m.group(2) if m else "PARSER_UNVERIFIED"
    condition = title
    if name != "UNKNOWN" and condition.startswith(name):
        condition = condition[len(name):].strip()
    condition = re.sub(r"コース[：:].*$", "", condition).strip()
    obstacle = bool(re.search(r"障害|ジャンプ", title + " " + name))
    if obstacle:
        surface = "OBSTACLE_TURF" if "芝" in course_detail else "OBSTACLE_OTHER"
    elif "ダート" in course_detail:
        surface = "DIRT"
    elif "芝" in course_detail:
        surface = "TURF"
    else:
        surface = "UNKNOWN"
    age = "UNKNOWN"
    am = re.search(r"(障害)?(3歳以上|4歳以上|2歳|3歳)", condition)
    if am:
        age = (am.group(1) or "") + am.group(2)
    class_name = "UNKNOWN"
    for label in ["新馬", "未勝利", "1勝クラス", "2勝クラス", "3勝クラス", "オープン"]:
        if label in condition:
            class_name = label
            break
    sex = "FEMALE_ONLY" if re.search(r"(?:^|\s)牝(?:\s|\[|［|（|$)", condition) else "NONE_OBSERVED"
    return dict(
        race_name=name,
        condition_text=condition or "PARSER_UNVERIFIED",
        age_condition=age,
        class_name=class_name,
        sex_restriction=sex,
        grade=grade_from(unit),
        surface=surface,
        course_text=course or "PARSER_UNVERIFIED",
        distance_m=distance,
        obstacle=obstacle,
    )


def parse_catalog_full(html, meeting, cname):
    token = identity(cname, "catalog")
    require(same_meeting(token, meeting), "CATALOG_TOKEN_MISMATCH", "identity")
    root = Document(html).root
    races = []
    for unit in root.all("li"):
        m = re.fullmatch(r"syutsuba_(\d{1,2})R", unit.attrs.get("id", ""))
        if not m:
            continue
        race_no = int(m.group(1))
        header = text(unit.first(cls="race_header"))
        sm = re.search(r"発走時刻[：:]\s*(\d{1,2})時(\d{2})分", header)
        require(sm is not None, "START_TIME_MISSING")
        hour, minute = map(int, sm.groups())
        scheduled = dt.datetime.combine(dt.date.fromisoformat(meeting["date"]), dt.time(hour, minute), JST)
        meta = parse_course_and_condition(unit)
        runners = []
        seen = set()
        for row in unit.all("tr"):
            ntext = text(row.first("td", "num"))
            if not re.fullmatch(r"\d+", ntext):
                continue
            n = int(ntext)
            if n in seen:
                continue
            seen.add(n)
            horse = text(row.first("td", "horse"))
            status_text = text(row)
            scratched = bool(re.search(r"取消|除外", status_text))
            age = text(row.first("td", "age"))
            carried = parse_float_cell(row, "weight")
            odds_text = text(row.first("td", "odds")).replace(",", "")
            odds = float(odds_text) if re.fullmatch(r"[1-9]\d{0,3}\.\d", odds_text) else None
            runners.append(dict(
                frame=frame_no(row),
                horse_no=n,
                horse_name=horse or "PARSER_UNVERIFIED",
                sex_age=age or "PARSER_UNVERIFIED",
                carried_weight_kg=carried,
                final_win_odds=odds,
                scratched=scratched,
            ))
        require(runners, "ROSTER_EMPTY")
        race_id = f'{meeting["date"][:4]}{meeting["venue_code"]}{meeting["meeting"]:02}{meeting["day"]:02}{race_no:02}'
        races.append(dict(
            race_id=race_id,
            date=meeting["date"],
            venue_code=meeting["venue_code"],
            venue=meeting["venue"],
            meeting=meeting["meeting"],
            day=meeting["day"],
            race_no=race_no,
            scheduled_start=stamp(scheduled),
            source_card_cname=cname,
            runners=runners,
            **meta,
        ))
    require(len(races) == 12, "CATALOG_RACE_COUNT_UNEXPECTED", "JRA source change")
    result_hints = {}
    for token2 in actions(html, PATHS["result"]):
        try:
            rid = identity(token2, "result")
            if same_meeting(rid, meeting):
                result_hints[rid["race_no"]] = token2
        except Exception:
            pass
    for race in races:
        race["source_result_cname_hint"] = result_hints.get(race["race_no"])
    return sorted(races, key=lambda x: x["race_no"])



def parse_detail_card_full(html, spec, cname):
    root = Document(html).root
    page_text = text(root)
    y, m, d = map(int, spec["date"].split("-"))
    require(
        re.search(rf"{y}年\s*{m}月\s*{d}日.*?{spec['meeting']}回{spec['venue']}{spec['day']}日", page_text),
        "DETAIL_BODY_MEETING_MISMATCH", "identity"
    )
    tm = re.fullmatch(r"pw01dde(?:01|10)(\d{2})(\d{4})(\d{2})(\d{2})(\d{2})(\d{8})/[0-9A-Za-z]{2}", cname)
    require(tm is not None, "DETAIL_TOKEN_UNSUPPORTED", "identity")
    venue_code, year, meeting_no, day_no, race_no, token_date = tm.groups()
    require(
        venue_code == spec["venue_code"]
        and year == spec["date"][:4]
        and int(meeting_no) == spec["meeting"]
        and int(day_no) == spec["day"]
        and int(race_no) == spec["race_no"]
        and dt.datetime.strptime(token_date, "%Y%m%d").date().isoformat() == spec["date"],
        "DETAIL_RACE_NUMBER_MISMATCH", "identity"
    )
    date_line = text(root.first(cls="date_line"))
    sm = None
    for source_text in [date_line, page_text]:
        if not source_text:
            continue
        sm = re.search(r"発走時刻\s*[：:]?\s*(\d{1,2})時\s*(\d{2})分", source_text)
        if sm:
            break
    if sm is None and date_line:
        sm = re.search(r"(\d{1,2})時\s*(\d{2})分", date_line)
    require(sm is not None, "START_TIME_MISSING")
    hour, minute = map(int, sm.groups())
    scheduled = dt.datetime.combine(dt.date.fromisoformat(spec["date"]), dt.time(hour, minute), JST)
    meta = parse_course_and_condition(root)

    roster_table = None
    for table in root.all("table"):
        headers = {text(x) for x in table.all("th")}
        if {"馬番", "馬名", "性齢", "負担重量", "単勝オッズ"}.issubset(headers):
            roster_table = table
            break
    require(roster_table is not None, "DETAIL_ROSTER_TABLE_MISSING", "JRA source change")

    runners, seen = [], set()
    for row in roster_table.all("tr"):
        ntext = text(row.first("td", "num"))
        if not re.fullmatch(r"\d+", ntext):
            continue
        n = int(ntext)
        if n in seen:
            continue
        seen.add(n)
        horse = text(row.first("td", "horse"))
        status_text = text(row)
        scratched = bool(re.search(r"取消|除外", status_text))
        odds_text = text(row.first("td", "odds")).replace(",", "")
        odds = float(odds_text) if re.fullmatch(r"[1-9]\d{0,3}\.\d", odds_text) else None
        runners.append(dict(
            frame=frame_no(row),
            horse_no=n,
            horse_name=horse or "PARSER_UNVERIFIED",
            sex_age=text(row.first("td", "age")) or "PARSER_UNVERIFIED",
            carried_weight_kg=parse_float_cell(row, "weight"),
            final_win_odds=odds,
            scratched=scratched,
        ))
    require(runners, "ROSTER_EMPTY")

    result_hint = None
    for token in actions(html, PATHS["result"]):
        try:
            rid = identity(token, "result")
            if (
                rid["date"] == spec["date"]
                and rid["venue_code"] == spec["venue_code"]
                and rid["meeting"] == spec["meeting"]
                and rid["day"] == spec["day"]
                and rid["race_no"] == spec["race_no"]
            ):
                result_hint = token
                break
        except Exception:
            pass
    require(result_hint is not None, "DETAIL_RESULT_LINK_MISSING", "JRA source change")
    race_id = f'{spec["date"][:4]}{spec["venue_code"]}{spec["meeting"]:02}{spec["day"]:02}{spec["race_no"]:02}'
    return dict(
        race_id=race_id,
        date=spec["date"],
        venue_code=spec["venue_code"],
        venue=spec["venue"],
        meeting=spec["meeting"],
        day=spec["day"],
        race_no=spec["race_no"],
        scheduled_start=stamp(scheduled),
        source_card_cname=cname,
        source_result_cname_hint=result_hint,
        runners=runners,
        **meta,
    )

def parse_result_full(html, cname, race):
    token = identity(cname, "result")
    require(same_meeting(token, race) and token["race_no"] == race["race_no"], "RESULT_TOKEN_MISMATCH", "identity")
    root = Document(html).root
    units = list(root.all(cls="race_result_unit"))
    require(len(units) == 1, "RESULT_STRUCTURE_UNSUPPORTED", "JRA source change")
    unit = units[0]
    finish = {}
    for row in unit.all("tr"):
        ntext = text(row.first("td", "num"))
        if not re.fullmatch(r"\d+", ntext):
            continue
        n = int(ntext)
        place_text = text(row.first("td", "place"))
        place = int(place_text) if re.fullmatch(r"\d+", place_text) else None
        pop_text = text(row.first("td", "pop"))
        popularity = int(pop_text) if re.fullmatch(r"\d+", pop_text) else None
        finish[n] = dict(
            frame=frame_no(row),
            horse_no=n,
            horse_name=text(row.first("td", "horse")) or "PARSER_UNVERIFIED",
            sex_age=text(row.first("td", "age")) or "PARSER_UNVERIFIED",
            carried_weight_kg=parse_float_cell(row, "weight"),
            finish_position=place,
            finish_status=place_text or "UNKNOWN",
            popularity=popularity,
        )
    require(any(x["finish_position"] == 1 for x in finish.values()), "RESULT_NOT_PUBLISHED")
    payouts = []
    for win in root.all("li", "win"):
        for line in win.all(cls="line"):
            n, yen = text(line.first(cls="num")), text(line.first(cls="yen")).replace(",", "")
            if re.fullmatch(r"\d{1,2}", n) and re.fullmatch(r"\d+\s*円", yen):
                payouts.append(dict(horse_no=int(n), payout_per_100_yen=int(re.search(r"\d+", yen).group(0))))
    require(payouts, "WIN_PAYOUT_MISSING")
    return dict(finish=finish, win_payout=payouts)


def discover_meetings(c):
    def parser(html):
        found = []
        for token in actions(html, PATHS["card"]):
            try:
                m = identity(token, "meeting")
            except Exception:
                continue
            if m["date"] == DATE:
                found.append(dict(m, source_cname=token))
        return found
    rec, meetings = historical_request(c,PATHS["card"], "pw01dli00/F3", parser)
    require(rec["result_status"] == "SUCCESS" and meetings, "MEETING_DISCOVERY_FAILED", "network")
    return sorted(meetings, key=lambda x: x["venue_code"])


def catalog_for(c, meeting):
    def find_catalog(html):
        for token in actions(html, PATHS["card"]):
            try:
                if same_meeting(identity(token, "catalog"), meeting):
                    return token
            except Exception:
                pass
        raise Invalid("CATALOG_LINK_NOT_OBSERVED", "JRA source change")
    rec1, cname = historical_request(c,PATHS["card"], meeting["source_cname"], find_catalog)
    require(rec1["result_status"] == "SUCCESS" and cname, "CATALOG_LINK_FAILED")
    rec2, races = historical_request(c,PATHS["card"], cname, lambda html: parse_catalog_full(html, meeting, cname))
    require(rec2["result_status"] == "SUCCESS", rec2.get("error_code") or "CATALOG_FETCH_FAILED")
    return races, rec2


def result_tokens_for(c, meeting):
    def find_meeting(html):
        for token in actions(html, PATHS["result"]):
            try:
                if same_meeting(identity(token, "result_meeting"), meeting):
                    return token
            except Exception:
                pass
        raise Invalid("RESULT_MEETING_NOT_OBSERVED", "JRA source change")
    rec1, meeting_cname = historical_request(c,PATHS["result"], "pw01sli00/AF", find_meeting)
    require(rec1["result_status"] == "SUCCESS" and meeting_cname, "RESULT_MEETING_FAILED")

    def find_races(html):
        out = {}
        for token in actions(html, PATHS["result"]):
            try:
                r = identity(token, "result")
            except Exception:
                continue
            if same_meeting(r, meeting):
                out[r["race_no"]] = token
        return out
    rec2, out = historical_request(c,PATHS["result"], meeting_cname, find_races)
    require(rec2["result_status"] == "SUCCESS" and out, "RESULT_LINKS_MISSING")
    return out


def distance_bucket(r):
    d = r.get("distance_m")
    if d is None:
        return "UNKNOWN"
    if d <= 1400:
        return "SHORT"
    if d <= 1800:
        return "MILE"
    if d <= 2200:
        return "MIDDLE"
    return "LONG"


def select_races(all_races):
    mandatory = set()
    def add_first(pred):
        for r in all_races:
            if pred(r):
                mandatory.add(r["race_id"])
                return
        raise RuntimeError("MANDATORY_COVERAGE_MISSING")
    add_first(lambda r: r["obstacle"])
    add_first(lambda r: r["sex_restriction"] == "FEMALE_ONLY")
    add_first(lambda r: r["grade"] in {"GⅠ","GⅡ","GⅢ","J・GⅠ","J・GⅡ","J・GⅢ"})
    for bucket in ["SHORT", "MILE", "MIDDLE", "LONG"]:
        add_first(lambda r, b=bucket: distance_bucket(r) == b)
    add_first(lambda r: r["surface"] == "DIRT")
    add_first(lambda r: r["surface"] == "TURF")

    selected = [r for r in all_races if r["race_id"] in mandatory]
    selected_ids = {r["race_id"] for r in selected}
    by_venue = defaultdict(list)
    for r in all_races:
        by_venue[r["venue_code"]].append(r)
    venues = sorted(by_venue)

    for race_no in range(1, 13):
        for venue in venues:
            for r in by_venue[venue]:
                if r["race_no"] == race_no and r["race_id"] not in selected_ids:
                    selected.append(r)
                    selected_ids.add(r["race_id"])
                    break
            if len(selected) >= TARGET_MIN_RACES:
                runners = sum(len([x for x in r["runners"] if not x["scratched"]]) for r in selected)
                if runners >= TARGET_MIN_RUNNERS:
                    break
        if len(selected) >= TARGET_MIN_RACES:
            runners = sum(len([x for x in r["runners"] if not x["scratched"]]) for r in selected)
            if runners >= TARGET_MIN_RUNNERS:
                break

    if len(selected) < TARGET_MIN_RACES or len(selected) > TARGET_MAX_RACES:
        raise RuntimeError(f"RACE_COUNT_TARGET_MISS:{len(selected)}")
    runners = sum(len([x for x in r["runners"] if not x["scratched"]]) for r in selected)
    if runners < TARGET_MIN_RUNNERS:
        for r in all_races:
            if r["race_id"] in selected_ids:
                continue
            if len(selected) >= TARGET_MAX_RACES:
                break
            selected.append(r)
            selected_ids.add(r["race_id"])
            runners += len([x for x in r["runners"] if not x["scratched"]])
            if runners >= TARGET_MIN_RUNNERS:
                break
    if not (TARGET_MIN_RUNNERS <= runners <= TARGET_MAX_RUNNERS):
        raise RuntimeError(f"RUNNER_COUNT_TARGET_MISS:{runners}")
    return sorted(selected, key=lambda r: (r["venue_code"], r["race_no"]))



def complete_race(c, race, cname, card_rec):
    require(bool(cname), "RESULT_LINK_MISSING_FOR_SELECTED_RACE", "JRA source change")
    rec, parsed = historical_request(c,PATHS["result"], cname, lambda html, cn=cname, rr=race: parse_result_full(html, cn, rr))
    require(rec["result_status"] == "SUCCESS", rec.get("error_code") or "RESULT_FETCH_FAILED")
    card_map = {x["horse_no"]: x for x in race["runners"]}
    result_map = parsed["finish"]
    merged = []
    for n in sorted(set(card_map) | set(result_map)):
        card = card_map.get(n, {})
        res = result_map.get(n, {})
        merged.append(dict(
            frame=res.get("frame") if res.get("frame") is not None else card.get("frame"),
            horse_no=n,
            horse_name=res.get("horse_name") or card.get("horse_name") or "PARSER_UNVERIFIED",
            sex_age=res.get("sex_age") or card.get("sex_age") or "PARSER_UNVERIFIED",
            carried_weight_kg=res.get("carried_weight_kg") if res.get("carried_weight_kg") is not None else card.get("carried_weight_kg"),
            final_win_odds=card.get("final_win_odds"),
            popularity=res.get("popularity"),
            finish_position=res.get("finish_position"),
            finish_status=res.get("finish_status", "SOURCE_MISSING"),
            scratched=card.get("scratched", False),
        ))
    active = [x for x in merged if not x["scratched"]]
    require(active and all(x["horse_name"] != "PARSER_UNVERIFIED" for x in active), "HORSE_NAME_INCOMPLETE")
    require(all(x["final_win_odds"] is not None for x in active), "FINAL_ODDS_INCOMPLETE")
    require(all(x["popularity"] is not None for x in active if x["finish_status"] not in {"取消","除外"}), "POPULARITY_INCOMPLETE")
    require(all(x["frame"] is not None for x in active), "FRAME_INCOMPLETE")
    return dict(
        schema="JRA_REAL_HISTORICAL_FIXTURE-v2",
        REAL_PROSPECTIVE=False,
        data_status="HISTORICAL_REPLAY_ONLY",
        realMoney="BLOCKED",
        race_id=race["race_id"],
        date=race["date"],
        venue_code=race["venue_code"],
        venue=race["venue"],
        meeting=race["meeting"],
        day=race["day"],
        race_no=race["race_no"],
        scheduled_start=race["scheduled_start"],
        race_name=race["race_name"],
        condition_text=race["condition_text"],
        age_condition=race["age_condition"],
        class_name=race["class_name"],
        sex_restriction=race["sex_restriction"],
        grade=race["grade"],
        surface=race["surface"],
        course_text=race["course_text"],
        distance_m=race["distance_m"],
        distance_bucket=distance_bucket(race),
        obstacle=race["obstacle"],
        runners=merged,
        win_payout=parsed["win_payout"],
        source=dict(
            origin=ORIGIN,
            card_path=PATHS["card"],
            card_cname=race["source_card_cname"],
            card_retrieved_at=card_rec["received_at"],
            card_raw_sha256=card_rec["raw_sha256"],
            result_path=PATHS["result"],
            result_cname=cname,
            result_retrieved_at=rec["received_at"],
            result_raw_sha256=rec["raw_sha256"],
        ),
    )


def main():
    for p in [OUT, SUMMARY, pathlib.Path("historical-fixture-state.json")]:
        if p.exists():
            p.unlink()
    c = Collector("historical-fixture-state.json")
    meetings = discover_meetings(c)
    all_races = []
    catalog_receipts = {}
    result_links = {}
    for meeting in meetings:
        races, rec = catalog_for(c, meeting)
        all_races.extend(races)
        catalog_receipts[meeting["venue_code"]] = rec
        result_links[meeting["venue_code"]] = result_tokens_for(c, meeting)

    selected = select_races(all_races)
    completed = []
    for race in selected:
        cname = race.get("source_result_cname_hint") or result_links[race["venue_code"]].get(race["race_no"])
        card_rec = catalog_receipts[race["venue_code"]]
        completed.append(complete_race(c, race, cname, card_rec))

    for spec in SUPPLEMENTAL_CARDS:
        rec_card, race = historical_request(c,
            PATHS["card"], spec["card_cname"],
            lambda html, sp=spec, cn=spec["card_cname"]: parse_detail_card_full(html, sp, cn)
        )
        require(rec_card["result_status"] == "SUCCESS", rec_card.get("error_code") or "SUPPLEMENTAL_CARD_FAILED")
        completed.append(complete_race(c, race, race["source_result_cname_hint"], rec_card))

    runner_count = sum(sum(not x["scratched"] for x in r["runners"]) for r in completed)
    summary = dict(
        schema="JRA_REAL_HISTORICAL_FIXTURE_SUMMARY-v2",
        dates=sorted({r["date"] for r in completed}),
        REAL_PROSPECTIVE=False,
        PROSPECTIVE_SUCCESS_CREDIT=0,
        realMoney="BLOCKED",
        race_count=len(completed),
        active_runner_count=runner_count,
        venues=dict(Counter(r["venue"] for r in completed)),
        surfaces=dict(Counter(r["surface"] for r in completed)),
        distance_buckets=dict(Counter(r["distance_bucket"] for r in completed)),
        age_conditions=dict(Counter(r["age_condition"] for r in completed)),
        sex_restrictions=dict(Counter(r["sex_restriction"] for r in completed)),
        grades=dict(Counter(r["grade"] for r in completed)),
        obstacle_races=sum(r["obstacle"] for r in completed),
        hanshin_flat_races=sum(r["venue"] == "阪神" and not r["obstacle"] for r in completed),
        hanshin_obstacle_races=sum(r["venue"] == "阪神" and r["obstacle"] for r in completed),
        parser_unknowns={
            "distance": sum(r["distance_m"] is None for r in completed),
            "class_name": sum(r["class_name"] == "UNKNOWN" for r in completed),
            "age_condition": sum(r["age_condition"] == "UNKNOWN" for r in completed),
        },
        race_ids=[r["race_id"] for r in completed],
    )
    require(TARGET_MIN_RACES <= summary["race_count"] <= TARGET_MAX_RACES, "FIXTURE_RACE_COUNT")
    require(TARGET_MIN_RUNNERS <= runner_count <= TARGET_MAX_RUNNERS, "FIXTURE_RUNNER_COUNT")
    require(len(summary["venues"]) >= 2, "FIXTURE_MULTI_VENUE")
    require(summary["surfaces"].get("TURF", 0) > 0 and summary["surfaces"].get("DIRT", 0) > 0, "FIXTURE_SURFACE_COVERAGE")
    require(summary["obstacle_races"] > 0, "FIXTURE_OBSTACLE_MISSING")
    require(summary["hanshin_flat_races"] > 0, "FIXTURE_HANSHIN_FLAT_MISSING")
    require(summary["hanshin_obstacle_races"] > 0, "FIXTURE_HANSHIN_OBSTACLE_MISSING")
    require(summary["sex_restrictions"].get("FEMALE_ONLY", 0) > 0, "FIXTURE_FEMALE_ONLY_MISSING")
    require(any(k != "NONE_OBSERVED" and v > 0 for k, v in summary["grades"].items()), "FIXTURE_GRADE_MISSING")
    require(all(summary["distance_buckets"].get(k, 0) > 0 for k in ["SHORT","MILE","MIDDLE","LONG"]), "FIXTURE_DISTANCE_COVERAGE")
    require(all(v == 0 for v in summary["parser_unknowns"].values()), "PARSER_UNVERIFIED_REQUIRED_FIELD")

    OUT.write_text(json.dumps({"summary": summary, "races": completed}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
