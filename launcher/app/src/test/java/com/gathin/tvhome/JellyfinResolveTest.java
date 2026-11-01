package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class JellyfinResolveTest {
    private final Map<String, byte[]> responses = new HashMap<>();
    private final MediaLoader.Fetcher fetcher = (url, headers) -> {
        if (!responses.containsKey(url)) {
            responses.put(url, null);
        }
        return responses.get(url);
    };

    @Test public void buildsQueryUrlWithProviderId() {
        JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550");
        assertTrue(responses.containsKey(
                "https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k"));
    }

    @Test public void returnsFirstItemId() {
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "{\"Items\":[{\"Id\":\"8f12\"}]}".getBytes(StandardCharsets.UTF_8));
        assertEquals("8f12", JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }

    @Test public void nullOnEmptyItemsMissingBodyOrGarbage() {
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "{\"Items\":[]}".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
        responses.put("https://jf.example/Items?Recursive=true&AnyProviderIdEquals=Tmdb.550&Limit=1&api_key=k",
                "]]garbage".getBytes(StandardCharsets.UTF_8));
        assertNull(JellyfinResolve.itemId(fetcher, "https://jf.example", "k", "550"));
    }
}
