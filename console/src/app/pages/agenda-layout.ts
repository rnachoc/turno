// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import {
  addDays,
  longDate,
  minutesIntoDay,
  minutesOfTime,
  monthGrid,
  shortDay,
  todayAt,
  weekdayOf,
} from '../core/format';
import { AgendaEntry, Clinic } from '../core/models';

/** Height of one minute in the agenda, in pixels. */
export const PIXELS_PER_MINUTE = 1.5;

export interface AgendaBlock {
  readonly entry: AgendaEntry;
  readonly top: number;
  readonly height: number;
}

export interface AgendaColumn {
  readonly practitionerId: string;
  readonly name: string;
  readonly blocks: readonly AgendaBlock[];
}

export interface AgendaLayout {
  readonly closed: boolean;
  readonly height: number;
  readonly hours: readonly { readonly label: string; readonly top: number }[];
  readonly columns: readonly AgendaColumn[];
  readonly cancelled: readonly AgendaEntry[];
}

/** How many appointments a day of a month's calendar names before only counting the rest. */
export const SHOWN_IN_A_MONTH_DAY = 3;

export interface WeekBlock extends AgendaBlock {
  /** Where it starts across its day, and how much of it it takes, in percent. */
  readonly left: number;
  readonly width: number;
}

export interface WeekDay {
  readonly date: string;
  readonly title: string;
  readonly closed: boolean;
  readonly isToday: boolean;
  readonly blocks: readonly WeekBlock[];
}

export interface WeekLayout {
  readonly height: number;
  readonly hours: readonly { readonly label: string; readonly top: number }[];
  readonly days: readonly WeekDay[];
}

export interface MonthDay {
  readonly date: string;
  readonly number: number;
  readonly label: string;
  readonly inMonth: boolean;
  readonly closed: boolean;
  readonly isToday: boolean;
  readonly total: number;
  readonly shown: readonly AgendaEntry[];
  readonly more: number;
}

export interface MonthLayout {
  readonly days: readonly MonthDay[];
}

function isClosed(clinic: Clinic, date: string): boolean {
  return clinic.closures.includes(date) || (clinic.openingHours[weekdayOf(date)] ?? []).length === 0;
}

/** Sorts what is still happening by the day it falls on at the clinic, which may not be the day in UTC. */
function byClinicDay(entries: readonly AgendaEntry[], timezone: string): Map<string, AgendaEntry[]> {
  const days = new Map<string, AgendaEntry[]>();
  for (const entry of entries) {
    if (entry.status !== 'CANCELLED') {
      const date = todayAt(timezone, new Date(entry.startsAt));
      days.set(date, [...(days.get(date) ?? []), entry]);
    }
  }
  return days;
}

/** Puts appointments side by side only where they meet, as two practitioners can see two patients at once. */
function placeSideBySide(blocks: readonly AgendaBlock[]): WeekBlock[] {
  const sorted = [...blocks].sort((one, other) => one.top - other.top);
  const placed: WeekBlock[] = [];
  let group: AgendaBlock[] = [];
  let groupEnd = -Infinity;

  const closeGroup = (): void => {
    const laneEnds: number[] = [];
    const lanes = group.map((block) => {
      const free = laneEnds.findIndex((end) => end <= block.top);
      const lane = free === -1 ? laneEnds.length : free;
      laneEnds[lane] = block.top + block.height;
      return lane;
    });
    group.forEach((block, index) => {
      placed.push({ ...block, left: (lanes[index] * 100) / laneEnds.length, width: 100 / laneEnds.length });
    });
    group = [];
  };

  for (const block of sorted) {
    if (block.top >= groupEnd) {
      closeGroup();
      groupEnd = -Infinity;
    }
    group.push(block);
    groupEnd = Math.max(groupEnd, block.top + block.height);
  }
  closeGroup();
  return placed;
}

