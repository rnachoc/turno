// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, forkJoin, map, tap } from 'rxjs';
import { ConsoleApi } from './api';
import { Clinic } from './models';

/** The clinic and what waits for someone, known from sign-in until sign-out. */
@Injectable({ providedIn: 'root' })
export class ClinicContext {
  private readonly api = inject(ConsoleApi);
  private readonly clinicValue = signal<Clinic | null>(null);
  private readonly pendingValue = signal<number | null>(null);

  readonly clinic = this.clinicValue.asReadonly();
  readonly pending = this.pendingValue.asReadonly();
  readonly timezone = computed(() => this.clinicValue()?.timezone ?? 'UTC');
  readonly currency = computed(() => this.clinicValue()?.currency ?? 'EUR');

  /** Loads the clinic. Reading a staff-only list too makes this fail for any other token. */
  load(): Observable<void> {
    return forkJoin([this.api.clinic(), this.countPending()]).pipe(
      tap(([clinic]) => this.clinicValue.set(clinic)),
      map(() => undefined),
    );
  }

  clear(): void {
    this.clinicValue.set(null);
    this.pendingValue.set(null);
  }

  /** Counts the decisions that wait for someone, for the badge in the navigation. */
  countPending(): Observable<number> {
    return forkJoin([this.api.handovers(), this.api.awaitingOutcome()]).pipe(
      map(([handovers, appointments]) => handovers.length + appointments.length),
      tap((pending) => this.pendingValue.set(pending)),
    );
  }

  treatmentName(id: string): string {
    return this.clinicValue()?.treatments.find((treatment) => treatment.id === id)?.name['en'] ?? id;
  }

  practitionerName(id: string): string {
    return this.clinicValue()?.practitioners.find((person) => person.id === id)?.name ?? id;
  }
}
