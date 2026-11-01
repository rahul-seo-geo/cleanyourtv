package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaSettingsTest {
    @Test public void parsesKeysSkipsCommentsAndBlankLines() {
        MediaSettings s = MediaSettings.parse(
                "# dashboard sources\n"
                + "seerr_url = https://seerr.example/\n"
                + "\n"
                + "m3u_url=http://nas:8096/playlist.m3u\n");
        assertEquals("https://seerr.example", s.get("seerr_url"));
        assertEquals("http://nas:8096/playlist.m3u", s.get("m3u_url"));
        assertEquals("", s.get("seerr_key"));
    }

    @Test public void trimsTrailingSlashesFromUrls() {
        assertEquals("https://seerr.example", MediaSettings.parse("seerr_url=https://seerr.example///").get("seerr_url"));
        assertEquals("https://seerr.example", MediaSettings.parse("seerr_url=https://seerr.example/").get("seerr_url"));
    }

    @Test public void anySourceFalseWhenEmptyAndTruePerSource() {
        assertFalse(MediaSettings.parse("").anySource());
        assertTrue(MediaSettings.parse("seerr_url=https://seerr.example").anySource());
        assertTrue(MediaSettings.parse("m3u_url=http://nas/playlist.m3u").anySource());
        assertTrue(MediaSettings.parse("game_day_pkg=com.gathin.game").anySource());
    }

    @Test public void refreshMinutesClampedToOneMinimum() {
        assertEquals(15, MediaSettings.parse("").refreshMinutes());
        assertEquals(1, MediaSettings.parse("refresh_minutes=0").refreshMinutes());
        assertEquals(7, MediaSettings.parse("refresh_minutes=7").refreshMinutes());
        assertEquals(15, MediaSettings.parse("refresh_minutes=bogus").refreshMinutes());
    }

    @Test public void blankTextYieldsAllEmptyButNotNull() {
        MediaSettings s = MediaSettings.parse(null);
        assertEquals("", s.get("seerr_url"));
        assertFalse(s.anySource());
    }
}
