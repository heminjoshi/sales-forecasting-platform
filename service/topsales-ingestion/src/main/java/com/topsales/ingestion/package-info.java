/**
 * Event ingestion: the local {@code POST /events} path with idempotent upserts into the
 * aggregate store (the Kinesis/KCL consumer is the production path, designed-only).
 *
 * <p>Populated in Phase 2.
 */
package com.topsales.ingestion;
