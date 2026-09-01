-- Restore the private profile identity key after an accidental Dashboard edit.
-- Account email stays in auth.users and must not be duplicated in public.profiles.
alter table public.profiles drop column if exists "Email";

do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conrelid = 'public.profiles'::regclass
      and contype = 'p'
  ) then
    alter table public.profiles
      add constraint profiles_pkey primary key (id);
  end if;
end
$$;

comment on table public.profiles is
  'Private per-user profile metadata. Account email remains in auth.users and is not duplicated here.';
