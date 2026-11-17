-- Empty replacement project only. Existing user data is not migrated by this script.
begin;
create table public.app_health (id integer primary key check (id = 1));
insert into public.app_health values (1);
alter table public.app_health enable row level security;
revoke all on public.app_health from anon, authenticated;
grant select on public.app_health to anon, authenticated;
create policy health_read on public.app_health for select to anon, authenticated using (true);

create table public.saved_points (
  id text primary key,
  device_id text not null,
  local_id bigint not null,
  latitude double precision not null check (latitude between -90 and 90),
  longitude double precision not null check (longitude between -180 and 180),
  recorded_at timestamptz not null,
  type text not null default 'Moment',
  note text not null default '',
  weather jsonb,
  catch_details jsonb check (catch_details is null or jsonb_typeof(catch_details) = 'object'),
  created_at timestamptz not null default now(),
  unique (device_id, local_id)
);
alter table public.saved_points enable row level security;
revoke all on public.saved_points from anon, authenticated;
-- ON CONFLICT updates require SELECT on the uploaded columns too.
grant select, insert, update, delete on public.saved_points to anon;
-- Preserve the app's current prototype installation-header contract.
-- This is not authenticated account ownership; migrate to auth.uid() before public release.
create policy device_points on public.saved_points for all to anon
using (device_id = nullif(coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id', ''))
with check (
  device_id = nullif(coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id', '')
  and id = device_id || '-' || local_id::text
);

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null check (char_length(btrim(display_name)) between 1 and 50),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
alter table public.profiles enable row level security;
revoke all on public.profiles from anon, authenticated;
grant select, insert, update on public.profiles to authenticated;
create policy profile_read on public.profiles for select to authenticated using ((select auth.uid()) = id);
create policy profile_insert on public.profiles for insert to authenticated with check ((select auth.uid()) = id);
create policy profile_update on public.profiles for update to authenticated using ((select auth.uid()) = id) with check ((select auth.uid()) = id);
commit;
