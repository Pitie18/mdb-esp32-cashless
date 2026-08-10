-- Printable machine posters: contact fields for the customer-facing signage
-- that operators print and stick on their vending machines.
--
-- Company columns extend the existing imprint set (20260410140000). Machine
-- columns are pure *overrides* — resolution is always `machine.x ?? company.x`,
-- so an empty machine column means "inherit". All nullable, all additive:
-- existing rows, older frontends and field firmware are unaffected.

-- ─── 1. Company-level poster contact data ───────────────────────────────────

alter table public.companies
  add column if not exists whatsapp_phone text,
  add column if not exists support_hours  text,
  add column if not exists logo_path      text;

comment on column public.companies.whatsapp_phone is 'WhatsApp support number (often a mobile, distinct from contact_phone); used for wa.me QR codes on printed posters';
comment on column public.companies.support_hours  is 'Free-text availability shown under the phone number on posters, e.g. "Mo-Fr 8-18 Uhr"';
comment on column public.companies.logo_path      is 'Object path inside the public company-logos storage bucket, e.g. "{company_id}.png"';


-- ─── 2. Per-machine overrides ───────────────────────────────────────────────

alter table public."vendingMachine"
  add column if not exists contact_phone  text,
  add column if not exists whatsapp_phone text,
  add column if not exists support_hours  text,
  add column if not exists contact_email  text;

comment on column public."vendingMachine".contact_phone  is 'Overrides companies.contact_phone on printed posters for this machine only; NULL/empty inherits';
comment on column public."vendingMachine".whatsapp_phone is 'Overrides companies.whatsapp_phone on printed posters for this machine only; NULL/empty inherits';
comment on column public."vendingMachine".support_hours  is 'Overrides companies.support_hours on printed posters for this machine only; NULL/empty inherits';
comment on column public."vendingMachine".contact_email  is 'Overrides companies.contact_email on printed posters for this machine only; NULL/empty inherits';

-- No new RLS policies on these tables: both already carry company-scoped
-- policies and the new columns inherit them.


-- ─── 3. company-logos storage bucket ────────────────────────────────────────
-- Created here, not only in config.toml: the self-hosted Docker stack never
-- reads config.toml, so a bucket declared only there would be missing in
-- production. Mirrors 20260301100000_product_images.sql.
--
-- No SVG in the allowed types — an uploadable SVG in a public bucket is an
-- XSS vector.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'company-logos',
  'company-logos',
  true,
  2097152, -- 2 MiB
  array['image/png', 'image/jpeg', 'image/webp']
)
on conflict (id) do nothing;

-- Anyone can view a company logo: it is printed on public-facing signage.
drop policy if exists "company_logos_select" on storage.objects;
create policy "company_logos_select" on storage.objects
  for select
  using (bucket_id = 'company-logos');

drop policy if exists "company_logos_insert" on storage.objects;
create policy "company_logos_insert" on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'company-logos'
    and (select public.i_am_admin())
  );

drop policy if exists "company_logos_update" on storage.objects;
create policy "company_logos_update" on storage.objects
  for update to authenticated
  using (
    bucket_id = 'company-logos'
    and (select public.i_am_admin())
  );

drop policy if exists "company_logos_delete" on storage.objects;
create policy "company_logos_delete" on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'company-logos'
    and (select public.i_am_admin())
  );
