from pathlib import Path
import hashlib
import subprocess

root=Path(__file__).resolve().parents[1]
base='app/src/main/java/com/example/algohud/execution/'
tests='app/src/test/java/com/example/algohud/execution/'
def git(*args): return subprocess.check_output(['git','-C',str(root),*args], text=True).strip()
def blob(data): return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
if git('branch','--show-current')!='feature/research-lab':
    raise SystemExit('STOP: wrong branch; main must remain unchanged')
expected={
 base+'TicketProtectionReset.kt':'197dc2cd73d722726028f608b185a4f78a80b0da',
 tests+'TicketProtectionResetTest.kt':'dbad605524d5b95ff35ad9b6a35bdc288960456f'}
for name,sha in expected.items():
    if blob((root/name).read_bytes()) != sha: raise SystemExit('STOP: unexpected version of '+name)
for name in ['TicketProtectionReset.kt','TicketProtectionResetTest.kt']:
    target=(tests if name.endswith('Test.kt') else base)+name
    path=root/target
    content=(root/'scripts/post-entry-reset'/name).read_text(encoding='utf-8')
    path.write_text(content,encoding='utf-8')
print('Applied controlled post-entry protection reset to 2 files.')
