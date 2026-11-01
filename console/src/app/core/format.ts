// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// Everything shown about time is the clinic's time, wherever the browser happens to be.

const LOCALE = 'en-GB';

/** Today's date at the clinic, as YYYY-MM-DD. */
export function todayAt(timezone: string, now: Date = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

/** Moves a YYYY-MM-DD date by whole days. It works in UTC so that no time zone can skip a day. */
export function addDays(date: string, days: number): string {
  const moved = new Date(`${date}T00:00:00Z`);
  moved.setUTCDate(moved.getUTCDate() + days);
  return moved.toISOString().slice(0, 10);
}

/** The Monday of the week of a date, since a clinic's week starts then. */
export function mondayOf(date: string): string {
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
  return addDays(date, -((weekday + 6) % 7));
}

/** Moves a date by whole months. A day the new month lacks becomes that month's last day. */
export function addMonths(date: string, months: number): string {
  const [year, month, day] = date.split('-').map(Number);
  const lastDay = new Date(Date.UTC(year, month - 1 + months + 1, 0));
  lastDay.setUTCDate(Math.min(day, lastDay.getUTCDate()));
  return lastDay.toISOString().slice(0, 10);
}

/** The days a month's calendar shows: from the Monday of its first week to the Sunday of its last. */
export function monthGrid(date: string): { readonly from: string; readonly to: string } {
  const first = `${date.slice(0, 7)}-01`;
  const last = addDays(addMonths(first, 1), -1);
  return { from: mondayOf(first), to: addDays(mondayOf(last), 6) };
}

function formatted(date: string, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(LOCALE, { timeZone: 'UTC', ...options }).format(new Date(`${date}T00:00:00Z`));
}

/** A month for reading, such as "March 2027". */
export function monthTitle(date: string): string {
  return formatted(date, { month: 'long', year: 'numeric' });
}

/** A week for reading, such as "1 – 7 March 2027", saying only what changes between its ends. */
export function weekTitle(monday: string): string {
  const sunday = addDays(monday, 6);
  const end = formatted(sunday, { day: 'numeric', month: 'long', year: 'numeric' });
  if (monday.slice(0, 4) !== sunday.slice(0, 4)) {
    return `${formatted(monday, { day: 'numeric', month: 'long', year: 'numeric' })} – ${end}`;
  }
  if (monday.slice(0, 7) !== sunday.slice(0, 7)) {
    return `${formatted(monday, { day: 'numeric', month: 'long' })} – ${end}`;
  }
  return `${Number(monday.slice(8))} – ${end}`;
}

/** A day in a week's heading, such as "Mon 1". */
export function shortDay(date: string): string {
  return `${formatted(date, { weekday: 'short' })} ${Number(date.slice(8))}`;
}

/** Tells whether a text is a real calendar date. */
export function isDate(text: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) {
    return false;
  }
  const parsed = new Date(`${text}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text;
}

/** A date for reading, such as "Tuesday, 2 March 2027". */
export function longDate(date: string): string {
  return new Intl.DateTimeFormat(LOCALE, {
    timeZone: 'UTC',
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(new Date(`${date}T00:00:00Z`));
}

/** The name of a weekday as the clinic's configuration writes it, such as "TUESDAY". */
export function weekdayOf(date: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'UTC', weekday: 'long' })
    .format(new Date(`${date}T00:00:00Z`))
    .toUpperCase();
}

/** An instant as the time at the clinic, such as "09:30". */
export function clockTime(instant: string, timezone: string): string {
  return new Intl.DateTimeFormat(LOCALE, {
    timeZone: timezone,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(instant));
}

/** An instant as date and time at the clinic, such as "2 Mar, 09:30". */
export function dateTime(instant: string, timezone: string): string {
  return new Intl.DateTimeFormat(LOCALE, {
    timeZone: timezone,
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(instant));
}

/** Minutes since midnight at the clinic, by the clock on the wall. */
export function minutesIntoDay(instant: string, timezone: string): number {
  const [hours, minutes] = clockTime(instant, timezone).split(':').map(Number);
  return hours * 60 + minutes;
}

/** Minutes since midnight of a time such as "09:30:00". */
export function minutesOfTime(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

/** An amount in cents, such as "€35.00". */
export function money(cents: number, currency: string): string {
  return new Intl.NumberFormat(LOCALE, { style: 'currency', currency }).format(cents / 100);
}

/** An estimated cost in dollars. Small amounts keep the digits that make them comparable. */
export function estimatedUsd(amount: number): string {
  const digits = amount !== 0 && Math.abs(amount) < 0.01 ? 4 : 2;
  return new Intl.NumberFormat(LOCALE, {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(amount);
}

/** A whole number with separators. */
export function count(value: number): string {
  return new Intl.NumberFormat(LOCALE).format(value);
}

const REASONS: Readonly<Record<string, string>> = {
  triage_emergency: 'Described an emergency',
  triage_medical: 'Asked a medical question',
  core_down: 'The core could not be reached',
  model_failure: 'The model gave no usable answer',
  invalid_format: 'The model kept answering in the wrong format',
  too_many_steps: 'The agent took too many steps',
};

/** Why an agent stepped aside, for a person. A code with no wording yet is shown readably. */
export function handoverReason(reason: string): string {
  return REASONS[reason] ?? reason.replaceAll('_', ' ');
}

const STATUSES: Readonly<Record<string, string>> = {
  HOLD: 'Held',
  CONFIRMED: 'Confirmed',
  COMPLETED: 'Attended',
  NO_SHOW: 'No-show',
  CANCELLED: 'Cancelled',
};

export function statusLabel(status: string): string {
  return STATUSES[status] ?? status;
}
