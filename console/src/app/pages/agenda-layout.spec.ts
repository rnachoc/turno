// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { describe, expect, it } from 'vitest';
import { CLINIC, appointment } from '../testing';
import { PIXELS_PER_MINUTE, layOutAgenda, layOutMonth, layOutWeek } from './agenda-layout';

const TUESDAY = '2027-03-02';
const MONDAY = '2027-03-01';

describe('layOutWeek', () => {
  const NOT_THIS_WEEK = '2020-01-01';

  it('has seven days from the Monday, with the closed ones marked', () => {
    const week = layOutWeek(CLINIC, MONDAY, [], TUESDAY);

    expect(week.days.map((day) => day.title)).toEqual(['Mon 1', 'Tue 2', 'Wed 3', 'Thu 4', 'Fri 5', 'Sat 6', 'Sun 7']);
    expect(week.days.map((day) => day.closed)).toEqual([false, false, true, true, true, true, true]);
    expect(week.days.map((day) => day.isToday)).toEqual([false, true, false, false, false, false, false]);
    expect(week.hours[0].label).toBe('09:00');
  });

  it('places an appointment on its day by the clock at the clinic', () => {
    // Late on Monday in UTC is already Tuesday in Madrid.
    const late = appointment({ startsAt: '2027-03-01T23:30:00Z', endsAt: '2027-03-02T00:00:00Z' });

    const week = layOutWeek(CLINIC, MONDAY, [late], NOT_THIS_WEEK);

    expect(week.days[0].blocks).toEqual([]);
    expect(week.days[1].blocks.map((block) => block.entry.appointmentId)).toEqual(['a1']);
    expect(week.hours[0].label).toBe('00:00');
  });

  it('leaves out what is cancelled, and what falls outside the week', () => {
    const cancelled = appointment({ appointmentId: 'gone', status: 'CANCELLED' });
    const nextWeek = appointment({
      appointmentId: 'later',
      startsAt: '2027-03-09T09:00:00Z',
      endsAt: '2027-03-09T09:30:00Z',
    });

    const week = layOutWeek(CLINIC, MONDAY, [cancelled, nextWeek], NOT_THIS_WEEK);

    expect(week.days.flatMap((day) => day.blocks)).toEqual([]);
  });

  it('puts appointments side by side only where they meet', () => {
    const one = appointment({ appointmentId: 'one' });
    const other = appointment({ appointmentId: 'other', practitionerId: 'ben' });
    const later = appointment({
      appointmentId: 'later',
      startsAt: '2027-03-02T10:00:00Z',
      endsAt: '2027-03-02T10:30:00Z',
    });

    const blocks = layOutWeek(CLINIC, MONDAY, [one, other, later], NOT_THIS_WEEK).days[1].blocks;

    expect(blocks.map((block) => [block.entry.appointmentId, block.left, block.width])).toEqual([
      ['one', 0, 50],
      ['other', 50, 50],
      ['later', 0, 100],
    ]);
  });

  it('keeps one after another in one lane when they only touch', () => {
    const first = appointment({ appointmentId: 'first' });
    const next = appointment({
      appointmentId: 'next',
      startsAt: '2027-03-02T09:30:00Z',
      endsAt: '2027-03-02T10:00:00Z',
    });

    const blocks = layOutWeek(CLINIC, MONDAY, [first, next], NOT_THIS_WEEK).days[1].blocks;

    expect(blocks.map((block) => block.width)).toEqual([100, 100]);
  });
});

describe('layOutMonth', () => {
  it('covers whole weeks, and marks the days of other months', () => {
    // March 2027 starts on a Monday and ends on a Wednesday.
    const { days } = layOutMonth(CLINIC, TUESDAY, [], '2020-01-01');

    expect(days).toHaveLength(35);
    expect(days[0].date).toBe('2027-03-01');
    expect(days.at(-1)?.date).toBe('2027-04-04');
    expect(days.filter((day) => day.inMonth)).toHaveLength(31);
    expect(days[30].inMonth).toBe(true);
    expect(days[31].inMonth).toBe(false);
  });

  it('counts all of a day, names the first few and says how many more', () => {
    const entries = ['a', 'b', 'c', 'd', 'e'].map((appointmentId) => appointment({ appointmentId }));

    const tuesday = layOutMonth(CLINIC, TUESDAY, entries, TUESDAY).days[1];

    expect(tuesday.total).toBe(5);
    expect(tuesday.shown.map((entry) => entry.appointmentId)).toEqual(['a', 'b', 'c']);
    expect(tuesday.more).toBe(2);
    expect(tuesday.isToday).toBe(true);
    expect(tuesday.label).toBe('Tuesday, 2 March 2027: 5 appointments');
  });

  it('does not count what is cancelled', () => {
    const tuesday = layOutMonth(CLINIC, TUESDAY, [appointment({ status: 'CANCELLED' })], TUESDAY).days[1];

    expect(tuesday.total).toBe(0);
    expect(tuesday.label).toBe('Tuesday, 2 March 2027: 0 appointments');
  });
});

