"""Where outgoing messages go.

No provider is connected: this project has no accounts and no budget for them. So the only gateway
keeps what would have been sent, which is enough to run and test everything up to that point.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import json
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol

Channel = Literal["whatsapp", "email"]


class GatewayError(Exception):
    """A message could not be handed over."""


class Gateway(Protocol):
    """A way of reaching patients."""

    def send(self, channel: Channel, to: str, text: str) -> None:
        """Hand over a message.

        Args:
            channel: Which channel.
            to: The address on that channel.
            text: The message.

        Raises:
            GatewayError: If it could not be handed over.
        """
        ...


@dataclass(frozen=True)
class SentMessage:
    """A message that was handed over.

    Attributes:
        channel: Which channel.
        to: The address on that channel.
        text: The message.
    """

    channel: Channel
    to: str
    text: str


class SimulatedGateway:
    """Keeps messages in memory and, if asked, in a file a person can read."""

    def __init__(self, path: Path | None = None) -> None:
        """Create the gateway.

        Args:
            path: A file to add each message to, as one line of JSON.
        """
        self._path = path
        self._lock = threading.Lock()
        self.sent: list[SentMessage] = []

    def send(self, channel: Channel, to: str, text: str) -> None:
        """Keep a message.

        Args:
            channel: Which channel.
            to: The address on that channel.
            text: The message.

        Raises:
            GatewayError: If the file cannot be written.
        """
        with self._lock:
            self.sent.append(SentMessage(channel, to, text))
            if self._path is None:
                return
            line = json.dumps({"channel": channel, "to": to, "text": text}, ensure_ascii=False)
            try:
                with self._path.open("a", encoding="utf-8") as file:
                    file.write(line + "\n")
            except OSError as error:
                raise GatewayError("The file of sent messages cannot be written") from error
