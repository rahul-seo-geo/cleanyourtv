package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeroRotationTest {
    @Test public void advancesWithinTheCategoryFirst() {
        int[] sizes = {3, 2, 1, 0};
        assertArrayEquals(new int[] {0, 1}, HeroRotation.advance(0, 0, sizes));
        assertArrayEquals(new int[] {0, 2}, HeroRotation.advance(0, 1, sizes));
    }

    @Test public void exhaustedCategoryHandsOverToTheNextNonEmptyAtZero() {
        int[] sizes = {3, 2, 1, 0};
        assertArrayEquals(new int[] {1, 0}, HeroRotation.advance(0, 2, sizes));
        assertArrayEquals(new int[] {2, 0}, HeroRotation.advance(1, 1, sizes));
    }

    @Test public void emptyCategoriesAreSkipped() {
        int[] sizes = {0, 0, 2, 1};
        assertArrayEquals(new int[] {2, 0}, HeroRotation.advance(0, -1, sizes));
        assertArrayEquals(new int[] {3, 0}, HeroRotation.advance(2, 1, sizes));
        assertArrayEquals(new int[] {2, 0}, HeroRotation.advance(3, 0, sizes)); // wraps past the end
    }

    @Test public void negativeIndexStartsACategoryAtZero() {
        int[] sizes = {1, 1, 1, 1};
        assertArrayEquals(new int[] {0, 0}, HeroRotation.advance(0, -1, sizes));
        assertArrayEquals(new int[] {1, 0}, HeroRotation.advance(0, 0, sizes));
    }

    @Test public void singleNonEmptyCategoryWrapsToItsFirstCard() {
        assertArrayEquals(new int[] {0, 0}, HeroRotation.advance(0, 1, new int[] {2, 0, 0, 0}));
        assertArrayEquals(new int[] {2, 0}, HeroRotation.advance(2, 0, new int[] {0, 0, 1, 0}));
    }

    @Test public void allEmptyIsNull() {
        assertNull(HeroRotation.advance(0, 0, new int[] {0, 0, 0, 0}));
        assertNull(HeroRotation.advance(0, 0, null));
        assertNull(HeroRotation.advance(0, 0, new int[] {1, 2}));
    }
}
