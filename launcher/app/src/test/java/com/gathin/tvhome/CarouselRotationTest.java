package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class CarouselRotationTest {
    @Test public void advanceWalksThePoolAndWrapsPastTheLast() {
        CarouselRotation rotation = new CarouselRotation(3);
        assertEquals(0, rotation.center());
        assertEquals(1, rotation.advance());
        assertEquals(2, rotation.advance());
        assertEquals(0, rotation.advance());
    }

    @Test public void previousWalksBackwardsAndWrapsPastTheFirst() {
        CarouselRotation rotation = new CarouselRotation(3);
        assertEquals(2, rotation.previous());
        assertEquals(1, rotation.previous());
        assertEquals(0, rotation.previous());
        assertEquals(2, rotation.previous());
    }

    @Test public void advanceAndPreviousAreEachOthersInverse() {
        CarouselRotation rotation = new CarouselRotation(5);
        rotation.advance();
        rotation.advance();
        assertEquals(1, rotation.previous());
        assertEquals(2, rotation.advance());
    }

    @Test public void singleItemPoolNeverMoves() {
        CarouselRotation rotation = new CarouselRotation(1);
        assertEquals(0, rotation.advance());
        assertEquals(0, rotation.previous());
        assertEquals(1, rotation.size());
    }

    @Test public void nonPositiveSizeBehavesAsSingleItem() {
        CarouselRotation rotation = new CarouselRotation(0);
        assertEquals(0, rotation.advance());
        assertEquals(0, rotation.previous());
    }
}
