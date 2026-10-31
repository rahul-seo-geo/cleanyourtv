package com.gathin.newin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void trimsOldestFirst() throws Exception {
        File oldest = temp.newFile("old"), newest = temp.newFile("new");
        Files.write(oldest.toPath(), new byte[6]);
        Files.write(newest.toPath(), new byte[6]);
        oldest.setLastModified(1000);
        newest.setLastModified(2000);
        Cache.trim(new ArrayList<>(List.of(newest, oldest)), 7);
        assertFalse(oldest.exists());
        assertTrue(newest.exists());
    }

    @Test public void formatsAge() {
        assertEquals("never updated", Ago.text(-1));
        assertEquals("updated just now", Ago.text(3));
        assertEquals("updated 2h ago", Ago.text(7200));
        assertEquals("updated 1d ago", Ago.text(86400));
    }
}
