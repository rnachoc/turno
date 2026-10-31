// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// The answers of the core's API. Field names are the API's: nothing here renames them.

export interface TimeWindow {
  readonly open: string;
  readonly close: string;
}

export interface Practitioner {
  readonly id: string;
  readonly name: string;
  readonly workingDays: readonly string[];
}

export interface Treatment {
  readonly id: string;
  readonly name: Readonly<Record<string, string>>;
}

export interface Clinic {
  readonly name: string;
  readonly fictional: boolean;
  readonly timezone: string;
  readonly currency: string;
  readonly openingHours: Readonly<Record<string, readonly TimeWindow[]>>;
  readonly closures: readonly string[];
  readonly treatments: readonly Treatment[];
  readonly practitioners: readonly Practitioner[];
  readonly cancellation: { readonly noShowKeepsDeposit: boolean };
}

export interface DailyReport {
  readonly date: string;
  readonly generatedAt: string;
  readonly timezone: string;
  readonly currency: string;
  readonly agenda: {
    readonly held: number;
    readonly confirmed: number;
    readonly completed: number;
    readonly noShow: number;
    readonly cancelled: number;
  };
  readonly money: {
    readonly completedCents: number;
    readonly noShowCents: number;
    readonly forfeitedDepositCents: number;
  };
  readonly occupancy: {
    readonly openMinutes: number;
    readonly bookedMinutes: number;
    readonly percent: number | null;
  };
  readonly activity: {
    readonly booked: number;
    readonly bookedByAgents: number;
    readonly cancelled: number;
    readonly rescheduled: number;
    readonly refused: number;
  };
  readonly waitlist: {
    readonly slotsFreed: number;
    readonly offersMade: number;
    readonly offersAccepted: number;
    readonly recoveredCents: number;
    readonly waitingNow: number;
  };
  readonly agents: {
    readonly conversations: number;
    readonly modelCalls: number;
    readonly inputTokens: number;
    readonly outputTokens: number;
    readonly estimatedCostUsd: number;
    readonly estimatedCostPerBookingUsd: number | null;
    readonly blockedReplies: number;
    readonly handovers: number;
    readonly handoversByReason: Readonly<Record<string, number>>;
  };
  readonly attention: {
    readonly openHandovers: number;
    readonly awaitingOutcome: number;
  };
}

export type AppointmentStatus = 'HOLD' | 'CONFIRMED' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED';

export interface AgendaEntry {
  readonly appointmentId: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly patientPhone: string;
  readonly practitionerId: string;
  readonly treatmentId: string;
  readonly startsAt: string;
  readonly endsAt: string;
  readonly status: AppointmentStatus;
  readonly priceCents: number;
  readonly depositCents: number;
  readonly depositForfeited: boolean;
}

export interface Handover {
  readonly id: string;
  readonly conversationId: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly patientPhone: string;
  readonly reason: string;
  readonly createdAt: string;
}

export interface ConversationSummary {
  readonly id: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly createdAt: string;
  readonly steps: number;
  readonly estimatedCostUsd: number;
  readonly waitingForPerson: boolean;
}

export interface ConversationDetail {
  readonly id: string;
  readonly patientId: string;
  readonly patientName: string;
  readonly steps: readonly AgentStep[];
}

export interface AgentStep {
  readonly position: number;
  readonly kind: string;
  readonly detail: string;
  readonly inputTokens: number;
  readonly outputTokens: number;
  readonly estimatedCostUsd: number;
  readonly seconds: number;
  readonly createdAt: string;
}

export interface AuditEvent {
  readonly id: number;
  readonly occurredAt: string;
  readonly actor: string;
  readonly action: string;
  readonly outcome: 'ACCEPTED' | 'REJECTED';
  readonly entityId: string | null;
}
