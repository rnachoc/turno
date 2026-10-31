"""Limits on how much anyone can ask of the agents, which cost a share of a paid plan per message.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import threading
import time
from collections.abc import Callable

# More keys than this at once means they are being made up, so old counts can go.
_MAX_KEYS = 10_000


class WindowLimiter:
    """Counts events per key in fixed windows, in memory."""

    def __init__(
        self, limit: int, window_seconds: int, now: Callable[[], float] = time.monotonic
    ) -> None:
        """Create the limiter.

        Args:
            limit: How many events a window allows.
            window_seconds: How long a window lasts.
            now: Provides the current time, in seconds.
        """
        self._limit = limit
        self._window_seconds = window_seconds
        self._now = now
        self._lock = threading.Lock()
        self._counts: dict[str, tuple[int, int]] = {}

    def allow(self, key: str) -> bool:
        """Count an event.

        Args:
            key: What is being limited.

        Returns:
            Whether the event is within the limit.
        """
        window = int(self._now() // self._window_seconds)
        with self._lock:
            if len(self._counts) >= _MAX_KEYS and key not in self._counts:
                self._counts = {k: v for k, v in self._counts.items() if v[0] == window}
                if len(self._counts) >= _MAX_KEYS:
                    self._counts.clear()
            seen_window, count = self._counts.get(key, (window, 0))
            count = count + 1 if seen_window == window else 1
            self._counts[key] = (window, count)
            return count <= self._limit

    def seconds_until_next_window(self) -> int:
        """Tell how long until the counts start again.

        Returns:
            The seconds, at least one.
        """
        return max(1, self._window_seconds - int(self._now() % self._window_seconds))
