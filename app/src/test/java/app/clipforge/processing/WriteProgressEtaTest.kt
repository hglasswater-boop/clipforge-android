package app.clipforge.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteProgressEtaTest {
    @Test
    fun estimateAppearsAfterEnoughWriteProgress() {
        var now = 0L
        val eta = WriteProgressEta { now }

        assertEquals("スマートカットを書き出し中 10%", eta.decorate("スマートカットを書き出し中 10%"))
        now = 2_000L
        assertEquals("スマートカットを書き出し中 15%", eta.decorate("スマートカットを書き出し中 15%"))
        now = 4_000L
        val decorated = eta.decorate("スマートカットを書き出し中 20%")

        assertTrue(decorated.contains("残り 約32秒"))
    }

    @Test
    fun estimateCountsDownWhileIntegerProgressIsUnchanged() {
        var now = 0L
        val eta = WriteProgressEta { now }
        eta.decorate("スマートカットを書き出し中 10%")
        now = 4_000L
        assertTrue(eta.decorate("スマートカットを書き出し中 20%").contains("残り 約32秒"))

        now = 9_000L
        assertTrue(eta.decorate("スマートカットを書き出し中 20%").contains("残り 約27秒"))
    }

    @Test
    fun nonWritePhaseResetsEstimate() {
        var now = 0L
        val eta = WriteProgressEta { now }
        eta.decorate("無劣化で書き出し中 10%（削除 2箇所）")
        now = 4_000L
        assertTrue(eta.decorate("無劣化で書き出し中 20%（削除 2箇所）").contains("残り"))

        assertEquals("SMB保存を確定しています", eta.decorate("SMB保存を確定しています"))
        now = 8_000L
        assertFalse(eta.decorate("無劣化で書き出し中 30%（削除 2箇所）").contains("残り"))
    }

    @Test
    fun changingWriteModeDoesNotReuseOldSpeed() {
        var now = 0L
        val eta = WriteProgressEta { now }
        eta.decorate("無劣化で書き出し中 10%（削除 1箇所）")
        now = 4_000L
        assertTrue(eta.decorate("無劣化で書き出し中 20%（削除 1箇所）").contains("残り"))

        now = 5_000L
        assertFalse(eta.decorate("スマートカットを書き出し中 60%").contains("残り"))
    }

    @Test
    fun remainingTimeFormattingKeepsSecondsMoving() {
        assertEquals("約45秒", formatRemainingTime(45_000L))
        assertEquals("約2分40秒", formatRemainingTime(160_000L))
        assertEquals("約2分9秒", formatRemainingTime(129_000L))
        assertEquals("約2分", formatRemainingTime(120_000L))
        assertEquals("約1時間12分", formatRemainingTime(72L * 60L * 1_000L))
    }
}
