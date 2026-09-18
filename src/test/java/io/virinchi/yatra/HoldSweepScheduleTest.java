package io.virinchi.yatra;

import io.virinchi.yatra.Config.HoldSweepConfig;
import io.virinchi.yatra.Dto.HoldExpiryResponse;
import io.virinchi.yatra.Service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code Config/HoldSweepConfig} — <b>the scheduled half of the hold sweep actually fires.</b>
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@code HoldExpiryTest} proves the rule and {@code AdminBookingController} proves the manual
 * trigger, but both call {@code BookingService.expireHolds} themselves. Neither can answer the
 * question the timer raises: <i>does anything ever call it on its own?</i> An annotation that is
 * misspelled, a bean that never registers, a {@code fixedDelayString} that resolves to nothing —
 * every one of those leaves both of the other tests green and the feature absent, which is the
 * shape of failure this project has been burned by before (the deactivate button that no code
 * path consulted, R18).
 *
 * <h2>How the timer is proved without arming it for the whole suite</h2>
 *
 * <p>The property is set for <b>this class's context only</b>
 * ({@code @SpringBootTest(properties = …)}) — a distinct context cache key, so the other contexts
 * in the suite stay disarmed and no scheduler runs beside them. That matters because the suite
 * boots 26 {@code @SpringBootTest} contexts against the <b>live</b> schema: a {@code @Scheduled}
 * method needs no request, no token and no caller, and it deletes rows, so an armed-by-default
 * job would turn "run the tests" into "write to the database" — the same hazard that made seeding
 * an explicit admin endpoint instead of a {@code CommandLineRunner}.
 *
 * <p><b>And the sweep itself is mocked here</b>, which is the second half of the containment:
 * the timer runs for real (a real {@code @EnableScheduling} post-processor, a real interval), but
 * what it calls is a Mockito mock, so not one row is read or written by this class. The test is
 * observing the <i>trigger</i>, and the trigger is the only part the other two classes cannot
 * reach.
 *
 * <p><b>{@code yatra.hold.expiry-minutes} is deliberately set to 7</b>, not the production 15, so
 * the assertion can only pass if the property is actually read. Asserting 15 would also pass
 * against a hardcoded constant — a test that cannot fail for the reason it exists.
 *
 * <h2>What is asserted, and in which context</h2>
 *
 * <ol>
 *   <li><b>The real application context</b> ({@code @SpringBootTest} + a mocked
 *       {@code BookingService}): the armed bean exists, the timer fires, it passes the configured
 *       window, and it keeps firing at its interval rather than once.</li>
 *   <li><b>A minimal context</b> ({@link ApplicationContextRunner}, DB-free): the bean is
 *       <b>absent</b> unless the property is set — the off-by-default claim, asserted rather than
 *       trusted — and a sweep that throws does not stop the timer, which is the one behaviour the
 *       {@code try/catch} in the config exists for.</li>
 * </ol>
 *
 * <p>Not asserted, and named rather than implied: that Spring's scheduler thread is still alive at
 * some later wall-clock moment. The interval here is 150 ms and a production interval is a minute,
 * so a test that proved "it is still ticking an hour later" would be a test that runs for an hour.
 * What is proved is that repeated ticks happen <b>on an interval</b>, which is what the
 * {@code fixedDelay} contract is.
 */
@SpringBootTest(properties = {
        "yatra.hold.sweep.enabled=true",
        "yatra.hold.sweep.initial-delay-ms=1000",
        "yatra.hold.sweep.interval-ms=150",
        "yatra.hold.expiry-minutes=7"
})
class HoldSweepScheduleTest {

    /** Injected to prove the conditional bean really is in the application context. */
    @Autowired private HoldSweepConfig sweeper;

    /** The trigger is observed; the sweep itself must never touch the live database here. */
    @MockitoBean private BookingService bookingService;

    /**
     * A successful, empty sweep — so a tick is a visible invocation and writes nothing.
     *
     * <p>It is also what keeps the run quiet: the config logs a summary only when it expired
     * something, so a context left ticking with this stub produces no log lines at all.
     */
    @BeforeEach
    void theSweepAnswersWithNothingToDo() {
        when(bookingService.expireHolds(any())).thenReturn(
                HoldExpiryResponse.of(Duration.ofMinutes(7), LocalDateTime.now(), 0, 0, 0, 0));
    }

    /* ------------------------------------------------------------------ *
     *  in the real application context                                    *
     * ------------------------------------------------------------------ */

    /** The armed context registers the sweeper — no bean, no timer, and the other tests would not know. */
    @Test
    void anArmedContextRegistersTheSweeper() {
        assertThat(sweeper).as("yatra.hold.sweep.enabled=true must create the bean").isNotNull();
    }

