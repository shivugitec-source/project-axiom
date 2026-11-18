-- Local development application role. Never use the bootstrap administrator in the application.
CREATE ROLE axiom LOGIN PASSWORD 'axiom_dev_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT, CREATE ON DATABASE axiom TO axiom;
