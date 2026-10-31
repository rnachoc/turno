"""Delivers a WhatsApp message to the local agents service, signed as a provider would.

It runs the real webhook, so it needs `make agents` running with TURNO_CHANNEL_WEBHOOK_SECRET set.
Answers go to the simulated gateway: with TURNO_CHANNEL_OUTBOX set, they are in that file.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import argparse
import json
import os
import secrets
import sys
import time

import httpx2

from turno_agents.channels.webhooks import SIGNATURE_HEADER, sign


def main() -> int:
    """Deliver one message.

    Returns:
        The exit code.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("text", help="The message")
    parser.add_argument("--phone", default="34600111222", help="The sender, with no plus sign")
    parser.add_argument("--name", default="Laura Test", help="The name on their profile")
    parser.add_argument("--url", default="http://127.0.0.1:8001")
    args = parser.parse_args()
    secret = os.environ.get("TURNO_CHANNEL_WEBHOOK_SECRET")
    if not secret:
        print("Set TURNO_CHANNEL_WEBHOOK_SECRET", file=sys.stderr)
        return 2
    payload = {
        "entry": [
            {
                "changes": [
                    {
                        "value": {
                            "contacts": [{"wa_id": args.phone, "profile": {"name": args.name}}],
                            "messages": [
                                {
                                    "id": secrets.token_hex(8),
                                    "from": args.phone,
                                    "timestamp": str(int(time.time())),
                                    "type": "text",
                                    "text": {"body": args.text},
                                }
                            ],
                        }
                    }
                ]
            }
        ]
    }
    body = json.dumps(payload).encode("utf-8")
    response = httpx2.post(
        f"{args.url.rstrip('/')}/webhooks/whatsapp",
        content=body,
        headers={SIGNATURE_HEADER: sign(secret, body), "Content-Type": "application/json"},
    )
    print(response.status_code, response.text)
    return 0 if response.status_code == 200 else 1


if __name__ == "__main__":
    sys.exit(main())