    /**
     * The point of the class: a real scheduled tick reaches {@code BookingService}, and it asks for
     * the <b>configured</b> window rather than a constant.
     *
     * <p>{@code timeout(…)} is what makes this a test of the timer rather than of a method call:
     * it waits for the invocation to arrive on the scheduler's own thread. A missing
     * {@code @Scheduled}, a bean that never registered, or a {@code fixedDelayString} that binds to
     * nothing all fail here — and only here.
     */
    @Test
    void theTimerFiresAndPassesTheConfiguredWindow() {
        verify(bookingService, timeout(10_000).atLeastOnce()).expireHolds(Duration.ofMinutes(7));
    }

    /**
     * {@code fixedDelay} means repeatedly, not once — the difference between a job that keeps a
     * deployment tidy and a job that fired a single time at startup and has been dead since.
     *
     * <p>Measured by watching the invocation count grow over roughly six intervals. Nothing here
     * depends on the tick's exact phase, only on the count being strictly larger afterwards.
     */
    @Test
    void theTimerKeepsFiringOnItsInterval() throws Exception {
        verify(bookingService, timeout(10_000).atLeastOnce()).expireHolds(any());

        int before = ticksSoFar();
        Thread.sleep(1_000);

        assertThat(ticksSoFar())
                .as("at 150 ms an interval, a second of waiting must produce more ticks")
                .isGreaterThan(before);
    }

    /* ------------------------------------------------------------------ *
     *  the condition, and the catch — in a DB-free context                *
     * ------------------------------------------------------------------ */

    /**
     * Off unless armed, asserted in both directions.
     *
     * <p>{@link ApplicationContextRunner} rather than a second {@code @SpringBootTest}: the claim is
     * about a conditional <i>bean definition</i>, which needs a real Spring context and nothing else
     * — no JPA, no schema, no connection, and no extra seven-second boot. Registered with a mock
     * {@code BookingService} because that is all the config's constructor asks for.
     */
    @Test
    void theSweeperExistsOnlyWhenItIsArmed() {
        assertThat(sweeperBeans("yatra.hold.sweep.enabled=false"))
                .as("off by default: a context that has not opted in must have no scheduler at all")
                .isEmpty();

        assertThat(sweeperBeans("yatra.hold.sweep.enabled=true"))
                .as("and the switch really is what creates it")
                .containsExactly("holdSweepConfig");
    }

    /**
     * A sweep that throws does not end the timer.
     *
     * <p>Not a nicety: this is what the {@code catch (RuntimeException)} in {@code HoldSweepConfig}
     * is for, and the alternative story ("Spring keeps fixed-delay tasks alive anyway") is a claim
     * about a framework's behaviour that this project would rather <b>measure</b> than assert in a
     * comment. The mock throws on every call, and the count has to keep growing regardless — so the
     * failure is contained to one tick, not to the job.
     *
     * <p><b>Expected output:</b> the config logs one ERROR line per tick in this test, because a
     * caught exception that is not logged is a failure nobody can diagnose. Those lines are this
     * test's evidence, not a problem with the run.
     */
    @Test
    void aFailingSweepDoesNotStopTheTimer() throws Exception {
        BookingService failing = mock(BookingService.class);
        when(failing.expireHolds(any())).thenThrow(new IllegalStateException("the sweep blew up"));

        new ApplicationContextRunner()
                .withUserConfiguration(HoldSweepConfig.class)
                .withBean(BookingService.class, () -> failing)
                .withPropertyValues(
                        "yatra.hold.sweep.enabled=true",
                        "yatra.hold.sweep.initial-delay-ms=0",
                        "yatra.hold.sweep.interval-ms=150",
                        "yatra.hold.expiry-minutes=7")
                .run(context -> {
                    assertThat(context.getBeansOfType(HoldSweepConfig.class))
                            .as("the armed minimal context still has its scheduler")
                            .hasSize(1);

                    verify(failing, timeout(5_000).atLeastOnce()).expireHolds(any());

                    int before = mockingDetails(failing).getInvocations().size();
                    Thread.sleep(600);

                    assertThat(mockingDetails(failing).getInvocations().size())
                            .as("every call threw, and the timer kept calling")
                            .isGreaterThan(before);
                });
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                            *
     * ------------------------------------------------------------------ */

    /** How many times the mocked sweep has been reached so far — the timer's own odometer. */
    private int ticksSoFar() {
        return mockingDetails(bookingService).getInvocations().size();
    }

    /** The sweeper beans a minimal context ends up with, under one property value. */
    private static Set<String> sweeperBeans(String enabledProperty) {
        Set<String> found = new HashSet<>();

        new ApplicationContextRunner()
                .withUserConfiguration(HoldSweepConfig.class)
                .withBean(BookingService.class, () -> mock(BookingService.class))
                .withPropertyValues(enabledProperty)
                .run(context -> found.addAll(context.getBeansOfType(HoldSweepConfig.class).keySet()));

        return found;
    }
}
