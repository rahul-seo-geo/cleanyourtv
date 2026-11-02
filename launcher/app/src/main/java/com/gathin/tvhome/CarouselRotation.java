package com.gathin.tvhome;

/** The center index of the dashboard carousel's five-slot window over one pool. Pure. */
final class CarouselRotation {
    private final int size;
    private int center;

    CarouselRotation(int size) {
        this.size = Math.max(1, size);
    }

    int size() { return size; }

    int center() { return center; }

    /** The next center, wrapping past the last card back to the first. */
    int advance() {
        center = (center + 1) % size;
        return center;
    }

    /** The previous center, wrapping past the first card back to the last; manual dpad moves. */
    int previous() {
        center = (center + size - 1) % size;
        return center;
    }
}
