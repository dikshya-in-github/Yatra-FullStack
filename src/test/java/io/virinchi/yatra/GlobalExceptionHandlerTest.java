package io.virinchi.yatra;

import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.GlobalExceptionHandler;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Exception.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the Backend Roadmap Phase 1 checkpoint without a database: a REST
 * endpoint that throws {@link ResourceNotFoundException} answers the project's
 * JSON shape, not Spring's default error page.
 *
 * <p>MockMvc is built with {@code standaloneSetup} rather than
 * {@code @WebMvcTest} on purpose — standalone setup boots no application
 * context, so this test needs no MySQL, no Hibernate schema and no security
 * filter chain. The whole point of Phase 1 is verifying the error contract
 * <i>before</i> any of that infrastructure exists, so the test must not depend
 * on it.
 *
 * <p>Assertions use the full-body `content().string(...)` form, which checks
 * the response has <b>exactly</b> the two contract fields — a `jsonPath` check
 * would happily pass if the handler started leaking extra data into the body.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /* One endpoint per handler, each throwing the exception under test. */
    @RestController
    static class BoomController {

        @GetMapping("/api/boom/not-found")
        void notFound() {
            throw ResourceNotFoundException.of("Booking", "BKG10000001");
        }

        @GetMapping("/api/boom/validation")
        void validation() {
            throw new ValidationException("Return date cannot be before the departure date.");
        }

        @GetMapping("/api/boom/duplicate-email")
        void duplicateEmail() {
            throw DuplicateResourceException.emailExists();
        }

        @GetMapping("/api/boom/duplicate-phone")
        void duplicatePhone() {
            throw DuplicateResourceException.phoneExists();
        }

        @GetMapping("/api/boom/unauthorized")
        void unauthorized() {
            throw UnauthorizedException.invalidCredentials();
        }

        @GetMapping("/api/boom/constraint")
        void constraint() {
            // jakarta.validation 3.1 dropped the message-only constructor, so an
            // empty violation set is supplied — the handler must fall back to the
            // message rather than answer an empty one.
            throw new ConstraintViolationException(
                    "mobile: must be a 10-digit Nepali mobile number",
                    Set.<ConstraintViolation<?>>of());
        }

        @GetMapping("/api/boom/integrity")
        void integrity() {
            // The Phase 6 seat case, in the shape the driver really produces:
            // DataIntegrityViolationException -> hibernate ConstraintViolationException
            // -> SQLIntegrityConstraintViolationException with error code 1062.
            // (Measured — and deliberately NOT a DuplicateKeyException, which is what
            // the old type-based assumption would have needed.)
            throw new DataIntegrityViolationException("could not execute statement",
                    hibernateViolation(1062, "Duplicate entry '1-12A' for key 'uk_seat_flight_number'",
                            "uk_seat_flight_number"));
        }

        @GetMapping("/api/boom/in-use")
        void inUse() {
            // R11: deleting a row other rows still point at — error 1451.
            throw new DataIntegrityViolationException("could not execute statement",
                    hibernateViolation(1451,
                            "Cannot delete or update a parent row: a foreign key constraint fails "
                                    + "(`yatra`.`flight`, CONSTRAINT `FK37wfh52g7g91rllg104gfq3yv` FOREIGN KEY "
                                    + "(`airline_id`) REFERENCES `airline` (`id`))",
                            "FK37wfh52g7g91rllg104gfq3yv"));
        }

        @GetMapping("/api/boom/missing-reference")
        void missingReference() {
            // The other direction: the row you point at does not exist — error 1452.
            throw new DataIntegrityViolationException("could not execute statement",
                    hibernateViolation(1452,
                            "Cannot add or update a child row: a foreign key constraint fails",
                            "FKmyn820y18mc93flytb0ujwja8"));
        }

        @GetMapping("/api/boom/hibernate-integrity")
        void hibernateIntegrity() {
            // Spring's translation never ran (no repository proxy touched this), so
            // the raw Hibernate exception is what a service using EntityManager direct
            // would surface. It must still be a 409, not a 500.
            throw hibernateViolation(1451, "Cannot delete or update a parent row", "FKprobe");
        }

        private static org.hibernate.exception.ConstraintViolationException hibernateViolation(
                int errorCode, String message, String constraintName) {
            SQLException sql = new SQLIntegrityConstraintViolationException(message, "23000", errorCode);
            return new org.hibernate.exception.ConstraintViolationException(message, sql, constraintName);
        }

        @GetMapping("/api/boom/denied")
        void denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/api/boom/unexpected")
        void unexpected() {
            throw new IllegalStateException("boom: connection string jdbc:mysql://root:hunter2@localhost");
        }
    }

    /* ---------- 404 ---------- */

    @Test
    void resourceNotFoundDerivesCodeFromResourceName() throws Exception {
        mockMvc.perform(get("/api/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(
                        "{\"error\":\"BOOKING_NOT_FOUND\",\"message\":\"No Booking exists with id BKG10000001.\"}"));
    }

    /* ---------- 400 ---------- */

    @Test
    void businessRuleValidationAnswers400() throws Exception {
        mockMvc.perform(get("/api/boom/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "{\"error\":\"VALIDATION_FAILED\",\"message\":\"Return date cannot be before the departure date.\"}"));
    }

    @Test
    void parameterConstraintAnswersTheSameCodeAsBodyValidation() throws Exception {
        mockMvc.perform(get("/api/boom/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "{\"error\":\"VALIDATION_FAILED\",\"message\":\"mobile: must be a 10-digit Nepali mobile number\"}"));
    }

    /* ---------- 409 ---------- */

    @Test
    void duplicateEmailUsesTheCodeTheProfilePageBranchesOn() throws Exception {
        mockMvc.perform(get("/api/boom/duplicate-email"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"error\":\"EMAIL_EXISTS\",\"message\":\"An account with this email already exists.\"}"));
    }

    @Test
    void duplicatePhoneUsesTheCodeTheProfilePageBranchesOn() throws Exception {
        mockMvc.perform(get("/api/boom/duplicate-phone"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"error\":\"PHONE_EXISTS\",\"message\":\"An account with this mobile number already exists.\"}"));
    }

    @Test
    void rawDatabaseConstraintIsStillA409AndDoesNotLeakTheSchema() throws Exception {
        mockMvc.perform(get("/api/boom/integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"error\":\"DUPLICATE_RESOURCE\",\"message\":\"That record already exists — it breaks a unique constraint.\"}"))
                .andExpect(content().string(not(containsString("uk_seat_flight_number"))))
                .andExpect(content().string(not(containsString("1062"))));
    }

    /**
     * R11 — the bug this replaced: a FK violation used to be answered with the
     * unique-constraint sentence, so "delete this airline" came back as "that
     * record already exists". Same status, honest text, and the constraint name
     * stays out of the body.
     */
    @Test
    void aRowStillInUseIsAForeignKeyConflictNotADuplicate() throws Exception {
        mockMvc.perform(get("/api/boom/in-use"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"error\":\"RECORD_IN_USE\",\"message\":\"This record is still referenced by other records, so it cannot be removed — disable it instead.\"}"))
                .andExpect(content().string(not(containsString("FOREIGN KEY"))))
                .andExpect(content().string(not(containsString("FK37wfh52g7g91rllg104gfq3yv"))));
    }

    @Test
    void aMissingReferencedRowIsA400BecauseTheRequestIsWrong() throws Exception {
        mockMvc.perform(get("/api/boom/missing-reference"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "{\"error\":\"INVALID_REFERENCE\",\"message\":\"A linked record does not exist.\"}"));
    }

    @Test
    void anUntranslatedHibernateConstraintViolationIsStillA409() throws Exception {
        mockMvc.perform(get("/api/boom/hibernate-integrity"))
                .andExpect(status().isConflict())
                .andExpect(content().string(
                        "{\"error\":\"RECORD_IN_USE\",\"message\":\"This record is still referenced by other records, so it cannot be removed — disable it instead.\"}"));
    }

    /* ---------- 401 / 403 ---------- */

    @Test
    void invalidCredentialsCarriesTheCodeLoginPageExpects() throws Exception {
        mockMvc.perform(get("/api/boom/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(
                        "{\"error\":\"INVALID_CREDENTIALS\",\"message\":\"Invalid email/mobile or password.\"}"));
    }

    @Test
    void methodSecurityDenialIsA403NotA500() throws Exception {
        mockMvc.perform(get("/api/boom/denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(
                        "{\"error\":\"FORBIDDEN\",\"message\":\"You do not have permission to perform this action.\"}"));
    }

    /* ---------- 500 ---------- */

    @Test
    void unexpectedExceptionIsAGeneric500ThatLeaksNothing() throws Exception {
        mockMvc.perform(get("/api/boom/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(
                        "{\"error\":\"INTERNAL_SERVER_ERROR\",\"message\":\"Something went wrong on the server. Please try again.\"}"))
                .andExpect(content().string(not(containsString("boom"))))
                .andExpect(content().string(not(containsString("hunter2"))));
    }
}
