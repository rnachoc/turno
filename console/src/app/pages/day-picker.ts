// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Component, computed, inject, input } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ClinicContext } from '../core/clinic-context';
import { addDays, isDate, longDate, todayAt } from '../core/format';

/** Reads the day of a page from its address, so that a day can be linked and reloaded. */
export function dayFrom(param: string | null, timezone: string): string {
  return param !== null && isDate(param) ? param : todayAt(timezone);
}

@Component({
  selector: 'app-day-picker',
  template: `
    <div class="day-picker">
      <button class="quiet" type="button" aria-label="Previous day" (click)="go(-1)">←</button>
      <h1>{{ title() }}</h1>
      <button class="quiet" type="button" aria-label="Next day" (click)="go(1)">→</button>
      @if (!isToday()) {
        <button class="quiet" type="button" (click)="today()">Today</button>
      }
    </div>
  `,
})
export class DayPicker {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly context = inject(ClinicContext);

  readonly date = input.required<string>();

  protected readonly title = computed(() => longDate(this.date()));
  protected readonly isToday = computed(() => this.date() === todayAt(this.context.timezone()));

  protected go(days: number): void {
    this.navigate(addDays(this.date(), days));
  }

  protected today(): void {
    this.navigate(todayAt(this.context.timezone()));
  }

  private navigate(date: string): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: { date } });
  }
}
