"""Private debug-app map backup before a migration update; never prints records or auth data."""
import argparse, hashlib, pathlib, subprocess, tarfile, io, json
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--output',required=True);args=p.parse_args()
base=[args.adb,'-s',args.serial,'exec-out','run-as','com.fiskentra.app']
names=subprocess.run(base+['find','files','shared_prefs','-type','f'],capture_output=True,check=True).stdout.decode('utf-8').splitlines()
allowed={'shared_prefs/fiskentra_points.xml','shared_prefs/fiskentra_track.xml','shared_prefs/fiskentra_fishing_days.xml','shared_prefs/field_map.xml','shared_prefs/fiskentra_point_sync_status.xml','files/map-data-v1.json'}
selected=[n for n in names if n in allowed or n.startswith('files/catch_photos/')]
if not selected: raise SystemExit('No map records found; backup not created')
result=subprocess.run(base+['tar','-cf','-']+selected,capture_output=True,check=True)
archive=tarfile.open(fileobj=io.BytesIO(result.stdout),mode='r:')
if len(archive.getmembers()) != len(selected): raise SystemExit('Backup verification failed')
out=pathlib.Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(result.stdout)
print(json.dumps({'backup':str(out),'files':len(selected),'bytes':len(result.stdout),'sha256':hashlib.sha256(result.stdout).hexdigest()}))
