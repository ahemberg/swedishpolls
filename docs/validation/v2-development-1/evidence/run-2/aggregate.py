"""Rebuild run 2's original-reason accounting from retained evidence, without fitting."""
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
INVENTORY = Path('docs/validation/remediation-inventory.json')
inventory = json.loads(INVENTORY.read_text())
load = lambda name: json.loads((ROOT / f'{name}.json').read_text())
tuning = load('tuning')
diagnostics = load('diagnostics')
estimation = load('estimation')
measurement = load('measurement')
comparison = load('cross-architecture')
periods = {p['periodId']: p for p in diagnostics['diagnostics']}
all_reasons = list(dict.fromkeys(
    diagnostics['reasons'] + estimation['reasons'] + measurement['reasons'] + comparison['reasons']
))
reasons = []
for original in inventory['reasons']:
    row = dict(original)
    group = row['evidence_group'] or ''
    row['propagation'] = inventory['evidence_groups'].get(group, {}).get('dependent_checks', [])
    evidence = ['diagnostics.json']
    detail = {}
    if row['kind'] == 'aggregate_wrapper':
        disposition = 'evaluated_fail' if all_reasons else 'evaluated_pass'
        detail = {'remainingReasons': all_reasons}
        evidence = ['tuning.json', 'estimation.json', 'diagnostics.json', 'measurement.json', 'cross-architecture.json']
    elif group in ('fi-no-training', 'fi-no-heldout'):
        disposition = 'inactive'
        detail = {'note': 'The registered inactive FI entries retain their reviewed reasons and are not passes.'}
        evidence = ['tuning.json']
    elif group == 'candidate-grid' or group.startswith('reference-grid:'):
        candidate = group == 'candidate-grid'
        method = 'midpoint_candidate' if candidate else 'ilr_window_reference'
        failures = [
            {'periodId': f['periodId'], 'cutoff': f['cutoff'], 'boundaries': m['gridBoundaries']}
            for f in tuning['folds'] for m in f.get('methods', [])
            if m['method'] == method and not m['gatePassed']
            and (candidate or f['periodId'] == group.split(':', 1)[1])
        ]
        disposition = 'evaluated_fail' if failures else 'evaluated_pass'
        detail = {'failedSelections': failures}
        evidence = ['tuning.json']
    elif group == 'reference-loss':
        detail = {k: v for k, v in periods['eight_party_2010']['paired'].items() if k != 'folds'}
        disposition = {'pass': 'evaluated_pass', 'fail': 'evaluated_fail', 'unevaluated': 'unevaluated_prerequisite'}[detail['status']]
    elif group.startswith('subgroup:'):
        _, period, scope, name = group.split(':', 3)
        matches = [m for m in periods[period]['misfit'] if m['candidate'] == 'midpoint' and m['scope'] == scope and m['name'] == name]
        assert len(matches) == 1, group
        m = matches[0]
        detail = m
        if m['status'] == 'unevaluated':
            disposition = 'unevaluated_prerequisite'
        else:
            reason = row['reason']
            if 'coverage' in reason:
                passed = .90 <= m['coverage95'] <= .98 and .40 <= m['coverage50'] <= .60
            elif 'absolute mean' in reason:
                passed = abs(m['meanStandardizedResidual']) <= .5
            else:
                assert 'root-mean-square' in reason, reason
                passed = .5 <= m['rootMeanSquareStandardizedResidual'] <= 1.5
            disposition = 'evaluated_pass' if passed else 'evaluated_fail'
    else:
        raise AssertionError(group)
    row.update(revisedDisposition=disposition, revisedEvidence=evidence, revisedDetail=detail)
    reasons.append(row)
assert len(reasons) == inventory['reason_count'] == 50
assert [r['release_reason_index'] for r in reasons] == list(range(50))
unevaluated = [m for p in periods.values() for m in p['misfit'] if m['required'] and m['status'] == 'unevaluated']
result = {
    'protocolVersion': tuning['protocolVersion'],
    'registrationSha256': tuning['registrationSha256'],
    'status': 'blocked' if all_reasons or unevaluated else 'complete',
    'releaseAuthorized': False,
    'inventory': {'path': str(INVENTORY), 'sha256': hashlib.sha256(INVENTORY.read_bytes()).hexdigest(), 'reasonCount': 50},
    'counts': dict(Counter(r['revisedDisposition'] for r in reasons)),
    'currentFailures': all_reasons,
    'unevaluatedSubgroups': unevaluated,
    'launchRequirement': 'The separate 30-minute deployment-host pipeline requirement remains unevaluated under #28.',
    'checks': {'estimation': estimation['checks'], 'measurement': measurement['checks'], 'crossArchitecture': comparison['checks']},
    'reasons': reasons,
}
with (ROOT / 'development-aggregate.json').open('x') as output:
    json.dump(result, output, indent=2)
    output.write('\n')
print(result['counts'])
