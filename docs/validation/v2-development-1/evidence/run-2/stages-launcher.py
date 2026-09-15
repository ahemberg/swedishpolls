import json, subprocess, time, pathlib, shlex, shutil
root=pathlib.Path('docs/validation/v2-development-1/evidence/run-2')
verify=pathlib.Path('/tmp/swedishpolls-run2-verify-3.log')
while True:
 text=verify.read_text() if verify.exists() else ''
 if 'BUILD FAILURE' in text: raise SystemExit('Verification failed; no real-data fits started')
 if 'BUILD SUCCESS' in text: break
 time.sleep(10)
shutil.copy2(verify,root/'logs/model-validation.log')
p=json.loads(pathlib.Path('docs/validation/v2-development-1/registration-plan-run-2.json').read_text())
for index,name in [(2,'tune'),(3,'estimate'),(4,'diagnose'),(5,'measure')]:
 command=p['commands'][index]
 start=time.time()
 print('START',name, time.strftime('%Y-%m-%dT%H:%M:%S%z'),flush=True)
 with (root/'logs'/f'{name}.log').open('x') as log:
  log.write(command+'\n'); log.flush()
  result=subprocess.run(shlex.split(command),stdout=log,stderr=subprocess.STDOUT)
  log.write(f'\nexit_status={result.returncode}\nwall_seconds={time.time()-start:.3f}\n')
 output=root/({'tune':'tuning','estimate':'estimation','diagnose':'diagnostics','measure':'measurement'}[name]+'.json')
 verdict=json.loads(output.read_text()) if output.exists() else {}
 print('END',name,result.returncode,verdict.get('status'), 'seconds',round(time.time()-start),flush=True)
 if verdict.get('status') not in ('blocked','complete','passed'):
  raise SystemExit('Stage rejected or missing; inspect before continuing')
print('LOCAL_STAGES_FINISHED',flush=True)
