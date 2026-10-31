// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { map, switchMap } from 'rxjs';
import { ConsoleApi } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { count, dateTime, estimatedUsd } from '../core/format';
import { Loader } from '../core/loader';
import { AgentStep, AuditEvent, ConversationDetail, ConversationSummary } from '../core/models';

@Component({
  selector: 'app-conversations',
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <h1>Conversations</h1>
    </div>
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (loader.data(); as conversations) {
      <section class="panel">
        <div class="table-scroll" tabindex="0" role="region" aria-label="Latest conversations">
          <table>
            <thead>
              <tr>
                <th scope="col">Patient</th>
                <th scope="col">Started</th>
                <th scope="col" class="number">Steps</th>
                <th scope="col" class="number">Est. cost</th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              @for (conversation of conversations; track conversation.id) {
                <tr>
                  <td>
                    <a [routerLink]="['/conversations', conversation.id]">{{ conversation.patientName }}</a>
                  </td>
                  <td>{{ when(conversation.createdAt) }}</td>
                  <td class="number">{{ conversation.steps }}</td>
                  <td class="number">{{ estimatedUsd(conversation.estimatedCostUsd) }}</td>
                  <td>
                    @if (conversation.waitingForPerson) {
                      <span class="tag urgent">waiting for a person</span>
                    } @else {
                      <span class="hint">with the agent or closed</span>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="empty">No conversation has been reported yet.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading the conversations…</p>
    }
  `,
})
export class Conversations {
  private readonly api = inject(ConsoleApi);
  private readonly context = inject(ClinicContext);
  protected readonly loader = new Loader<ConversationSummary[]>();
  protected readonly estimatedUsd = estimatedUsd;

  constructor() {
    this.loader.load(this.api.conversations());
  }

  protected when(instant: string): string {
    return dateTime(instant, this.context.timezone());
  }
}

interface Trace {
  readonly conversation: ConversationDetail;
  readonly audit: AuditEvent[];
}

const STEP_LABELS: Readonly<Record<string, string>> = {
  model: 'Asked the model',
  consent: 'Consent',
  action: 'The core did it',
  refused: 'The core refused',
  blocked: 'A guard stopped the reply',
  rejected: 'Answer in the wrong format',
  handover: 'Passed to a person',
};

/**
 * What happened in a conversation: on one side what the agent tried, on the other what the core
 * recorded. It holds codes and figures, because what patients write is not kept.
 */
@Component({
  selector: 'app-conversation',
  imports: [RouterLink],
  template: `
    <div class="page-head">
      <h1>{{ loader.data()?.conversation?.patientName ?? 'Conversation' }}</h1>
      <a routerLink="/conversations">All conversations</a>
    </div>
    @if (loader.error(); as message) {
      <p class="error" role="alert">{{ message }}</p>
    }
    @if (loader.data(); as trace) {
      <section class="figures" aria-label="Totals">
        <div class="figure"><span class="value">{{ trace.conversation.steps.length }}</span><span class="label">Steps</span></div>
        <div class="figure"><span class="value">{{ modelCalls() }}</span><span class="label">Calls to the model</span></div>
        <div class="figure"><span class="value">{{ count(tokens()) }}</span><span class="label">Tokens</span></div>
        <div class="figure"><span class="value">{{ estimatedUsd(cost()) }}</span><span class="label">Estimated cost</span></div>
      </section>
      <div class="columns two">
        <section class="panel">
          <h2>What the agent tried</h2>
          <ol class="trace">
            @for (step of trace.conversation.steps; track step.position) {
              <li [class]="'trace-step kind-' + step.kind">
                <strong>{{ label(step.kind) }}</strong>
                <code>{{ step.detail }}</code>
                @if (step.kind === 'model') {
                  <span class="hint">
                    {{ count(step.inputTokens) }} in, {{ count(step.outputTokens) }} out ·
                    {{ step.seconds }} s · {{ estimatedUsd(step.estimatedCostUsd) }}
                  </span>
                }
              </li>
            } @empty {
              <li class="empty">No steps.</li>
            }
          </ol>
        </section>
        <section class="panel">
          <h2>What the core recorded for this patient</h2>
          <ol class="trace">
            @for (event of trace.audit; track event.id) {
              <li class="trace-step" [class.refused]="event.outcome === 'REJECTED'">
                <strong>{{ event.action }}</strong>
                <span>{{ event.outcome === 'REJECTED' ? 'refused' : 'accepted' }} · {{ event.actor }}</span>
                <span class="hint">{{ when(event.occurredAt) }}</span>
              </li>
            } @empty {
              <li class="empty">No records.</li>
            }
          </ol>
        </section>
      </div>
      <p class="generated">Patients' words are not kept in this record, only codes and figures.</p>
    } @else if (loader.loading()) {
      <p class="hint" role="status">Reading the record…</p>
    }
  `,
})
export class ConversationPage {
  private readonly api = inject(ConsoleApi);
  private readonly context = inject(ClinicContext);
  readonly id = input.required<string>();

  protected readonly loader = new Loader<Trace>();
  protected readonly modelCalls = computed(
    () => this.steps().filter((step) => step.kind === 'model').length,
  );
  protected readonly tokens = computed(() =>
    this.steps().reduce((total, step) => total + step.inputTokens + step.outputTokens, 0),
  );
  protected readonly cost = computed(() =>
    this.steps().reduce((total, step) => total + step.estimatedCostUsd, 0),
  );
  protected readonly count = count;
  protected readonly estimatedUsd = estimatedUsd;

  constructor() {
    effect(() => {
      this.loader.load(
        this.api.conversation(this.id()).pipe(
          switchMap((conversation) =>
            this.api
              .auditOfPatient(conversation.patientId)
              .pipe(map((audit) => ({ conversation, audit }))),
          ),
        ),
      );
    });
  }

  private steps(): readonly AgentStep[] {
    return this.loader.data()?.conversation.steps ?? [];
  }

  protected label(kind: string): string {
    return STEP_LABELS[kind] ?? kind;
  }

  protected when(instant: string): string {
    return dateTime(instant, this.context.timezone());
  }
}
