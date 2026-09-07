"""Reproduce the pinned CSV audit: python3 docs/research/audit-polls.py [Polls.csv]."""
import collections, csv, datetime as dt, decimal, hashlib, io, json, pathlib, sys, urllib.request
SHA = 'f0390c05854d87bbf21db9d31c6431ffa0f07f7e'
body = pathlib.Path(sys.argv[1]).read_bytes() if len(sys.argv)>1 else urllib.request.urlopen(f'https://raw.githubusercontent.com/MansMeg/SwedishPolls/{SHA}/Data/Polls.csv').read()
rows = list(csv.DictReader(io.StringIO(body.decode())))
parties = 'M L C KD S V MP SD'.split()
def missing(x): return x in ('NA', '')
def date(x): return None if missing(x) else dt.date.fromisoformat(x)
def audit(rs):
    key = lambda r: tuple(r[k] for k in ('house','PublDate','collectPeriodFrom','collectPeriodTo','n'))
    keys = collections.Counter(map(key,rs))
    complete = [r for r in rs if not any(missing(r[k]) for k in ('collectPeriodFrom','collectPeriodTo','n'))]
    sums = [(r, sum(decimal.Decimal(r[p]) for p in parties)) for r in complete if all(not missing(r[p]) for p in parties)]
    days = collections.Counter()
    for r in complete:
        a,b=date(r['collectPeriodFrom']),date(r['collectPeriodTo'])
        for d in range((b-a).days+1): days[r['house'],str(a+dt.timedelta(days=d))]+=1
    overlap_rows = sum(any(days[r['house'],str(date(r['collectPeriodFrom'])+dt.timedelta(days=d))]>1 for d in range((date(r['collectPeriodTo'])-date(r['collectPeriodFrom'])).days+1)) for r in complete)
    return dict(rows=len(rs), missing={k:sum(missing(r[k]) for r in rs) for k in rows[0]}, retained_by_spec=len(complete), retained_missing_publication=sum(missing(r["PublDate"]) for r in complete), retained_precision_over_two=sum(any("." in r[p] and len(r[p].split(".")[1])>2 for p in parties if not missing(r[p])) for r in complete), missing_party_retained=sum(any(missing(r[p]) for p in parties) for r in complete), duplicate_key_groups=sum(n>1 for n in keys.values()), duplicate_key_extra_rows=sum(n-1 for n in keys.values()), approx=collections.Counter(r['approxPeriod'] for r in rs), reversed_dates=sum(date(r['collectPeriodFrom'])>date(r['collectPeriodTo']) for r in complete), publication_before_end=sum(date(r['PublDate'])<date(r['collectPeriodTo']) for r in complete if not missing(r['PublDate'])), nonpositive_n=sum(decimal.Decimal(r['n'])<=0 for r in complete), noninteger_n=sum(decimal.Decimal(r['n'])%1!=0 for r in complete), n_range=[min(decimal.Decimal(r['n']) for r in complete),max(decimal.Decimal(r['n']) for r in complete)], other_negative=[{k:r[k] for k in ('house','PublDate')}|{'sum':s} for r,s in sums if s>100], other_zero=sum(s==100 for r,s in sums), other_range=[min(100-s for r,s in sums),max(100-s for r,s in sums)], overlap_rows=overlap_rows, overlap_house_days=sum(n>1 for n in days.values()), max_overlap=max(days.values()), houses=collections.Counter(r['house'] for r in rs))
print(json.dumps(dict(commit=SHA,sha256=hashlib.sha256(body).hexdigest(),all=audit(rows),since_start=audit([r for r in rows if not missing(r['collectPeriodTo']) and date(r['collectPeriodTo'])>=dt.date(2006,9,1)])),indent=2,default=str))
# National 2022 check against Valmyndigheten's published two-decimal shares.
votes=dict(S=30.33,SD=20.54,M=19.10,V=6.75,C=6.71,KD=5.34,MP=5.08,L=4.61)
seats=dict.fromkeys(votes,0)
for _ in range(349):
    p=max(votes,key=lambda p:votes[p]/(1.2 if seats[p]==0 else 2*seats[p]+1))
    seats[p]+=1
assert seats==dict(S=107,SD=73,M=68,V=24,C=24,KD=19,MP=18,L=16),seats
print('2022 national allocation:',seats)
