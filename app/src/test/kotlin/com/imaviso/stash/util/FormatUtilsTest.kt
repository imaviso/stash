package com.imaviso.stash.util

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FormatUtilsTest {
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        // "%.1f"/"%.2f" formatting uses the default locale for the decimal
        // separator; pin US so assertions are machine-independent.
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // --- Bytes branch (bytes < 1024) ---

    @Test
    fun `zero bytes renders as 0 B`() {
        assertEquals("0 B", FormatUtils.formatBytes(0))
    }

    @Test
    fun `single byte renders as 1 B`() {
        assertEquals("1 B", FormatUtils.formatBytes(1))
    }

    @Test
    fun `1023 bytes stays in bytes branch`() {
        assertEquals("1023 B", FormatUtils.formatBytes(1023))
    }

    // --- KB branch (1024 <= bytes < 1 MB, one decimal) ---

    @Test
    fun `1024 bytes renders as 1,0 KB`() {
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024))
    }

    @Test
    fun `1536 bytes renders as 1,5 KB`() {
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536))
    }

    @Test
    fun `1 MB minus 1 byte rounds to 1024,0 KB`() {
        // 1048575 / 1024f = 1023.9990..., which %.1f rounds up.
        assertEquals("1024.0 KB", FormatUtils.formatBytes(1024 * 1024 - 1))
    }

    // --- MB branch (1 MB <= bytes < 1 GB, one decimal) ---

    @Test
    fun `1 MB renders as 1,0 MB`() {
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024))
    }

    @Test
    fun `1,5 MB renders as 1,5 MB`() {
        assertEquals("1.5 MB", FormatUtils.formatBytes(1536L * 1024))
    }

    @Test
    fun `1 GB minus 1 byte rounds to 1024,0 MB`() {
        assertEquals("1024.0 MB", FormatUtils.formatBytes(1024L * 1024 * 1024 - 1))
    }

    // --- GB branch (>= 1 GB, two decimals) ---

    @Test
    fun `1 GB renders as 1,00 GB`() {
        assertEquals("1.00 GB", FormatUtils.formatBytes(1024L * 1024 * 1024))
    }

    @Test
    fun `1,5 GB renders as 1,50 GB`() {
        assertEquals("1.50 GB", FormatUtils.formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun `5 GB renders as 5,00 GB`() {
        assertEquals("5.00 GB", FormatUtils.formatBytes(5L * 1024 * 1024 * 1024))
    }

    // --- Edge ---

    @Test
    fun `negative size falls into bytes branch verbatim`() {
        assertEquals("-5 B", FormatUtils.formatBytes(-5))
    }
}
