-- Local-dev bootstrap only. EC2 uses real passwords from SSM Parameter Store.
-- Canonical databases: conciergedb (Concierge) / bizdb (Marketplace) / staticdb (Atlas).
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'concierge_app') THEN
    CREATE USER concierge_app WITH PASSWORD 'milobroker-local';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'bizdb_app') THEN
    CREATE USER bizdb_app WITH PASSWORD 'milobroker-local';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'staticdb_app') THEN
    CREATE USER staticdb_app WITH PASSWORD 'milobroker-local';
  END IF;
END
$$;

SELECT 'CREATE DATABASE conciergedb OWNER concierge_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'conciergedb')\gexec
SELECT 'CREATE DATABASE bizdb OWNER bizdb_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bizdb')\gexec
SELECT 'CREATE DATABASE staticdb OWNER staticdb_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'staticdb')\gexec

REVOKE ALL ON DATABASE conciergedb FROM PUBLIC;
REVOKE ALL ON DATABASE bizdb FROM PUBLIC;
REVOKE ALL ON DATABASE staticdb FROM PUBLIC;
GRANT CONNECT ON DATABASE conciergedb TO concierge_app;
GRANT CONNECT ON DATABASE bizdb TO bizdb_app;
GRANT CONNECT ON DATABASE staticdb TO staticdb_app;
