package com.gathin.news;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class DashboardSlotsTest {
    private static DashboardSnapshot.StorySlot story(String slotId, String identity, String title,
                                                       long publishedMs, boolean cached, boolean stale) {
        return new DashboardSnapshot.StorySlot(slotId, identity, title, "ABC News",
                "https://example.com/" + identity, publishedMs,
                DashboardSnapshot.ContentKind.HEADLINE, cached, stale);
    }

    @Test public void retainedIdentityUpdatesInItsOriginalSlot() {
        List<DashboardSnapshot.StorySlot> previous = Arrays.asList(
                story("story-0", "a", "Old", 100, true, true),
                story("story-1", "b", "B", 200, false, false));
        List<DashboardSnapshot.StorySlot> result = DashboardSlots.reconcile(previous,
                Arrays.asList(story(null, "b", "Updated B", 300, false, false),
                        story(null, "a", "Updated A", 400, false, false)), 2);

        assertEquals("a", result.get(0).contentIdentity);
        assertEquals("story-0", result.get(0).slotId);
        assertEquals("Updated A", result.get(0).title);
        assertEquals(400, result.get(0).publishedMs);
        assertEquals("b", result.get(1).contentIdentity);
        assertEquals("story-1", result.get(1).slotId);
        assertEquals("Updated B", result.get(1).title);
    }

    @Test public void removedItemIsReplacedWithoutMovingRetainedItems() {
        List<DashboardSnapshot.StorySlot> previous = Arrays.asList(
                story("story-0", "a", "A", 100, false, false),
                story("story-1", "b", "B", 200, false, false),
                story("story-2", "c", "C", 300, false, false));
        List<DashboardSnapshot.StorySlot> result = DashboardSlots.reconcile(previous,
                Arrays.asList(story(null, "c", "C", 300, false, false),
                        story(null, "d", "D", 400, false, false),
                        story(null, "a", "A", 100, false, false)), 3);

        assertEquals("a", result.get(0).contentIdentity);
        assertEquals("d", result.get(1).contentIdentity);
        assertEquals("c", result.get(2).contentIdentity);
        assertEquals("story-1", result.get(1).slotId);
    }

    @Test public void duplicateCandidatesDoNotConsumeSlotsOrExceedLimit() {
        List<DashboardSnapshot.StorySlot> result = DashboardSlots.reconcile(Collections.emptyList(),
                Arrays.asList(story(null, "a", "First", 100, false, false),
                        story(null, "a", "Duplicate", 200, false, false),
                        story(null, "b", "Second", 300, false, false),
                        story(null, "c", "Third", 400, false, false)), 2);

        assertEquals(2, result.size());
        assertEquals("story-0", result.get(0).slotId);
        assertEquals("First", result.get(0).title);
        assertEquals("story-1", result.get(1).slotId);
        assertEquals("b", result.get(1).contentIdentity);
    }

    @Test public void cachedSourceMetadataComesFromCurrentCandidate() {
        List<DashboardSnapshot.StorySlot> result = DashboardSlots.reconcile(
                Collections.singletonList(story("story-0", "a", "Old", 100, false, false)),
                Collections.singletonList(story(null, "a", "Cached", 123, true, true)), 1);

        assertEquals("story-0", result.get(0).slotId);
        assertEquals("Cached", result.get(0).title);
        assertEquals(123, result.get(0).publishedMs);
        assertTrue(result.get(0).cached);
        assertTrue(result.get(0).stale);
        assertEquals("ABC News", result.get(0).source);
    }

    @Test public void fewerCandidatesLeaveUnfilledSlotsWithoutShifting() {
        List<DashboardSnapshot.StorySlot> result = DashboardSlots.reconcile(
                Arrays.asList(story("story-0", "a", "A", 1, false, false),
                        story("story-1", "b", "B", 2, false, false)),
                Collections.singletonList(story(null, "b", "B", 2, false, false)), 2);

        assertEquals(2, result.size());
        assertNull(result.get(0));
        assertEquals("story-1", result.get(1).slotId);
        try {
            result.add(story(null, "c", "C", 3, false, false));
            fail("slot list should be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void snapshotOwnsImmutableCopiesOfAllCollections() {
        List<DashboardSnapshot.StorySlot> stories = new ArrayList<>();
        stories.add(story("story-0", "a", "A", 100, true, true));
        List<DashboardSnapshot.MatchSlot> matches = new ArrayList<>();
        matches.add(new DashboardSnapshot.MatchSlot("fixture-1", "Arsenal vs Chelsea",
                DashboardSnapshot.MatchPhase.FINAL, "2–1", 100, 200, 0xff112233,
                DashboardSnapshot.Freshness.STALE));
        Map<String, DashboardSnapshot.SourceStatus> sources = new LinkedHashMap<>();
        sources.put("ABC News", new DashboardSnapshot.SourceStatus(
                DashboardSnapshot.Freshness.STALE, 123, true));
        DashboardSnapshot snapshot = new DashboardSnapshot(stories, matches, sources);
        stories.clear();
        matches.clear();
        sources.clear();

        assertEquals(1, snapshot.stories.size());
        assertEquals(1, snapshot.matches.size());
        assertEquals(123, snapshot.sources.get("ABC News").updatedMs);
        assertTrue(snapshot.sources.get("ABC News").cached);
        try {
            snapshot.sources.clear();
            fail("source statuses should be immutable");
        } catch (UnsupportedOperationException expected) { }
    }
}
