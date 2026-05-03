package org.eirb.eirbone.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileManagerTest {

    @TempDir
    Path tempDir;

    FileManager fm;

    @BeforeEach
    void setUp() {
        fm = new FileManager(tempDir.toString());
    }

    // --- listSharedFiles ---

    @Test
    void listSharedFilesEmpty() throws IOException {
        assertTrue(fm.listSharedFiles().isEmpty());
    }

    @Test
    void listSharedFilesReturnsOneFile() throws IOException {
        Files.writeString(tempDir.resolve("hello.txt"), "hello");
        List<FileManager.FileInfo> files = fm.listSharedFiles();
        assertEquals(1, files.size());
        assertEquals("hello.txt", files.get(0).filename);
        assertEquals(5L, files.get(0).length);
        assertNotNull(files.get(0).md5);
        assertEquals(32, files.get(0).md5.length());
        assertEquals(1024, files.get(0).pieceSize);
    }

    @Test
    void listSharedFilesIgnoresSubdirectories() throws IOException {
        Files.createDirectory(tempDir.resolve("subdir"));
        Files.writeString(tempDir.resolve("file.txt"), "data");
        List<FileManager.FileInfo> files = fm.listSharedFiles();
        assertEquals(1, files.size());
        assertEquals("file.txt", files.get(0).filename);
    }

    // --- computeMD5 ---

    @Test
    void computeMD5EmptyFile() throws IOException {
        Path f = tempDir.resolve("empty.bin");
        Files.write(f, new byte[0]);
        // known MD5 of empty input
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", FileManager.computeMD5(f.toFile()));
    }

    @Test
    void computeMD5KnownContent() throws IOException {
        Path f = tempDir.resolve("hw.txt");
        Files.writeString(f, "hello world");
        // known MD5 of "hello world"
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", FileManager.computeMD5(f.toFile()));
    }

    @Test
    void computeMD5IsDeterministic() throws IOException {
        Path f = tempDir.resolve("det.bin");
        Files.write(f, new byte[]{1, 2, 3, 4});
        String first  = FileManager.computeMD5(f.toFile());
        String second = FileManager.computeMD5(f.toFile());
        assertEquals(first, second);
    }

    // --- readPiece / writePiece ---

    @Test
    void readWritePieceRoundTrip() throws IOException {
        byte[] data = new byte[1024];
        for (int i = 0; i < 1024; i++) data[i] = (byte) (i % 256);
        fm.writePiece("test.bin", 0, data);
        assertArrayEquals(data, fm.readPiece("test.bin", 0));
    }

    @Test
    void readPieceZeroPadsLastPiece() throws IOException {
        byte[] small = {10, 20, 30, 40, 50};
        Files.write(tempDir.resolve("small.bin"), small);
        byte[] piece = fm.readPiece("small.bin", 0);
        assertEquals(1024, piece.length);
        assertEquals(10, piece[0]);
        assertEquals(50, piece[4]);
        for (int i = 5; i < 1024; i++) assertEquals(0, piece[i], "byte at " + i + " should be 0");
    }

    @Test
    void writeSecondPieceDoesNotCorruptFirst() throws IOException {
        byte[] piece0 = new byte[1024]; Arrays.fill(piece0, (byte) 'A');
        byte[] piece1 = new byte[1024]; Arrays.fill(piece1, (byte) 'B');
        fm.writePiece("multi.bin", 0, piece0);
        fm.writePiece("multi.bin", 1, piece1);
        assertArrayEquals(piece0, fm.readPiece("multi.bin", 0));
        assertArrayEquals(piece1, fm.readPiece("multi.bin", 1));
    }

    // --- truncate ---

    @Test
    void truncateShortenFile() throws IOException {
        byte[] data = new byte[1024]; Arrays.fill(data, (byte) 'X');
        fm.writePiece("trunc.bin", 0, data);
        assertEquals(1024L, tempDir.resolve("trunc.bin").toFile().length());
        fm.truncate("trunc.bin", 500);
        assertEquals(500L, tempDir.resolve("trunc.bin").toFile().length());
    }

    // --- renameAfterDownload ---

    @Test
    void renameAfterDownloadMovesFile() throws IOException {
        byte[] data = "content".getBytes();
        Files.write(tempDir.resolve("abc123md5"), data);
        fm.renameAfterDownload("abc123md5", "final.txt");
        assertFalse(Files.exists(tempDir.resolve("abc123md5")), "Temp file should be removed");
        assertTrue(Files.exists(tempDir.resolve("final.txt")), "Final file should exist");
        assertArrayEquals(data, Files.readAllBytes(tempDir.resolve("final.txt")));
    }

    // --- getFile ---

    @Test
    void getFileReturnsCorrectAbsolutePath() {
        File f = fm.getFile("myfile.txt");
        assertEquals(tempDir.resolve("myfile.txt").toString(), f.getAbsolutePath());
    }

    // --- FileInfo ---

    @Test
    void fileInfoToStringFormat() {
        FileManager.FileInfo info = new FileManager.FileInfo();
        info.filename  = "archive.zip";
        info.length    = 4096L;
        info.pieceSize = 1024;
        info.md5       = "deadbeef1234";
        assertEquals("archive.zip 4096 1024 deadbeef1234", info.toString());
    }
}
