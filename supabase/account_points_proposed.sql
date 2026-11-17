-- Additive account sync; leaves all legacy saved_points rows and policies untouched.
-- Apply only after inspecting the target project, then verify anon/A/B RLS separately.
begin;
create table public.account_points (
  owner_id uuid not null references auth.users(id) on delete cascade,
  point_id uuid not null,
  version bigint not null default 1 check (version > 0),
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  deleted boolean not null default false,
  last_operation_id uuid not null,
  updated_at timestamptz not null default now(),
  primary key (owner_id, point_id),
  check (not deleted or payload = '{}'::jsonb)
);
alter table public.account_points enable row level security;
revoke all on public.account_points from anon, authenticated;
grant select, insert, update on public.account_points to authenticated;
create policy account_points_select on public.account_points for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy account_points_insert on public.account_points for insert to authenticated
  with check ((select auth.uid()) = owner_id);
create policy account_points_update on public.account_points for update to authenticated
  using ((select auth.uid()) = owner_id) with check ((select auth.uid()) = owner_id);

-- SECURITY INVOKER: the caller still needs its own row's RLS/privileges.
-- Stable operation IDs make response-loss retries idempotent; expected_version rejects stale edits.
create function public.sync_account_point(
  p_point_id uuid, p_operation_id uuid, p_expected_version bigint, p_payload jsonb, p_deleted boolean
) returns bigint language plpgsql security invoker set search_path = '' as $$
declare v_owner uuid := auth.uid(); v_version bigint;
begin
  if v_owner is null then raise exception 'Sign in required' using errcode = '42501'; end if;
  if jsonb_typeof(p_payload) <> 'object' or p_payload is null then
    raise exception 'Invalid point payload' using errcode = '22023';
  end if;
  select version into v_version from public.account_points
    where owner_id = v_owner and point_id = p_point_id and last_operation_id = p_operation_id;
  if found then return v_version; end if;
  if p_expected_version = 0 then
    insert into public.account_points(owner_id, point_id, payload, deleted, last_operation_id)
      values(v_owner, p_point_id, case when p_deleted then '{}'::jsonb else p_payload end, p_deleted, p_operation_id)
      on conflict (owner_id, point_id) do nothing returning version into v_version;
  else
    update public.account_points set payload = case when p_deleted then '{}'::jsonb else p_payload end, deleted = p_deleted,
      version = version + 1, last_operation_id = p_operation_id, updated_at = now()
      where owner_id = v_owner and point_id = p_point_id and version = p_expected_version
      returning version into v_version;
  end if;
  if not found then
    -- A retry may have raced the first transaction while it was committing.
    select version into v_version from public.account_points
      where owner_id = v_owner and point_id = p_point_id and last_operation_id = p_operation_id;
    if not found then raise exception 'Cloud point changed; restore before resolving' using errcode = '40001'; end if;
  end if;
  return v_version;
end;
$$;
revoke all on function public.sync_account_point(uuid, uuid, bigint, jsonb, boolean) from public, anon;
grant execute on function public.sync_account_point(uuid, uuid, bigint, jsonb, boolean) to authenticated;
commit;
