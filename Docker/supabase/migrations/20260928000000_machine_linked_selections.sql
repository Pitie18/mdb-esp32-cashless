-- Per-machine "linked selections" flag.
--
-- Stock is judged per product: all slots holding the same product in a
-- machine form one group (clients: lib/stock-health.ts and its iOS/Android
-- ports). A slot that is empty while its product is still in another slot is
-- shown as a hint, because on most machines that selection then reports
-- "sold out" to the customer.
--
-- Many VMCs can link selections and vend from a sibling spiral instead. For
-- such a machine the empty slot is irrelevant, so the clients hide the hint
-- when this flag is set. Purely a display setting: no trigger, no webhook
-- and no firmware reads it. Additive with a default, so older clients keep
-- working and simply keep showing the hint.

ALTER TABLE public."vendingMachine"
  ADD COLUMN IF NOT EXISTS linked_selections boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public."vendingMachine".linked_selections IS
  'True when the machine itself vends a product from another slot once one of its slots is empty (linked selections). Clients then hide the "slot empty, product in another slot" hint. Display only.';
