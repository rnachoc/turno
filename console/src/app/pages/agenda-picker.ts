// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, inject, input } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ClinicContext } from '../core/clinic-context';
import { addDays, addMonths, longDate, mondayOf, monthTitle, todayAt, weekTitle } from '../core/format';

export type AgendaView = 'day' | 'week' | 'month';

/** Reads the view of the agenda from its address, so that it can be linked and reloaded. */
export function viewFrom(param: string | null): AgendaView {
  return param === 'week' || param === 'month' ? param : 'day';
}

@Component({
  selector: 'app-agenda-picker',
  imports: [RouterLink],
  template: `
    <div class="day-picker">
      <button class="quiet" type="button" [attr.aria-label]="'Previous ' + view()" (click)="go(-1)">←</button>
      <h1>{{ title() }}</h1>
      <button class="quiet" type="button" [attr.aria-label]="'Next ' + view()" (click)="go(1)">→</button>
      @if (!showsToday()) {
        <button class="quiet" type="button" (click)="today()">Today</button>
      }
      <nav class="view-switch" aria-label="Agenda view">
        @for (option of options; track option.view) {
          <a
            [routerLink]="[]"
            [queryParams]="{ view: option.view }"
            queryParamsHandling="merge"
            [class.active]="option.view === view()"
            [attr.aria-current]="option.view === view() ? 'true' : null"
          >
            {{ option.label }}
          </a>
        }
      </nav>
    </div>
  `,
})
export class AgendaPicker {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly context = inject(ClinicContext);

  readonly view = input.required<AgendaView>();
  readonly date = input.required<string>();

  protected readonly options: readonly { readonly view: AgendaView; readonly label: string }[] = [
    { view: 'day', label: 'Day' },
    { view: 'week', label: 'Week' },
    { view: 'month', label: 'Month' },
  ];

  protected readonly title = computed(() => {
    switch (this.view()) {
      case 'week':
        return weekTitle(mondayOf(this.date()));
      case 'month':
        return monthTitle(this.date());
      default:
        return longDate(this.date());
    }
  });

  protected readonly showsToday = computed(() => {
    const today = todayAt(this.context.timezone());
    switch (this.view()) {
      case 'week':
        return mondayOf(this.date()) === mondayOf(today);
      case 'month':
        return this.date().slice(0, 7) === today.slice(0, 7);
      default:
        return this.date() === today;
    }
  });

  protected go(step: number): void {
    switch (this.view()) {
      case 'week':
        this.navigate(addDays(this.date(), 7 * step));
        break;
      case 'month':
        this.navigate(addMonths(this.date(), step));
        break;
      default:
        this.navigate(addDays(this.date(), step));
    }
  }

  protected today(): void {
    this.navigate(todayAt(this.context.timezone()));
  }

  private navigate(date: string): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { date },
      queryParamsHandling: 'merge',
    });
  }
}
