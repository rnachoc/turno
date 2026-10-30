# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
FROM python:3.13.18-slim-trixie AS build
COPY --from=ghcr.io/astral-sh/uv:0.13.6 /uv /usr/local/bin/uv
ENV UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
WORKDIR /app
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project
COPY src/ src/
RUN uv sync --locked --no-dev --no-editable

FROM python:3.13.18-slim-trixie
RUN groupadd --system --gid 10001 turno && useradd --system --uid 10001 --gid turno --no-create-home turno
WORKDIR /app
COPY --from=build /app/.venv .venv
USER 10001:10001
ENV PATH="/app/.venv/bin:$PATH" PYTHONUNBUFFERED=1
EXPOSE 8000
ENTRYPOINT ["uvicorn", "turno_agents.main:app", "--host", "0.0.0.0", "--port", "8000"]
