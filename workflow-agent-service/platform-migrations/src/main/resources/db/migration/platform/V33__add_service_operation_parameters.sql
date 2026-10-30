ALTER TABLE service_operation_draft
    ADD COLUMN input_parameters_json TEXT NOT NULL DEFAULT '[]',
    ADD COLUMN output_parameters_json TEXT NOT NULL DEFAULT '[]';
