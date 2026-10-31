package com.gathin.news;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class SlidesTest {
    private Feeds.Item item(String title, long date) {
        Feeds.Item item = new Feeds.Item(); item.title = title; item.link = "https://example.com/" + title; item.pubDateMs = date; return item;
    }

    @Test public void interleavesAndBuildsPosters() {
        Map<String, List<Feeds.Item>> teams = new HashMap<>();
        teams.put("Arsenal", List.of(item("arsenal", 4)));
        teams.put("Real Madrid", List.of(item("madrid", 3)));
        String json = "{\"movies\":[{\"title\":\"Film A\",\"releaseDate\":\"2026-12-01\",\"posterPath\":\"/a.jpg\"},{\"title\":\"Film B\",\"releaseDate\":\"2026-11-01\"}],\"tv\":[{\"name\":\"Series A\",\"firstAirDate\":\"2026-11-20\"}]}";
        Slides.setSeerr("http://seerr", "key");
        List<Slides.Slide> slides = Slides.merge(List.of(item("abc", 6), item("abc2", 2)), List.of(item("bbc", 5)), teams, json);
        assertEquals(List.of("abc", "bbc", "arsenal", "madrid", "Film A", "Series A", "abc2", "Film B"), slides.stream().map(s -> s.title).toList());
        assertEquals(Slides.Kind.UPCOMING_MOVIE, slides.get(4).kind);
        assertEquals("http://seerr/image?poster_path=/a.jpg&api_key=key&width=400", slides.get(4).posterUrl);
        assertTrue(slides.get(4).dateMs > 0);
    }

    @Test public void trimsSixAndIgnoresBadUpcoming() {
        List<Feeds.Item> abc = new ArrayList<>();
        for (int i = 0; i < 9; i++) abc.add(item("abc" + i, i));
        List<Slides.Slide> slides = Slides.merge(abc, List.of(), Map.of(), "broken");
        assertEquals(6, slides.size());
        assertEquals("abc8", slides.get(0).title);
        assertEquals("abc3", slides.get(5).title);
        assertTrue(slides.stream().allMatch(s -> s.kind == Slides.Kind.HEADLINE));
        assertEquals(6, Slides.merge(abc, List.of(), Map.of(), null).size());
    }
}
