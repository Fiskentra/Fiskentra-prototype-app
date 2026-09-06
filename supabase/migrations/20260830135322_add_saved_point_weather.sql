alter table public.saved_points
add column if not exists weather jsonb;
