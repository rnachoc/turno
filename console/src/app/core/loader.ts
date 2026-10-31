// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { DestroyRef, inject, signal } from '@angular/core';
import { Observable, Subscription } from 'rxjs';
import { describeError } from './api';

/**
 * What a page reads from the core. A new load cancels the previous one, so going quickly through
 * several days can never leave the answer of an older request on the screen.
 */
export class Loader<T> {
  private readonly dataValue = signal<T | null>(null);
  private readonly errorValue = signal<string | null>(null);
  private readonly loadingValue = signal(false);
  private subscription: Subscription | null = null;

  readonly data = this.dataValue.asReadonly();
  readonly error = this.errorValue.asReadonly();
  readonly loading = this.loadingValue.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }

  load(source: Observable<T>): void {
    this.subscription?.unsubscribe();
    this.loadingValue.set(true);
    this.errorValue.set(null);
    this.subscription = source.subscribe({
      next: (value) => {
        this.dataValue.set(value);
        this.loadingValue.set(false);
      },
      error: (error: unknown) => {
        this.dataValue.set(null);
        this.errorValue.set(describeError(error));
        this.loadingValue.set(false);
      },
    });
  }
}
