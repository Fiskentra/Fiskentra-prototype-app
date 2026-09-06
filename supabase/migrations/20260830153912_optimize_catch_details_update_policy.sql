drop policy if exists "Prototype clients can update own saved points" on public.saved_points;

create policy "Prototype clients can update own saved points"
on public.saved_points
for update
to anon
using (
  device_id = nullif(
    coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id',
    ''
  )
)
with check (
  device_id = nullif(
    coalesce(nullif((select current_setting('request.headers', true)), ''), '{}')::json ->> 'x-device-id',
    ''
  )
);
