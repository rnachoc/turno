# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
.PHONY: up down agents test test-core test-agents test-contract test-live lint

up: ## Build and start the whole stack, waiting until it is healthy.
	docker compose up --build --detach --wait

down: ## Stop the stack and keep the data.
	docker compose down

agents: ## Run the agents service on this machine, with your own Claude login.
	cd agents && TURNO_LLM_BACKEND=claude-cli TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env uvicorn turno_agents.main:app --host 127.0.0.1 --port 8001

test: test-core test-agents ## Run every test suite.

test-core:
	cd core && ./mvnw -B -ntp verify

test-agents:
	cd agents && uv run --locked pytest

test-contract: ## Check the agents against the real core. Needs `make up`.
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env pytest -m integration

test-live: ## Attack the real model. Spends your Claude plan and needs `make up`.
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env pytest -m live -v

lint: ## Run static checks on the agents and validate the tenants.
	cd agents && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked mypy
	uvx check-jsonschema==0.39.0 --schemafile config/tenant.schema.json config/tenants/*.yaml
