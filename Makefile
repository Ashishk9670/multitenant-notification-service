.PHONY: build test unit integration verify infra-up infra-down run e2e coverage

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
