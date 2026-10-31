// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { tap } from 'rxjs';

/**
 * Holds the staff token in memory and nowhere else, so that nothing outlives the page: a script
 * cannot read it back from storage, and reloading asks for it again.
 */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly tokenValue = signal<string | null>(null);

  readonly token = this.tokenValue.asReadonly();
  readonly signedIn = computed(() => this.tokenValue() !== null);

  signIn(token: string): void {
    this.tokenValue.set(token);
  }

  signOut(): void {
    this.tokenValue.set(null);
  }
}

/** Sends the token to this console's own API only, and ends the session when the core refuses it. */
export const sessionInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(Session);
  const router = inject(Router);
  const token = session.token();
  if (token === null || !request.url.startsWith('/api/')) {
    return next(request);
  }
  const authorized = request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  return next(authorized).pipe(
    tap({
      error: (error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) {
          session.signOut();
          void router.navigateByUrl('/login');
        }
      },
    }),
  );
};

export const signedInGuard: CanActivateFn = () =>
  inject(Session).signedIn() || inject(Router).createUrlTree(['/login']);
