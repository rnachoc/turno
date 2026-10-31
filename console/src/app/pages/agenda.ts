// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { ConsoleApi } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { clockTime, statusLabel } from '../core/format';
import { Loader } from '../core/loader';
import { AgendaEntry } from '../core/models';
import { layOutAgenda } from './agenda-layout';
import { DayPicker, dayFrom } from './day-picker';

@Component({
  selector: 'app-agenda',
  imports: [DayPicker],
  template: `
    <app-day-picker [date]="date()" />
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (layout(); as day) {
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
        @if (loader.data()?.length === 0) {
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
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading the agenda…</p>
    }
  `,
})
export class Agenda {
  private readonly api = inject(ConsoleApi);
  private readonly dateParam = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('date'))),
    { initialValue: null },
  );

  protected readonly context = inject(ClinicContext);
  protected readonly loader = new Loader<AgendaEntry[]>();
  protected readonly date = computed(() => dayFrom(this.dateParam(), this.context.timezone()));
  protected readonly layout = computed(() => {
    const clinic = this.context.clinic();
    const entries = this.loader.data();
    return clinic === null || entries === null ? null : layOutAgenda(clinic, this.date(), entries);
  });

  constructor() {
    effect(() => this.loader.load(this.api.agenda(this.date())));
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
}
