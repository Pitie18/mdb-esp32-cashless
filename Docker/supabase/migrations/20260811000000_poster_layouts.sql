-- Saved poster configuration: which QR source sits in which slot of a motif,
-- the operator's own link, and any overridden headline / QR labels.
--
-- Same inheritance shape as the contact data (20260810000000): a row with
-- machine_id IS NULL is the company default, a row with machine_id set
-- overrides it for one machine. Resolution is machine-row ?? company-row.
--
-- The point of persisting it: when the contact data changes half a year from
-- now and the operator reprints, the sign has to come back out of the printer
-- looking the way they set it up — not reset to defaults.

create table if not exists public.poster_layouts (
  id         uuid        primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  company_id uuid        not null references public.companies(id) on delete cascade,
  -- NULL = company-wide default for this motif.
  machine_id uuid        references public."vendingMachine"(id) on delete cascade,
  motif      text        not null,
  config     jsonb       not null default '{}'::jsonb
);

comment on table  public.poster_layouts        is 'Per-motif poster configuration (QR slot sources, custom link, text overrides, content blocks)';
comment on column public.poster_layouts.machine_id is 'NULL = company default for this motif; set = override for one machine';
comment on column public.poster_layouts.config     is 'jsonb: { slots: {slotId: {source}}, custom: {url,title,hint}, texts: {key: value}, blocks: [] }';

-- A plain UNIQUE(company_id, machine_id, motif) would not work: Postgres treats
-- NULLs as distinct, so a company could accumulate any number of "defaults".
create unique index if not exists poster_layouts_company_default_idx
  on public.poster_layouts (company_id, motif)
  where machine_id is null;

create unique index if not exists poster_layouts_machine_override_idx
  on public.poster_layouts (machine_id, motif)
  where machine_id is not null;

create index if not exists poster_layouts_company_idx
  on public.poster_layouts (company_id);

alter table public.poster_layouts enable row level security;

grant select, insert, update, delete on public.poster_layouts to authenticated;
grant all on public.poster_layouts to service_role;

-- Every member reads: printing is not an admin task.
drop policy if exists poster_layouts_select on public.poster_layouts;
create policy poster_layouts_select on public.poster_layouts
  for select to authenticated
  using (company_id = public.my_company_id());

-- Saving a configuration is: it changes what colleagues get on their next
-- print. One-off, unsaved tweaks stay available to everyone in the UI.
drop policy if exists poster_layouts_insert on public.poster_layouts;
create policy poster_layouts_insert on public.poster_layouts
  for insert to authenticated
  with check (company_id = public.my_company_id() and (select public.i_am_admin()));

drop policy if exists poster_layouts_update on public.poster_layouts;
create policy poster_layouts_update on public.poster_layouts
  for update to authenticated
  using (company_id = public.my_company_id() and (select public.i_am_admin()))
  with check (company_id = public.my_company_id() and (select public.i_am_admin()));

drop policy if exists poster_layouts_delete on public.poster_layouts;
create policy poster_layouts_delete on public.poster_layouts
  for delete to authenticated
  using (company_id = public.my_company_id() and (select public.i_am_admin()));
