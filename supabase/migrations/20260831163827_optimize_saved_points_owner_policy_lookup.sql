-- Evaluate request.headers once per statement instead of once per row.
-- This keeps the existing anonymous device ownership rule unchanged.
alter policy "Prototype clients can read own saved points for delete"
on public.saved_points
using (
  device_id = nullif(
    (
      coalesce(
        nullif((select current_setting('request.headers', true)), ''),
        '{}'
      )::json ->> 'x-device-id'
    ),
    ''
  )
);

alter policy "Prototype clients can delete saved points"
on public.saved_points
using (
  device_id = nullif(
    (
      coalesce(
        nullif((select current_setting('request.headers', true)), ''),
        '{}'
      )::json ->> 'x-device-id'
    ),
    ''
  )
);
