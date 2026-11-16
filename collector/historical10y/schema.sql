PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS meta(
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS imports(
  batch_id TEXT PRIMARY KEY,
  previous_cursor INTEGER NOT NULL,
  next_cursor INTEGER NOT NULL,
  record_count INTEGER NOT NULL,
  status TEXT NOT NULL,
  imported_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS manifest_races(
  race_id TEXT PRIMARY KEY,
  year INTEGER NOT NULL,
  race_date TEXT NOT NULL,
  venue_code TEXT NOT NULL,
  venue TEXT NOT NULL,
  meeting INTEGER NOT NULL,
  day INTEGER NOT NULL,
  race_no INTEGER NOT NULL,
  race_status TEXT NOT NULL,
  source_locator TEXT NOT NULL,
  manifest_hash TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(race_date, venue_code, meeting, day, race_no)
);

CREATE TABLE IF NOT EXISTS races(
  race_id TEXT PRIMARY KEY,
  current_version INTEGER NOT NULL,
  race_date TEXT NOT NULL,
  venue_code TEXT NOT NULL,
  venue TEXT NOT NULL,
  meeting INTEGER NOT NULL,
  day INTEGER NOT NULL,
  race_no INTEGER NOT NULL,
  obstacle INTEGER NOT NULL CHECK(obstacle IN (0,1)),
  canonical_status TEXT NOT NULL CHECK(canonical_status IN ('CANONICAL_READY','PARTIAL','BLOCKED')),
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS race_versions(
  race_id TEXT NOT NULL REFERENCES races(race_id),
  version INTEGER NOT NULL,
  payload_hash TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  source_correction_epoch INTEGER NOT NULL DEFAULT 0,
  imported_at TEXT NOT NULL,
  PRIMARY KEY(race_id, version),
  UNIQUE(race_id, payload_hash)
);

CREATE TABLE IF NOT EXISTS runners(
  race_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  horse_no INTEGER NOT NULL,
  horse_name TEXT NOT NULL,
  scratched INTEGER NOT NULL CHECK(scratched IN (0,1)),
  payload_json TEXT NOT NULL,
  PRIMARY KEY(race_id, version, horse_no),
  FOREIGN KEY(race_id, version) REFERENCES race_versions(race_id, version)
);

CREATE TABLE IF NOT EXISTS results(
  race_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  result_json TEXT NOT NULL,
  PRIMARY KEY(race_id, version),
  FOREIGN KEY(race_id, version) REFERENCES race_versions(race_id, version)
);

CREATE TABLE IF NOT EXISTS payouts(
  race_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  bet_type TEXT NOT NULL,
  selection_key TEXT NOT NULL,
  payout_per_100_yen INTEGER,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(race_id, version, bet_type, selection_key),
  FOREIGN KEY(race_id, version) REFERENCES race_versions(race_id, version)
);

CREATE TABLE IF NOT EXISTS sources(
  race_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  source_role TEXT NOT NULL,
  source_provider TEXT NOT NULL,
  source_record_id TEXT NOT NULL,
  source_retrieved_at TEXT,
  raw_sha256 TEXT,
  raw_size INTEGER,
  source_record_make_date TEXT,
  source_correction_epoch INTEGER NOT NULL DEFAULT 0,
  decoder_version TEXT NOT NULL,
  parser_version TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  PRIMARY KEY(race_id, version, source_role, source_record_id),
  FOREIGN KEY(race_id, version) REFERENCES race_versions(race_id, version)
);

CREATE TABLE IF NOT EXISTS corrections(
  race_id TEXT NOT NULL,
  from_version INTEGER NOT NULL,
  to_version INTEGER NOT NULL,
  old_hash TEXT NOT NULL,
  new_hash TEXT NOT NULL,
  detected_at TEXT NOT NULL,
  PRIMARY KEY(race_id, to_version),
  FOREIGN KEY(race_id, from_version) REFERENCES race_versions(race_id, version),
  FOREIGN KEY(race_id, to_version) REFERENCES race_versions(race_id, version)
);

CREATE TABLE IF NOT EXISTS checkpoints(
  chunk_id TEXT PRIMARY KEY,
  previous_cursor INTEGER NOT NULL,
  next_cursor INTEGER NOT NULL,
  expected INTEGER NOT NULL,
  success INTEGER NOT NULL,
  failed INTEGER NOT NULL,
  skipped INTEGER NOT NULL,
  duplicate INTEGER NOT NULL,
  raw_hash_count INTEGER NOT NULL,
  canonical_count INTEGER NOT NULL,
  status TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_events(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  event_type TEXT NOT NULL,
  race_id TEXT,
  batch_id TEXT,
  detail_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS race_versions_hash ON race_versions(payload_hash);
CREATE INDEX IF NOT EXISTS runners_name ON runners(horse_name, race_id);
CREATE INDEX IF NOT EXISTS manifest_year_venue ON manifest_races(year, venue_code, race_date);
CREATE INDEX IF NOT EXISTS audit_race ON audit_events(race_id, id);
