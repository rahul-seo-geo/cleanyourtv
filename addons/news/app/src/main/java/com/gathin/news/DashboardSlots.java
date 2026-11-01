package com.gathin.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DashboardSlots {
    static List<DashboardSnapshot.StorySlot> reconcile(List<DashboardSnapshot.StorySlot> previous,
                                                        List<DashboardSnapshot.StorySlot> candidates,
                                                        int slotCount) {
        if (slotCount < 0) throw new IllegalArgumentException("slotCount must not be negative");
        List<DashboardSnapshot.StorySlot> slots = new ArrayList<>(Collections.nCopies(slotCount, null));
        Map<String, DashboardSnapshot.StorySlot> unique = new HashMap<>();
        List<DashboardSnapshot.StorySlot> ordered = new ArrayList<>();
        for (DashboardSnapshot.StorySlot candidate : candidates) {
            if (candidate != null && !unique.containsKey(candidate.contentIdentity)) {
                unique.put(candidate.contentIdentity, candidate);
                ordered.add(candidate);
            }
        }
        Set<String> assigned = new HashSet<>();
        for (DashboardSnapshot.StorySlot prior : previous) {
            if (prior == null || prior.slotId == null) continue;
            int index;
            try {
                if (!prior.slotId.startsWith("story-")) continue;
                index = Integer.parseInt(prior.slotId.substring("story-".length()));
            } catch (NumberFormatException ignored) {
                continue;
            }
            DashboardSnapshot.StorySlot candidate = unique.get(prior.contentIdentity);
            if (index >= 0 && index < slotCount && slots.get(index) == null
                    && candidate != null && assigned.add(prior.contentIdentity)) {
                slots.set(index, candidate.withSlotId("story-" + index));
            }
        }
        int next = 0;
        for (DashboardSnapshot.StorySlot candidate : ordered) {
            if (!assigned.add(candidate.contentIdentity)) continue;
            while (next < slotCount && slots.get(next) != null) next++;
            if (next == slotCount) break;
            slots.set(next, candidate.withSlotId("story-" + next));
        }
        return Collections.unmodifiableList(slots);
    }
}
