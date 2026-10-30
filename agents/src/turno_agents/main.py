"""HTTP entry point of the agents service.

Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
"""

from fastapi import FastAPI


def create_app() -> FastAPI:
    """Build the FastAPI application.

    The interactive docs and the OpenAPI schema stay off because this is an internal service and
    nothing should describe its surface to an anonymous caller.

    Returns:
        The configured application.
    """
    app = FastAPI(title="Turno Agents", docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health() -> dict[str, str]:
        """Report that the process is able to serve requests.

        Returns:
            The service status, in the same shape as the core's health endpoint.
        """
        return {"status": "UP"}

    return app


app = create_app()
