# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
.PHONY: up down agents demo-data test test-core test-agents test-console test-contract test-e2e test-live eval eval-gate lint

up: ## Build and start the whole stack, waiting until it is healthy.
	docker compose up --build --detach --wait

down: ## Stop the stack and keep the data.
	docker compose down

agents: ## Run the agents service on this machine, with your own Claude login.
	cd agents && TURNO_LLM_BACKEND=claude-cli TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env uvicorn turno_agents.main:app --host 127.0.0.1 --port 8001

demo-data: ## Fill the running stack with synthetic activity, for a look at the console.
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env python demo/seed.py

test: test-core test-agents test-console ## Run every test suite.

test-core:
	cd core && ./mvnw -B -ntp verify

test-agents:
	cd agents && uv run --locked pytest

test-console:
	cd console && npm ci --no-audit --no-fund && npm test

test-contract: ## Check the agents against the real core. Needs `make up`.
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env pytest -m integration

test-e2e: ## Run the slot refill through the whole stack, with a clinic that may contact at any hour.
	TURNO_TENANT=e2e-clinic docker compose up --detach --wait core
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env pytest -m e2e

test-live: ## Attack the real model. Spends your Claude plan and needs `make up`.
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env pytest -m live -v

eval: ## Record the scenarios still missing. Spends your plan: limit it with ARGS="--limit 10".
	cd agents && TURNO_CORE_URL=http://127.0.0.1:8080 uv run --locked --env-file ../.env python -m evals run $(ARGS)

eval-gate: ## What CI runs: grade the recordings, with no model and no core.
	cd agents && uv run --locked python -m evals gate

lint: ## Run static checks on the agents and validate the tenants.
	cd agents && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked mypy
	uvx check-jsonschema==0.39.0 --schemafile config/tenant.schema.json config/tenants/*.yaml