/** Lays out a week: seven days from a Monday, on one scale of hours. Cancelled appointments stay out. */
export function layOutWeek(
  clinic: Clinic,
  monday: string,
  entries: readonly AgendaEntry[],
  today: string,
): WeekLayout {
  const timezone = clinic.timezone;
  const dates = Array.from({ length: 7 }, (_, index) => addDays(monday, index));
  const byDay = byClinicDay(entries, timezone);
  const active = dates.flatMap((date) => byDay.get(date) ?? []);
  const windows = dates.flatMap((date) => (isClosed(clinic, date) ? [] : (clinic.openingHours[weekdayOf(date)] ?? [])));

  const starts = [
    ...windows.map((window) => minutesOfTime(window.open)),
    ...active.map((entry) => minutesIntoDay(entry.startsAt, timezone)),
  ];
  const ends = [
    ...windows.map((window) => minutesOfTime(window.close)),
    ...active.map((entry) => minutesIntoDay(entry.endsAt, timezone)),
  ];
  const first = starts.length === 0 ? 0 : Math.floor(Math.min(...starts) / 60) * 60;
  const last = ends.length === 0 ? 0 : Math.ceil(Math.max(...ends) / 60) * 60;

  const hours = [];
  for (let minute = first; minute <= last && last > first; minute += 60) {
    hours.push({
      label: `${String(minute / 60).padStart(2, '0')}:00`,
      top: (minute - first) * PIXELS_PER_MINUTE,
    });
  }

  return {
    height: (last - first) * PIXELS_PER_MINUTE,
    hours,
    days: dates.map((date) => ({
      date,
      title: shortDay(date),
      closed: isClosed(clinic, date),
      isToday: date === today,
      blocks: placeSideBySide(
        (byDay.get(date) ?? []).map((entry) => {
          const start = minutesIntoDay(entry.startsAt, timezone);
          return {
            entry,
            top: (start - first) * PIXELS_PER_MINUTE,
            height: (minutesIntoDay(entry.endsAt, timezone) - start) * PIXELS_PER_MINUTE,
          };
        }),
      ),
    })),
  };
}

/** Lays out the calendar of a month, in whole weeks. Cancelled appointments are not counted. */
export function layOutMonth(clinic: Clinic, date: string, entries: readonly AgendaEntry[], today: string): MonthLayout {
  const { from, to } = monthGrid(date);
  const byDay = byClinicDay(entries, clinic.timezone);
  const days: MonthDay[] = [];
  for (let day = from; day <= to; day = addDays(day, 1)) {
    const booked = byDay.get(day) ?? [];
    days.push({
      date: day,
      number: Number(day.slice(8)),
      label: `${longDate(day)}: ${booked.length} ${booked.length === 1 ? 'appointment' : 'appointments'}`,
      inMonth: day.slice(0, 7) === date.slice(0, 7),
      closed: isClosed(clinic, day),
      isToday: day === today,
      total: booked.length,
      shown: booked.slice(0, SHOWN_IN_A_MONTH_DAY),
      more: Math.max(0, booked.length - SHOWN_IN_A_MONTH_DAY),
    });
  }
  return { days };
}

/**
 * Lays out a day: one column per practitioner who works or has an appointment that day. Cancelled
 * appointments go apart, because the slot they left may already have someone else in it.
 */
export function layOutAgenda(clinic: Clinic, date: string, entries: readonly AgendaEntry[]): AgendaLayout {
  const weekday = weekdayOf(date);
  const closed = clinic.closures.includes(date);
  const windows = closed ? [] : (clinic.openingHours[weekday] ?? []);
  const active = entries.filter((entry) => entry.status !== 'CANCELLED');
  const timezone = clinic.timezone;

  const starts = [
    ...windows.map((window) => minutesOfTime(window.open)),
    ...active.map((entry) => minutesIntoDay(entry.startsAt, timezone)),
  ];
  const ends = [
    ...windows.map((window) => minutesOfTime(window.close)),
    ...active.map((entry) => minutesIntoDay(entry.endsAt, timezone)),
  ];
  const first = starts.length === 0 ? 0 : Math.floor(Math.min(...starts) / 60) * 60;
  const last = ends.length === 0 ? 0 : Math.ceil(Math.max(...ends) / 60) * 60;

  const hours = [];
  for (let minute = first; minute <= last && last > first; minute += 60) {
    hours.push({
      label: `${String(minute / 60).padStart(2, '0')}:00`,
      top: (minute - first) * PIXELS_PER_MINUTE,
    });
  }

  const columns = clinic.practitioners
    .filter(
      (practitioner) =>
        (!closed && windows.length > 0 && practitioner.workingDays.includes(weekday)) ||
        active.some((entry) => entry.practitionerId === practitioner.id),
    )
    .map((practitioner) => ({
      practitionerId: practitioner.id,
      name: practitioner.name,
      blocks: active
        .filter((entry) => entry.practitionerId === practitioner.id)
        .map((entry) => {
          const start = minutesIntoDay(entry.startsAt, timezone);
          return {
            entry,
            top: (start - first) * PIXELS_PER_MINUTE,
            height: (minutesIntoDay(entry.endsAt, timezone) - start) * PIXELS_PER_MINUTE,
          };
        }),
    }));

  return {
    closed: columns.length === 0,
    height: (last - first) * PIXELS_PER_MINUTE,
    hours,
    columns,
    cancelled: entries.filter((entry) => entry.status === 'CANCELLED'),
  };
}
