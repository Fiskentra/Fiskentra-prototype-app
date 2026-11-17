import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir, access } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Ephemeral PostgreSQL fixture. No credentials, HTTP client, Supabase endpoint or persistent database.
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let workspace = project;
while (true) {
  try { await access(path.join(workspace, '.tools/sql-tests/package.json')); break; }
  catch { const parent = path.dirname(workspace); if (parent === workspace) throw new Error('Existing isolated PGlite runtime is required'); workspace = parent; }
}
const require = createRequire(path.join(workspace, '.tools/sql-tests/package.json'));
const { PGlite } = require('@electric-sql/pglite');
const output = path.join(project, 'build/account-backups-sql-tests'); await mkdir(output, { recursive: true });
const source = await readFile(path.join(project, 'supabase/account_backups_proposed.sql'), 'utf8');
const hash = value => createHash('sha256').update(value).digest('hex');
const report = {
  scope: 'Isolated PGlite with auth/storage metadata fixtures; no live API or file-byte service is used',
  sqlSha256: hash(source), checks: [],
  sources: [
    'https://supabase.com/docs/guides/storage/security/access-control',
    'https://supabase.com/docs/guides/storage/schema/design',
    'https://supabase.com/docs/guides/storage/uploads/standard-uploads',
    'https://supabase.com/docs/guides/storage/serving/downloads'
  ],
  limitations: ['JWT signatures and the real Storage byte service are not implemented by the fixture.',
    'Single backend: real concurrent commit races are not exercised.',
    'Object metadata is seeded by the fixture owner only; real objects must be uploaded using Storage API.']
};
const A = '10000000-0000-4000-8000-000000000001', B = '10000000-0000-4000-8000-000000000002';
const BUCKET = 'fiskentra-backups-v1', H1 = '1'.repeat(64), H2 = '2'.repeat(64), H3 = '3'.repeat(64), H4 = '4'.repeat(64);
const id = n => `20000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const blob = (sha256,bytes) => ({ sha256,bytes });
const file = (sha256,bytes,chunks=[blob(sha256,bytes)]) => ({ sha256,bytes,chunks });
const valid = () => ({ schema:1, source_id:A, captured_at:123456789, device_label:'Fixture phone',
  counts:{ points:3,photos:1,trips:2 }, snapshot:file(H1,11), photos:[file(H2,7)], blobs:[blob(H1,11),blob(H2,7)] });
const clone = value => JSON.parse(JSON.stringify(value));
const db = await PGlite.create();
async function test(name,body) {
  try { await body(); report.checks.push({name,result:'PASS'}); }
  catch(error) { report.checks.push({name,result:'FAIL',code:error.code,message:error.message}); }
}
async function code(expected,body) {
  try { await body(); assert.fail(`Expected SQLSTATE ${expected}`); }
  catch(error) { assert.equal(error.code,expected,`Expected ${expected}; got ${error.code}: ${error.message}`); }
}
async function as(role,owner,body) {
  return db.transaction(async tx => {
    assert.ok(['authenticated','anon'].includes(role));
    await tx.exec(`SET LOCAL ROLE ${role}`);
    await tx.query("SELECT set_config('request.jwt.claim.sub',$1,true)",[owner??'']);
    const identity=(await tx.query(`SELECT current_user AS role, r.rolsuper,r.rolbypassrls,
      row_security_active('public.account_backups') AS backups_rls,row_security_active('storage.objects') AS objects_rls
      FROM pg_roles r WHERE r.rolname=current_user`)).rows[0];
    assert.equal(identity.role,role); assert.equal(identity.rolsuper,false); assert.equal(identity.rolbypassrls,false);
    assert.equal(identity.backups_rls,true); assert.equal(identity.objects_rls,true);
    return body(tx);
  });
}
async function rpc(tx,backup,manifest) {
  return (await tx.query('SELECT public.commit_account_backup($1::uuid,$2::jsonb) AS id',[backup,JSON.stringify(manifest)])).rows[0].id;
}
async function validator(tx,owner,manifest) {
  return (await tx.query('SELECT public.account_backup_manifest_valid($1::uuid,$2::jsonb) AS valid',[owner,JSON.stringify(manifest)])).rows[0].valid;
}
try {
  await db.exec(`
    CREATE ROLE anon NOLOGIN NOSUPERUSER NOBYPASSRLS;
    CREATE ROLE authenticated NOLOGIN NOSUPERUSER NOBYPASSRLS;
    CREATE SCHEMA auth;
    CREATE TABLE auth.users(id uuid PRIMARY KEY);
    CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE SECURITY INVOKER SET search_path=''
      AS $$ SELECT nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    GRANT USAGE ON SCHEMA auth,public TO anon,authenticated;
    GRANT EXECUTE ON FUNCTION auth.uid() TO anon,authenticated;
    CREATE SCHEMA storage;
    CREATE TABLE storage.buckets(id text PRIMARY KEY,public boolean NOT NULL DEFAULT false,file_size_limit bigint,
      allowed_mime_types text[] DEFAULT ARRAY['application/octet-stream']::text[]);
    CREATE TABLE storage.objects(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),bucket_id text REFERENCES storage.buckets(id),
      name text NOT NULL,owner_id text,metadata jsonb,UNIQUE(bucket_id,name));
    ALTER TABLE storage.buckets ENABLE ROW LEVEL SECURITY;
    ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
    GRANT USAGE ON SCHEMA storage TO anon,authenticated;
    GRANT SELECT,INSERT,UPDATE,DELETE ON storage.buckets,storage.objects TO anon,authenticated;
    -- Deliberately unsafe pre-existing permissive policies: the backup guards must override these.
    CREATE POLICY preexisting_broad_buckets ON storage.buckets FOR ALL TO public USING(true) WITH CHECK(true);
    CREATE POLICY preexisting_broad_objects ON storage.objects FOR ALL TO public USING(true) WITH CHECK(true);
    CREATE TABLE public.saved_points(id integer PRIMARY KEY,note text);
    INSERT INTO public.saved_points VALUES(1,'legacy sentinel');
    ALTER TABLE public.saved_points ENABLE ROW LEVEL SECURITY;
    CREATE POLICY legacy_sentinel ON public.saved_points FOR SELECT TO anon USING(false);
  `);
  await db.query('INSERT INTO auth.users VALUES($1::uuid),($2::uuid)',[A,B]);
  await db.query('INSERT INTO storage.buckets(id,public,file_size_limit) VALUES($1,false,4194304),($2,false,9999999)',[BUCKET,'legacy-bucket']);
  for(const [owner,h,n] of [[A,H1,11],[A,H2,7],[B,H1,11],[B,H2,7],[A,H3,4194304],[A,H4,1]])
    await db.query('INSERT INTO storage.objects(bucket_id,name,owner_id,metadata) VALUES($1,$2,$3,$4::jsonb)',[BUCKET,`${owner}/${h}`,owner,JSON.stringify({size:n})]);
  await db.query("INSERT INTO storage.objects(bucket_id,name,owner_id,metadata) VALUES('legacy-bucket','old.txt',$1,'{\"size\":3}')",[A]);
  const legacyBefore=(await db.query("SELECT * FROM public.saved_points")).rows;
  const priorPolicy=(await db.query("SELECT * FROM pg_policies WHERE tablename='saved_points'")).rows;
  await db.exec(source);

  await test('Functions are SECURITY INVOKER with empty search_path and no PUBLIC/anon execution',async()=>{
    const rows=(await db.query(`SELECT proname,prosecdef,proconfig FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='public' AND proname IN ('commit_account_backup','account_backup_manifest_valid')`)).rows;
    assert.equal(rows.length,2);for(const r of rows){assert.equal(r.prosecdef,false);assert.deepEqual(r.proconfig,['search_path=""']);}
    assert.equal((await db.query("SELECT has_function_privilege('anon','public.commit_account_backup(uuid,jsonb)','EXECUTE') AS yes")).rows[0].yes,false);
  });
  await test('Unchanged legacy rows and policies',async()=>{
    assert.deepEqual((await db.query('SELECT * FROM public.saved_points')).rows,legacyBefore);
    assert.deepEqual((await db.query("SELECT * FROM pg_policies WHERE tablename='saved_points'")).rows,priorPolicy);
  });
  await test('A valid complete manifest commits through RPC under real non-bypass RLS',()=>as('authenticated',A,async tx=>assert.equal(await rpc(tx,id(1),valid()),id(1))));
  await test('A identical response-loss retry remains one immutable row',()=>as('authenticated',A,async tx=>{
    assert.equal(await rpc(tx,id(1),valid()),id(1));assert.equal((await tx.query('SELECT count(*)::int AS n FROM public.account_backups')).rows[0].n,1);
  }));
  await test('A cannot replace committed manifest under the same backup ID',()=>code('40001',()=>as('authenticated',A,async tx=>{const m=valid();m.device_label='different';await rpc(tx,id(1),m);})));
  await test('A sees its own summary and immutable manifest',()=>as('authenticated',A,async tx=>assert.equal((await tx.query('SELECT backup_id FROM public.account_backups')).rows[0].backup_id,id(1))));
  await test('B cannot read A backup metadata',()=>as('authenticated',B,async tx=>assert.equal((await tx.query('SELECT * FROM public.account_backups')).rows.length,0)));
  await test('B can commit its own same-ID backup without touching A',()=>as('authenticated',B,async tx=>assert.equal(await rpc(tx,id(1),valid()),id(1))));
  await test('A and B same-ID manifests remain different owners',async()=>assert.equal((await db.query('SELECT count(*)::int AS n FROM public.account_backups WHERE backup_id=$1::uuid',[id(1)])).rows[0].n,2));
  await test('Anonymous table reads denied despite broad storage policies',()=>code('42501',()=>as('anon',null,tx=>tx.query('SELECT * FROM public.account_backups'))));
  await test('Anonymous commit denied',()=>code('42501',()=>as('anon',null,tx=>rpc(tx,id(2),valid()))));
  await test('Anonymous validator cannot be used to probe object metadata',()=>code('42501',()=>as('anon',null,tx=>validator(tx,A,valid()))));
  await test('Authenticated hard UPDATE and DELETE of committed backup denied',async()=>{
    await code('42501',()=>as('authenticated',A,tx=>tx.query("UPDATE public.account_backups SET manifest='{}'")));
    await code('42501',()=>as('authenticated',A,tx=>tx.query('DELETE FROM public.account_backups')));
  });
  await test('Direct INSERT also requires complete files and cannot bypass RPC validation',async()=>{
    const m=valid();m.blobs[0].sha256='9'.repeat(64);
    await code('42501',()=>as('authenticated',A,tx=>tx.query('INSERT INTO public.account_backups VALUES($1::uuid,$2::uuid,now(),$3::jsonb)',[A,id(3),JSON.stringify(m)])));
  });
  await test('Cross-owner direct INSERT fails RLS',()=>code('42501',()=>as('authenticated',B,tx=>tx.query('INSERT INTO public.account_backups VALUES($1::uuid,$2::uuid,now(),$3::jsonb)',[A,id(3),JSON.stringify(valid())]))));
  await test('A owner override passed to helper is rejected under B',()=>as('authenticated',B,async tx=>assert.equal(await validator(tx,A,valid()),false)));

  const badManifests=[
    ['unknown schema',m=>m.schema=2],['null snapshot',m=>m.snapshot=null],['unknown top-level key',m=>m.local_path='/private/device/photo.jpg'],
    ['invalid source UUID',m=>m.source_id='../escape'],['fractional timestamp',m=>m.captured_at=1.5],['zero timestamp',m=>m.captured_at=0],
    ['timestamp overflow',m=>m.captured_at=1e30],['oversized device label',m=>m.device_label='x'.repeat(129)],
    ['missing counts field',m=>delete m.counts.trips],['extra counts field',m=>m.counts.other=1],['negative count',m=>m.counts.points=-1],
    ['photo count does not match files',m=>m.counts.photos=2],['missing blob',m=>m.blobs.pop()],['duplicate blob key',m=>m.blobs.push(clone(m.blobs[0]))],
    ['nonexistent storage bytes',m=>{m.blobs[1].sha256='9'.repeat(64);m.photos[0].sha256='9'.repeat(64);m.photos[0].chunks[0].sha256='9'.repeat(64);} ],
    ['wrong metadata byte length',m=>{m.blobs[1].bytes=8;m.photos[0].bytes=8;m.photos[0].chunks[0].bytes=8;}],
    ['zero chunk size',m=>m.snapshot.chunks[0].bytes=0],['oversized chunk',m=>m.snapshot.chunks[0].bytes=4194305],
    ['chunk size differs from inventory',m=>m.snapshot.chunks[0].bytes=10],['whole size differs from chunk sum',m=>m.snapshot.bytes=10],
    ['empty chunks',m=>m.snapshot.chunks=[]],['invalid chunk hash',m=>m.snapshot.chunks[0].sha256='../../escape'],
    ['uppercase hash',m=>m.snapshot.sha256='A'.repeat(64)],['extra descriptor key',m=>m.snapshot.local_path='/device/private'],
    ['extra chunk key',m=>m.snapshot.chunks[0].offset=0],['extra blob key',m=>m.blobs[0].owner=B],
    ['duplicate original photo descriptor',m=>{m.photos.push(clone(m.photos[0]));m.counts.photos=2;}],
    ['unreferenced uploaded inventory blob',m=>m.blobs.push(blob(H4,1))],['empty original photo',m=>m.photos[0].bytes=0],
    ['manifest bigger than one MiB',m=>m.device_label='x'.repeat(1048577)]
  ];
  for(const [name,change] of badManifests) await test(`Reject ${name} without publishing`,async()=>{
    const m=valid();change(m);await code('22023',()=>as('authenticated',A,tx=>rpc(tx,id(9),m)));
    assert.equal((await db.query('SELECT count(*)::int AS n FROM public.account_backups WHERE backup_id=$1::uuid',[id(9)])).rows[0].n,0);
  });
  await test('Repeated chunks can reconstruct a large original without duplicating inventory',()=>as('authenticated',A,async tx=>{
    const m=valid();m.snapshot=file(H2,22,[blob(H1,11),blob(H1,11)]);assert.equal(await validator(tx,A,m),true);
  }));
  await test('Two-GiB original descriptor is valid; larger exact descriptor fails',()=>as('authenticated',A,async tx=>{
    const m=valid();m.photos=[];m.counts.photos=0;m.snapshot=file(H1,2147483648,Array.from({length:512},()=>blob(H3,4194304)));m.blobs=[blob(H3,4194304)];
    assert.equal(await validator(tx,A,m),true);m.snapshot.bytes++;m.snapshot.chunks.push(blob(H4,1));m.blobs.push(blob(H4,1));assert.equal(await validator(tx,A,m),false);
  }));
  await test('Private bucket configuration is required even for an idempotent retry',async()=>{
    await db.query('UPDATE storage.buckets SET public=true WHERE id=$1',[BUCKET]);
    try {await code('22023',()=>as('authenticated',A,tx=>rpc(tx,id(1),valid())));} finally {await db.query('UPDATE storage.buckets SET public=false WHERE id=$1',[BUCKET]);}
  });
  await test('A sees only its own content-addressed backup objects',()=>as('authenticated',A,async tx=>{
    const rows=(await tx.query('SELECT name FROM storage.objects WHERE bucket_id=$1',[BUCKET])).rows;
    assert.equal(rows.length,4);assert.ok(rows.every(r=>r.name.startsWith(A+'/')));
  }));
  await test('B cannot select A backup objects even under an existing broad SELECT policy',()=>as('authenticated',B,async tx=>assert.equal((await tx.query('SELECT * FROM storage.objects WHERE bucket_id=$1 AND name=$2',[BUCKET,`${A}/${H1}`])).rows.length,0)));
  await test('Anonymous cannot select any private backup object despite broad SELECT',()=>as('anon',null,async tx=>assert.equal((await tx.query('SELECT * FROM storage.objects WHERE bucket_id=$1',[BUCKET])).rows.length,0)));
  await test('Anonymous spoofed subject still cannot pass current-role guard',()=>as('anon',A,async tx=>assert.equal((await tx.query('SELECT * FROM storage.objects WHERE bucket_id=$1',[BUCKET])).rows.length,0)));
  await test('B cannot upload into A namespace',()=>code('42501',()=>as('authenticated',B,tx=>tx.query('INSERT INTO storage.objects(bucket_id,name,owner_id,metadata) VALUES($1,$2,$3,$4)',[BUCKET,`${A}/${'8'.repeat(64)}`,B,{size:1}]))));
  await test('A cannot forge owner_id or add an extra folder',async()=>{
    await code('42501',()=>as('authenticated',A,tx=>tx.query('INSERT INTO storage.objects(bucket_id,name,owner_id,metadata) VALUES($1,$2,$3,$4)',[BUCKET,`${A}/${'8'.repeat(64)}`,B,{size:1}])));
    await code('42501',()=>as('authenticated',A,tx=>tx.query('INSERT INTO storage.objects(bucket_id,name,owner_id,metadata) VALUES($1,$2,$3,$4)',[BUCKET,`${A}/extra/${'8'.repeat(64)}`,A,{size:1}])));
  });
  await test('A and anon cannot overwrite or delete backup objects despite broad ALL',async()=>{
    for(const [role,owner] of [['authenticated',A],['anon',null]]) await as(role,owner,async tx=>{
      assert.equal((await tx.query("UPDATE storage.objects SET metadata='{\"size\":999}' WHERE bucket_id=$1 RETURNING id",[BUCKET])).rows.length,0);
      assert.equal((await tx.query('DELETE FROM storage.objects WHERE bucket_id=$1 RETURNING id',[BUCKET])).rows.length,0);
    });
  });
  await test('A cannot move an unrelated object into backup namespace via UPDATE',()=>code('42501',()=>as('authenticated',A,tx=>tx.query("UPDATE storage.objects SET bucket_id=$1,name=$2 WHERE bucket_id='legacy-bucket'",[BUCKET,`${A}/${'8'.repeat(64)}`]))));
  await test('Authenticated users read bucket configuration; anon cannot',async()=>{
    for(const owner of [A,B]) await as('authenticated',owner,async tx=>assert.equal((await tx.query('SELECT * FROM storage.buckets WHERE id=$1',[BUCKET])).rows.length,1));
    await as('anon',null,async tx=>assert.equal((await tx.query('SELECT * FROM storage.buckets WHERE id=$1',[BUCKET])).rows.length,0));
  });
  await test('Authenticated and anon cannot make backup bucket public/delete/recreate it',async()=>{
    for(const [role,owner] of [['authenticated',A],['anon',null]]) {
      await as(role,owner,async tx=>{
        assert.equal((await tx.query('UPDATE storage.buckets SET public=true WHERE id=$1 RETURNING id',[BUCKET])).rows.length,0);
        assert.equal((await tx.query('DELETE FROM storage.buckets WHERE id=$1 RETURNING id',[BUCKET])).rows.length,0);
      });
      await code('42501',()=>as(role,owner,tx=>tx.query('INSERT INTO storage.buckets(id,public,file_size_limit) VALUES($1,true,4194304)',[BUCKET])));
    }
  });
  await test('Unrelated pre-existing bucket/object operations retain their old permissions',()=>as('authenticated',A,async tx=>{
    assert.equal((await tx.query("UPDATE storage.buckets SET file_size_limit=9999998 WHERE id='legacy-bucket' RETURNING id")).rows.length,1);
    assert.equal((await tx.query("UPDATE storage.objects SET metadata='{\"size\":4}' WHERE bucket_id='legacy-bucket' RETURNING id")).rows.length,1);
  }));
  await test('Rollback discards disposable complete backup without affecting existing manifests',async()=>{
    await db.exec('BEGIN; SET LOCAL ROLE authenticated');await db.query("SELECT set_config('request.jwt.claim.sub',$1,true)",[A]);
    await rpc(db,id(99),valid());await db.exec('ROLLBACK');
    assert.equal((await db.query('SELECT count(*)::int AS n FROM public.account_backups WHERE backup_id=$1::uuid',[id(99)])).rows[0].n,0);
    assert.equal((await db.query('SELECT count(*)::int AS n FROM public.account_backups WHERE backup_id=$1::uuid',[id(1)])).rows[0].n,2);
  });
  if(process.argv[2]) {
    const smoke=await readFile(path.resolve(process.argv[2]),'utf8');report.liveSmokeSha256=hash(smoke);report.liveSmokeCases=[];
    assert.equal(/(?:insert\s+into|update|delete\s+from)\s+storage\./i.test(smoke),false,'Live smoke must never mutate Storage metadata');
    assert.equal(/(?:insert\s+into|update|delete\s+from)\s+auth\./i.test(smoke),false,'Live smoke must never mutate Auth metadata');
    async function smokeCase(name,expected,expectedStep) {
      await test('Exact live smoke: '+name,async()=>{
        const before=(await db.query('SELECT owner_id,backup_id,created_at,manifest FROM public.account_backups ORDER BY owner_id,backup_id')).rows;
        const objects=(await db.query('SELECT * FROM storage.objects ORDER BY id')).rows;
        const buckets=(await db.query('SELECT * FROM storage.buckets ORDER BY id')).rows;
        const users=(await db.query('SELECT * FROM auth.users ORDER BY id')).rows;
        const results=await db.exec(smoke);
        const value=results.flatMap(r=>r.rows??[]).find(r=>r.smoke_report)?.smoke_report;
        assert.ok(value,'Smoke emits one JSON SELECT before ROLLBACK');assert.equal(value.status,expected);
        if(expectedStep)assert.equal(value.failed_step,expectedStep);
        if(expected==='PASS'){assert.equal(value.check_count,32);assert.equal(value.storage_rows_written,0);assert.equal(value.auth_users_created,0);}
        assert.equal(value.private_values_returned,false);assert.equal(value.rollback_follows,true);
        assert.deepEqual((await db.query('SELECT owner_id,backup_id,created_at,manifest FROM public.account_backups ORDER BY owner_id,backup_id')).rows,before,'All disposable rows are rolled back');
        assert.deepEqual((await db.query('SELECT * FROM storage.objects ORDER BY id')).rows,objects,'Smoke preserves all Storage object metadata');
        assert.deepEqual((await db.query('SELECT * FROM storage.buckets ORDER BY id')).rows,buckets,'Smoke preserves bucket configuration');
        assert.deepEqual((await db.query('SELECT * FROM auth.users ORDER BY id')).rows,users,'Smoke preserves Auth users');
        const text=JSON.stringify(value);for(const secret of [A,B,H1,H2,H3,H4,'Fixture phone'])assert.ok(!text.includes(secret),'Smoke result has no identity/hash/label values');
        report.liveSmokeCases.push({name,result:'PASS',smokeReport:value});
      });
    }
    await smokeCase('two existing users and complete backup','PASS');
    await db.exec('ALTER ROLE authenticated BYPASSRLS');
    await smokeCase('unsafe bypass role rejected before writes','FAIL','preflight');
    await db.exec('ALTER ROLE authenticated NOBYPASSRLS');
    await db.query('UPDATE storage.buckets SET public=true WHERE id=$1',[BUCKET]);
    await smokeCase('public bucket rejected','FAIL','private_bucket_and_restrictive_policy_catalog');
    await db.query('UPDATE storage.buckets SET public=false WHERE id=$1',[BUCKET]);
    await db.query('UPDATE storage.buckets SET file_size_limit=4194305 WHERE id=$1',[BUCKET]);
    await smokeCase('incorrect bucket limit rejected','FAIL','private_bucket_and_restrictive_policy_catalog');
    await db.query('UPDATE storage.buckets SET file_size_limit=4194304 WHERE id=$1',[BUCKET]);
    await db.query('UPDATE storage.buckets SET allowed_mime_types=NULL WHERE id=$1',[BUCKET]);
    await smokeCase('unrestricted MIME configuration rejected','FAIL','private_bucket_and_restrictive_policy_catalog');
    await db.query("UPDATE storage.buckets SET allowed_mime_types=ARRAY['application/octet-stream']::text[] WHERE id=$1",[BUCKET]);
    await db.exec('DROP POLICY fiskentra_backup_objects_owner_guard ON storage.objects');
    await smokeCase('missing restrictive Storage guard rejected','FAIL','private_bucket_and_restrictive_policy_catalog');
    const restoreGuard=source.match(/create policy fiskentra_backup_objects_owner_guard[\s\S]*?;/i)?.[0];assert.ok(restoreGuard);await db.exec(restoreGuard);
    await db.exec('ALTER FUNCTION public.commit_account_backup(uuid,jsonb) SECURITY DEFINER');
    await smokeCase('unsafe definer function rejected','FAIL','preflight');
    await db.exec('ALTER FUNCTION public.commit_account_backup(uuid,jsonb) SECURITY INVOKER');
    await db.query('DELETE FROM auth.users WHERE id=$1::uuid',[B]);
    await smokeCase('one existing user uses only negative synthetic B probes','PASS');
    const missingObject=(await db.query('DELETE FROM storage.objects WHERE bucket_id=$1 AND name=$2 RETURNING *',[BUCKET,`${A}/${H1}`])).rows[0];
    await smokeCase('incomplete existing Storage inventory rejected','FAIL','A_role_and_existing_backup_read');
    await db.query('INSERT INTO storage.objects(id,bucket_id,name,owner_id,metadata) VALUES($1::uuid,$2,$3,$4,$5::jsonb)',
      [missingObject.id,missingObject.bucket_id,missingObject.name,missingObject.owner_id,JSON.stringify(missingObject.metadata)]);
    await db.exec('DELETE FROM public.account_backups');
    await smokeCase('no complete backup blocks before writes','BLOCKED_NO_FULL_BACKUP');
  }
} catch(error) { report.fatal={code:error.code,message:error.message}; }
finally { await db.close(); }
report.passed=report.checks.filter(c=>c.result==='PASS').length;report.failed=report.checks.filter(c=>c.result==='FAIL').length;
await writeFile(path.join(output,'report.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({passed:report.passed,failed:report.failed,fatal:report.fatal??null,sqlSha256:report.sqlSha256,report:path.join(output,'report.json')}));
for(const check of report.checks.filter(c=>c.result==='FAIL'))console.log(JSON.stringify(check));
if(report.failed||report.fatal)process.exitCode=1;
