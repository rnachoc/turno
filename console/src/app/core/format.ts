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
