"""Compare public-only normalized PC observations with the public report.
No DB adapter, identities/names other than the public raceKey, or raw HTML.
"""
import argparse
import json
import pathlib
from public_proxy import instant, POLICY


def compare(public, pc):
    out = dict(**POLICY, status='NO_MATCHING_PC_MILESTONES',
               comparison_time_threshold_ms=30000, stages=[])
    for a in public.get('attempts', []):
        if a.get('result_status') != 'SUCCESS':
            continue
        matches = [b for b in pc if b.get('raceKey') == a['raceKey'] and b.get('milestone') == a['milestone']
                   and b.get('result_status') == 'SUCCESS']
        if not matches:
            out['stages'].append(dict(raceKey=a['raceKey'], milestone=a['milestone'], status='PC_SNAPSHOT_UNAVAILABLE'))
            continue
        b = min(matches, key=lambda x: abs((instant(x['received_at'])-instant(a['received_at'])).total_seconds()))
        gap = abs((instant(b['received_at'])-instant(a['received_at'])).total_seconds())*1000
        av, bv = ({r['horse_no']: r['odds'] for r in x['runners']} for x in [a, b])
        out['stages'].append(dict(raceKey=a['raceKey'], milestone=a['milestone'],
                                 public_received_at=a['received_at'], pc_received_at=b['received_at'],
                                 timestamp_difference_ms=gap, public_raw_sha256=a['raw_sha256'], pc_raw_sha256=b['raw_sha256'],
                                 raw_sha_equal=a['raw_sha256']==b['raw_sha256'],
                                 public_runner_count=a['runner_count'], pc_runner_count=b['runner_count'],
                                 public_odds_count=a['odds_count'], pc_odds_count=b['odds_count'],
                                 public_completeness=a['completeness'], pc_completeness=b['completeness'],
                                 odds_equal=av==bv, public_odds=a['runners'], pc_odds=b['runners'],
                                 status='SEPARATE_SNAPSHOTS' if gap>30000 else 'NEAR_TIME_SNAPSHOTS',
                                 automatic_failure=False))
    if any(x['status']!='PC_SNAPSHOT_UNAVAILABLE' for x in out['stages']):
        out['status']='COMPARISON_RECORDED_NOT_AN_IDENTITY_OR_PROFITABILITY_GATE'
    return out


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('public');ap.add_argument('pc');ap.add_argument('--output',required=True);a=ap.parse_args()
    out=compare(json.loads(pathlib.Path(a.public).read_text()),json.loads(pathlib.Path(a.pc).read_text()))
    pathlib.Path(a.output).write_text(json.dumps(out,indent=2,ensure_ascii=False)+'\n')
