package xyz.vmflow.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What these pin, in order of how much they would cost if they broke:
 *
 * 1. A server whose own row cap is smaller than the requested page size must
 *    not be mistaken for the end of the table. That is the exact shape of the
 *    silent truncation [fetchAllPages] exists to prevent.
 * 2. Running past the page guard must throw rather than return a partial list
 *    that looks complete.
 */
class PagingTest {

    /** Serves [total] ids in pages of at most [serverCap], recording each request. */
    private class FakeTable(val total: Int, val serverCap: Int) {
        val requests = mutableListOf<Pair<Long, Long>>()

        fun page(from: Long, to: Long): List<Int> {
            requests += from to to
            val requested = (to - from + 1).toInt()
            val size = minOf(requested, serverCap)
            if (from >= total) return emptyList()
            val end = minOf(from + size, total.toLong())
            return (from until end).map { it.toInt() }
        }
    }

    @Test
    fun `a single short page is returned whole, and the loop stops on the empty page`() = runBlocking {
        val table = FakeTable(total = 7, serverCap = 1000)
        val rows = fetchAllPages(pageSize = 1000) { from, to -> table.page(from, to) }
        assertEquals((0..6).toList(), rows)
        assertEquals(2, table.requests.size)  // the rows, then the empty page
    }

    @Test
    fun `a full last page is followed by one more request, not assumed to be the end`() = runBlocking {
        // Note this case does NOT discriminate advance-by-returned-rows from
        // advance-by-requested-size (serverCap == pageSize makes them equal);
        // the case below does. What it does catch is an early stop after a
        // full page.
        val table = FakeTable(total = 2000, serverCap = 1000)
        val rows = fetchAllPages(pageSize = 1000) { from, to -> table.page(from, to) }
        assertEquals(2000, rows.size)
        assertEquals((0..1999).toList(), rows)
    }

    @Test
    fun `a server cap smaller than the page size does not look like the end of the table`() = runBlocking {
        // The bug this guards: asking for 1000 and getting 300 back because the
        // SERVER caps at 300. Treating a short page as "done" would return 300
        // of 1000 rows and report success.
        val table = FakeTable(total = 1000, serverCap = 300)
        val rows = fetchAllPages(pageSize = 1000) { from, to -> table.page(from, to) }
        assertEquals(1000, rows.size)
        assertEquals((0..999).toList(), rows)
    }

    @Test
    fun `an empty table costs one request and returns nothing`() = runBlocking {
        val table = FakeTable(total = 0, serverCap = 1000)
        val rows = fetchAllPages(pageSize = 1000) { from, to -> table.page(from, to) }
        assertTrue(rows.isEmpty())
        assertEquals(1, table.requests.size)
    }

    @Test
    fun `every request asks for a window of exactly the page size`() = runBlocking {
        val table = FakeTable(total = 250, serverCap = 100)
        fetchAllPages(pageSize = 100) { from, to -> table.page(from, to) }
        assertEquals(listOf(0L to 99L, 100L to 199L, 200L to 299L, 250L to 349L), table.requests)
    }

    // A timeout, so a regression that removes the guard entirely fails this
    // test instead of hanging the whole suite on an endless loop.
    @Test(timeout = 10_000)
    fun `a server that never runs out fails loudly instead of returning a partial list`() {
        try {
            runBlocking {
                // Always hands back a full page, so the end never arrives.
                fetchAllPages(pageSize = 10) { _, _ -> List(10) { 0 } }
            }
            fail("expected the page guard to throw")
        } catch (e: IllegalStateException) {
            assertTrue(
                "message should say it refused a partial result, was: ${e.message}",
                e.message!!.contains("partial"),
            )
        }
    }

    @Test(timeout = 10_000)
    fun `a table needing exactly the maximum number of pages still completes`() {
        // The guard must not throw one page early: 200 full pages is a legal
        // table, and only the 201st trip is a runaway. Pins the boundary the
        // first version of this got wrong.
        val maxPages = 200
        val table = FakeTable(total = maxPages * 10, serverCap = 10)
        val rows = runBlocking { fetchAllPages(pageSize = 10) { from, to -> table.page(from, to) } }
        assertEquals(maxPages * 10, rows.size)
    }

    @Test
    fun `a non-positive page size is rejected`() {
        try {
            runBlocking { fetchAllPages(pageSize = 0) { _, _ -> emptyList<Int>() } }
            fail("expected a rejected page size")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("positive"))
        }
    }
}
