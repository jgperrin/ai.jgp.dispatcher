# Platform Workflow

The end-to-end spec propagation workflow (Workbench → GitHub → GHA → Zeenea +
Control Center → Kafka → data products → feedback) is documented centrally in
the Control Center monorepo. That document is the master; this file is a
pointer only.

**Master document:**
https://github.com/jgperrin/ai.jgp.controlcenter/blob/main/doc/WORKFLOW.md

## This repo's role in the workflow

This is the GitHub Actions leg: triggered by a push that touches
`podem/**/*.odps.yaml`, it uploads the changed product specs (with their
contracts) to Zeenea via the Data Product API and, when Kafka is configured,
publishes the spec to `controlcenter.dataproduct.descriptors` for the Control
Center to ingest.

**Known gap (tracked in the master doc's status table):** the Kafka publish
currently sends the spec ZIP as raw bytes without the `x-org-id` header; the
Control Center's `SpecIngestConsumer` expects an ODPS YAML string (key =
product id) and drops header-less records to `event_log` with
`processingError`. Payload and header must be aligned before this leg is live
end-to-end.
