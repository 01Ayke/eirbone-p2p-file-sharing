package org.eirb.eirbone.download;

import org.eirb.eirbone.config.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the private parsing helpers of DownloadManager via reflection.
 * These methods parse the tracker's "list [...]" response format.
 */
class DownloadManagerParserTest {

    DownloadManager dm;

    @BeforeEach
    void setUp() {
        // tracker and fileManager are not needed for parsing
        dm = new DownloadManager(new Config("nonexistent.ini"), null, null);
    }

    private String[] extractTokens(String response, String filename) throws Exception {
        Method m = DownloadManager.class.getDeclaredMethod("extractTokens", String.class, String.class);
        m.setAccessible(true);
        return (String[]) m.invoke(dm, response, filename);
    }

    private String parseMd5(String response, String filename) throws Exception {
        Method m = DownloadManager.class.getDeclaredMethod("parseMd5", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(dm, response, filename);
    }

    private long parseLength(String response, String filename) throws Exception {
        Method m = DownloadManager.class.getDeclaredMethod("parseLength", String.class, String.class);
        m.setAccessible(true);
        return (long) m.invoke(dm, response, filename);
    }

    // --- extractTokens ---

    @Test
    void extractTokensValidResponse() throws Exception {
        String[] t = extractTokens("list [test.txt 2048 1024 abc123def456]", "test.txt");
        assertNotNull(t);
        assertEquals("test.txt",     t[0]);
        assertEquals("2048",         t[1]);
        assertEquals("1024",         t[2]);
        assertEquals("abc123def456", t[3]);
    }

    @Test
    void extractTokensNullResponseReturnsNull() throws Exception {
        assertNull(extractTokens(null, "test.txt"));
    }

    @Test
    void extractTokensWrongFilenameReturnsNull() throws Exception {
        assertNull(extractTokens("list [other.txt 2048 1024 abc]", "test.txt"));
    }

    @Test
    void extractTokensInvalidPrefixReturnsNull() throws Exception {
        assertNull(extractTokens("nothing here", "test.txt"));
    }

    @Test
    void extractTokensEmptyListReturnsNull() throws Exception {
        assertNull(extractTokens("list []", "test.txt"));
    }

    // --- parseMd5 ---

    @Test
    void parseMd5ExtractsCorrectValue() throws Exception {
        assertEquals("deadbeefcafe1234",
            parseMd5("list [file.zip 4096 1024 deadbeefcafe1234]", "file.zip"));
    }

    @Test
    void parseMd5ReturnsNullWhenMissing() throws Exception {
        assertNull(parseMd5(null, "file.zip"));
        assertNull(parseMd5("list []", "file.zip"));
    }

    // --- parseLength ---

    @Test
    void parseLengthExtractsCorrectValue() throws Exception {
        assertEquals(4096L, parseLength("list [file.zip 4096 1024 deadbeef]", "file.zip"));
    }

    @Test
    void parseLengthReturnsZeroWhenMissing() throws Exception {
        assertEquals(0L, parseLength(null, "file.zip"));
        assertEquals(0L, parseLength("garbage", "file.zip"));
    }
}
