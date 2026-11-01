package com.gathin.news;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DashboardSlots {
    static final class Policy {
        final int slotCount;
        final long maxAgeMs;

        Policy(int slotCount, long maxAgeMs) {
            if (slotCount < 0 || maxAgeMs < 0)
                throw new IllegalArgumentException("slotCount and maxAgeMs must not be negative");
            this.slotCount = slotCount;
            this.maxAgeMs = maxAgeMs;
        }
    }

    static List<DashboardSnapshot.StorySlot> reconcile(List<DashboardSnapshot.StorySlot> previous,
                                                        List<DashboardSnapshot.StorySlot> candidates,
                                                        int slotCount) {
        return reconcile(previous, candidates, Long.MIN_VALUE, new Policy(slotCount, Long.MAX_VALUE));
    }

    static List<DashboardSnapshot.StorySlot> reconcile(List<DashboardSnapshot.StorySlot> previous,
                                                        List<DashboardSnapshot.StorySlot> candidates,
                                                        long now, Policy policy) {
        int slotCount = policy.slotCount;
        List<DashboardSnapshot.StorySlot> slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) slots.add(DashboardSnapshot.StorySlot.vacant("story-" + i));
        Map<String, DashboardSnapshot.StorySlot> unique = new HashMap<>();
        List<DashboardSnapshot.StorySlot> ordered = new ArrayList<>();
        for (DashboardSnapshot.StorySlot candidate : candidates) {
            if (candidate != null && !candidate.isVacant()
                    && candidate.publishedMs > 0
                    && now > candidate.publishedMs
                    && now - candidate.publishedMs > policy.maxAgeMs) continue;
            if (candidate != null && !candidate.isVacant()
                    && !unique.containsKey(candidate.contentIdentity)) {
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
            if (index >= 0 && index < slotCount && slots.get(index).isVacant()
                    && candidate != null && assigned.add(prior.contentIdentity)) {
                slots.set(index, candidate.withSlotId("story-" + index));
            }
        }
        int next = 0;
        for (DashboardSnapshot.StorySlot candidate : ordered) {
            if (!assigned.add(candidate.contentIdentity)) continue;
            while (next < slotCount && !slots.get(next).isVacant()) next++;
            if (next == slotCount) break;
            slots.set(next, candidate.withSlotId("story-" + next));
        }
        return Collections.unmodifiableList(slots);
    }
}
