package com.gathin.tvhome;

/** Hero strip rotation order over the four categories: CONTINUE, DOWNLOADS, UPCOMING, SPORTS-HERO. Pure. */
final class HeroRotation {
    static final int COUNT = 4;

    private HeroRotation() { }

    /**
     * Position after (category, index); index -1 starts a category at its first card. Empty categories
     * are skipped and an exhausted one hands over to the next non-empty category at index 0, wrapping
     * past the end. Null when no category has a card.
     */
    static int[] advance(int category, int index, int[] sizes) {
        if (sizes == null || sizes.length != COUNT) return null;
        boolean any = false;
        for (int size : sizes) any |= size > 0;
        if (!any) return null;
        for (int step = 0; step < COUNT; step++) {
            int at = (category + step) % COUNT;
            int next = step == 0 ? index + 1 : 0;
            if (sizes[at] > 0 && next < sizes[at]) return new int[] {at, next};
        }
        // Everything after the current card is exhausted: wrap to the first non-empty category.
        for (int at = 0; at < COUNT; at++) {
            if (sizes[at] > 0) return new int[] {at, 0};
        }
        return null; // unreachable: at least one category is non-empty
    }
}
