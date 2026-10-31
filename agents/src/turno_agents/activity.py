"""Passes what the agents did to the core, away from the conversations.

It runs on its own thread because nothing here may delay or break a reply: an emergency must be
answered even with the core hanging. It retries for as long as the process lives, because a handover
caused by a failure of the core is just what the clinic needs to hear about once the core is back.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import logging
import threading

from turno_agents.conversation import ConversationStore
from turno_agents.core_client import CoreApi, CoreRefusalError, CoreUnavailableError

LOG = logging.getLogger(__name__)

# The most the core accepts in one request.
MAX_STEPS_PER_REPORT = 100
_INTERVAL_SECONDS = 2.0


class ActivityReporter:
    """Sends the steps of every conversation to the core, in order and in the background."""

    def __init__(
        self, store: ConversationStore, core: CoreApi, interval: float = _INTERVAL_SECONDS
    ) -> None:
        """Create the reporter.

        Args:
            store: Where the conversations are.
            core: Access to the core.
            interval: Seconds between rounds.
        """
        self._store = store
        self._core = core
        self._interval = interval
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="activity-reporter", daemon=True)

    def start(self) -> None:
        """Start reporting in the background."""
        self._thread.start()

    def stop(self) -> None:
        """Stop reporting, after one last round."""
        self._stop.set()
        self._thread.join()

    def _run(self) -> None:
        while not self._stop.wait(self._interval):
            self.report_pending()
        self.report_pending()

    def report_pending(self) -> None:
        """Send what the core has not heard yet. It never raises: there is always a next round."""
        for conversation in self._store.all():
            while True:
                first = conversation.reported_steps
                steps = conversation.trace[first : first + MAX_STEPS_PER_REPORT]
                if not steps:
                    break
                try:
                    self._core.record_steps(conversation.patient_id, conversation.id, first, steps)
                except CoreUnavailableError:
                    LOG.warning("The core could not be told about the agents' work, retrying later")
                    return
                except CoreRefusalError as error:
                    # The same request would get the same answer, so retrying would block the rest.
                    LOG.error("The core refused the agents' steps with code %s", error.code)
                conversation.reported_steps = first + len(steps)
