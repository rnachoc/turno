// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { describeError } from '../core/api';
import { ClinicContext } from '../core/clinic-context';
import { Session } from '../core/session';

@Component({
  selector: 'app-login',
  template: `
    <main class="login">
      <form class="login-card" (submit)="signIn($event)">
        <p class="wordmark">Turno</p>
        <h1>Clinic console</h1>
        <label for="token">Staff token</label>
        <input
          #tokenInput
          id="token"
          type="password"
          autocomplete="off"
          spellcheck="false"
          required
          [disabled]="busy()"
          (input)="error.set(null)"
        />
        @if (error(); as message) {
          <p class="error" role="alert">{{ message }}</p>
        }
        <button class="primary" type="submit" [disabled]="busy()">
          {{ busy() ? 'Checking…' : 'Sign in' }}
        </button>
        <p class="hint">
          The token stays in this page's memory, so reloading asks for it again. Synthetic data only.
        </p>
      </form>
    </main>
  `,
})
export class Login {
  private readonly session = inject(Session);
  private readonly context = inject(ClinicContext);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected signIn(event: Event): void {
    event.preventDefault();
    const form = event.target as HTMLFormElement;
    const token = (form.elements.namedItem('token') as HTMLInputElement).value.trim();
    if (token === '') {
      this.error.set('Enter the staff token.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.session.signIn(token);
    this.context.load().subscribe({
      next: () => void this.router.navigateByUrl('/'),
      error: (error: unknown) => {
        this.session.signOut();
        this.context.clear();
        this.busy.set(false);
        this.error.set(this.explain(error));
      },
    });
  }

  private explain(error: unknown): string {
    if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
      return 'That is not the staff token.';
    }
    return describeError(error);
  }
}
