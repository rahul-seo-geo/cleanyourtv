package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Round-robin merge of the dashboard's source lists into one card pool. Pure. */
final class MediaMix {
    private MediaMix() { }

    /**
     * One card at a time from each non-empty source in order, cycling until every source is
     * exhausted or the total reaches cap. Card ids pass through untouched, so a pool is a
     * stable mix of its sources' stable identities.
     */
    static List<MediaCard> interleave(List<List<MediaCard>> sources, int cap) {
        List<List<MediaCard>> live = new ArrayList<>();
        for (List<MediaCard> source : sources) {
            if (!source.isEmpty()) live.add(source);
        }
        List<MediaCard> mixed = new ArrayList<>();
        if (cap <= 0 || live.isEmpty()) return mixed;
        int[] at = new int[live.size()];
        int remaining = 0;
        for (List<MediaCard> source : live) remaining += source.size();
        int i = 0;
        while (mixed.size() < cap && remaining > 0) {
            List<MediaCard> source = live.get(i);
            if (at[i] < source.size()) {
                mixed.add(source.get(at[i]++));
                remaining--;
            }
            i = (i + 1) % live.size();
        }
        return mixed;
    }
}
