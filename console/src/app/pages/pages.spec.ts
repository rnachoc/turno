// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Type } from '@angular/core';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../app.routes';
import { ConsoleApi } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { Session, sessionInterceptor } from '../core/session';
import { CLINIC, appointment, handover, report } from '../testing';
import { Agenda } from './agenda';
import { ConversationPage, Conversations } from './conversations';
import { DecisionsPage } from './decisions';
import { Login } from './login';
import { Report } from './report';
import { Shell } from './shell';

const FAILURE = { status: 500, statusText: 'Server Error' };

let http: HttpTestingController;

function text(fixture: ComponentFixture<unknown>): string {
  return (fixture.nativeElement as HTMLElement).textContent?.replace(/\s+/g, ' ') ?? '';
}

function button(fixture: ComponentFixture<unknown>, label: string): HTMLButtonElement {
  const found = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
    (candidate) => candidate.textContent?.trim() === label,
  );
  if (found === undefined) {
    throw new Error(`No button says ${label}`);
  }
  return found;
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
}

async function open<T>(component: Type<T>): Promise<ComponentFixture<T>> {
  const fixture = TestBed.createComponent(component);
  await settle(fixture);
  return fixture;
}

beforeEach(() => {
  TestBed.configureTestingModule({
    providers: [
      provideRouter(routes),
      provideHttpClient(withInterceptors([sessionInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  http = TestBed.inject(HttpTestingController);
  const context = TestBed.inject(ClinicContext);
  vi.spyOn(TestBed.inject(ConsoleApi), 'clinic').mockReturnValue(of(CLINIC));
  context.load().subscribe();
  for (const url of ['/api/v1/staff/handovers', '/api/v1/staff/appointments/awaiting-outcome']) {
    http.expectOne(url).flush([]);
  }
});

afterEach(() => {
  http.verify();
  vi.restoreAllMocks();
});

describe('Login', () => {
  async function signIn(fixture: ComponentFixture<Login>, token: string): Promise<void> {
    const form = (fixture.nativeElement as HTMLElement).querySelector('form') as HTMLFormElement;
    (form.elements.namedItem('token') as HTMLInputElement).value = token;
    form.dispatchEvent(new Event('submit'));
    await settle(fixture);
  }

  function answerWith(status: number): void {
    vi.spyOn(TestBed.inject(ClinicContext), 'load').mockRestore();
    http.expectOne('/api/v1/staff/handovers').flush(status === 200 ? [] : null, {
      status,
      statusText: 'x',
    });
    http.match('/api/v1/staff/appointments/awaiting-outcome').forEach((request) => {
      if (!request.cancelled) {
        request.flush([]);
      }
    });
  }

  it('asks for a token before calling anyone', async () => {
    const fixture = await open(Login);

    await signIn(fixture, '   ');

    expect(text(fixture)).toContain('Enter the staff token');
    expect(TestBed.inject(Session).signedIn()).toBe(false);
  });

  it('enters with the staff token', async () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = await open(Login);

    await signIn(fixture, 'staff-token');
    answerWith(200);

    expect(TestBed.inject(Session).token()).toBe('staff-token');
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it.each([
    [401, 'That is not the staff token.'],
    // The agents' token is valid for the clinic but not for the staff's lists.
    [403, 'That is not the staff token.'],
    [502, 'The core cannot be reached'],
  ])('stays out on a %i', async (status, message) => {
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = await open(Login);

    await signIn(fixture, 'some-token');
    answerWith(status);
    await settle(fixture);

    expect(text(fixture)).toContain(message);
    expect(TestBed.inject(Session).signedIn()).toBe(false);
  });
});

describe('Shell', () => {
  it('shows what waits, and nothing when nothing does', async () => {
    const fixture = await open(Shell);
    expect((fixture.nativeElement as HTMLElement).querySelector('.badge')).toBeNull();
    expect(text(fixture)).toContain('Test Clinic');

    TestBed.inject(ClinicContext).countPending().subscribe();
    http.expectOne('/api/v1/staff/handovers').flush([handover()]);
    http.expectOne('/api/v1/staff/appointments/awaiting-outcome').flush([appointment()]);
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).querySelector('.badge')?.textContent).toBe('2');
  });

  it('forgets the token and the clinic on signing out', async () => {
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    TestBed.inject(Session).signIn('secret');
    const fixture = await open(Shell);

    button(fixture, 'Sign out').click();

    expect(TestBed.inject(Session).token()).toBeNull();
    expect(TestBed.inject(ClinicContext).clinic()).toBeNull();
  });
});

describe('Report', () => {
  async function openWith(answer: ReturnType<typeof report>): Promise<ComponentFixture<Report>> {
    const fixture = await open(Report);
    http.expectOne((request) => request.url === '/api/v1/staff/reports/daily').flush(answer);
    await settle(fixture);
    return fixture;
  }

  it('shows the figures of the core', async () => {
    const fixture = await openWith(report());

    const shown = text(fixture);
    expect(shown).toContain('19%');
    expect(shown).toContain('€105.00');
    expect(shown).toContain('€80.00 lost');
    expect(shown).toContain('of 6 bookings that day');
    expect(shown).toContain('US$0.09');
    expect(shown).toContain('US$0.02');
    expect(shown).toContain('Asked a medical question');
    expect(shown).toContain('with no model involved');
  });

  it('says closed rather than zero percent', async () => {
    const fixture = await openWith(report({ occupancy: { openMinutes: 0, bookedMinutes: 0, percent: null } }));

    expect(text(fixture)).toContain('Closed');
    expect(text(fixture)).not.toContain('0%');
  });

  it('shows no cost per booking when there were no bookings', async () => {
    const base = report();
    const fixture = await openWith(
      report({ agents: { ...base.agents, estimatedCostPerBookingUsd: null } }),
    );

    expect(text(fixture)).toContain('Estimated cost per booking—');
  });

  it('points to what waits, in the right number', async () => {
    const fixture = await openWith(report({ attention: { openHandovers: 1, awaitingOutcome: 2 } }));

    expect(text(fixture)).toContain('1 person to call, 2 appointments to close');
    expect((fixture.nativeElement as HTMLElement).querySelector('a.attention')).not.toBeNull();
  });

  it('hides the notice when nothing waits', async () => {
    const fixture = await openWith(report());

    expect((fixture.nativeElement as HTMLElement).querySelector('a.attention')).toBeNull();
  });

  it('says so when the core fails', async () => {
    const fixture = await open(Report);

    http.expectOne((request) => request.url === '/api/v1/staff/reports/daily').flush(null, FAILURE);
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).querySelector('[role=alert]')?.textContent).toContain('could not do that');
  });
});

describe('Agenda', () => {
  it('shows who comes, and a hostile name as plain text', async () => {
    const fixture = await open(Agenda);

    http.expectOne((request) => request.url === '/api/v1/staff/agenda').flush([
      appointment({ patientName: '<img src=x onerror=alert(1)>' }),
    ]);
    await settle(fixture);

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(element.querySelector('img')).toBeNull();
  });
});

describe('Agenda views', () => {
  async function openAt(url: string): Promise<ComponentFixture<Agenda>> {
    TestBed.inject(Session).signIn('token');
    await TestBed.inject(Router).navigateByUrl(url);
    return open(Agenda);
  }

  afterEach(() => {
    for (const request of http.match((candidate) => candidate.url.startsWith('/api/v1/staff/agenda'))) {
      if (!request.cancelled) {
        request.flush([]);
      }
    }
  });

  function pending(): { url: string; from: string | null; to: string | null } {
    const [request] = http.match((candidate) => candidate.url.startsWith('/api/v1/staff/agenda'));
    return {
      url: request.request.url,
      from: request.request.params.get('from'),
      to: request.request.params.get('to'),
    };
  }

  it('asks for the whole week at once, from its Monday', async () => {
    const fixture = await openAt('/agenda?view=week&date=2027-03-03');

    expect(text(fixture)).toContain('1 – 7 March 2027');
    expect(pending()).toEqual({ url: '/api/v1/staff/agenda/range', from: '2027-03-01', to: '2027-03-07' });
  });

  it('asks for every day its calendar shows in a month', async () => {
    const fixture = await openAt('/agenda?view=month&date=2027-03-15');

    expect(text(fixture)).toContain('March 2027');
    expect(pending()).toEqual({ url: '/api/v1/staff/agenda/range', from: '2027-03-01', to: '2027-04-04' });
  });

  it('takes an unknown view as the day', async () => {
    await openAt('/agenda?view=year&date=2027-03-02');

    expect(http.expectOne((request) => request.url === '/api/v1/staff/agenda').request.params.get('date')).toBe(
      '2027-03-02',
    );
  });

  it('shows a hostile name as plain text in the week and in the month', async () => {
    for (const view of ['week', 'month']) {
      const fixture = await openAt(`/agenda?view=${view}&date=2027-03-02`);

      http
        .expectOne((request) => request.url === '/api/v1/staff/agenda/range')
        .flush([appointment({ patientName: '<img src=x onerror=alert(1)>' })]);
      await settle(fixture);

      const element = fixture.nativeElement as HTMLElement;
      expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
      expect(element.querySelector('img')).toBeNull();
      fixture.destroy();
    }
  });

  it('never draws the answer to one view as another', async () => {
    const fixture = await openAt('/agenda?view=month&date=2027-03-02');
    http.expectOne((request) => request.url === '/api/v1/staff/agenda/range').flush([appointment()]);
    await settle(fixture);
    expect((fixture.nativeElement as HTMLElement).querySelector('.month-day')).not.toBeNull();

    await TestBed.inject(Router).navigateByUrl('/agenda?view=day&date=2027-03-02');
    await settle(fixture);

    expect((fixture.nativeElement as HTMLElement).querySelector('.month-day')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).querySelector('.agenda-block')).toBeNull();
    expect(text(fixture)).toContain('Reading the agenda');
    http.expectOne((request) => request.url === '/api/v1/staff/agenda').flush([appointment()]);
    await settle(fixture);
    expect((fixture.nativeElement as HTMLElement).querySelector('.agenda-block')).not.toBeNull();
  });

  it.each([
    ['day', 'Next day', '2027-03-03'],
    ['week', 'Next week', '2027-03-09'],
    ['month', 'Next month', '2027-04-02'],
  ])('moves a %s at a time, and stays in that view', async (view, label, expected) => {
    const fixture = await openAt(`/agenda?view=${view}&date=2027-03-02`);
    const router = TestBed.inject(Router);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>(`[aria-label="${label}"]`)?.click();
    await settle(fixture);

    const params = router.parseUrl(router.url).queryParams;
    expect(params['date']).toBe(expected);
    expect(params['view']).toBe(view);
  });

  it('opens a day from the calendar of the month', async () => {
    const fixture = await openAt('/agenda?view=month&date=2027-03-15');
    http.expectOne((request) => request.url === '/api/v1/staff/agenda/range').flush([]);
    await settle(fixture);

    const days = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.month-day');
    expect(days[1].getAttribute('href')).toContain('view=day');
    expect(days[1].getAttribute('href')).toContain('date=2027-03-02');
  });

  it('keeps the day when the view changes', async () => {
    const fixture = await openAt('/agenda?view=day&date=2027-03-02');

    const links = (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLAnchorElement>('.view-switch a');
    expect(Array.from(links).map((link) => link.textContent?.trim())).toEqual(['Day', 'Week', 'Month']);
    expect(links[1].getAttribute('href')).toContain('view=week');
    expect(links[1].getAttribute('href')).toContain('date=2027-03-02');
    expect(links[0].getAttribute('aria-current')).toBe('true');
    expect(links[1].getAttribute('aria-current')).toBeNull();
  });
});

describe('Decisions', () => {
  async function openWith(handovers: unknown[], appointments: unknown[]): Promise<ComponentFixture<DecisionsPage>> {
    const fixture = await open(DecisionsPage);
    answer(handovers, appointments);
    await settle(fixture);
    return fixture;
  }

  /** Answers the lists and the count for the badge, which go out at the same time. */
  function answer(handovers: unknown[], appointments: unknown[]): void {
    for (const request of http.match('/api/v1/staff/handovers')) {
      request.flush(handovers as never);
    }
    for (const request of http.match('/api/v1/staff/appointments/awaiting-outcome')) {
      request.flush(appointments as never);
    }
  }

  it('says when nothing waits', async () => {
    const fixture = await openWith([], []);

    expect(text(fixture)).toContain('Nobody is waiting');
    expect(text(fixture)).toContain('No appointment is waiting');
  });

  it('marks a handover as dealt with once, then reads again', async () => {
    const fixture = await openWith([handover()], []);

    expect(text(fixture)).toContain('Asked a medical question');
    const dealtWith = button(fixture, 'Dealt with');
    dealtWith.click();
    await settle(fixture);
    expect(button(fixture, 'Dealt with').disabled).toBe(true);

    http.expectOne('/api/v1/staff/handovers/h1/resolve').flush({ status: 'RESOLVED' });
    answer([], []);
    await settle(fixture);

    expect(text(fixture)).toContain('Nobody is waiting');
    expect(TestBed.inject(ClinicContext).pending()).toBe(0);
  });

  it('keeps the badge in the navigation up to date', async () => {
    await openWith([handover()], [appointment()]);

    expect(TestBed.inject(ClinicContext).pending()).toBe(2);
  });

  it('marks an emergency as urgent', async () => {
    const fixture = await openWith([handover({ reason: 'triage_emergency' })], []);

    expect((fixture.nativeElement as HTMLElement).querySelector('.decision.urgent')).not.toBeNull();
    expect(text(fixture)).toContain('Described an emergency');
  });

  it('records attendance with one click', async () => {
    const fixture = await openWith([], [appointment()]);

    button(fixture, 'Attended').click();

    http.expectOne('/api/v1/staff/appointments/a1/complete').flush({});
    answer([], []);
  });

  it('asks before a no-show, saying what it costs', async () => {
    const fixture = await openWith([], [appointment({ depositCents: 1500 })]);

    button(fixture, 'No-show').click();
    await settle(fixture);

    http.expectNone('/api/v1/staff/appointments/a1/no-show');
    expect(text(fixture)).toContain('This records the deposit of €15.00 as lost.');

    button(fixture, 'Confirm no-show').click();
    http.expectOne('/api/v1/staff/appointments/a1/no-show').flush({});
    answer([], []);
  });

  it('lets someone change their mind about a no-show', async () => {
    const fixture = await openWith([], [appointment()]);

    button(fixture, 'No-show').click();
    await settle(fixture);
    expect(text(fixture)).toContain('No deposit is at stake.');

    button(fixture, 'Back').click();
    await settle(fixture);

    http.expectNone('/api/v1/staff/appointments/a1/no-show');
    expect(button(fixture, 'Attended')).toBeDefined();
  });

  it('says when someone got there first, and shows the new state', async () => {
    const fixture = await openWith([], [appointment()]);

    button(fixture, 'Attended').click();
    http
      .expectOne('/api/v1/staff/appointments/a1/complete')
      .flush({ code: 'INVALID_STATE' }, { status: 409, statusText: 'Conflict' });
    answer([], []);
    await settle(fixture);

    expect(text(fixture)).toContain('Someone has already recorded');
    expect(text(fixture)).toContain('No appointment is waiting');
  });

  it('calls with a phone link', async () => {
    const fixture = await openWith([handover()], []);

    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href^="tel:"]');
    expect(link?.getAttribute('href')).toBe('tel:+34600333444');
  });

  it('keeps the patient out of the address of the conversation', async () => {
    const fixture = await openWith([handover()], []);

    const link = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('a')).find(
      (candidate) => candidate.textContent?.trim() === 'what happened',
    );
    expect(link?.getAttribute('href')).toBe('/conversations/c1');
  });
});

describe('Conversations', () => {
  it('lists them and marks the ones that wait', async () => {
    const fixture = await open(Conversations);

    http.expectOne('/api/v1/staff/conversations').flush([
      {
        id: 'c1',
        patientId: 'p1',
        patientName: 'Laura Test',
        createdAt: '2027-03-02T09:00:00Z',
        steps: 3,
        estimatedCostUsd: 0.0123,
        waitingForPerson: true,
      },
    ]);
    await settle(fixture);

    const shown = text(fixture);
    expect(shown).toContain('Laura Test');
    expect(shown).toContain('2 Mar, 10:00');
    expect(shown).toContain('US$0.01');
    expect(shown).toContain('waiting for a person');
  });

  it('says when there are none', async () => {
    const fixture = await open(Conversations);

    http.expectOne('/api/v1/staff/conversations').flush([]);
    await settle(fixture);

    expect(text(fixture)).toContain('No conversation has been reported yet');
  });
});

describe('ConversationPage', () => {
  const step = (kind: string, detail: string, inputTokens = 0, outputTokens = 0, estimatedCostUsd = 0) => ({
    position: 0,
    kind,
    detail,
    inputTokens,
    outputTokens,
    estimatedCostUsd,
    seconds: 2,
    createdAt: '2027-03-02T09:00:00Z',
  });

  it('puts what the agent tried beside what the core recorded', async () => {
    const fixture = TestBed.createComponent(ConversationPage);
    fixture.componentRef.setInput('id', 'c1');
    await settle(fixture);

    http.expectOne('/api/v1/staff/conversations/c1').flush({
      id: 'c1',
      patientId: 'p1',
      patientName: 'Laura Test',
      steps: [
        { ...step('model', 'claude-opus-5-5', 1000, 200, 0.01), position: 0 },
        { ...step('model', 'claude-opus-5-5', 500, 100, 0.02), position: 1 },
        { ...step('handover', 'core_down'), position: 2 },
      ],
    });
    http.expectOne('/api/v1/staff/audit?patientId=p1&limit=100').flush([
      { id: 1, occurredAt: '2027-03-02T09:00:00Z', actor: 'agents', action: 'appointment.hold', outcome: 'REJECTED', entityId: null },
    ]);
    await settle(fixture);

    const shown = text(fixture);
    expect(shown).toContain('Laura Test');
    expect(shown).toContain('1,800');
    expect(shown).toContain('US$0.03');
    expect(shown).toContain('Passed to a person');
    expect(shown).toContain('appointment.hold');
    expect(shown).toContain('refused');
  });

  it('says when it does not exist', async () => {
    const fixture = TestBed.createComponent(ConversationPage);
    fixture.componentRef.setInput('id', 'nope');
    await settle(fixture);

    http
      .expectOne('/api/v1/staff/conversations/nope')
      .flush({ code: 'CONVERSATION_NOT_FOUND' }, { status: 404, statusText: 'Not Found' });
    await settle(fixture);

    expect(text(fixture)).toContain('That does not exist.');
  });
});
