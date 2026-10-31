// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  AgendaEntry,
  ConversationDetail,
  AuditEvent,
  Clinic,
  ConversationSummary,
  DailyReport,
  Handover,
} from './models';

const API = '/api/v1';
const STAFF = `${API}/staff`;

/** One key per decision of a person, so that a retry of the same click cannot act twice. */
export function newIdempotencyKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Words for a person. The core's codes are stable, while its messages are not meant for reading aloud. */
export function describeError(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong.';
  }
  if (error.status === 0 || error.status === 502 || error.status === 503 || error.status === 504) {
    return 'The core cannot be reached right now. Nothing was changed.';
  }
  const code: unknown = (error.error as { code?: unknown } | null)?.code;
  switch (code) {
    case 'DATE_RANGE_INVALID':
    case 'VALIDATION_FAILED':
      return 'That date is not valid.';
    case 'FORBIDDEN':
      return 'This token cannot do that.';
    case 'HANDOVER_NOT_FOUND':
      return 'Someone has already dealt with this.';
    case 'INVALID_STATE':
      return 'Someone has already recorded what happened with this appointment.';
    case 'APPOINTMENT_NOT_STARTED':
      return 'This appointment has not started yet.';
    case 'CONVERSATION_NOT_FOUND':
    case 'NOT_FOUND':
      return 'That does not exist.';
    default:
      return 'The core could not do that. Nothing was changed.';
  }
}

/** The core's API, as the clinic's staff use it. */
@Injectable({ providedIn: 'root' })
export class ConsoleApi {
  private readonly http = inject(HttpClient);

  clinic(): Observable<Clinic> {
    return this.http.get<Clinic>(`${API}/clinic`);
  }

  dailyReport(date: string): Observable<DailyReport> {
    return this.http.get<DailyReport>(`${STAFF}/reports/daily`, { params: { date } });
  }

  agenda(date: string): Observable<AgendaEntry[]> {
    return this.http.get<AgendaEntry[]>(`${STAFF}/agenda`, { params: { date } });
  }

  awaitingOutcome(): Observable<AgendaEntry[]> {
    return this.http.get<AgendaEntry[]>(`${STAFF}/appointments/awaiting-outcome`);
  }

  handovers(): Observable<Handover[]> {
    return this.http.get<Handover[]>(`${STAFF}/handovers`);
  }

  resolveHandover(id: string): Observable<unknown> {
    return this.act(`${STAFF}/handovers/${id}/resolve`);
  }

  markCompleted(appointmentId: string): Observable<unknown> {
    return this.act(`${STAFF}/appointments/${appointmentId}/complete`);
  }

  markNoShow(appointmentId: string): Observable<unknown> {
    return this.act(`${STAFF}/appointments/${appointmentId}/no-show`);
  }

  conversations(): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>(`${STAFF}/conversations`);
  }

  conversation(id: string): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`${STAFF}/conversations/${id}`);
  }

  auditOfPatient(patientId: string): Observable<AuditEvent[]> {
    return this.http.get<AuditEvent[]>(`${STAFF}/audit`, {
      params: new HttpParams().set('patientId', patientId).set('limit', 100),
    });
  }

  private act(url: string): Observable<unknown> {
    const headers = new HttpHeaders({ 'Idempotency-Key': newIdempotencyKey() });
    return this.http.post(url, null, { headers });
  }
}
