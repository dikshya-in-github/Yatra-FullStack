package io.virinchi.yatra.Dto;

/**
 * The ONE error body every REST failure returns (Backend Roadmap Phase 1):
 *
 * <pre>{ "error": "CODE", "message": "…" }</pre>
 *
 * The shape is deliberately exactly two fields, because the frontend is already
 * frozen against it — `assets/js/api.js`'s real-mode branch reads
 * `data.error` and `data.message`, and pages branch on the code
 * (`profile.js` / `admin-profile.js` check `EMAIL_EXISTS` / `PHONE_EXISTS`,
 * `admin-profile.js` checks `status === 401`). Adding a field is safe; renaming
 * one is not.
 *
 * `error` is a stable SCREAMING_SNAKE machine code the UI can switch on;
 * `message` is human-readable text safe to show in a toast. A `record` is used
 * because this is an immutable response body — Jackson serialises records
 * natively, and Boot 4's Jackson 3 writes the fields in declaration order.
 */
public record ErrorResponse(String error, String message) {
}
