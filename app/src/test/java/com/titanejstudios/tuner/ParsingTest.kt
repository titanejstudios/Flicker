package com.titanejstudios.tuner

import com.titanejstudios.tuner.apps.Updater
import com.titanejstudios.tuner.data.M3uParser
import com.titanejstudios.tuner.data.SeriesLinks
import com.titanejstudios.tuner.data.StreamAlternates
import com.titanejstudios.tuner.data.XmltvParser
import com.titanejstudios.tuner.integrations.OpenSubtitles
import com.titanejstudios.tuner.integrations.Tmdb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playlist parsing, name matching and other text handling. */
class ParsingTest {

    @Test
    fun m3uKeepsOrderNumbersAndGroups() {
        val text = """
            #EXTM3U url-tvg="http://example.com/epg.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-chno="101" tvg-logo="http://logo/bbc1.png" group-title="UK",BBC One HD
            http://example.com/live/1.ts
            #EXTINF:-1 tvg-chno="102" group-title="UK",BBC Two
            http://example.com/live/2.ts
            #EXTINF:-1 group-title="Sport",Sky Sports Main Event
            http://example.com/live/3.ts
        """.trimIndent()
        val r = M3uParser.parse(text.byteInputStream(), "s1")
        assertEquals("http://example.com/epg.xml", r.epgUrl)
        assertEquals(listOf("BBC One HD", "BBC Two", "Sky Sports Main Event"), r.channels.map { it.name })
        assertEquals(listOf(101, 102, 0), r.channels.map { it.number })
        assertEquals(listOf("UK", "UK", "Sport"), r.channels.map { it.group })
        assertEquals("bbc1.uk", r.channels[0].epgId)
        // Groups numbered in order of first appearance.
        assertEquals(0, r.channels[0].groupPos)
        assertEquals(1, r.channels[2].groupPos)
    }

    @Test
    fun guideNamesMatchLoosely() {
        assertEquals("bbcone", XmltvParser.normalise("BBC One HD"))
        assertEquals(XmltvParser.normalise("bbc one"), XmltvParser.normalise("UK: BBC One"))
    }

    @Test
    fun seriesLinkIgnoresNewAndEpisodeNumbers() {
        assertEquals(SeriesLinks.key("Doctor Who"), SeriesLinks.key("New: Doctor Who (S2 E4)"))
        assertFalse(SeriesLinks.key("Doctor Who") == SeriesLinks.key("Doctor Foster"))
    }

    @Test
    fun backupLinks() {
        assertEquals("http://x.com:8080/live/u/p/123.m3u8", StreamAlternates.otherFormat("http://x.com:8080/live/u/p/123.ts"))
        assertNull(StreamAlternates.otherFormat("http://x.com/movie/u/p/1.mp4"))
        assertEquals(StreamAlternates.nameKey("Sky Sports Main Event FHD"), StreamAlternates.nameKey("Sky Sports Main Event HD"))
    }

    @Test
    fun versionCompare() {
        assertTrue(Updater.newer("v5.4.1", "5.4"))
        assertTrue(Updater.newer("5.10", "5.9"))
        assertFalse(Updater.newer("5.4", "5.4"))
        assertFalse(Updater.newer("5.3", "5.4"))
    }

    @Test
    fun filmNamesAreCleanedForLookups() {
        assertEquals("The Matrix" to 1999, Tmdb.cleanTitle("EN - The Matrix (1999) 4K"))
        assertEquals("Paddington 2" to null, Tmdb.cleanTitle("|UK| Paddington 2 [HD]"))
        assertEquals("Dune: Part Two" to 2024, Tmdb.cleanTitle("Dune: Part Two - 2024"))
        assertEquals("1917" to null, Tmdb.cleanTitle("1917"))
    }

    @Test
    fun seasonAndEpisodeFromSubtitle() {
        assertEquals(2 to 4, OpenSubtitles.seasonEpisode("S2 E4  The Girl in the Fireplace"))
        assertNull(OpenSubtitles.seasonEpisode("A film"))
    }

    @Test
    fun xtreamStyleM3uLinkGivesTheAccountAddress() {
        assertEquals(
            "http://example.com:8080/player_api.php?username=me&password=pw",
            com.titanejstudios.tuner.data.XtreamClient.apiFromM3u("http://example.com:8080/get.php?username=me&password=pw&type=m3u_plus&output=ts"),
        )
        assertNull(com.titanejstudios.tuner.data.XtreamClient.apiFromM3u("https://example.com/my-list.m3u"))
        assertNull(com.titanejstudios.tuner.data.XtreamClient.apiFromM3u("http://example.com/get.php?type=m3u"))
    }
}
