package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * eSewa's transaction status API answer — the callback's <i>independent</i>
 * confirmation ({@code GET /api/epay/transaction/status/}).
 *
 * <h2>Why the callback asks eSewa twice</h2>
 * <p>The redirect payload says what the browser was told; this endpoint says what
 * eSewa's own ledger holds, and it is the answer a merchant is supposed to trust. The
 * two are checked against each other on purpose: a payload that says
 * {@code COMPLETE} while the status API says {@code NOT_FOUND} is exactly the shape a
 * forged callback takes, and confirming a booking on the payload alone would make the
 * signature check the only thing standing between a curious customer and a free
 * ticket.
 *
 * <h2>The status vocabulary</h2>
 * <p>{@code COMPLETE} is the only value this project treats as paid. The others are
 * deliberately not lumped together as "failed": {@code PENDING} means eSewa has not
 * finished, {@code AMBIGUOUS} means it does not know, {@code NOT_FOUND} means it has
 * no record of the transaction at all, and the two refund states mean the money has
 * already gone back. All of them are recorded as a non-success outcome locally, and
 * the one that matters for support — whether a row exists on eSewa's side at all —
 * is visible in the logged answer rather than flattened into a boolean.
 *
 * <p>{@code ignoreUnknown} because eSewa sends several more fields
 * ({@code ref_id}, service charge, delivery charge) that this project has no use
 * for; {@code ref_id} is kept because it is the reference a human quotes when
 * reconciling with eSewa's support.
 *
 * <p><b>{@code total_amount} is a {@link java.math.BigDecimal}, not a string, and that
 * is what the wire actually carries.</b> Verified against the sandbox on 2026-09-19 —
 * {@code /api/epay/transaction/status/?product_code=EPAYTEST&total_amount=100&transaction_uuid=123}
 * answers {@code {"product_code":"EPAYTEST","transaction_uuid":"123","total_amount":100.0,"status":"COMPLETE","ref_id":"00066XV"}},
 * a JSON <i>number</i> as eSewa's own examples show, so binding it as text would put
 * this project at the mercy of how a double renders: {@code 8299.99} is not exactly
 * representable and its text form is one bad round trip away from
 * {@code 8299.989999999999}, which would then be compared against the booking and refuse
 * a payment that was made correctly. A {@code BigDecimal} compared with
 * {@code compareTo} is scale-insensitive, so {"8299.99"} and {"8299.990"} are the same
 * amount — which is what a money comparison should mean.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EsewaStatusResponse(

        String product_code,
        String transaction_uuid,
        java.math.BigDecimal total_amount,

        /* COMPLETE / PENDING / AMBIGUOUS / NOT_FOUND / FULL_REFUND / PARTIAL_REFUND / CANCELED */
        String status,

        /* eSewa's reference id for the transaction — for a human reading a log line. */
        String ref_id
) {

    /** Whether eSewa's own ledger says this transaction is done. */
    public boolean isComplete() {
        return "COMPLETE".equalsIgnoreCase(String.valueOf(status == null ? "" : status).trim());
    }
}
