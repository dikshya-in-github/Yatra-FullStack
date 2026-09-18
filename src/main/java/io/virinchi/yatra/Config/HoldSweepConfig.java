package io.virinchi.yatra.Config;

import io.virinchi.yatra.Service.BookingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * The timer behind the abandoned-hold sweep: calls
 * {@link BookingService#expireHolds(Duration)} every interval.
 *
 * <h2>Off unless it is armed, and that is the whole design</h2>
 *
 * <p>The bean only exists when {@code yatra.hold.sweep.enabled=true}
 * ({@code HOLD_SWEEP_ENABLED}). The default is <b>false</b>, deliberately, and the
 * reason is the project's own recorded rule rather than caution in general: the test
 * suite is 26 {@code @SpringBootTest} classes booting against the <b>live</b> TiDB
 * schema, which is why seeding is an explicit admin endpoint and never a
 * {@code CommandLineRunner} — anything that fires on its own in every context turns
 * "run the tests" into "write to the database". A {@code @Scheduled} method is that
 * hazard with a longer fuse: it needs no request, no token and no caller, and it
 * <i>deletes rows</i>. So the suite gets no scheduler at all, and a real run opts in
 * with one line. {@code @ConditionalOnProperty(havingValue = "true")} is the right
 * form here even though the property is always <i>present</i> in
 * {@code application.properties} — it compares the value, where
 * {@code @ConditionalOnProperty} alone would be asking about existence (R15's note
 * about {@code ${...:}} placeholders).
 *
 * <h2>What it does not do</h2>
 *
 * <p>No business logic: the rule lives in {@code BookingService} so the timer and the
 * admin endpoint (<code>POST /api/admin/bookings/expire-holds</code>) cannot drift
 * apart. The interval arithmetic, the window and the exception handling are all this
 * class owns, and the exception handling is the part worth naming. <b>Not</b> to keep
 * the timer alive — Spring's fixed-delay tasks do continue after an uncaught
 * exception, and pretending otherwise would be a comment that does not survive being
 * checked. The catch is there so the failure is <i>attributable</i>: an uncaught one
 * reaches Spring's own handler as "Unexpected error occurred in scheduled task", which
 * names neither the sweep nor the window. A sweep that has quietly stopped failing is
 * exactly the kind of bug nobody notices until a flight looks full, so its one line
 * says which operation failed.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "yatra.hold.sweep.enabled", havingValue = "true")
@Slf4j
public class HoldSweepConfig {

    private final BookingService bookingService;
    private final Duration window;

    public HoldSweepConfig(
            BookingService bookingService,
            @Value("${yatra.hold.expiry-minutes:15}") int expiryMinutes) {
        this.bookingService = bookingService;
        this.window = Duration.ofMinutes(expiryMinutes);
    }

    /**
     * Runs the sweep on a fixed delay (the next run starts the configured interval
     * <i>after</i> the previous one finished, so a slow sweep cannot pile up behind
     * itself). The first run waits a minute as well, so a boot that is still starting
     * is not also being written to.
     */
    @Scheduled(
            fixedDelayString = "${yatra.hold.sweep.interval-ms:60000}",
            initialDelayString = "${yatra.hold.sweep.initial-delay-ms:60000}")
    public void sweepAbandonedHolds() {
        try {
            int expired = bookingService.expireHolds(window).expired();
            if (expired > 0) {
                log.info("Hold sweep finished: {} booking(s) expired (window {}m)", expired,
                        window.toMinutes());
            }
        } catch (RuntimeException ex) {
            //Catch so the log line names the operation; the next interval is the retry.
            log.error("Hold sweep failed (window {}m) — retrying on the next interval",
                    window.toMinutes(), ex);
        }
    }
}