describe('layOutAgenda', () => {
  it('has a column for each practitioner who works that day', () => {
    expect(layOutAgenda(CLINIC, MONDAY, []).columns.map((column) => column.name)).toEqual(['Ana Gil', 'Ben Ruiz']);
    expect(layOutAgenda(CLINIC, TUESDAY, []).columns.map((column) => column.name)).toEqual(['Ana Gil']);
  });

  it('runs from the first opening to the last closing', () => {
    const day = layOutAgenda(CLINIC, TUESDAY, []);

    expect(day.hours[0].label).toBe('09:00');
    expect(day.hours.at(-1)?.label).toBe('20:00');
    expect(day.height).toBe(11 * 60 * PIXELS_PER_MINUTE);
    expect(day.closed).toBe(false);
  });

  it('places an appointment by the clock at the clinic', () => {
    const [block] = layOutAgenda(CLINIC, TUESDAY, [appointment()]).columns[0].blocks;

    expect(block.top).toBe(60 * PIXELS_PER_MINUTE);
    expect(block.height).toBe(30 * PIXELS_PER_MINUTE);
  });

  it('keeps cancelled appointments apart, since someone else may be in the slot', () => {
    const cancelled = appointment({ appointmentId: 'gone', status: 'CANCELLED' });
    const replacement = appointment({ appointmentId: 'new' });

    const day = layOutAgenda(CLINIC, TUESDAY, [cancelled, replacement]);

    expect(day.cancelled.map((entry) => entry.appointmentId)).toEqual(['gone']);
    expect(day.columns[0].blocks.map((block) => block.entry.appointmentId)).toEqual(['new']);
  });

  it('says a closed day is closed', () => {
    for (const date of ['2027-03-03', '2027-03-06', '2027-03-07']) {
      const day = layOutAgenda(CLINIC, date, []);

      expect(day.closed).toBe(true);
      expect(day.columns).toEqual([]);
      expect(day.hours).toEqual([]);
    }
  });

  it('takes a closure over the opening hours of that weekday', () => {
    // A Tuesday, on which the clinic would otherwise open.
    const clinic = { ...CLINIC, closures: [TUESDAY] };

    const day = layOutAgenda(clinic, TUESDAY, []);

    expect(day.closed).toBe(true);
    expect(day.hours).toEqual([]);
  });

  it('still shows an appointment on a day that became closed', () => {
    const entry = appointment({ startsAt: '2027-03-03T09:00:00Z', endsAt: '2027-03-03T09:30:00Z' });

    const day = layOutAgenda(CLINIC, '2027-03-03', [entry]);

    expect(day.closed).toBe(false);
    expect(day.columns.map((column) => column.practitionerId)).toEqual(['ana']);
    expect(day.columns[0].blocks).toHaveLength(1);
  });

  it('still shows an appointment outside the opening hours or of someone not working', () => {
    const early = appointment({
      practitionerId: 'ben',
      // 07:15 in Madrid, on a Tuesday, when Ben does not work.
      startsAt: '2027-03-02T06:15:00Z',
      endsAt: '2027-03-02T06:45:00Z',
    });

    const day = layOutAgenda(CLINIC, TUESDAY, [early]);

    expect(day.hours[0].label).toBe('07:00');
    expect(day.columns.map((column) => column.practitionerId)).toEqual(['ana', 'ben']);
    expect(day.columns[1].blocks[0].top).toBe(15 * PIXELS_PER_MINUTE);
  });

  it('does not use an appointment of someone unknown to the clinic', () => {
    const day = layOutAgenda(CLINIC, TUESDAY, [appointment({ practitionerId: 'gone' })]);

    expect(day.columns.flatMap((column) => column.blocks)).toEqual([]);
  });

  it('keeps the clock right after the clocks change', () => {
    // 10:00 in Madrid is 08:00 UTC once summer time has started on 28 March 2027.
    const entry = appointment({ startsAt: '2027-03-29T08:00:00Z', endsAt: '2027-03-29T08:30:00Z' });

    const [block] = layOutAgenda(CLINIC, '2027-03-29', [entry]).columns[0].blocks;

    expect(block.top).toBe(60 * PIXELS_PER_MINUTE);
  });
});
