package com.gathin.newin;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class RowsTest {
    @Test public void splitsLatestAndMakesPosters() throws Exception {
        List<Rows.Shelf> shelves = Rows.latest("[{\"Type\":\"Movie\",\"Id\":\"a\",\"Name\":\"Film\"},{\"Type\":\"Series\",\"Id\":\"b\",\"Name\":\"Show\"}]", "http://jf");
        assertEquals("Movies", shelves.get(0).title);
        assertEquals("Series", shelves.get(1).title);
        assertEquals("http://jf/Items/a/Images/Primary?maxWidth=400", shelves.get(0).items.get(0).poster);
    }

    @Test public void resumeProgress() throws Exception {
        Rows.Item item = Rows.resume("{\"Items\":[{\"Name\":\"Next\",\"UserData\":{\"PlayedPercentage\":64.3}}]}", "http://jf").get(0).items.get(0);
        assertEquals(64, item.progress);
        assertEquals("jellyfin", item.intent);
    }

    @Test public void seerrBadgesAndPosters() throws Exception {
        Rows.Item item = Rows.seerr("{\"results\":[{\"status\":2,\"media\":{\"title\":\"Film\",\"posterPath\":\"/poster.jpg\"}}]}").get(0).items.get(0);
        assertEquals("Approved", item.subtitle);
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg", item.poster);
        assertEquals("", item.intent);
        assertEquals("Pending", Rows.badge(1));
        assertEquals("Processing", Rows.badge(3));
        assertEquals("Available", Rows.badge(4));
    }

    @Test public void seerrTvTitleAndMissingTitle() throws Exception {
        Rows.Item tv = Rows.seerr("{\"results\":[{\"status\":1,\"media\":{\"mediaType\":\"tv\",\"info\":{\"title\":\"The Series\"}}}]}").get(0).items.get(0);
        assertEquals("The Series", tv.title);
        Rows.Item movie = Rows.seerr("{\"results\":[{\"id\":42,\"media\":{\"mediaType\":\"movie\"}}]}").get(0).items.get(0);
        assertEquals("Movie request", movie.title);
        assertEquals("Media request", Rows.seerrTitle(new org.json.JSONObject("{\"media\":{}}")));
    }

    @Test public void ownTvOnlyFiresOwnLinks() {
        assertTrue(Rows.ownTv("tv.own.owntv", "owntv://play/123"));
        assertFalse(Rows.ownTv("other", "owntv://play/123"));
        assertFalse(Rows.ownTv("tv.own.owntv", ""));
    }
}
