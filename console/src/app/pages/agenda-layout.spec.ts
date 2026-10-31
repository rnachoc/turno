// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { describe, expect, it } from 'vitest';
import { CLINIC, appointment } from '../testing';
import { PIXELS_PER_MINUTE, layOutAgenda } from './agenda-layout';

const TUESDAY = '2027-03-02';
const MONDAY = '2027-03-01';

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
