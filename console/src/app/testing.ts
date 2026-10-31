// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
// What the tests of the pages share: a clinic and answers shaped like the core's.
import { AgendaEntry, Clinic, DailyReport, Handover } from './core/models';

const WORKDAY = [
  { open: '09:00:00', close: '14:00:00' },
  { open: '16:00:00', close: '20:00:00' },
];

export const CLINIC: Clinic = {
  name: 'Test Clinic',
  fictional: true,
  timezone: 'Europe/Madrid',
  currency: 'EUR',
  openingHours: { MONDAY: WORKDAY, TUESDAY: WORKDAY, SATURDAY: [], SUNDAY: [] },
  closures: ['2027-03-03'],
  treatments: [{ id: 'check-up', name: { es: 'Revisión', en: 'Check-up' } }],
  practitioners: [
    { id: 'ana', name: 'Ana Gil', workingDays: ['MONDAY', 'TUESDAY'] },
    { id: 'ben', name: 'Ben Ruiz', workingDays: ['MONDAY'] },
  ],
  cancellation: { noShowKeepsDeposit: true },
};

export function appointment(overrides: Partial<AgendaEntry> = {}): AgendaEntry {
  return {
    appointmentId: 'a1',
    patientId: 'p1',
    patientName: 'Laura Test',
    patientPhone: '+34600111222',
    practitionerId: 'ana',
    treatmentId: 'check-up',
    // 10:00 to 10:30 in Madrid.
    startsAt: '2027-03-02T09:00:00Z',
    endsAt: '2027-03-02T09:30:00Z',
    status: 'CONFIRMED',
    priceCents: 3500,
    depositCents: 0,
    depositForfeited: false,
    ...overrides,
  };
}

export function handover(overrides: Partial<Handover> = {}): Handover {
  return {
    id: 'h1',
    conversationId: 'c1',
    patientId: 'p1',
    patientName: 'Marcos Prueba',
    patientPhone: '+34600333444',
    reason: 'triage_medical',
    createdAt: '2027-03-02T09:00:00Z',
    ...overrides,
  };
}

export function report(overrides: Partial<DailyReport> = {}): DailyReport {
  return {
    date: '2027-03-02',
    generatedAt: '2027-03-02T12:00:00Z',
    timezone: 'Europe/Madrid',
    currency: 'EUR',
    agenda: { held: 1, confirmed: 2, completed: 3, noShow: 4, cancelled: 5 },
    money: { completedCents: 10500, noShowCents: 8000, forfeitedDepositCents: 1500 },
    occupancy: { openMinutes: 540, bookedMinutes: 105, percent: 19 },
    activity: { booked: 6, bookedByAgents: 4, cancelled: 1, rescheduled: 2, refused: 3 },
    waitlist: { slotsFreed: 2, offersMade: 3, offersAccepted: 1, recoveredCents: 3500, waitingNow: 7 },
    agents: {
      conversations: 8,
      modelCalls: 9,
      inputTokens: 12000,
      outputTokens: 900,
      estimatedCostUsd: 0.09,
      estimatedCostPerBookingUsd: 0.0225,
      blockedReplies: 1,
      handovers: 2,
      handoversByReason: { triage_medical: 2 },
    },
    attention: { openHandovers: 0, awaitingOutcome: 0 },
    ...overrides,
  };
}
