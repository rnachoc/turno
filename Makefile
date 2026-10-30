# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
.PHONY: up down test test-core test-agents lint

up: ## Build and start the whole stack, waiting until it is healthy.
	docker compose up --build --detach --wait

down: ## Stop the stack and keep the data.
	docker compose down

test: test-core test-agents ## Run every test suite.

test-core:
	cd core && ./mvnw -B -ntp verify

test-agents:
	cd agents && uv run --locked pytest

lint: ## Run static checks on the agents and validate the tenants.
	cd agents && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked mypy
	uvx check-jsonschema==0.39.0 --schemafile config/tenant.schema.json config/tenants/*.yaml
