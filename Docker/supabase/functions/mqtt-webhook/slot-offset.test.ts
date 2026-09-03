import { assertEquals } from 'jsr:@std/assert';
import { applyItemOffset, shiftSlotCounters } from './slot-offset.ts';

Deno.test('applyItemOffset: offset 0 is a no-op', () => {
  assertEquals(applyItemOffset(1, 0), 1);
  assertEquals(applyItemOffset(0, 0), 0);
});

Deno.test('applyItemOffset: shifts by the configured amount', () => {
  assertEquals(applyItemOffset(1, 9), 10);
  assertEquals(applyItemOffset(11, 9), 20);
  assertEquals(applyItemOffset(10, -9), 1);
});

Deno.test('applyItemOffset: never produces a negative item number', () => {
  // A misconfigured offset must not turn a real selection into a negative key
  // that no tray can ever match; clamp at 0 and let the tray join miss loudly.
  assertEquals(applyItemOffset(1, -9), 0);
});

Deno.test('applyItemOffset: leaves the MDB "unknown item" sentinel alone', () => {
  assertEquals(applyItemOffset(0xffff, 9), 0xffff);
});

Deno.test('shiftSlotCounters: offset 0 returns an equal map', () => {
  const input = { '1': { vends: 5, value_cents: 250 } };
  assertEquals(shiftSlotCounters(input, 0), input);
});

Deno.test('shiftSlotCounters: shifts numeric keys and normalises padding', () => {
  const input = {
    '01': { vends: 5, value_cents: 250 },
    '2': { vends: 7, value_cents: 350 },
  };
  assertEquals(shiftSlotCounters(input, 9), {
    '10': { vends: 5, value_cents: 250 },
    '11': { vends: 7, value_cents: 350 },
  });
});

Deno.test('shiftSlotCounters: keeps non-numeric keys verbatim', () => {
  const input = { 'ZZ': { vends: 1, value_cents: 100 } };
  assertEquals(shiftSlotCounters(input, 9), { 'ZZ': { vends: 1, value_cents: 100 } });
});

Deno.test('shiftSlotCounters: colliding keys keep the higher counter', () => {
  // Defensive: two raw keys can only collide if the DEX dump was malformed.
  // Losing the smaller counter is safer than silently halving the larger one.
  const input = {
    '1': { vends: 5, value_cents: 250 },
    '01': { vends: 9, value_cents: 450 },
  };
  assertEquals(shiftSlotCounters(input, 9), { '10': { vends: 9, value_cents: 450 } });
});
