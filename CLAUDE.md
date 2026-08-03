# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Java CLI tool designed to run as a GitHub Action. Uploads data product specs (`.odps.yaml`, `.odcs.yaml`) to Zeenea (Actian Data Catalog) and publishes them to Kafka for the Data Product Control Center.

## Cross-Project Documentation

Documents that span multiple projects live in `doc/` in the Libot Services repo (`ai.jgp.bitol.svc`). Documents that pertain to only this project stay here.

## Related Projects

| Project                                 | Repo                                    | Description                                                              |
|-----------------------------------------|-----------------------------------------|--------------------------------------------------------------------------|
| **Libot Services**                      | `ai.jgp.bitol.svc`                     | Spring Boot REST API — contracts, products, users, GitHub publishing, maturity assessment |
| **Workbench Web App**                   | `ai.jgp.data-product-workbench`        | React/Vite web UI for editing contracts and products                     |
| **Workbench iPhone App**                | `ai.jgp.data-product-workbench-app`    | Native iOS companion app (read-only viewer)                              |
| **Control Center**                      | `jgperrin/ai.jgp.controlcenter`        | Control Center monorepo — Spring Boot svc + React webapp (cc#169)        |
| **Data Product Sidecar**                | `ai.jgp.dataproduct.sidecar`           | Lightweight Spring Boot agent co-deployed with data products             |
| **Dispatcher** (this repo)              | `ai.jgp.dispatcher`                    | Java CLI / GitHub Action for uploading specs to Zeenea + Kafka           |
