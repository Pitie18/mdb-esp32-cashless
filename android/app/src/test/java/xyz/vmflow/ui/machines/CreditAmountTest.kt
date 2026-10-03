package xyz.vmflow.ui.machines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditAmountTest {

    @Test
    fun `parses a plain dot decimal`() {
        assertEquals(5.5, parseCreditAmount("5.5"))
    }

    @Test
    fun `accepts comma as the decimal separator, like iOS`() {
        assertEquals(5.5, parseCreditAmount("5,5"))
    }

    @Test
    fun `parses a whole number`() {
        assertEquals(2.0, parseCreditAmount("2"))
    }

    @Test
    fun `rejects zero`() {
        assertNull(parseCreditAmount("0"))
    }

    @Test
    fun `rejects a negative amount`() {
        assertNull(parseCreditAmount("-1"))
    }

    @Test
    fun `rejects blank input`() {
        assertNull(parseCreditAmount(""))
    }

    @Test
    fun `rejects unparseable input`() {
        assertNull(parseCreditAmount("abc"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(2.0, parseCreditAmount(" 2 "))
    }
}
