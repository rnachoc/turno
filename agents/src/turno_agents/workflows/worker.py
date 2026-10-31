"""The worker process: runs the workflows, and starts one for each freed slot.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import asyncio
import logging
from concurrent.futures import ThreadPoolExecutor

from temporalio.client import Client
from temporalio.common import WorkflowIDConflictPolicy, WorkflowIDReusePolicy
from temporalio.exceptions import WorkflowAlreadyStartedError
from temporalio.worker import Worker

from turno_agents.settings import Settings
from turno_agents.workflows.core_api import HttpWorkflowCore, WorkflowCore, WorkflowCoreError
from turno_agents.workflows.slot_refill import TASK_QUEUE, RefillActivities, SlotRefillWorkflow

LOG = logging.getLogger(__name__)

POLL_SECONDS = 10.0


def workflow_id(slot_id: str) -> str:
    """Name the workflow of a slot. Temporal refuses a second one with the same name.

    Args:
        slot_id: The freed slot.

    Returns:
        The workflow's identifier.
    """
    return f"slot-refill-{slot_id}"


async def start_pending_workflows(client: Client, core: WorkflowCore) -> int:
    """Start a workflow for each freed slot not picked up yet.

    A crash between starting and acknowledging only repeats the attempt, and Temporal refuses the
    duplicate. So each slot gets exactly one workflow, whatever fails and whenever.

    Args:
        client: The connection to Temporal.
        core: Access to the core.

    Returns:
        How many workflows were started.
    """
    started = 0
    for slot in await asyncio.to_thread(core.pending_slots):
        slot_id = slot["id"]
        try:
            await client.start_workflow(
                SlotRefillWorkflow.run,
                slot_id,
                id=workflow_id(slot_id),
                task_queue=TASK_QUEUE,
                id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
                id_conflict_policy=WorkflowIDConflictPolicy.FAIL,
            )
            started += 1
        except WorkflowAlreadyStartedError:
            LOG.info("The workflow of a freed slot already existed")
        await asyncio.to_thread(core.acknowledge, slot_id)
    return started


async def _poll_forever(client: Client, core: WorkflowCore) -> None:
    while True:
        try:
            await start_pending_workflows(client, core)
        except WorkflowCoreError:
            LOG.warning("The core could not be reached. Trying again shortly.")
        await asyncio.sleep(POLL_SECONDS)


async def main() -> None:
    """Run the worker until the process is stopped.

    Raises:
        RuntimeError: If the worker has no token for the core.
    """
    logging.basicConfig(level=logging.INFO)
    # One line per request would bury everything else, with a request every few seconds.
    logging.getLogger("httpx2").setLevel(logging.WARNING)
    settings = Settings()
    if settings.api_token_workflows is None:
        raise RuntimeError("Set TURNO_API_TOKEN_WORKFLOWS")
    core = HttpWorkflowCore(settings.core_url, settings.api_token_workflows.get_secret_value())
    client = await Client.connect(settings.temporal_address)
    with ThreadPoolExecutor(max_workers=4) as executor:
        activities = RefillActivities(core)
        worker = Worker(
            client,
            task_queue=TASK_QUEUE,
            workflows=[SlotRefillWorkflow],
            activities=[activities.next_offer, activities.offer_outcome],
            activity_executor=executor,
        )
        async with worker:
            await _poll_forever(client, core)


if __name__ == "__main__":
    asyncio.run(main())
