-- ============================================================================
-- YATRA 2.0 — CANONICAL SCHEMA (source of truth for the `yatra` database)
-- ============================================================================
--
-- WHY THIS FILE EXISTS (risk R2):
--   `spring.jpa.hibernate.ddl-auto=update` used to let Hibernate diff and ALTER
--   the live TiDB Cloud schema on every boot — including every `./mvnw test`.
--   TiDB's ALTER TABLE support is narrower than MySQL's, so some Hibernate
--   schema diffs could fail or be skipped silently, and there was no reviewable
--   history of any schema change.
--
--   DDL is now owned here, and `ddl-auto=validate` makes Hibernate *enforce*
--   that the entities and the database agree. Changing an entity without
--   updating this file now FAILS AT STARTUP instead of silently rewriting the
--   cloud database.
--
-- WHAT IT IS:
--   An exact dump of the live `yatra` schema (SHOW CREATE TABLE, 2026-09-17),
--   so it describes what actually exists rather than what the entities were
--   intended to produce. Never edit it to match a wish — change the entity,
--   apply the change, then re-dump.
--
-- HOW TO APPLY:
--   It is deliberately NOT run at startup — the app must not mutate its own
--   schema. Apply it by hand when bootstrapping a fresh database:
--     mysql -h <host> -P 4000 -u <user> -p yatra < src/main/resources/db/schema.sql
--   To have Spring bootstrap it automatically instead, see the two commented
--   `spring.sql.init.*` lines in application.properties.
--
-- PORTABILITY:
--   Statements are unqualified (no schema name), so this can be replayed into
--   any empty database. `/*T![clustered_index] CLUSTERED */` is TiDB's
--   clustered-index hint; plain MySQL parses it as an ordinary comment.
--
-- TABLE ORDER matters: parents before children, or the foreign keys fail.
--
-- KEY CONSTRAINTS (business rules depend on these — do not drop them):
--   seat.uk_seat_flight_number (flight_id, seat_number)
--       → the database-level guarantee behind double-booking prevention (rule 2).
--   users.email, users.phone
--       → the unique constraints SignupController/ProfileService map to
--         409 EMAIL_EXISTS / PHONE_EXISTS.
--   payment.booking_id, ticket.booking_id (both UNIQUE)
--       → enforce the 1:1 Booking↔Payment / Booking↔Ticket relationships.
--   airline.logo is `mediumblob`
--       → the assignment's mandatory "images in the database" requirement;
--         the column holds Base64 text. Do not convert it to a path/varchar.
--   flight.fare, booking.total_amount, booking.product_amount, payment.amount
--       are all `decimal(10,2)`, never `double` (risk R5).
--       → money must be exact: `double` cannot represent 8299.99, and the error
--         propagates into the amount a customer is charged. Changing any of these
--         back to `double` fails `ddl-auto=validate` at startup, and
--         EntitySchemaTest asserts the precision/scale as well.
-- ============================================================================


