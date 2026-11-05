.PHONY: build test unit integration verify infra-up infra-down run e2e coverage demo demo-down screenshots

build:
	mvn -q -DskipTests compile

unit test:
	mvn test

integration verify:
	mvn verify    # needs Docker (Testcontainers)

coverage:
	mvn verify && open target/site/jacoco/index.html

infra-up:
	docker compose up -d --wait

infra-down:
	docker compose down -v

run:
	mvn spring-boot:run

e2e:
	./scripts/e2e.sh

# Full stack in containers (Postgres, Redis, Kafka, app), seeded with a demo tenant, templates and notifications.
demo:
	docker compose --profile app up -d --build --wait
	ADMIN_TOKEN=$${ADMIN_TOKEN:-dev-admin-token} ./scripts/seed-demo.sh
	@echo "Admin UI: http://localhost:8080  (paste the API key from .demo-key)"
	@echo "Swagger:  http://localhost:8080/swagger-ui.html"

demo-down:
	docker compose --profile app down -v

# Regenerates docs/img/*.png against a running stack (e.g. after `make demo`)
screenshots:
	cd e2e && npm install && npx playwright install chromium && npx playwright test -c playwright.screenshots.config.js
