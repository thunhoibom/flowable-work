ALTER TABLE service_operation_draft
    ADD COLUMN content_type VARCHAR(16) NOT NULL DEFAULT 'JSON',
    ADD COLUMN raw_media_type VARCHAR(128) NOT NULL DEFAULT '',
    ADD COLUMN static_headers_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN body_presence VARCHAR(16) NOT NULL DEFAULT 'CONTENT',
    ADD COLUMN api_key_location VARCHAR(16) NOT NULL DEFAULT 'HEADER';
