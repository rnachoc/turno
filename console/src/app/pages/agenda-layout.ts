// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { minutesIntoDay, minutesOfTime, weekdayOf } from '../core/format';
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