CREATE TABLE IF NOT EXISTS `airline` (
  `id` int NOT NULL AUTO_INCREMENT,
  `description` varchar(1000) DEFAULT NULL,
  `iata` varchar(255) DEFAULT NULL,
  `logo` mediumblob DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UKea5w9t4ji4nfbgu5w0jva9eoj` (`iata`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `destination` (
  `id` int NOT NULL AUTO_INCREMENT,
  `airport` varchar(255) DEFAULT NULL,
  `city` varchar(255) DEFAULT NULL,
  `code` varchar(255) DEFAULT NULL,
  `description` varchar(1000) DEFAULT NULL,
  `image_public_id` varchar(255) DEFAULT NULL,
  `image_url` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UKnml51gy7a1kbvnxkyj8i0vhnt` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `users` (
  `id` int NOT NULL AUTO_INCREMENT,
  `email` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `phone` varchar(255) DEFAULT NULL,
  `registered_at` datetime(6) DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`),
  UNIQUE KEY `UKdu5v5sr43g5bfnji4vb8hg5s3` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `flight` (
  `id` int NOT NULL AUTO_INCREMENT,
  `aircraft` varchar(255) DEFAULT NULL,
  `arrive_time` time DEFAULT NULL,
  `depart_time` time DEFAULT NULL,
  `fare` decimal(10,2) NOT NULL,
  `flight_date` date DEFAULT NULL,
  `flight_no` varchar(255) DEFAULT NULL,
  `seat_capacity` int NOT NULL,
  `status` varchar(255) DEFAULT NULL,
  `airline_id` int DEFAULT NULL,
  `destination_id` int DEFAULT NULL,
  `origin_id` int DEFAULT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UKg9lyjbdea3jbrhy3t85n9bfq2` (`flight_no`),
  KEY `FK37wfh52g7g91rllg104gfq3yv` (`airline_id`),
  KEY `FKj3cwyjrxiewgboemu9l7lke9o` (`destination_id`),
  KEY `FKmyn820y18mc93flytb0ujwja8` (`origin_id`),
  CONSTRAINT `FK37wfh52g7g91rllg104gfq3yv` FOREIGN KEY (`airline_id`) REFERENCES `airline` (`id`),
  CONSTRAINT `FKj3cwyjrxiewgboemu9l7lke9o` FOREIGN KEY (`destination_id`) REFERENCES `destination` (`id`),
  CONSTRAINT `FKmyn820y18mc93flytb0ujwja8` FOREIGN KEY (`origin_id`) REFERENCES `destination` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `booking` (
  `id` int NOT NULL AUTO_INCREMENT,
  `booking_status` varchar(255) DEFAULT NULL,
  `contact_email` varchar(255) DEFAULT NULL,
  `contact_name` varchar(255) DEFAULT NULL,
  `contact_phone` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `fare_class` varchar(255) DEFAULT NULL,
  `payment_status` varchar(255) DEFAULT NULL,
  `product_amount` decimal(10,2) NOT NULL,
  `refundable` bit(1) NOT NULL,
  `total_amount` decimal(10,2) NOT NULL,
  `flight_id` int NOT NULL,
  `user_id` int DEFAULT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  KEY `FK546eybei9q7dsna94vryofrbr` (`flight_id`),
  KEY `FK7udbel7q86k041591kj6lfmvw` (`user_id`),
  CONSTRAINT `FK546eybei9q7dsna94vryofrbr` FOREIGN KEY (`flight_id`) REFERENCES `flight` (`id`),
  CONSTRAINT `FK7udbel7q86k041591kj6lfmvw` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `seat` (
  `id` int NOT NULL AUTO_INCREMENT,
  `seat_number` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `flight_id` int NOT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `uk_seat_flight_number` (`flight_id`,`seat_number`),
  CONSTRAINT `FKeda0njvaxhowgf6120eh6hxpq` FOREIGN KEY (`flight_id`) REFERENCES `flight` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `passenger` (
  `id` int NOT NULL AUTO_INCREMENT,
  `first_name` varchar(255) DEFAULT NULL,
  `last_name` varchar(255) DEFAULT NULL,
  `nationality` varchar(255) DEFAULT NULL,
  `passenger_type` varchar(255) DEFAULT NULL,
  `seat_number` varchar(255) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `booking_id` int NOT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  KEY `FKtco0omesfld1qi5sw76eomvt4` (`booking_id`),
  CONSTRAINT `FKtco0omesfld1qi5sw76eomvt4` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `payment` (
  `id` int NOT NULL AUTO_INCREMENT,
  `amount` decimal(10,2) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `method` varchar(255) DEFAULT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `txn_id` varchar(255) DEFAULT NULL,
  `booking_id` int NOT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UKbh8ayhk7glrphuly40f5blgwe` (`txn_id`),
  UNIQUE KEY `UKku02qy6369hn9uhy3n7jk9v6e` (`booking_id`),
  CONSTRAINT `FKqewrl4xrv9eiad6eab3aoja65` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `ticket` (
  `id` int NOT NULL AUTO_INCREMENT,
  `issued_at` datetime(6) DEFAULT NULL,
  `pnr` varchar(255) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `ticket_no` varchar(255) DEFAULT NULL,
  `booking_id` int NOT NULL,
  PRIMARY KEY (`id`) /*T![clustered_index] CLUSTERED */,
  UNIQUE KEY `UKmgw6j05itl42q64kr42djbm38` (`pnr`),
  UNIQUE KEY `UKodeus980tafqcksysjikt3gvg` (`ticket_no`),
  UNIQUE KEY `UKgco27k8cbs8j67db3oadbna6o` (`booking_id`),
  CONSTRAINT `FKrg7x158t96nucwslhq2bad6qm` FOREIGN KEY (`booking_id`) REFERENCES `booking` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
