// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ConsoleApi } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { addDays, clockTime, mondayOf, monthGrid, statusLabel, todayAt } from '../core/format';
import { Loader } from '../core/loader';
import { AgendaEntry } from '../core/models';
import { layOutAgenda, layOutMonth, layOutWeek } from './agenda-layout';
import { AgendaPicker, viewFrom } from './agenda-picker';
import { dayFrom } from './day-picker';

/** An answer, with what it answers, so that it can never be drawn as the answer to something else. */
interface LoadedAgenda {
  readonly key: string;
  readonly entries: AgendaEntry[];
}

@Component({
  selector: 'app-agenda',
  imports: [AgendaPicker, RouterLink],
  template: `
    <app-agenda-picker [view]="view()" [date]="date()" />
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (entries() !== null) {
      @if (dayLayout(); as day) {
        @if (day.closed) {
          <p class="empty">The clinic is closed this day.</p>
        } @else {
          <div class="agenda-scroll" tabindex="0" role="region" aria-label="Appointments by practitioner">
            <div class="agenda-heads">
              <span class="agenda-gutter"></span>
              @for (column of day.columns; track column.practitionerId) {
                <span class="agenda-head">{{ column.name }}</span>
              }
            </div>
            <div class="agenda-body" [style.height.px]="day.height">
              <div class="agenda-gutter">
                @for (hour of day.hours; track hour.label) {
                  <span class="agenda-hour" [style.top.px]="hour.top">{{ hour.label }}</span>
                }
              </div>
              @for (column of day.columns; track column.practitionerId) {
                <div class="agenda-column">
                  @for (hour of day.hours; track hour.label) {
                    <span class="agenda-line" [style.top.px]="hour.top"></span>
                  }
                  @for (block of column.blocks; track block.entry.appointmentId) {
                    <article
                      class="agenda-block"
                      [class]="'agenda-block status-' + block.entry.status"
                      [style.top.px]="block.top"
                      [style.height.px]="block.height"
                    >
                      <strong>{{ time(block.entry.startsAt) }} {{ block.entry.patientName }}</strong>
                      <span>{{ treatment(block.entry) }} · {{ status(block.entry) }}</span>
                    </article>
                  }
                </div>
              }
            </div>
          </div>
          @if (entries()?.length === 0) {
            <p class="empty">Nobody is booked this day.</p>
          }
        }
        @if (day.cancelled.length > 0) {
          <section class="panel">
            <h2>Cancelled</h2>
            <ul class="plain">
              @for (entry of day.cancelled; track entry.appointmentId) {
                <li>
                  {{ time(entry.startsAt) }} · {{ entry.patientName }} · {{ treatment(entry) }} ·
                  {{ context.practitionerName(entry.practitionerId) }}
                  @if (entry.depositForfeited) {
                    <span class="tag">deposit recorded as lost</span>
                  }
                </li>
              }
            </ul>
          </section>
        }
      }

      @if (weekLayout(); as week) {
        <div class="agenda-scroll week" tabindex="0" role="region" aria-label="Appointments of the week">
          <div class="agenda-heads">
            <span class="agenda-gutter"></span>
            @for (day of week.days; track day.date) {
              <a
                class="agenda-head"
                [class.today]="day.isToday"
                [class.closed]="day.closed"
                [routerLink]="[]"
                [queryParams]="{ view: 'day', date: day.date }"
              >
                {{ day.title }}
              </a>
            }
          </div>
          <div class="agenda-body" [style.height.px]="week.height">
            <div class="agenda-gutter">
              @for (hour of week.hours; track hour.label) {
                <span class="agenda-hour" [style.top.px]="hour.top">{{ hour.label }}</span>
              }
            </div>
            @for (day of week.days; track day.date) {
              <div class="agenda-column" [class.closed]="day.closed">
                @for (hour of week.hours; track hour.label) {
                  <span class="agenda-line" [style.top.px]="hour.top"></span>
                }
                @for (block of day.blocks; track block.entry.appointmentId) {
                  <article
                    [class]="'agenda-block status-' + block.entry.status"
                    [style.top.px]="block.top"
                    [style.height.px]="block.height"
                    [style.left.%]="block.left"
                    [style.width.%]="block.width"
                    [title]="describe(block.entry)"
                  >
                    <strong>{{ time(block.entry.startsAt) }}</strong>
                    <span>{{ block.entry.patientName }}</span>
                  </article>
                }
              </div>
            }
          </div>
        </div>
        @if (entries()?.length === 0) {
          <p class="empty">Nobody is booked this week.</p>
        }
        <p class="hint">Open a day to see its cancelled appointments.</p>
      }

      @if (monthLayout(); as month) {
        <div class="month" role="region" aria-label="Appointments of the month">
          <div class="month-heads" aria-hidden="true">
            @for (name of weekdays; track name) {
              <span>{{ name }}</span>
            }
          </div>
          <div class="month-grid">
            @for (day of month.days; track day.date) {
              <a
                class="month-day"
                [class.outside]="!day.inMonth"
                [class.closed]="day.closed"
                [class.today]="day.isToday"
                [routerLink]="[]"
                [queryParams]="{ view: 'day', date: day.date }"
                [attr.aria-label]="day.label"
              >
                <span class="month-top">
                  <span class="month-number">{{ day.number }}</span>
                  @if (day.total > 0) {
                    <span class="month-count">{{ day.total }}</span>
                  }
                </span>
                @for (entry of day.shown; track entry.appointmentId) {
                  <span [class]="'month-entry status-' + entry.status">
                    {{ time(entry.startsAt) }} {{ entry.patientName }}
                  </span>
                }
                @if (day.more > 0) {
                  <span class="month-more">{{ day.more }} more</span>
                }
              </a>
            }
          </div>
        </div>
        @if (entries()?.length === 0) {
          <p class="empty">Nobody is booked this month.</p>
        }
        <p class="hint">Open a day to see its cancelled appointments.</p>
      }
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading the agenda…</p>
    }
  `,
})
export class Agenda {
  private readonly api = inject(ConsoleApi);
  private readonly route = inject(ActivatedRoute);
  private readonly dateParam = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('date'))), {
    initialValue: null,
  });
  private readonly viewParam = toSignal(this.route.queryParamMap.pipe(map((params) => params.get('view'))), {
    initialValue: null,
  });

  protected readonly context = inject(ClinicContext);
  protected readonly loader = new Loader<LoadedAgenda>();
  protected readonly weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  protected readonly view = computed(() => viewFrom(this.viewParam()));
  protected readonly date = computed(() => dayFrom(this.dateParam(), this.context.timezone()));

  private readonly period = computed(() => {
    const date = this.date();
    switch (this.view()) {
      case 'week': {
        const monday = mondayOf(date);
        return { from: monday, to: addDays(monday, 6) };
      }
      case 'month':
        return monthGrid(date);
      default:
        return { from: date, to: date };
    }
  });
  private readonly key = computed(() => `${this.view()}:${this.period().from}:${this.period().to}`);

  protected readonly entries = computed(() => {
    const loaded = this.loader.data();
    return loaded !== null && loaded.key === this.key() ? loaded.entries : null;
  });
  protected readonly dayLayout = computed(() => {
    const clinic = this.context.clinic();
    const entries = this.entries();
    return this.view() !== 'day' || clinic === null || entries === null
      ? null
      : layOutAgenda(clinic, this.date(), entries);
  });
  protected readonly weekLayout = computed(() => {
    const clinic = this.context.clinic();
    const entries = this.entries();
    return this.view() !== 'week' || clinic === null || entries === null
      ? null
      : layOutWeek(clinic, this.period().from, entries, todayAt(clinic.timezone));
  });
  protected readonly monthLayout = computed(() => {
    const clinic = this.context.clinic();
    const entries = this.entries();
    return this.view() !== 'month' || clinic === null || entries === null
      ? null
      : layOutMonth(clinic, this.date(), entries, todayAt(clinic.timezone));
  });

  constructor() {
    effect(() => {
      const key = this.key();
      const { from, to } = this.period();
      const source = this.view() === 'day' ? this.api.agenda(from) : this.api.agendaRange(from, to);
      this.loader.load(source.pipe(map((entries) => ({ key, entries }))));
    });
  }

  protected time(instant: string): string {
    return clockTime(instant, this.context.timezone());
  }

  protected treatment(entry: AgendaEntry): string {
    return this.context.treatmentName(entry.treatmentId);
  }

  protected status(entry: AgendaEntry): string {
    return statusLabel(entry.status);
  }

  protected describe(entry: AgendaEntry): string {
    return `${this.time(entry.startsAt)} ${entry.patientName} · ${this.treatment(entry)} · ${this.status(entry)} · ${this.context.practitionerName(entry.practitionerId)}`;
  }
}
