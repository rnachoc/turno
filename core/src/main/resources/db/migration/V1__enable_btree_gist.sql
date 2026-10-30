-- Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
--
-- btree_gist lets a single GiST exclusion constraint combine equality on scalar columns
-- (for example, the practitioner) with overlap on time ranges. The booking tables rely on it
-- so that PostgreSQL itself rejects double bookings, even under concurrent requests.
-- It is a trusted extension, so the database owner can enable it without superuser rights.
CREATE EXTENSION IF NOT EXISTS btree_gist;
