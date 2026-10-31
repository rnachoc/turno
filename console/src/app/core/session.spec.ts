// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Session, sessionInterceptor, signedInGuard } from './session';

describe('sessionInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let session: Session;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    session = TestBed.inject(Session);
  });

  it('sends the token to the console\'s own API', () => {
    session.signIn('secret');

    http.get('/api/v1/clinic').subscribe();

    expect(backend.expectOne('/api/v1/clinic').request.headers.get('Authorization')).toBe('Bearer secret');
  });

  it('never sends the token anywhere else', () => {
    session.signIn('secret');

    http.get('https://elsewhere.example/api/v1/clinic').subscribe();
    http.get('/favicon.ico').subscribe();

    expect(backend.expectOne('https://elsewhere.example/api/v1/clinic').request.headers.has('Authorization')).toBe(false);
    expect(backend.expectOne('/favicon.ico').request.headers.has('Authorization')).toBe(false);
  });

  it('sends nothing before signing in', () => {
    http.get('/api/v1/clinic').subscribe();

    expect(backend.expectOne('/api/v1/clinic').request.headers.has('Authorization')).toBe(false);
  });

  it('ends the session when the core stops accepting the token', () => {
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    session.signIn('secret');

    http.get('/api/v1/clinic').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/clinic').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(session.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/login');
  });

  it.each([403, 404, 500])('keeps the session on a %i', (status) => {
    session.signIn('secret');

    http.get('/api/v1/clinic').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/clinic').flush(null, { status, statusText: 'No' });

    expect(session.signedIn()).toBe(true);
  });
});

describe('Session', () => {
  it('keeps nothing in the browser\'s storage', () => {
    localStorage.clear();
    sessionStorage.clear();
    const session = new Session();

    session.signIn('secret');

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
    session.signOut();
    expect(session.token()).toBeNull();
  });
});

describe('signedInGuard', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
  });

  const run = () => TestBed.runInInjectionContext(() => signedInGuard({} as never, {} as never));

  it('sends strangers to sign in', () => {
    expect(TestBed.inject(Router).serializeUrl(run() as never)).toBe('/login');
  });

  it('lets the staff through', () => {
    TestBed.inject(Session).signIn('secret');

    expect(run()).toBe(true);
  });
});
