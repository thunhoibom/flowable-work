-- Extended auth material. Secrets inside the JSON are ciphers, same as secret_cipher.
ALTER TABLE service_operation_draft
    ADD COLUMN auth_details_json TEXT NOT NULL DEFAULT '{}';
