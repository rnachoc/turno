// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, effect, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { ConsoleApi } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { count, dateTime, estimatedUsd, handoverReason, money } from '../core/format';
import { Loader } from '../core/loader';
import { DailyReport } from '../core/models';
import { DayPicker, dayFrom } from './day-picker';

@Component({
  selector: 'app-report',
  imports: [DayPicker, RouterLink],
  template: `
    <app-day-picker [date]="date()" />
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (loader.data(); as report) {
      @if (report.attention.openHandovers + report.attention.awaitingOutcome > 0) {
        <a class="attention" routerLink="/decisions">
          <strong>Waiting for you now:</strong>
          {{ report.attention.openHandovers }} {{ report.attention.openHandovers === 1 ? 'person' : 'people' }}
          to call, {{ report.attention.awaitingOutcome }}
          {{ report.attention.awaitingOutcome === 1 ? 'appointment' : 'appointments' }} to close
        </a>
      }

      <section class="figures" aria-label="The day in figures">
        <div class="figure">
          <span class="value">{{ occupancy(report) }}</span>
          <span class="label">Occupancy</span>
          <span class="note">{{ count(report.occupancy.bookedMinutes) }} of {{ count(report.occupancy.openMinutes) }} min</span>
        </div>
        <div class="figure">
          <span class="value">{{ report.agenda.completed }}</span>
          <span class="label">Attended</span>
          <span class="note">{{ money(report.money.completedCents, report.currency) }}</span>
        </div>
        <div class="figure">
          <span class="value">{{ report.agenda.noShow }}</span>
          <span class="label">No-shows</span>
          <span class="note">{{ money(report.money.noShowCents, report.currency) }} lost</span>
        </div>
        <div class="figure">
          <span class="value">{{ report.activity.bookedByAgents }}</span>
          <span class="label">Booked by agents</span>
          <span class="note">of {{ report.activity.booked }} bookings that day</span>
        </div>
        <div class="figure">
          <span class="value">{{ report.waitlist.offersAccepted }}</span>
          <span class="label">Slots recovered</span>
          <span class="note">{{ money(report.waitlist.recoveredCents, report.currency) }}</span>
        </div>
      </section>

      <div class="columns">
        <section class="panel">
          <h2>Appointments of the day</h2>
          <dl class="rows">
            <div><dt>Attended</dt><dd>{{ report.agenda.completed }}</dd></div>
            <div><dt>Confirmed, no outcome yet</dt><dd>{{ report.agenda.confirmed }}</dd></div>
            <div><dt>Held, not confirmed</dt><dd>{{ report.agenda.held }}</dd></div>
            <div><dt>No-shows</dt><dd>{{ report.agenda.noShow }}</dd></div>
            <div><dt>Cancelled</dt><dd>{{ report.agenda.cancelled }}</dd></div>
            <div><dt>Deposits recorded as lost</dt><dd>{{ money(report.money.forfeitedDepositCents, report.currency) }}</dd></div>
          </dl>
          <p class="hint">Amounts are records only: Turno charges nothing yet.</p>
        </section>

        <section class="panel">
          <h2>Done during the day</h2>
          <dl class="rows">
            <div><dt>Bookings confirmed</dt><dd>{{ report.activity.booked }}</dd></div>
            <div><dt>Moved</dt><dd>{{ report.activity.rescheduled }}</dd></div>
            <div><dt>Cancelled</dt><dd>{{ report.activity.cancelled }}</dd></div>
            <div><dt>Refused by the core</dt><dd>{{ report.activity.refused }}</dd></div>
          </dl>
          <h3>Refilling freed slots</h3>
          <dl class="rows">
            <div><dt>Slots freed</dt><dd>{{ report.waitlist.slotsFreed }}</dd></div>
            <div><dt>Offers made</dt><dd>{{ report.waitlist.offersMade }}</dd></div>
            <div><dt>Offers accepted so far</dt><dd>{{ report.waitlist.offersAccepted }}</dd></div>
            <div><dt>On the waitlist now</dt><dd>{{ report.waitlist.waitingNow }}</dd></div>
          </dl>
        </section>

        <section class="panel">
          <h2>The agents</h2>
          <dl class="rows">
            <div><dt>Conversations</dt><dd>{{ report.agents.conversations }}</dd></div>
            <div><dt>Calls to the model</dt><dd>{{ report.agents.modelCalls }}</dd></div>
            <div><dt>Tokens read / written</dt><dd>{{ count(report.agents.inputTokens) }} / {{ count(report.agents.outputTokens) }}</dd></div>
            <div><dt>Estimated cost</dt><dd>{{ estimatedUsd(report.agents.estimatedCostUsd) }}</dd></div>
            <div>
              <dt>Estimated cost per booking</dt>
              <dd>{{ report.agents.estimatedCostPerBookingUsd === null ? '—' : estimatedUsd(report.agents.estimatedCostPerBookingUsd) }}</dd>
            </div>
            <div><dt>Replies stopped by the guards</dt><dd>{{ report.agents.blockedReplies }}</dd></div>
            <div><dt>Passed to a person</dt><dd>{{ report.agents.handovers }}</dd></div>
          </dl>
          @if (reasons(); as list) {
            @if (list.length > 0) {
              <h3>Why they passed it on</h3>
              <dl class="rows">
                @for (reason of list; track reason.code) {
                  <div><dt>{{ reason.label }}</dt><dd>{{ reason.total }}</dd></div>
                }
              </dl>
            }
          }
          <p class="hint">Cost is what the API would charge. The plan in use has no cost per call.</p>
        </section>
      </div>

      <p class="generated">
        Worked out from the records at {{ dateTime(report.generatedAt, report.timezone) }}, clinic time
        ({{ report.timezone }}), with no model involved. Later events can still change a past day.
      </p>
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading the records…</p>
    }
  `,
})
export class Report {
  private readonly api = inject(ConsoleApi);
  private readonly context = inject(ClinicContext);
  private readonly dateParam = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(map((params) => params.get('date'))),
    { initialValue: null },
  );

  protected readonly loader = new Loader<DailyReport>();
  protected readonly date = computed(() => dayFrom(this.dateParam(), this.context.timezone()));
  protected readonly reasons = computed(() =>
    Object.entries(this.loader.data()?.agents.handoversByReason ?? {}).map(([code, total]) => ({
      code,
      total,
      label: handoverReason(code),
    })),
  );

  protected readonly count = count;
  protected readonly money = money;
  protected readonly estimatedUsd = estimatedUsd;
  protected readonly dateTime = dateTime;

  constructor() {
    effect(() => this.loader.load(this.api.dailyReport(this.date())));
  }

  protected occupancy(report: DailyReport): string {
    return report.occupancy.percent === null ? 'Closed' : `${report.occupancy.percent}%`;
  }
}
