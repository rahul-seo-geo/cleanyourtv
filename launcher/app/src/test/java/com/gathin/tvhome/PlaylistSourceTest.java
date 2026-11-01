package com.gathin.tvhome;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaylistSourceTest {
    private static final String PLAYLIST = "#EXTM3U\n"
            + "#EXTINF:-1 tvg-logo=\"https://cdn.example/a.png\" group-title=\"Sports\",AFL 24/7 | HD\n"
            + "https://nas.example/live/afl.m3u8\n"
            + "#EXTINF:-1 tvg-logo=\"data:image/png;base64,bad\",ABC News\n"
            + "http://nas.example/live/abc.ts\n"
            + "#EXTINF:-1 group-title=\"News\",\n"
            + "http://nas.example/live/nameless.ts\n"
            + "#EXTINF:-1 tvg-logo=\"https://cdn.example/z.png\" group-title=\"Kids\",Cartoon\n"
            + "# comment line, no URL follows this tag\n"
            + "https://nas.example/live/cartoon.m3u8\n"
            + "http://orphan-url-without-extinf.ts\n"
            + "#EXTINF:-1,Rogue\n";

    @Test public void parsesNameGroupAndUrlPairs() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals(4, cards.size());
        assertEquals("AFL 24/7 | HD", cards.get(0).title);
        assertEquals("Sports", cards.get(0).line);
        assertEquals("https://nas.example/live/afl.m3u8", cards.get(0).streamUrl);
        assertEquals("https://nas.example/live/afl.m3u8", cards.get(0).id);
        assertEquals(MediaCard.Kind.LIVE, cards.get(0).kind);
        assertEquals(MediaCard.State.AVAILABLE, cards.get(0).state);
    }

    @Test public void parsesTvgLogoAsArtUrl() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals("https://cdn.example/a.png", cards.get(0).artUrl);
        assertEquals("https://cdn.example/z.png", cards.get(3).artUrl);
    }

    @Test public void channelsWithoutLogoHaveNoArt() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals("", cards.get(1).artUrl); // Non-URL logo must not be fetched.
        assertEquals("", cards.get(2).artUrl);
    }

    @Test public void namelessChannelFallsBackToBoundedUrlName() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertEquals("nameless.ts", cards.get(2).title);
    }

    @Test public void orphanUrlsAndTrailingTagsAreDropped() {
        List<MediaCard> cards = PlaylistSource.parse(PLAYLIST, 64);
        assertTrue(cards.stream().noneMatch(c -> c.streamUrl.contains("orphan")));
        assertTrue(cards.stream().noneMatch(c -> c.title.equals("Rogue")));
    }

    @Test public void capsListAtLimit() {
        StringBuilder many = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 300; i++)
            many.append("#EXTINF:-1,Ch ").append(i).append("\nhttp://nas.example/").append(i).append(".ts\n");
        List<MediaCard> cards = PlaylistSource.parse(many.toString(), 64);
        assertEquals(64, cards.size());
        assertEquals("Ch 63", cards.get(63).title);
    }

    @Test public void boundsNameLengthAndRejectsOversizedLines() {
        String longName = "#EXTINF:-1," + "X".repeat(200) + "\nhttp://nas.example/ok.ts\n"
                + "#EXTINF:-1,Fine\nhttp://nas.example/".repeat(1) + "y".repeat(3000) + "\n";
        List<MediaCard> cards = PlaylistSource.parse(longName, 64);
        assertEquals(1, cards.size());
        assertEquals(64, cards.get(0).title.length());
        assertEquals('…', cards.get(0).title.charAt(63));
    }

    @Test public void nullAndGarbageYieldEmpty() {
        assertTrue(PlaylistSource.parse(null, 64).isEmpty());
        assertTrue(PlaylistSource.parse("random text\nno scheme here\n", 64).isEmpty());
    }
}
