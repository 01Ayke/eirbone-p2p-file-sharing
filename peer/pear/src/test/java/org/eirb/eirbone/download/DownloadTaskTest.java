package org.eirb.eirbone.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DownloadTaskTest {

    private DownloadManager.DownloadTask task(long size) {
        return new DownloadManager.DownloadTask("id1", "file.txt", "md5abc", size, "127.0.0.1", 6881);
    }

    // --- Initial state ---

    @Test
    void initialStatusIsQueued() {
        assertEquals("queued", task(2048).status);
    }

    @Test
    void initialDownloadedPiecesIsZero() {
        assertEquals(0, task(2048).downloadedPieces);
    }

    // --- totalPieces calculation ---

    @Test
    void totalPiecesExactMultiple() {
        assertEquals(2, task(2048).totalPieces);
    }

    @Test
    void totalPiecesRoundsUp() {
        assertEquals(2, task(1025).totalPieces);
    }

    @Test
    void totalPiecesExactlyOne() {
        assertEquals(1, task(1024).totalPieces);
    }

    @Test
    void totalPiecesOneByte() {
        assertEquals(1, task(1).totalPieces);
    }

    // --- progressPercent ---

    @Test
    void progressZeroInitially() {
        assertEquals(0.0, task(2048).progressPercent(), 0.001);
    }

    @Test
    void progressHalfway() {
        DownloadManager.DownloadTask t = task(2048);
        t.downloadedPieces = 1;
        assertEquals(50.0, t.progressPercent(), 0.001);
    }

    @Test
    void progressComplete() {
        DownloadManager.DownloadTask t = task(2048);
        t.downloadedPieces = 2;
        assertEquals(100.0, t.progressPercent(), 0.001);
    }

    @Test
    void progressHundredWhenDoneAndSizeZero() {
        DownloadManager.DownloadTask t = task(0);
        t.status = "done";
        assertEquals(100.0, t.progressPercent(), 0.001);
    }

    @Test
    void progressZeroWhenQueuedAndSizeZero() {
        DownloadManager.DownloadTask t = task(0);
        assertEquals(0.0, t.progressPercent(), 0.001);
    }
}
