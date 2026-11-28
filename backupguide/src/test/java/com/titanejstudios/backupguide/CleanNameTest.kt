package com.titanejstudios.backupguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CleanNameTest {
    @Test
    fun namesThatShouldMatch() {
        assertEquals(BackupGuide.cleanName("BBC 1"), BackupGuide.cleanName("BBC One HD"))
        assertEquals(BackupGuide.cleanName("BBC One"), BackupGuide.cleanName("UK: BBC One FHD (1080p)"))
        assertEquals(BackupGuide.cleanName("Sky Sports Main Event"), BackupGuide.cleanName("UK | Sky Sports Main Event HD"))
        assertEquals(BackupGuide.cleanName("Channel 4"), BackupGuide.cleanName("Channel Four"))
        assertEquals("nowsports1", BackupGuide.cleanName("HK: Now Sports 1 HD"))
    }

    @Test
    fun namesThatShouldNot() {
        assertNotEquals(BackupGuide.cleanName("ITV1"), BackupGuide.cleanName("ITV1 +1"))
        assertNotEquals(BackupGuide.cleanName("BBC One"), BackupGuide.cleanName("BBC Two"))
    }

    @Test
    fun channelsKnowTheirCountry() {
        val c = BackupGuideCore
        org.junit.Assert.assertEquals("UK", c.countryOf("Sky Sports Main Event FHD (1080p)", "UK | Sports*"))
        org.junit.Assert.assertEquals("CH", c.countryOf("CH: 7+", "Switzerland Locals"))
        org.junit.Assert.assertEquals("BG", c.countryOf("BG | NHK World TV", "Bulgaria Locals"))
        org.junit.Assert.assertEquals("US", c.countryOf("[Hulu Arizona] KNXV", "US | Locals (Hulu)"))
        org.junit.Assert.assertEquals("HK", c.countryOf("Now Sports 1", "NOW HK"))
        org.junit.Assert.assertEquals("NL", c.countryOf("NL: Nat Geo Wild", "Netherlands Locals"))
        org.junit.Assert.assertEquals(null, c.countryOf("Arsenal", "⚽️ | EPL Teams"))
    }

    @Test
    fun usLocalsMatchByCallSign() {
        val c = BackupGuideCore
        org.junit.Assert.assertEquals("WALB:2", c.callKey("DirectTV Albany GA | ABC-WALB-D2"))
        org.junit.Assert.assertEquals("WALB:2", c.callKey("WALB-DT2"))
        org.junit.Assert.assertEquals("KNXV:", c.callKey("[Hulu Arizona] KNXV"))
        org.junit.Assert.assertEquals("KNXV:", c.callKey("KNXV-DT"))
        org.junit.Assert.assertEquals(null, c.callKey("BBC One"))
    }
}
