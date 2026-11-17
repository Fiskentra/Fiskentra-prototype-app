-- Additive immutable backups. Create PRIVATE fiskentra-backups-v1 through the Storage API/Dashboard first:
-- maximum object size 4194304 bytes; allowed MIME type application/octet-stream.
-- Do not INSERT/UPDATE storage metadata with SQL. Existing points, backups and unrelated buckets are untouched.
begin;

create function public.account_backup_manifest_valid(p_owner uuid, p_manifest jsonb)
returns boolean language plpgsql stable security invoker set search_path = '' as $$
declare
  v_file jsonb; v_chunk jsonb; v_blob jsonb; v_hash text; v_size bigint;
  v_sum bigint; v_total bigint := 0; v_file_size bigint;
  v_inventory jsonb := '{}'::jsonb; v_referenced jsonb := '{}'::jsonb;
  v_photo_hashes text[] := array[]::text[]; v_key text;
begin
  if p_owner is null or p_owner is distinct from auth.uid()
      or jsonb_typeof(p_manifest) is distinct from 'object'
      or octet_length(p_manifest::text) > 1048576
      or p_manifest->'schema' is distinct from '1'::jsonb
      or jsonb_typeof(p_manifest->'source_id') is distinct from 'string'
      or (p_manifest->>'source_id') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      or jsonb_typeof(p_manifest->'captured_at') is distinct from 'number'
      or (p_manifest->>'captured_at') !~ '^[1-9][0-9]{0,18}$'
      or (p_manifest->>'captured_at')::numeric > 9223372036854775807
      or jsonb_typeof(p_manifest->'snapshot') is distinct from 'object'
      or jsonb_typeof(p_manifest->'photos') is distinct from 'array'
      or jsonb_typeof(p_manifest->'blobs') is distinct from 'array'
      or jsonb_typeof(p_manifest->'counts') is distinct from 'object' then return false; end if;
  for v_key in select jsonb_object_keys(p_manifest) loop
    if v_key not in ('schema','source_id','captured_at','snapshot','photos','blobs','counts','device_label') then return false; end if;
  end loop;
  if p_manifest ? 'device_label' and (jsonb_typeof(p_manifest->'device_label') is distinct from 'string'
      or char_length(p_manifest->>'device_label') > 128) then return false; end if;
  for v_key in select jsonb_object_keys(p_manifest->'counts') loop
    if v_key not in ('points','photos','trips') then return false; end if;
  end loop;
  foreach v_key in array array['points','photos','trips'] loop
    if jsonb_typeof(p_manifest->'counts'->v_key) is distinct from 'number'
        or (p_manifest->'counts'->>v_key) !~ '^(0|[1-9][0-9]{0,7})$'
        or (p_manifest->'counts'->>v_key)::bigint > 10000000 then return false; end if;
  end loop;
  if (p_manifest->'counts'->>'photos')::bigint <> jsonb_array_length(p_manifest->'photos')
      or jsonb_array_length(p_manifest->'blobs') < 1
      or jsonb_array_length(p_manifest->'blobs') > 16384 then return false; end if;
  if not exists(select 1 from storage.buckets b where b.id='fiskentra-backups-v1'
      and b.public is false and b.file_size_limit > 0 and b.file_size_limit <= 4194304) then return false; end if;

  for v_blob in select value from jsonb_array_elements(p_manifest->'blobs') loop
    if jsonb_typeof(v_blob) is distinct from 'object'
        or (select count(*) from jsonb_object_keys(v_blob)) <> 2
        or jsonb_typeof(v_blob->'sha256') is distinct from 'string'
        or (v_blob->>'sha256') !~ '^[0-9a-f]{64}$'
        or jsonb_typeof(v_blob->'bytes') is distinct from 'number'
        or (v_blob->>'bytes') !~ '^[1-9][0-9]{0,6}$' then return false; end if;
    v_hash := v_blob->>'sha256'; v_size := (v_blob->>'bytes')::bigint;
    if v_size > 4194304 or v_inventory ? v_hash then return false; end if;
    if not exists(select 1 from storage.objects o
        where o.bucket_id='fiskentra-backups-v1' and o.name=p_owner::text || '/' || v_hash
          and o.owner_id=p_owner::text and (o.metadata->>'size') ~ '^[0-9]{1,10}$'
          and (o.metadata->>'size')::bigint=v_size) then return false; end if;
    v_inventory := v_inventory || jsonb_build_object(v_hash,v_size);
  end loop;

  for v_file in select value from jsonb_array_elements(jsonb_build_array(p_manifest->'snapshot') || (p_manifest->'photos')) loop
    if jsonb_typeof(v_file) is distinct from 'object'
        or (select count(*) from jsonb_object_keys(v_file)) <> 3
        or jsonb_typeof(v_file->'sha256') is distinct from 'string'
        or (v_file->>'sha256') !~ '^[0-9a-f]{64}$'
        or jsonb_typeof(v_file->'bytes') is distinct from 'number'
        or (v_file->>'bytes') !~ '^[1-9][0-9]{0,9}$'
        or jsonb_typeof(v_file->'chunks') is distinct from 'array'
        or jsonb_array_length(v_file->'chunks') < 1 or jsonb_array_length(v_file->'chunks') > 16384 then return false; end if;
    v_file_size := (v_file->>'bytes')::bigint;
    if v_file_size > 2147483648 then return false; end if;
    v_total := v_total + v_file_size;
    if v_total > 68719476736 then return false; end if;
    v_sum := 0;
    for v_chunk in select value from jsonb_array_elements(v_file->'chunks') loop
      if jsonb_typeof(v_chunk) is distinct from 'object'
          or (select count(*) from jsonb_object_keys(v_chunk)) <> 2
          or jsonb_typeof(v_chunk->'sha256') is distinct from 'string'
          or (v_chunk->>'sha256') !~ '^[0-9a-f]{64}$'
          or jsonb_typeof(v_chunk->'bytes') is distinct from 'number'
          or (v_chunk->>'bytes') !~ '^[1-9][0-9]{0,6}$' then return false; end if;
      v_hash := v_chunk->>'sha256'; v_size := (v_chunk->>'bytes')::bigint;
      if v_size > 4194304 or not (v_inventory ? v_hash)
          or (v_inventory->>v_hash)::bigint <> v_size then return false; end if;
      v_sum := v_sum + v_size; v_referenced := v_referenced || jsonb_build_object(v_hash,true);
    end loop;
    if v_sum <> v_file_size then return false; end if;
  end loop;
  for v_file in select value from jsonb_array_elements(p_manifest->'photos') loop
    if (v_file->>'sha256') = any(v_photo_hashes) then return false; end if;
    v_photo_hashes := array_append(v_photo_hashes,v_file->>'sha256');
  end loop;
  if (select count(*) from jsonb_object_keys(v_inventory)) <> (select count(*) from jsonb_object_keys(v_referenced)) then return false; end if;
  return true;
