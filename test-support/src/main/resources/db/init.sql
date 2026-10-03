-- One database and one login role per service. Run once as a superuser; replace the ${...}
-- placeholders with real secrets. No service role can connect to another service's database.
CREATE ROLE order_svc LOGIN PASSWORD '${order_password}';
CREATE ROLE payment_svc LOGIN PASSWORD '${payment_password}';
CREATE ROLE inventory_svc LOGIN PASSWORD '${inventory_password}';
CREATE DATABASE order_db OWNER order_svc;
CREATE DATABASE payment_db OWNER payment_svc;
CREATE DATABASE inventory_db OWNER inventory_svc;
REVOKE CONNECT, TEMPORARY ON DATABASE order_db FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE payment_db FROM PUBLIC;
REVOKE CONNECT, TEMPORARY ON DATABASE inventory_db FROM PUBLIC;
