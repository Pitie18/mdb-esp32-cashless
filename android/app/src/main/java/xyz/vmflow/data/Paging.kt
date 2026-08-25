package xyz.vmflow.data

/**
 * PostgREST caps how many rows one request may return (`db.max_rows`, set to
 * 1000 for the local CLI stack in `Docker/supabase/config.toml`). The cap is
 * applied **silently**: the response is a valid 200 with a truncated body, so
 * an unpaginated fetch of a growing table does not fail — it quietly starts
 * returning less than the truth.
 */
internal const val POSTGREST_PAGE_SIZE = 1000L

/**
 * Guard against a server that keeps handing back rows: at this many pages the
 * fetch fails loudly instead of looping forever or returning a partial answer.
 * 200 pages is 200k rows, far beyond any table this app reads whole.
 */
private const val MAX_PAGES = 200

/**
 * Reads every row of a query by paging until the server runs out, rather than
 * trusting one request to return the whole table.
 *
 * Two properties matter more than the paging itself:
 *
 * 1. **It advances by the number of rows actually returned**, not by the page
 *    size it asked for. A server whose `max_rows` is smaller than
 *    [POSTGREST_PAGE_SIZE] hands back a short page; treating that as "the end"
 *    would reintroduce the very truncation this exists to prevent. The loop
 *    therefore stops only on an empty page, at the cost of one extra request.
 * 2. **It never truncates silently.** If the page count runs past [MAX_PAGES],
 *    it throws. A partial answer that looks complete is worse than an error:
 *    in the refill wizard a missing tray row makes a half-empty machine render
 *    as fully stocked.
 *
 * The caller's query **must impose a total order** (a unique tiebreaker such
 * as the primary key). Paging a non-unique sort lets the server return rows in
 * a different order per page, so a row can appear twice or never — a data bug
 * that looks exactly like a data-entry mistake.
 */
internal suspend fun <T> fetchAllPages(
    pageSize: Long = POSTGREST_PAGE_SIZE,
    fetchPage: suspend (from: Long, to: Long) -> List<T>,
): List<T> {
    require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    val all = mutableListOf<T>()
    var from = 0L
    var pages = 0
    while (true) {
        val page = fetchPage(from, from + pageSize - 1)
        if (page.isEmpty()) return all
        all += page
        from += page.size
        pages++
        if (pages >= MAX_PAGES) {
            error("Paged fetch exceeded $MAX_PAGES pages (${all.size} rows) — refusing to return a partial result")
        }
    }
}
