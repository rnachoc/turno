"""Tests for the operational surface of the agents service.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

import pytest
from fastapi.testclient import TestClient

from turno_agents.main import create_app


@pytest.fixture
def client() -> TestClient:
    """Provide an HTTP client bound to a fresh application.

    Returns:
        A test client for the application.
    """
    return TestClient(create_app())


def test_health_reports_up(client: TestClient) -> None:
    """The health endpoint answers 200 with the status and nothing else."""
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


@pytest.mark.parametrize("path", ["/docs", "/redoc", "/openapi.json"])
def test_api_description_is_not_exposed(client: TestClient, path: str) -> None:
    """The docs and the schema must not reveal the service surface."""
    assert client.get(path).status_code == 404


@pytest.mark.parametrize("method", ["post", "put", "patch", "delete"])
def test_health_is_read_only(client: TestClient, method: str) -> None:
    """The health endpoint rejects every method that could change state."""
    assert getattr(client, method)("/health").status_code == 405
