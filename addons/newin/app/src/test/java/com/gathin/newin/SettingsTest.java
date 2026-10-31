package com.gathin.newin;

import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsTest {
    @Test public void parsesCommentsAndValues() {
        Settings settings = Settings.parse("# comment\r\njellyfin_url=http://host:8096\n jellyfin_key = secret \ninvalid\n");
        assertEquals("http://host:8096", settings.get("jellyfin_url"));
        assertEquals("secret", settings.get("jellyfin_key"));
        assertEquals("", settings.get("jellyseerr_key"));
        assertEquals("com.cricfytv.sports", settings.sportsApp());
    }
}
