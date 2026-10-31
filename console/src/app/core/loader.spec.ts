// Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { Loader } from './loader';

const newLoader = <T>() => TestBed.runInInjectionContext(() => new Loader<T>());

describe('Loader', () => {
  it('goes from loading to the answer', () => {
    const loader = newLoader<string>();
    const answer = new Subject<string>();

    loader.load(answer);
    expect(loader.loading()).toBe(true);
    expect(loader.data()).toBeNull();

    answer.next('today');
    expect(loader.loading()).toBe(false);
    expect(loader.data()).toBe('today');
  });

  it('never lets an older answer replace a newer one', () => {
    const loader = newLoader<string>();
    const monday = new Subject<string>();
    const tuesday = new Subject<string>();

    loader.load(monday);
    loader.load(tuesday);
    tuesday.next('tuesday');
    monday.next('monday');

    expect(loader.data()).toBe('tuesday');
    expect(monday.observed).toBe(false);
  });

  it('does not show old data beside an error', () => {
    const loader = newLoader<string>();
    const first = new Subject<string>();
    loader.load(first);
    first.next('today');

    loader.load(throwError(() => new HttpErrorResponse({ status: 503 })));

    expect(loader.data()).toBeNull();
    expect(loader.error()).toContain('cannot be reached');
    expect(loader.loading()).toBe(false);
  });

  it('clears the error when loading again', () => {
    const loader = newLoader<string>();
    loader.load(throwError(() => new HttpErrorResponse({ status: 503 })));

    loader.load(new Subject<string>());

    expect(loader.error()).toBeNull();
  });
});
