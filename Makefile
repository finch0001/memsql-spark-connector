SHELL := /bin/bash

##############################
# BUILD
#
VERSION := $(shell sbt 'export version' | tail -n 1)
export VERSION

default: build

.PHONY: git-commit-template
git-commit-template: .commit_template
	@git config commit.template .commit_template

.PHONY: version
version:
	@echo $(VERSION)

.PHONY: clean
clean:
	sbt clean \
		"project connectorLib" clean \
		"project etlLib" clean \
		"project interface" clean \
		"project tests" clean
	rm -rf distribution/

.PHONY: style
style:
	sbt "project etlLib" scalastyle \
	    "project connectorLib" scalastyle \
	    "project interface" scalastyle

.PHONY: build
build: clean
	sbt assembly

.PHONY: build-test
build-test: clean
	sbt "project tests" assembly

.PHONY: build-sample
build-sample:
	cd dockertest/sample_pipelines; sbt assembly

.PHONY: docs
docs: clean
	sbt unidoc

.PHONY: package
package: docs build
	mkdir -p distribution/dist/memsql-$(VERSION)
	cp README.md distribution/dist/memsql-$(VERSION)/
	cp target/scala-2.10/MemSQL-assembly-$(VERSION).jar distribution/dist/memsql-$(VERSION)/
	cp -r target/scala-2.10/unidoc/ distribution/dist/memsql-$(VERSION)/docs/
	cd distribution/dist; \
	tar cvzf memsql-$(VERSION).tar.gz memsql-$(VERSION)/

.PHONY: psytest
psytest: build-test build-sample
	psy dockertest dockertest/.psyduck

.PHONY: publish
publish:
	sbt "project connectorLib" publish \
	"project etlLib" publish \
	"project interface" publish \
	"project root" publish
