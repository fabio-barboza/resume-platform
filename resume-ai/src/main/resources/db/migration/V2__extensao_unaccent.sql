CREATE EXTENSION IF NOT EXISTS unaccent;

DO $$
BEGIN
    IF to_regclass('public.alembic_version') IS NULL THEN
        CREATE TABLE alembic_version (
            version_num VARCHAR(32) NOT NULL,
            CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
        );
        INSERT INTO alembic_version (version_num) VALUES ('0002');
    END IF;
END
$$;