exception when others then return false;
end;
$$;
revoke all on function public.account_backup_manifest_valid(uuid,jsonb) from public,anon;
grant execute on function public.account_backup_manifest_valid(uuid,jsonb) to authenticated;

create table public.account_backups (
  owner_id uuid not null references auth.users(id) on delete cascade,
  backup_id uuid not null,
  created_at timestamptz not null default now(),
  manifest jsonb not null,
  primary key(owner_id,backup_id)
);
create index account_backups_recent on public.account_backups(owner_id,created_at desc,backup_id desc);
alter table public.account_backups enable row level security;
revoke all on public.account_backups from anon,authenticated;
grant select,insert on public.account_backups to authenticated;
create policy account_backups_select on public.account_backups for select to authenticated
  using ((select auth.uid())=owner_id);
create policy account_backups_insert on public.account_backups for insert to authenticated
  with check ((select auth.uid())=owner_id and public.account_backup_manifest_valid(owner_id,manifest));

create function public.commit_account_backup(p_backup_id uuid,p_manifest jsonb)
returns uuid language plpgsql security invoker set search_path = '' as $$
declare v_owner uuid := auth.uid(); v_previous jsonb;
begin
  if v_owner is null then raise exception 'Sign in required' using errcode='42501'; end if;
  if not public.account_backup_manifest_valid(v_owner,p_manifest) then
    raise exception 'Backup manifest or required files are incomplete' using errcode='22023';
  end if;
  select manifest into v_previous from public.account_backups where owner_id=v_owner and backup_id=p_backup_id;
  if found then
    if v_previous=p_manifest then return p_backup_id; end if;
    raise exception 'Backup identity already committed with different contents' using errcode='40001';
  end if;
  insert into public.account_backups(owner_id,backup_id,manifest) values(v_owner,p_backup_id,p_manifest)
    on conflict(owner_id,backup_id) do nothing;
  if not found then
    select manifest into v_previous from public.account_backups where owner_id=v_owner and backup_id=p_backup_id;
    if v_previous is distinct from p_manifest then
      raise exception 'Backup identity already committed with different contents' using errcode='40001';
    end if;
  end if;
  return p_backup_id;
end;
$$;
revoke all on function public.commit_account_backup(uuid,jsonb) from public,anon;
grant execute on function public.commit_account_backup(uuid,jsonb) to authenticated;

-- Restrictive guards also constrain any pre-existing broad permissive policies.
-- They evaluate true for unrelated buckets and do not change those buckets' permissions.
create policy fiskentra_backup_bucket_read on storage.buckets for select to authenticated
  using (id='fiskentra-backups-v1');
create policy fiskentra_backup_bucket_read_guard on storage.buckets as restrictive for select to public
  using (id<>'fiskentra-backups-v1' or ((select auth.uid()) is not null and current_user='authenticated'));
create policy fiskentra_backup_bucket_insert_guard on storage.buckets as restrictive for insert to public
  with check (id<>'fiskentra-backups-v1');
create policy fiskentra_backup_bucket_update_guard on storage.buckets as restrictive for update to public
  using (id<>'fiskentra-backups-v1') with check (id<>'fiskentra-backups-v1');
create policy fiskentra_backup_bucket_delete_guard on storage.buckets as restrictive for delete to public
  using (id<>'fiskentra-backups-v1');

create policy fiskentra_backup_objects_read on storage.objects for select to authenticated
  using (bucket_id='fiskentra-backups-v1' and owner_id=(select auth.uid())::text
    and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f]{64}$'));
create policy fiskentra_backup_objects_insert on storage.objects for insert to authenticated
  with check (bucket_id='fiskentra-backups-v1' and owner_id=(select auth.uid())::text
    and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f]{64}$'));
create policy fiskentra_backup_objects_owner_guard on storage.objects as restrictive for all to public
  using (bucket_id<>'fiskentra-backups-v1' or (current_user='authenticated'
    and owner_id=(select auth.uid())::text and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f]{64}$')))
  with check (bucket_id<>'fiskentra-backups-v1' or (current_user='authenticated'
    and owner_id=(select auth.uid())::text and name ~ ('^' || (select auth.uid())::text || '/[0-9a-f]{64}$')));
create policy fiskentra_backup_objects_update_guard on storage.objects as restrictive for update to public
  using (bucket_id<>'fiskentra-backups-v1') with check (bucket_id<>'fiskentra-backups-v1');
create policy fiskentra_backup_objects_delete_guard on storage.objects as restrictive for delete to public
  using (bucket_id<>'fiskentra-backups-v1');
commit;
