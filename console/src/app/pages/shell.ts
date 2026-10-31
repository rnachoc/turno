// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ClinicContext } from '../core/clinic-context';
import { Session } from '../core/session';

@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <div class="shell">
      <header class="rail">
        <div class="brand">
          <span class="wordmark">Turno</span>
          <span class="clinic-name">{{ context.clinic()?.name }}</span>
        </div>
        <nav aria-label="Main">
          <a routerLink="/report" routerLinkActive="active" ariaCurrentWhenActive="page">Daily report</a>
          <a routerLink="/agenda" routerLinkActive="active" ariaCurrentWhenActive="page">Agenda</a>
          <a routerLink="/decisions" routerLinkActive="active" ariaCurrentWhenActive="page">
            Decisions
            @if (context.pending(); as pending) {
              <span class="badge" [attr.aria-label]="pending + ' waiting'">{{ pending }}</span>
            }
          </a>
          <a routerLink="/conversations" routerLinkActive="active" ariaCurrentWhenActive="page">Conversations</a>
        </nav>
        <button class="quiet sign-out" type="button" (click)="signOut()">Sign out</button>
      </header>
      <main class="content">
        <router-outlet />
      </main>
    </div>
  `,
})
export class Shell {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  protected readonly context = inject(ClinicContext);

  protected signOut(): void {
    this.session.signOut();
    this.context.clear();
    void this.router.navigateByUrl('/login');
  }
}
