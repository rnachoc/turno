// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, forkJoin } from 'rxjs';
import { ConsoleApi, describeError } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { dateTime, handoverReason, money } from '../core/format';
import { Loader } from '../core/loader';
import { AgendaEntry, Handover } from '../core/models';

interface Decisions {
  readonly handovers: Handover[];
  readonly appointments: AgendaEntry[];
}

/**
 * What the code leaves to a person. Nothing here is decided by a model: the agents only pass
 * conversations on, and the core applies its own rules to what the staff decide.
 */
@Component({
  selector: 'app-decisions',
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <h1>Decisions</h1>
      <button class="quiet" type="button" [disabled]="loader.loading()" (click)="refresh()">Refresh</button>
    </div>
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (actionError(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (loader.data(); as decisions) {
      <section class="panel">
        <h2>People waiting for a call</h2>
        @for (handover of decisions.handovers; track handover.id) {
          <article class="decision" [class.urgent]="handover.reason === 'triage_emergency'">
            <div class="decision-main">
              <strong>{{ handover.patientName }}</strong>
              <a [href]="'tel:' + handover.patientPhone">{{ handover.patientPhone }}</a>
              <span>{{ reason(handover.reason) }}</span>
              <span class="hint">
                Since {{ when(handover.createdAt) }} ·
                <a [routerLink]="['/conversations', handover.conversationId]">what happened</a>
              </span>
            </div>
            <button
              class="primary"
              type="button"
              [disabled]="busy() === handover.id"
              (click)="act(handover.id, api.resolveHandover(handover.id))"
            >
              Dealt with
            </button>
          </article>
        } @empty {
          <p class="empty">Nobody is waiting.</p>
        }
      </section>

      <section class="panel">
        <h2>Did they come?</h2>
        @for (entry of decisions.appointments; track entry.appointmentId) {
          <article class="decision">
            <div class="decision-main">
              <strong>{{ entry.patientName }}</strong>
              <span>
                {{ when(entry.startsAt) }} · {{ context.treatmentName(entry.treatmentId) }} ·
                {{ context.practitionerName(entry.practitionerId) }}
              </span>
              <span class="hint">{{ price(entry) }}</span>
            </div>
            @if (confirmingNoShow() === entry.appointmentId) {
              <div class="confirm" role="group" aria-label="Confirm the no-show">
                <span>{{ noShowConsequence(entry) }}</span>
                <button
                  class="danger"
                  type="button"
                  [disabled]="busy() === entry.appointmentId"
                  (click)="act(entry.appointmentId, api.markNoShow(entry.appointmentId))"
                >
                  Confirm no-show
                </button>
                <button class="quiet" type="button" (click)="confirmingNoShow.set(null)">Back</button>
              </div>
            } @else {
              <div class="actions">
                <button
                  class="primary"
                  type="button"
                  [disabled]="busy() === entry.appointmentId"
                  (click)="act(entry.appointmentId, api.markCompleted(entry.appointmentId))"
                >
                  Attended
                </button>
                <button
                  class="quiet"
                  type="button"
                  [disabled]="busy() === entry.appointmentId"
                  (click)="confirmingNoShow.set(entry.appointmentId)"
                >
                  No-show
                </button>
              </div>
            }
          </article>
        } @empty {
          <p class="empty">No appointment is waiting for an outcome.</p>
        }
      </section>
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading what is waiting…</p>
    }
  `,
})
export class DecisionsPage {
  protected readonly api = inject(ConsoleApi);
  protected readonly context = inject(ClinicContext);
  protected readonly loader = new Loader<Decisions>();
  protected readonly busy = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly confirmingNoShow = signal<string | null>(null);

  constructor() {
    this.refresh();
  }

  protected refresh(): void {
    this.loader.load(
      forkJoin({ handovers: this.api.handovers(), appointments: this.api.awaitingOutcome() }),
    );
    this.context.countPending().subscribe({ error: () => undefined });
  }

  protected act(id: string, action: Observable<unknown>): void {
    this.busy.set(id);
    this.actionError.set(null);
    action.subscribe({
      next: () => this.finish(),
      error: (error: unknown) => {
        this.actionError.set(describeError(error));
        this.finish();
      },
    });
  }

  private finish(): void {
    this.busy.set(null);
    this.confirmingNoShow.set(null);
    this.refresh();
  }

  protected reason(code: string): string {
    return handoverReason(code);
  }

  protected when(instant: string): string {
    return dateTime(instant, this.context.timezone());
  }

  protected price(entry: AgendaEntry): string {
    return money(entry.priceCents, this.context.currency());
  }

  protected noShowConsequence(entry: AgendaEntry): string {
    const keepsDeposit = this.context.clinic()?.cancellation.noShowKeepsDeposit ?? false;
    return keepsDeposit && entry.depositCents > 0
      ? `This records the deposit of ${money(entry.depositCents, this.context.currency())} as lost.`
      : 'No deposit is at stake.';
  }
}
