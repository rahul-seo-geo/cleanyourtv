package com.gathin.news;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeedsTest {
    @Test public void parsesArsenalItems() throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/bbc-arsenal.xml")) {
            List<Feeds.Item> items = Feeds.parse(in);
            assertEquals(6, items.size());
            assertEquals("Teen football photographer 'living the dream'", items.get(0).title);
            assertTrue(items.get(0).pubDateMs > 0);
            assertTrue(items.get(1).link.startsWith("https://www.bbc.com/sport"));
        }
    }

    @Test public void malformedXmlIsEmpty() throws Exception {
        assertTrue(Feeds.parse(new ByteArrayInputStream("<rss><channel>".getBytes(StandardCharsets.UTF_8))).isEmpty());
    }
}
