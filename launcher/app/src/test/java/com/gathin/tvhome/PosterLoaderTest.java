package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class PosterLoaderTest {
    @Test public void sampleSizeOneWhenSourceSmallerThanTarget() {
        assertEquals(1, PosterLoader.inSampleSize(342, 513, 640, 960));
    }

    @Test public void sampleSizeHalvesUntilFit() {
        assertEquals(2, PosterLoader.inSampleSize(1368, 2052, 640, 960));
        assertEquals(4, PosterLoader.inSampleSize(2736, 4104, 640, 960));
    }

    @Test public void neverBelowOne() {
        assertEquals(1, PosterLoader.inSampleSize(0, 0, 640, 960));
        assertEquals(1, PosterLoader.inSampleSize(-1, 2000, 640, 960));
        assertEquals(1, PosterLoader.inSampleSize(2000, 2000, 0, -1));
    }

    @Test public void powerOfTwoAlways() {
        for (int width = 1; width <= 8192; width += 137) {
            int sample = PosterLoader.inSampleSize(width, width * 3 / 2, 320, 480);
            assertTrue(sample > 0 && (sample & (sample - 1)) == 0);
        }
    }
}
