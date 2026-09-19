package io.virinchi.yatra.Service;

import io.virinchi.yatra.Config.EsewaConfig;
import io.virinchi.yatra.Dto.EsewaStatusResponse;
import io.virinchi.yatra.Exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Asks eSewa what its own ledger says about a transaction — the callback's
 * independent confirmation.
 *
 * <h2>Why this is its own class</h2>
 * <p>It is the only part of the eSewa flow that leaves the process, so it is the one
 * part tests must be able to replace. {@code EsewaGatewayTest} overrides this bean
 * with {@code @MockitoBean} and asserts the whole callback path — signature check,
 * amount check, settle, ticket — with no socket opened anywhere. That is not a
 * testing convenience dressed up as design: the project's rule is that the suite
 * needs no third-party credentials (R15), and a suite that dialled eSewa's sandbox
 * would be slower, flakier and dependent on a service nobody here controls.
 *
 * <h2>Timeouts, because a callback is a browser waiting</h2>
 * <p>Both timeouts are 5 seconds, the same figure {@code spring.mail}'s properties
 * use. The default for {@link HttpClient} is <i>no timeout at all</i>, and an
 * unresponsive eSewa would otherwise pin a request thread — and the customer's
 * browser, which is sitting on a redirect it cannot cancel — for as long as the
 * connection stays half-open.
 *
 * <h2>There is no retry, deliberately</h2>
 * <p>A single failed status check leaves the payment {@code PENDING} and answers 503
 * (see {@link EsewaGatewayService}). Retrying inside the callback would only delay
 * the answer while the same uncertainty persists, and the state it leaves behind is
 * exactly the one an operator can act on: nothing was confirmed, and the row still
 * names the transaction to look up.
 */
@Slf4j
@Component
public class EsewaStatusClient {

    /** Connect and read timeout — the mail properties' figure, for the same reason. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final EsewaConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public EsewaStatusClient(EsewaConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * eSewa's answer for one transaction.
     *
     * @param transactionUuid the uuid this project minted at initiate
     * @param totalAmount     the amount the payment row holds, re-asserted to eSewa —
     *                        the status API requires it, and sending our own stored
     *                        figure (never the callback's) is what makes the answer
     *                        about <i>this</i> transaction
     * @throws ServiceUnavailableException 503 — eSewa was unreachable, answered
     *                                    non-200, or answered something unparseable.
     *                                    Never a confirmation and never a failure:
     *                                    the payment stays {@code PENDING}.
     */
    public EsewaStatusResponse status(String transactionUuid, BigDecimal totalAmount) {
        URI uri = statusUri(transactionUuid, totalAmount);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            //Restore the interrupt flag before wrapping — swallowing it is the classic
            //way a shutdown turns into a hung thread.
            Thread.currentThread().interrupt();
            log.warn("eSewa status check interrupted for transaction {}", transactionUuid);
            throw statusCheckFailed("the request was interrupted");
        } catch (Exception ex) {
            log.warn("eSewa status check could not reach {}: {}", uri.getHost(), ex.getMessage());
            throw statusCheckFailed("eSewa could not be reached");
        }

        if (response.statusCode() != 200) {
            //The body is not logged: on a gateway an error body can echo the request,
            //and the request carries the product code and the amount.
            log.warn("eSewa status check answered HTTP {} for transaction {}",
                    response.statusCode(), transactionUuid);
            throw statusCheckFailed("eSewa answered HTTP " + response.statusCode());
        }

        try {
            return objectMapper.readValue(response.body(), EsewaStatusResponse.class);
        } catch (Exception ex) {
            log.warn("eSewa status check answered unreadable JSON for transaction {}: {}",
                    transactionUuid, ex.getMessage());
            throw statusCheckFailed("eSewa's answer could not be read");
        }
    }

    /**
     * The status URL with eSewa's three required parameters.
     *
     * <p>Values are URL-encoded even though a uuid and a decimal amount contain
     * nothing that needs it: the product code is configuration, and this is the one
     * place where a character that is legal in a properties file (a {@code +}, a
     * space, a {@code &}) silently becomes a different query.
     */
    private URI statusUri(String transactionUuid, BigDecimal totalAmount) {
        return URI.create(config.statusUrl()
                + "?product_code=" + encode(config.productCode())
                + "&total_amount=" + encode(totalAmount.toPlainString())
                + "&transaction_uuid=" + encode(transactionUuid));
    }

    private static String encode(String value) {
        return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8);
    }

    private static ServiceUnavailableException statusCheckFailed(String why) {
        return new ServiceUnavailableException(
                "ESEWA_STATUS_CHECK_FAILED",
                "The payment could not be confirmed with eSewa (" + why + "), so the booking was "
                        + "left unchanged and unpaid. Its transaction is still pending — check the "
                        + "admin ledger before telling the customer anything, and try again.");
    }
}
