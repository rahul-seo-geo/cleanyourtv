package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class JellyfinResolveTest {
    private static final String URL =
            "https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1";

    private final Map<String, byte[]> responses = new HashMap<>();
    private final Map<String, Map<String, String>> seenHeaders = new HashMap<>();
    private final MediaLoader.Fetcher fetcher = (url, headers) -> {
        seenHeaders.put(url, headers);
        if (!responses.containsKey(url)) {
            responses.put(url, null);
        }
        return responses.get(url);
    };

    @Test public void sendsTokenInAuthorizationHeader() {
        JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550");
        assertTrue(responses.containsKey(URL));
        assertFalse(URL.contains("api_key"));
        assertEquals("MediaBrowser Token=\"k\"", seenHeaders.get(URL).get("Authorization"));
    }

    @Test public void returnsFirstItemId() {
        responses.put(URL, "{\"Items\":[{\"Id\":\"8f12\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("8f12", JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }

    @Test public void nullOnEmptyItemsMissingBodyOrGarbage() {
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put(URL, "{\"Items\":[]}".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put(URL, "]]garbage".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }
}
