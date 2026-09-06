alter table public.saved_points
add column if not exists catch_details jsonb;

alter table public.saved_points
drop constraint if exists saved_points_catch_details_object;

alter table public.saved_points
add constraint saved_points_catch_details_object
check (catch_details is null or jsonb_typeof(catch_details) = 'object');

drop policy if exists "Prototype clients can update own saved points" on public.saved_points;

create policy "Prototype clients can update own saved points"
on public.saved_points
for update
to anon
using (
  device_id = (select nullif(
    coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-device-id',
    ''
  ))
)
with check (
  device_id = (select nullif(
    coalesce(nullif(current_setting('request.headers', true), ''), '{}')::json ->> 'x-device-id',
    ''
  ))
);

grant update on table public.saved_points to anon;
