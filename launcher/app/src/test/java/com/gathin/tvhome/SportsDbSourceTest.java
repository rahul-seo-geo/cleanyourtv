package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SportsDbSourceTest {
    private static final long NOW = 1_800_000_000_000L;

    private static byte[] json(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static String event(String id, String status, String date, String time,
                                String homeId, String awayId) {
        return "{\"idEvent\":\"" + id + "\",\"strStatus\":\"" + status + "\","
                + "\"dateEvent\":\"" + date + "\",\"strTime\":\"" + time + "\","
                + "\"strLeague\":\"Test League\","
                + "\"strHomeTeam\":\"Home FC\",\"idHomeTeam\":\"" + homeId + "\","
                + "\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"" + awayId + "\","
                + "\"strHomeTeamBadge\":\"/pic/home.png\",\"strAwayTeamBadge\":\"/pic/away.png\"}";
    }

    private static List<MediaCard> parse(String eventsBody, String teamId) {
        return SportsDbSource.parseTeam(json("{\"events\":" + eventsBody + "}"), teamId, NOW);
    }

    @Test public void buildsUrlPerTeam() {
        assertEquals("https://www.thesportsdb.com/api/v1/json/k/eventsnextteam.php?id=133602",
                SportsDbSource.eventsUrl("k", "133602"));
    }

    @Test public void followedTeamAtHomeMakesAwaySideTheOpponent() {
        List<MediaCard> cards = parse("[" + event("1", "", "2030-01-01", "15:00:00", "100", "200") + "]", "100");
        assertEquals(1, cards.size());
        MediaCard card = cards.get(0);
        assertEquals("sdb-1", card.id);
        assertEquals(MediaCard.Kind.SPORTS, card.kind);
        assertEquals("Away FC", card.title); // the side that is not the followed team
        assertEquals("https://www.thesportsdb.com/pic/away.png", card.artUrl);
    }

    @Test public void followedTeamAwayMakesHomeSideTheOpponent() {
        List<MediaCard> cards = parse("[" + event("1", "", "2030-01-01", "15:00:00", "100", "200") + "]", "200");
        assertEquals("Home FC", cards.get(0).title);
        assertEquals("https://www.thesportsdb.com/pic/home.png", cards.get(0).artUrl);
    }

    @Test public void badgeFallsBackToFollowedTeamThenEmpty() {
        String noBadges = "{\"idEvent\":\"1\",\"strStatus\":\"\",\"dateEvent\":\"2030-01-01\","
                + "\"strTime\":\"15:00:00\",\"strLeague\":\"L\","
                + "\"strHomeTeam\":\"Home FC\",\"idHomeTeam\":\"100\","
                + "\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"200\","
                + "\"strHomeTeamBadge\":\"/pic/home.png\"}";
        List<MediaCard> cards = parse("[" + noBadges + "]", "100");
        assertEquals("https://www.thesportsdb.com/pic/home.png", cards.get(0).artUrl);
        String emptyBadges = noBadges.replace("\"/pic/home.png\"", "\"\"");
        assertEquals("", parse("[" + emptyBadges + "]", "100").get(0).artUrl);
    }

    @Test public void absoluteBadgeKeepsAbsoluteAndDropsProtocolRelative() {
        assertEquals("https://www.thesportsdb.com/pic/x.png", SportsDbSource.absoluteBadge("/pic/x.png"));
        assertEquals("https://cdn.example/x.png", SportsDbSource.absoluteBadge("https://cdn.example/x.png"));
        assertEquals("", SportsDbSource.absoluteBadge("//cdn.example/x.png"));
        assertEquals("", SportsDbSource.absoluteBadge(""));
        assertEquals("", SportsDbSource.absoluteBadge(null));
    }

    @Test public void fullTimeStatusesMapToFinal() {
        for (String status : new String[] {"FT", "ft", "AET", "PEN"}) {
            List<MediaCard> cards = parse("[" + event("1", status, "2030-01-01", "15:00:00", "100", "200") + "]", "100");
            assertEquals(status, MediaCard.State.FINAL, cards.get(0).state);
        }
    }

    @Test public void kickoffMoreThanThreeHoursPastIsFinal() {
        // NOW is 2027-01-15T08:00:00Z; 02:00 is six hours past.
        List<MediaCard> cards = parse("[" + event("1", "", "2027-01-15", "02:00:00", "100", "200") + "]", "100");
        assertEquals(MediaCard.State.FINAL, cards.get(0).state);
    }

    @Test public void futureKickoffIsUpcomingAndAnchored() {
        List<MediaCard> cards = parse("[" + event("1", "", "2030-06-01", "18:30:00", "100", "200") + "]", "100");
        MediaCard card = cards.get(0);
        assertEquals(MediaCard.State.UPCOMING, card.state);
        assertEquals(1_906_569_000_000L, card.whenMs); // 2030-06-01T18:30:00Z
        assertTrue(card.line, card.line.startsWith("Test League · "));
    }

    @Test public void kickoffParseFailureOmitsTimeButKeepsLeague() {
        List<MediaCard> cards = parse("[" + event("1", "", "2030-06-01", "", "100", "200") + "]", "100");
        assertEquals(0, cards.get(0).whenMs);
        assertEquals("Test League", cards.get(0).line);
        assertEquals(MediaCard.State.UPCOMING, cards.get(0).state);
        assertTrue(SportsDbSource.heroCards(cards, NOW, 16).isEmpty());
    }

    @Test public void heroWindowIncludesBothBoundariesButNotOutside() {
        long threeHours = 3L * 60 * 60 * 1000;
        long sevenDays = 7L * 24 * 60 * 60 * 1000;
        List<MediaCard> cards = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long time = i == 0 ? NOW - threeHours - 1 : i == 1 ? NOW - threeHours
                    : i == 2 ? NOW + sevenDays : NOW + sevenDays + 1;
            cards.add(new MediaCard("sdb-" + i, MediaCard.Kind.SPORTS, "Opponent", "",
                    SportsDbSource.state("", time, NOW), time, 0, "", "", ""));
        }
        List<MediaCard> selected = SportsDbSource.heroCards(cards, NOW, 16);
        assertEquals(2, selected.size());
        assertEquals("sdb-1", selected.get(0).id);
        assertEquals("sdb-2", selected.get(1).id);
    }

    @Test public void liveGamesPinBeforeFutureKickoffsInChronologicalOrder() {
        List<MediaCard> cards = parse("["
                + event("future", "", "2027-01-16", "11:00:00", "100", "200") + ","
                + event("liveLate", "", "2027-01-15", "07:00:00", "100", "200") + ","
                + event("final", "FT", "2027-01-15", "06:00:00", "100", "200") + ","
                + event("liveEarly", "", "2027-01-15", "05:30:00", "100", "200") + "]", "100");
        List<MediaCard> selected = SportsDbSource.heroCards(cards, NOW, 16);
        assertEquals(List.of("sdb-liveEarly", "sdb-liveLate", "sdb-final", "sdb-future"),
                selected.stream().map(card -> card.id).toList());
        assertEquals(MediaCard.State.LIVE, selected.get(0).state);
        assertEquals(MediaCard.State.FINAL, selected.get(2).state);
    }

    @Test public void heroCapIsAppliedAfterChronologicalSort() {
        StringBuilder events = new StringBuilder("[");
        for (int i = 20; i >= 1; i--) {
            if (i < 20) events.append(',');
            events.append(event(String.valueOf(i), "", "2027-01-16",
                    String.format(java.util.Locale.ROOT, "%02d:00:00", i), "100", "200"));
        }
        events.append(']');
        List<MediaCard> selected = SportsDbSource.heroCards(parse(events.toString(), "100"), NOW, 16);
        assertEquals(16, selected.size());
        assertEquals("sdb-1", selected.get(0).id);
        assertEquals("sdb-16", selected.get(15).id);
    }

    private static List<MediaCard> parseLeague(String eventsBody) {
        return SportsDbSource.parseLeague(json("{\"events\":" + eventsBody + "}"), NOW);
    }

    private static String leagueEvent(String id, String title, String thumb) {
        return "{\"idEvent\":\"" + id + "\",\"strEvent\":\"" + title + "\",\"strThumb\":\"" + thumb + "\","
                + "\"strStatus\":\"NS\",\"strLeague\":\"Test League\","
                + "\"strHomeTeam\":\"Home FC\",\"strAwayTeam\":\"Away FC\","
                + "\"dateEvent\":\"2030-01-01\",\"strTime\":\"15:00:00\"}";
    }

    @Test public void buildsLeagueUrlPerLeague() {
        assertEquals("https://www.thesportsdb.com/api/v1/json/k/eventsnextleague.php?id=4328",
                SportsDbSource.eventsLeagueUrl("k", "4328"));
    }

    @Test public void leagueCardUsesFixtureTitleAndAbsoluteThumb() {
        List<MediaCard> cards = parseLeague("[" + leagueEvent("1", "Home FC vs Away FC",
                "https://cdn.example/thumb.jpg") + "]");
        assertEquals(1, cards.size());
        MediaCard card = cards.get(0);
        assertEquals("sdb-1", card.id);
        assertEquals(MediaCard.Kind.SPORTS, card.kind);
        assertEquals("Home FC vs Away FC", card.title);
        assertEquals("https://cdn.example/thumb.jpg", card.artUrl);
        assertEquals(MediaCard.State.UPCOMING, card.state);
        assertTrue(card.line, card.line.startsWith("Test League · "));
    }

    @Test public void leagueCardPrefersPosterOverThumb() {
        String ev = leagueEvent("1", "Home FC vs Away FC", "https://cdn.example/thumb.jpg")
                .replace("\"strStatus\"",
                        "\"strPoster\":\"https://cdn.example/poster.jpg\",\"strStatus\"");
        assertEquals("https://cdn.example/poster.jpg", parseLeague("[" + ev + "]").get(0).artUrl);
    }

    @Test public void leagueCardResolvesRelativeEventArt() {
        assertEquals("https://www.thesportsdb.com/pic/x.png",
                parseLeague("[" + leagueEvent("1", "Home FC vs Away FC", "/pic/x.png") + "]")
                        .get(0).artUrl);
    }

    @Test public void leagueCardFallsBackToLeagueBadge() {
        String ev = leagueEvent("1", "Home FC vs Away FC", "")
                .replace("\"strStatus\"",
                        "\"strLeagueBadge\":\"/pic/league.png\",\"strStatus\"");
        assertEquals("https://www.thesportsdb.com/pic/league.png",
                parseLeague("[" + ev + "]").get(0).artUrl);
    }

    @Test public void leagueCardDropsProtocolRelativeArt() {
        assertEquals("", parseLeague("[" + leagueEvent("1", "Home FC vs Away FC",
                "//cdn.example/x.png") + "]").get(0).artUrl);
    }

    @Test public void leagueCardFallsBackToTeamsTitleWhenStrEventBlank() {
        List<MediaCard> cards = parseLeague("[" + leagueEvent("1", "", "https://cdn.example/t.jpg") + "]");
        assertEquals("Home FC vs Away FC", cards.get(0).title);
        assertEquals("https://cdn.example/t.jpg", cards.get(0).artUrl);
    }

    @Test public void defensiveAgainstGarbageLeagueEntries() {
        assertTrue(SportsDbSource.parseLeague(null, NOW).isEmpty());
        assertTrue(SportsDbSource.parseLeague(json("]]garbage"), NOW).isEmpty());
        assertTrue(parseLeague("[null,{\"strThumb\":\"x\"},42]").isEmpty());
    }

    @Test public void defensiveAgainstGarbageEntries() {
        assertTrue(SportsDbSource.parseTeam(null, "100", NOW).isEmpty());
        assertTrue(SportsDbSource.parseTeam(json("]]garbage"), "100", NOW).isEmpty());
        assertTrue(SportsDbSource.parseTeam(json("{\"events\":[]}"), "100", NOW).isEmpty());
        List<MediaCard> cards = parse("[null,\"garbage\",{\"idEvent\":\"\",\"strHomeTeam\":\"H\"},"
                + event("9", "", "2030-01-01", "15:00:00", "", "200") + "]", "200");
        assertEquals(1, cards.size());
        assertEquals("sdb-9", cards.get(0).id);
    }

    @Test public void kickoffLabelUsesDeviceTimeZone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Australia/Melbourne"));
            long kickoff = SportsDbSource.kickoffMs("2027-01-15", "08:00:00");
            assertEquals("Test League · 15 Jan · 19:00", SportsDbSource.line("Test League", kickoff));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    @Test public void singleEventObjectWithoutArrayIsParsed() {
        List<MediaCard> cards = SportsDbSource.parseTeam(
                json("{\"idEvent\":\"7\",\"strStatus\":\"\",\"dateEvent\":\"2030-01-01\",\"strTime\":\"15:00:00\","
                        + "\"strLeague\":\"L\",\"strHomeTeam\":\"Home FC\",\"idHomeTeam\":\"100\","
                        + "\"strAwayTeam\":\"Away FC\",\"idAwayTeam\":\"200\"}"), "200", NOW);
        assertEquals(1, cards.size());
        assertEquals("Home FC", cards.get(0).title); // followed team is away, so the opponent is Home
    }
}
