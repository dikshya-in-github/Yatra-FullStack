package io.virinchi.yatra.Service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The single place a list endpoint's {@code page}/{@code size} becomes a Spring Data
 * {@link PageRequest}.
 *
 * <p>Every admin table and both storefront lists take their paging from query
 * parameters, which means every one of them is fed by the caller. Two of the seven
 * call sites used to clamp by hand ({@code Math.max(page, 0)}, {@code Math.max(size, 1)})
 * and none of them capped the <i>upper</i> end, so a caller could ask for
 * {@code ?size=1000000} and have the server materialise the whole table into one page.
 * On {@code GET /api/airlines} — which is public, because the storefront is — that is
 * a request any anonymous visitor can make. The bindings are the same on every
 * surface, so they are stated once here rather than seven times.
 *
 * <p>Phase 14 (cross-cutting polish) found this: the audit checked that every list had
 * paging and a filter, and the arithmetic behind "paging" was the one part of it that
 * was unbounded.
 *
 * <h2>Why {@code size} is capped rather than refused</h2>
 *
 * <p>A {@code 400} would be more honest than a silent smaller page, but {@code size} is
 * a display hint that no caller depends on: the pages ask for what they will draw
 * (the tests here ask for 1–2 rows), so nothing legitimate is anywhere near the
 * ceiling. Clamping keeps a hand-typed URL working while making the worst case
 * bounded, which is the property that was missing. {@link #MAX_SIZE} is generous
 * deliberately: it is a backstop, not a page size.
 *
 * <p>Unpaged lists are a different path and stay unpaged — the services call
 * {@code findAll(sort)} when {@code size} is absent, which is what the admin tables
 * rely on while they page their own rows client-side.
 */
public final class Paging {

    /** The largest page any list will serve, whatever the caller asks for. */
    public static final int MAX_SIZE = 200;

    private Paging() {
    }

    /** A {@link PageRequest} from request parameters that may be negative or huge. */
    public static PageRequest request(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), clamp(size), sort);
    }

    /** {@code size} brought into {@code [1, MAX_SIZE]}. */
    public static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }
}
