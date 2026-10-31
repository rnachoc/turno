// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ConsoleApi, describeError, newIdempotencyKey } from './api';

describe('newIdempotencyKey', () => {
  it('fits what the core accepts and never repeats', () => {
    const keys = new Set(Array.from({ length: 100 }, () => newIdempotencyKey()));

    expect(keys.size).toBe(100);
    for (const key of keys) {
      expect(key).toMatch(/^[A-Za-z0-9_-]{8,128}$/);
    }
  });
});

describe('describeError', () => {
  const coded = (status: number, code: string) => new HttpErrorResponse({ status, error: { code } });

  it('says the core is out of reach when it is', () => {
    for (const status of [0, 502, 503, 504]) {
      expect(describeError(new HttpErrorResponse({ status }))).toContain('cannot be reached');
    }
  });

  it('has words for the codes a person can run into', () => {
    expect(describeError(coded(400, 'DATE_RANGE_INVALID'))).toBe('That date is not valid.');
    expect(describeError(coded(404, 'HANDOVER_NOT_FOUND'))).toContain('already dealt with');
    expect(describeError(coded(409, 'INVALID_STATE'))).toContain('already recorded');
    expect(describeError(coded(403, 'FORBIDDEN'))).toContain('cannot do that');
  });

  it('never shows what a server sent', () => {
    const hostile = new HttpErrorResponse({ status: 500, error: { code: '<img src=x>', detail: 'secret' } });

    expect(describeError(hostile)).toBe('The core could not do that. Nothing was changed.');
    expect(describeError(new Error('secret'))).toBe('Something went wrong.');
  });
});

describe('ConsoleApi', () => {
  let api: ConsoleApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(ConsoleApi);
    http = TestBed.inject(HttpTestingController);
  });

  it('reads the report of the day that was asked for', () => {
    api.dailyReport('2027-03-02').subscribe();

    const request = http.expectOne('/api/v1/staff/reports/daily?date=2027-03-02');
    expect(request.request.method).toBe('GET');
  });

  it.each([
    ['resolveHandover', '/api/v1/staff/handovers/h1/resolve', (a: ConsoleApi) => a.resolveHandover('h1')],
    ['markCompleted', '/api/v1/staff/appointments/a1/complete', (a: ConsoleApi) => a.markCompleted('a1')],
    ['markNoShow', '/api/v1/staff/appointments/a1/no-show', (a: ConsoleApi) => a.markNoShow('a1')],
  ])('%s posts with its own idempotency key and no body', (_name, url, call) => {
    call(api).subscribe();

    const request = http.expectOne(url).request;
    expect(request.method).toBe('POST');
    expect(request.body).toBeNull();
    expect(request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f]{32}$/);
  });

  it('gives each decision a new key', () => {
    api.markCompleted('a1').subscribe();
    const first = http.expectOne('/api/v1/staff/appointments/a1/complete').request;
    api.markCompleted('a1').subscribe();
    const second = http.expectOne('/api/v1/staff/appointments/a1/complete').request;

    expect(first.headers.get('Idempotency-Key')).not.toBe(second.headers.get('Idempotency-Key'));
  });
});
