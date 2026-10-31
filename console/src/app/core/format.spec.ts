// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { describe, expect, it } from 'vitest';
import {
  addDays,
  clockTime,
  count,
  estimatedUsd,
  handoverReason,
  isDate,
  longDate,
  minutesIntoDay,
  minutesOfTime,
  money,
  statusLabel,
  todayAt,
  weekdayOf,
} from './format';

const MADRID = 'Europe/Madrid';

describe('todayAt', () => {
  it('follows the clinic, not UTC', () => {
    expect(todayAt(MADRID, new Date('2027-03-01T23:30:00Z'))).toBe('2027-03-02');
    expect(todayAt('UTC', new Date('2027-03-01T23:30:00Z'))).toBe('2027-03-01');
  });
});

describe('addDays', () => {
  it('crosses months, years and leap days', () => {
    expect(addDays('2027-03-01', -1)).toBe('2027-02-28');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
  });

  it('does not lose a day when the clocks change', () => {
    expect(addDays('2027-03-27', 1)).toBe('2027-03-28');
    expect(addDays('2027-03-28', 1)).toBe('2027-03-29');
    expect(addDays('2027-10-31', 1)).toBe('2027-11-01');
  });
});

describe('isDate', () => {
  it('accepts real dates only', () => {
    expect(isDate('2027-03-02')).toBe(true);
    expect(isDate('2028-02-29')).toBe(true);
    expect(isDate('2027-02-29')).toBe(false);
    expect(isDate('2027-13-01')).toBe(false);
    expect(isDate('nonsense')).toBe(false);
    expect(isDate('2027-3-2')).toBe(false);
    expect(isDate('')).toBe(false);
  });
});

describe('dates for reading', () => {
  it('names the day that was asked for, wherever the browser is', () => {
    expect(longDate('2027-03-02')).toBe('Tuesday, 2 March 2027');
    expect(weekdayOf('2027-03-02')).toBe('TUESDAY');
  });
});

describe('times', () => {
  it('shows the time at the clinic', () => {
    expect(clockTime('2027-03-02T09:00:00Z', MADRID)).toBe('10:00');
    // After the clocks change, the same UTC hour is an hour later on the wall.
    expect(clockTime('2027-03-29T09:00:00Z', MADRID)).toBe('11:00');
  });

  it('writes midnight as 00:00', () => {
    expect(clockTime('2027-03-01T23:00:00Z', MADRID)).toBe('00:00');
    expect(minutesIntoDay('2027-03-01T23:00:00Z', MADRID)).toBe(0);
  });

  it('counts minutes by the clock on the wall', () => {
    expect(minutesIntoDay('2027-03-02T09:30:00Z', MADRID)).toBe(10 * 60 + 30);
    expect(minutesOfTime('09:30:00')).toBe(570);
    expect(minutesOfTime('20:00')).toBe(1200);
  });
});

describe('amounts', () => {
  it('writes cents as money', () => {
    expect(money(3500, 'EUR')).toBe('€35.00');
    expect(money(0, 'EUR')).toBe('€0.00');
    expect(money(123456, 'EUR')).toBe('€1,234.56');
  });

  it('keeps the digits that make small costs comparable', () => {
    expect(estimatedUsd(0)).toBe('US$0.00');
    expect(estimatedUsd(0.0012)).toBe('US$0.0012');
    expect(estimatedUsd(1.5)).toBe('US$1.50');
  });

  it('separates thousands', () => {
    expect(count(1234567)).toBe('1,234,567');
  });
});

describe('words for codes', () => {
  it('explains known reasons and keeps unknown ones readable', () => {
    expect(handoverReason('triage_emergency')).toBe('Described an emergency');
    expect(handoverReason('some_new_reason')).toBe('some new reason');
  });

  it('names the statuses', () => {
    expect(statusLabel('COMPLETED')).toBe('Attended');
    expect(statusLabel('NO_SHOW')).toBe('No-show');
    expect(statusLabel('OTHER')).toBe('OTHER');
  });
});
