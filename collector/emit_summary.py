"""Mirror a small allowlisted validation notice for read-only PC cross-checking.
The full report artifact remains authoritative. No HTML or names are emitted.
"""
import json
import pathlib
from public_proxy import POLICY, STAGES, accepted


def compact(report):
    race = report.get('target_race')
    records = []
    for stage in STAGES:
        a = next((x for x in report.get('attempts', []) if race and accepted(x, race, stage)), None)
        if a is None:
            continue
        fields = ['raceKey','venue','race_no','scheduled_start','milestone','target_time','attempted_at','received_at','parsed_at','http_status','raw_sha256','server_date','clock_skew_ms','clock_verified','processing_latency_ms','target_offset_ms','runner_count','odds_count','completeness','retry_number','race_identity_verified','result_status']
        records.append(dict(**POLICY, **{k:a[k] for k in fields}, runners=[dict(horse_no=r['horse_no'],odds=r['odds']) for r in a['runners']]))
    target = {k:race[k] for k in ['raceKey','date','venue','venue_code','meeting','day','race_no','scheduled_start']} if race else None
    return dict(**POLICY, schema='PUBLIC-PROXY-CROSSCHECK-v1', public_run_sha=report['public_run_sha'],
                phase=report['phase'], target_race=target, four_milestone_complete=len(records)==4,
                attempts=records, result_path_status=report['result_path']['status'])


if __name__=='__main__':
    path=pathlib.Path('proxy-timechain-report.json')
    if path.exists():
        message=json.dumps(compact(json.loads(path.read_text())),ensure_ascii=True,separators=(',',':'))
        if len(message.encode())>60000: raise SystemExit('SUMMARY_TOO_LARGE')
        print('::notice title=PROXY_TIMECHAIN_SUMMARY::'+message.replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
