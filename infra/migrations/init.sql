-- Enterprise SecureChat — Initial Schema
-- Apply once via Neon SQL Editor on the fga_registry database.
-- See docs/cloud.md for step-by-step instructions.

-- ── FGA Registry ──────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS roles (
  id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_name TEXT UNIQUE NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now()
);

-- A row here means the role is DENIED access to any document whose
-- subject_path starts with this value (prefix-based hierarchical restriction).
CREATE TABLE IF NOT EXISTS role_restrictions (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_name    TEXT NOT NULL REFERENCES roles(role_name) ON DELETE CASCADE,
  subject_path TEXT NOT NULL,   -- e.g. "finance" blocks finance/* as well
  reason       TEXT,
  created_by   TEXT,
  created_at   TIMESTAMPTZ DEFAULT now(),
  UNIQUE (role_name, subject_path)
);

CREATE INDEX IF NOT EXISTS idx_restrictions_role ON role_restrictions(role_name);

-- Audit: every chat query logs which paths were blocked (never the raw prompt)
CREATE TABLE IF NOT EXISTS restriction_audit_log (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_sub         TEXT NOT NULL,
  role_names       TEXT[] NOT NULL,
  restricted_paths TEXT[] NOT NULL,
  query_hash       TEXT NOT NULL,   -- SHA-256 of prompt
  accessed_at      TIMESTAMPTZ DEFAULT now()
);

-- ── Conversations ──────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS conversations (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_sub   TEXT NOT NULL,
  title      TEXT,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_conversations_user ON conversations(user_sub);

CREATE TABLE IF NOT EXISTS messages (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content         TEXT NOT NULL,
  sources         JSONB,             -- array of {source_file, subject_path, score}
  dlp_redacted    INTEGER DEFAULT 0, -- count of entities redacted from this message
  created_at      TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id, created_at);

-- ── Seed Data ─────────────────────────────────────────────────────────────────
-- Default roles — expand as needed via the admin panel

INSERT INTO roles (role_name) VALUES
  ('admin'),
  ('employee'),
  ('finance-analyst'),
  ('hr-manager'),
  ('it-ops')
ON CONFLICT (role_name) DO NOTHING;
