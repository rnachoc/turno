-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- Runs after every migration, so that changing the password in the environment takes effect. The
-- password must not contain a single quote.
ALTER ROLE turno_app LOGIN PASSWORD '${appPassword}';
