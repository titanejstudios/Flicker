package com.titanejstudios.tuner

import com.titanejstudios.tuner.data.KidsRules
import com.titanejstudios.tuner.data.Lite
import com.titanejstudios.tuner.data.Moods
import com.titanejstudios.tuner.data.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RicherTest {
    @Test
    fun voiceCommands() {
        assertEquals("eastenders", (VoiceCommand.parse("Record EastEnders") as VoiceCommand.Record).what)
        assertEquals("match of the day", (VoiceCommand.parse("remind me about Match of the Day please") as VoiceCommand.Remind).what)
        assertTrue(VoiceCommand.parse("play the next episode") === VoiceCommand.NextEpisode)
        assertTrue(VoiceCommand.parse("Next episode") === VoiceCommand.NextEpisode)
        val later = VoiceCommand.parse("What's on BBC One later?") as VoiceCommand.WhatsOn
        assertEquals("bbc one", later.channel)
        assertTrue(later.later)
        val now = VoiceCommand.parse("whats on itv") as VoiceCommand.WhatsOn
        assertEquals("itv", now.channel)
        assertFalse(now.later)
        assertEquals("whatson", (VoiceCommand.parse("what's on tonight") as VoiceCommand.Go).place)
        assertEquals("dune", (VoiceCommand.parse("Where can I watch Dune?") as VoiceCommand.WhereToWatch).what)
        assertEquals("guide", (VoiceCommand.parse("go to the TV guide") as VoiceCommand.Go).place)
        assertEquals("funny", (VoiceCommand.parse("something funny") as VoiceCommand.Mood).key)
        assertTrue(VoiceCommand.parse("surprise me") === VoiceCommand.Surprise)
        // Channels and plain searches are left alone.
        assertNull(VoiceCommand.parse("BBC One"))
        assertNull(VoiceCommand.parse("watch Sky Sports Main Event"))
        assertNull(VoiceCommand.parse("family guy"))
    }

    @Test
    fun moodsFitByCategoryGenreAndLength() {
        val funny = Moods.byKey("funny")!!
        assertTrue(Moods.fits(funny, listOf("EN | Comedy"), null))
        assertFalse(Moods.fits(funny, listOf("EN | Horror"), null))
        val feelgood = Moods.byKey("feelgood")!!
        assertFalse(Moods.fits(feelgood, listOf("Comedy", "Horror"), null))
        val short = Moods.byKey("short")!!
        assertTrue(Moods.fits(short, emptyList(), 88))
        assertFalse(Moods.fits(short, emptyList(), 150))
        assertFalse(Moods.fits(short, emptyList(), null))
        assertEquals("scary", Moods.fromSpeech("i want something scary")?.key)
        assertNull(Moods.fromSpeech("horror"))
    }

    @Test
    fun liteModeChoice() {
        assertTrue(Lite.isLowMemory(false, 0, 1_450_000_000L)) // Fire TV Stick 4K (1.5 GB)
        assertFalse(Lite.isLowMemory(false, 192, 1_950_000_000L)) // a 2 GB Google TV
        assertTrue(Lite.isLowMemory(true, 0, 0L))
        assertTrue(Lite.decide("auto", true))
        assertFalse(Lite.decide("auto", false))
        assertTrue(Lite.decide("on", false))
        assertFalse(Lite.decide("off", true))
        assertEquals("on", Lite.next("auto"))
    }

    @Test
    fun liteModeKnowsTheDevice() {
        assertEquals("Fire TV Stick 4K (1st gen)", Lite.niceName("Amazon", "AFTMM"))
        assertEquals("Amazon Fire TV (AFTXYZ)", Lite.niceName("Amazon", "AFTXYZ"))
        assertEquals("Google Chromecast", Lite.niceName("Google", "Chromecast"))
        assertEquals("NVIDIA SHIELD Android TV", Lite.niceName("NVIDIA", "SHIELD Android TV"))
        assertEquals(1.5, Lite.roundedGb(1_430_000_000L), 0.001)
        assertEquals(2.0, Lite.roundedGb(1_950_000_000L), 0.001)
        assertEquals("under 2 GB of memory", Lite.why(false, 196, 1_430_000_000L))
        assertEquals("enough memory", Lite.why(false, 192, 1_950_000_000L))
    }

    @Test
    fun bedtimeHeadsUpAtTenMinutes() {
        assertTrue(KidsRules.bedtimeHeadsUp(10))
        assertTrue(KidsRules.bedtimeHeadsUp(9))
        assertFalse(KidsRules.bedtimeHeadsUp(5))
        assertFalse(KidsRules.bedtimeHeadsUp(null))
    }

    @Test
    fun teamsInOtherSports() {
        val espn = com.titanejstudios.tuner.integrations.Espn
        assertEquals("soccer/eng.1", espn.path("eng.1"))
        assertEquals("rugby/267979", espn.path("rugby/267979"))
        assertEquals("football", espn.sportOfLeague("eng.1")?.key)
        assertEquals("nfl", espn.sportOfLeague("football/nfl")?.key)
        assertEquals("Rugby", espn.sport("rugby")?.guideType)
        assertTrue(espn.SPORTS.any { it.key == "darts" && it.leagues.isEmpty() })
    }

    @Test
    fun playlistPasswordsAreEncryptedForTheAccount() {
        val c = com.titanejstudios.tuner.data.SyncCrypto
        val enc = c.encrypt("s3cret-pass", "Mark@Example.com")
        assertTrue(c.isEncrypted(enc))
        assertFalse(enc.contains("s3cret"))
        assertEquals("s3cret-pass", c.decrypt(enc, "mark@example.com"))
        assertNull(c.decrypt(enc, "someone.else@example.com"))
        assertEquals("plain", c.decrypt("plain", null))
    }

    @Test
    fun liveSubtitlesOnlyOnCapableDevices() {
        val l = com.titanejstudios.tuner.subtitles.LiveSubtitles
        // Fire TV Stick 4K (1st gen): 32-bit, 1.5 GB.
        assertNotNull(l.whyNot(false, true, 1_500_000_000L, 4))
        // Google TV Streamer: 64-bit, 4 GB, 4 cores.
        assertNull(l.whyNot(true, false, 4_000_000_000L, 4))
        assertNotNull(l.whyNot(true, false, 1_500_000_000L, 8))
        assertNotNull(l.whyNot(true, false, 4_000_000_000L, 2))
    }

    @Test
    fun liveSubtitleCaptionsKeepTheLastWholeWords() {
        val l = com.titanejstudios.tuner.subtitles.LiveSubtitles
        assertEquals("short", l.tail("short", 20))
        assertEquals("over the lazy dog", l.tail("the quick brown fox jumps over the lazy dog", 18))
    }

    @Test
    fun problemLogShowsNewestEntriesFirstButEachTheRightWayUp() {
        val lines = listOf(
            "26 Nov 22:05:20  INFO  started",
            "26 Nov 22:05:26  PROBLEM  Last closed: it froze",
            "\"main\" prio=5 tid=1 Blocked",
            "  at com.example.Thing.wait(Thing.kt:1)",
            "26 Nov 22:05:32  PROBLEM  low on memory",
        )
        val shown = com.titanejstudios.tuner.data.AppLog.newestFirst(lines).lines()
        assertEquals("26 Nov 22:05:32  PROBLEM  low on memory", shown[0])
        assertEquals("26 Nov 22:05:26  PROBLEM  Last closed: it froze", shown[1])
        assertEquals("\"main\" prio=5 tid=1 Blocked", shown[2])
        assertEquals("26 Nov 22:05:20  INFO  started", shown[4])
    }

    @Test
    fun releaseNotesAreTidiedForTheTv() {
        val raw = "## What's Changed\n* **Live subtitles** by @markbrown3000 in https://github.com/x/y/pull/12\n- See [the log](https://example.com)\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\nhttps://claude.ai/code/session_x"
        val t = com.titanejstudios.tuner.apps.Updater.tidyNotes(raw)
        assertEquals("What's Changed\n• Live subtitles\n• See the log", t)
    }

    @Test
    fun tmdbScoresAreLabelled() {
        assertEquals("TMDB 8.5", com.titanejstudios.tuner.integrations.Tmdb.ratingText(8.5))
        assertNull(com.titanejstudios.tuner.integrations.Tmdb.ratingText(0.0))
    }

    @Test
    fun freezeReportsStartJustBeforeFlickersOwnCode() {
        val frames = (1..10).map { "android.os.Internal$it.run(X.java:1)" } +
            listOf("com.titanejstudios.tuner.TunerViewModel.slowThing(TunerViewModel.kt:42)") +
            (1..40).map { "android.os.Looper.loop$it(Looper.java:1)" }
        val kept = com.titanejstudios.tuner.data.FreezeWatch.interesting(frames)
        assertEquals(25, kept.size)
        assertEquals("com.titanejstudios.tuner.TunerViewModel.slowThing(TunerViewModel.kt:42)", kept[6])
    }

    @Test
    fun anUpdateIsNotAnUnexpectedClose() {
        val s = com.titanejstudios.tuner.data.SafeMode
        val closed = 1_700_000_000_000L
        assertTrue(s.wasUpdate(closed, closed + 20_000L))
        assertTrue(s.wasUpdate(closed, closed - 60_000L))
        assertFalse(s.wasUpdate(closed, closed - 3 * 86_400_000L))
        assertFalse(s.wasUpdate(closed, 0L))
    }

    @Test
    fun teamNamesCoverTheGuidesWaysOfWritingThem() {
        assertTrue("Newcastle United" in teamNames("Newcastle United FC"))
        assertTrue("Bournemouth" in teamNames("AFC Bournemouth"))
        assertTrue("Manchester United" in teamNames("Man Utd"))
        assertTrue("Man Utd" in teamNames("Man Utd", "Manchester United"))
        assertTrue("Brighton & Hove Albion" in teamNames("Brighton", "Brighton and Hove Albion"))
        assertEquals(listOf("Eagles"), teamNames("Eagles"))
    }

    @Test
    fun eventSlotListingsBecomeTheMatch() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 10, 28, 9, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val now = cal.timeInMillis
        val allDay = com.titanejstudios.tuner.data.Programme(now - 9 * 3_600_000L, now + 15 * 3_600_000L, "EPL02: Man Utd 15:00 Brentford 28/11", null)
        val p = com.titanejstudios.tuner.data.Sports.normalise(allDay, now)
        assertNotNull(p)
        assertEquals("Man Utd v Brentford", p!!.title)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 15)
        assertEquals(cal.timeInMillis, p.start)
        // An ordinary programme is left alone.
        val show = com.titanejstudios.tuner.data.Programme(now, now + 3_600_000L, "Football Focus", null)
        assertEquals("Football Focus", com.titanejstudios.tuner.data.Sports.normalise(show, now)?.title)
    }

    @Test
    fun aShowNamedAfterItselfIsNotAReplay() {
        val p = com.titanejstudios.tuner.data.Programme(0L, 3_600_000L, "Saturday Night Football",
            "On Saturday Night Football, Ipswich Town play host to Aston Villa at Portman Road in the Premier League.")
        assertFalse(com.titanejstudios.tuner.data.Sports.looksLikeReplay(p))
        assertNotNull(com.titanejstudios.tuner.data.Sports.classify(p, "UK | Sports"))
        val h = com.titanejstudios.tuner.data.Programme(0L, 3_600_000L, "Premier League Highlights", "Ipswich Town v Aston Villa")
        assertTrue(com.titanejstudios.tuner.data.Sports.looksLikeReplay(h))
    }

    @Test
    fun theNationsChampionshipIsRugby() {
        val p = com.titanejstudios.tuner.data.Programme(0L, 3_600_000L, "England v New Zealand: Nations Championship", null)
        assertEquals("Rugby", com.titanejstudios.tuner.data.Sports.classify(p, "UK | Entertainment"))
        val f = com.titanejstudios.tuner.data.Programme(0L, 3_600_000L, "Ipswich Town v Aston Villa", null)
        assertEquals("Football", com.titanejstudios.tuner.data.Sports.classify(f, "UK | Sports"))
    }
}
