import pathlib,time,subprocess,shlex,json,concurrent.futures
root=pathlib.Path('docs/validation/v2-development-1/evidence/run-2')
while True:
 status=pathlib.Path('/tmp/swedishpolls-run2-stages.log').read_text()
 if 'LOCAL_STAGES_FINISHED' in status: break
 if 'Stage rejected' in status or 'Verification failed' in status: raise SystemExit(status)
 time.sleep(10)
subprocess.run(['rsync','-a','--exclude=logs',str(root)+'/', 'pinas:/tmp/swedishpolls-validation-run-2/'+str(root)+'/'],check=True)
subprocess.run(['rsync','-a','target/classes/','pinas:/tmp/swedishpolls-validation-run-2/target/classes/'],check=True)
images={'amd64':'8c6736fa623090b057a5bbd36d42f90c9de4c7d2d4b6c285921a4f85ce65a445','arm64':'b4c93a50fc67612798db73d68ca3b0ee4ebdd51736e59cca370e689b9797037e'}
def reproduce(arch):
 work=pathlib.Path.cwd() if arch=='amd64' else pathlib.Path('/tmp/swedishpolls-validation-run-2')
 command=f'''docker run --rm --platform linux/{arch} --user "$(id -u):$(id -g)" -v {shlex.quote(str(work)+':/work')} -w /work eclipse-temurin@sha256:{images[arch]} java -Duser.home=/work/target/validation-runtime/home -cp 'target/classes:target/validation-runtime/lib/*' se.swedishpolls.estimation.DevelopmentValidation reproduce docs/validation/v2-development-1/registration-run-2.json src/test/resources/polls/audit.csv {root}/tuning.json {root}/reproduction-{arch}.json'''
 invocation=['bash','-c',command] if arch=='amd64' else ['ssh','-o','BatchMode=yes','pinas',command]
 print('START reproduction',arch,flush=True); start=time.time()
 with (root/'logs'/f'reproduce-{arch}.log').open('x') as log:
  log.write(shlex.join(invocation)+'\n');log.flush()
  result=subprocess.run(invocation,stdout=log,stderr=subprocess.STDOUT)
  log.write(f'\nexit_status={result.returncode}\nwall_seconds={time.time()-start:.3f}\n')
 print('END reproduction',arch,result.returncode,round(time.time()-start),flush=True)
 return result.returncode
with concurrent.futures.ThreadPoolExecutor(2) as pool: results=list(pool.map(reproduce,['amd64','arm64']))
for suffix in ['json','draws']:
 subprocess.run(['rsync','-a',f'pinas:/tmp/swedishpolls-validation-run-2/{root}/reproduction-arm64.{suffix}',str(root)+'/'],check=True)
p=json.loads(pathlib.Path('docs/validation/v2-development-1/registration-plan-run-2.json').read_text());command=p['commands'][8]
with (root/'logs/compare.log').open('x') as log:
 log.write(command+'\n');log.flush();start=time.time()
 result=subprocess.run(shlex.split(command),stdout=log,stderr=subprocess.STDOUT)
 log.write(f'\nexit_status={result.returncode}\nwall_seconds={time.time()-start:.3f}\n')
print('COMPARISON_FINISHED',result.returncode,flush=True)
