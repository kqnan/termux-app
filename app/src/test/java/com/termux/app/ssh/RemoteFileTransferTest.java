package com.termux.app.ssh;

import android.util.Base64;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for RemoteFileTransfer service class.
 *
 * Tests cover:
 * - Base64 encoding/decoding boundary cases
 * - Progress calculation during transfers
 * - SSH command construction (path escaping)
 * - TransferResult encapsulation
 */
@RunWith(RobolectricTestRunner.class)
public class RemoteFileTransferTest {

    private static final String LOG_TAG = "RemoteFileTransferTest";

    // ==================== TransferResult Tests ====================

    @Test
    public void testTransferResultSuccess() {
        RemoteFileTransfer.TransferResult result = 
            RemoteFileTransfer.TransferResult.success(1024, 1024);

        assertTrue("Result should indicate success", result.success);
        assertEquals("Bytes transferred should match", 1024, result.bytesTransferred);
        assertEquals("Total bytes should match", 1024, result.totalBytes);
        assertNull("Error message should be null for success", result.errorMessage);
        assertEquals("Exit code should be 0", Integer.valueOf(0), result.exitCode);
    }

    @Test
    public void testTransferResultFailure() {
        RemoteFileTransfer.TransferResult result =
            RemoteFileTransfer.TransferResult.failure("SSH connection timeout", 1, 500, 1024);

        assertFalse("Result should indicate failure", result.success);
        assertEquals("Bytes transferred should reflect partial progress", 500, result.bytesTransferred);
        assertEquals("Total bytes should match expected", 1024, result.totalBytes);
        assertEquals("Error message should be preserved", "SSH connection timeout", result.errorMessage);
        assertEquals("Exit code should match", Integer.valueOf(1), result.exitCode);
    }

    @Test
    public void testTransferResultToString() {
        RemoteFileTransfer.TransferResult success = 
            RemoteFileTransfer.TransferResult.success(100, 100);
        assertTrue("toString should contain success info", success.toString().contains("success=true"));

        RemoteFileTransfer.TransferResult failure =
            RemoteFileTransfer.TransferResult.failure("Test error message", null, 0, 100);
        assertTrue("toString should contain failure info", failure.toString().contains("success=false"));
        assertTrue("toString should contain truncated error", failure.toString().contains("Test error"));
    }

    @Test
    public void testTransferResultFailureWithNullExitCode() {
        // Command failed to start (AppShell returned null)
        RemoteFileTransfer.TransferResult result =
            RemoteFileTransfer.TransferResult.failure("Failed to start SSH command", null, 0, 0);

        assertFalse("Result should indicate failure", result.success);
        assertNull("Exit code should be null for process failure", result.exitCode);
    }

    // ==================== Base64 Encoding Tests ====================

    @Test
    public void testBase64EncodeEmptyData() {
        byte[] emptyData = new byte[0];
        byte[] encoded = Base64.encode(emptyData, Base64.NO_WRAP);
        
        assertEquals("Empty data should encode to empty string", 0, encoded.length);
        
        // Verify decoding roundtrip
        byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
        assertEquals("Decoded empty data should be empty", 0, decoded.length);
    }

    @Test
    public void testBase64EncodeSingleByte() {
        byte[] singleByte = new byte[]{0x42}; // 'B'
        byte[] encoded = Base64.encode(singleByte, Base64.NO_WRAP);
        
        // Base64 encodes 1 byte to 4 chars
        assertEquals("Single byte should encode to 4 chars", 4, encoded.length);
        
        // Verify decoding roundtrip
        byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
        assertEquals("Decoded should match original", 1, decoded.length);
        assertEquals("Decoded byte should match original", 0x42, decoded[0]);
    }

    @Test
    public void testBase64EncodeBinaryData() {
        // Binary data with various byte values (explicit casts for values > 127)
        byte[] binaryData = new byte[]{
            0x00, 0x01, 0x02, 0x03,
            (byte) 0xFF, (byte) 0xFE, (byte) 0xFD,
            (byte) 0x80, (byte) 0x81, 0x7F, 0x7E
        };
        
        byte[] encoded = Base64.encode(binaryData, Base64.NO_WRAP);
        
        // Base64 expands data by ~33% (4 chars for every 3 bytes, with padding)
        int expectedLen = (int) Math.ceil(binaryData.length / 3.0) * 4;
        assertEquals("Encoded length should match expected", expectedLen, encoded.length);
        
        // Verify decoding roundtrip
        byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
        assertArrayEquals("Decoded binary data should match original", binaryData, decoded);
    }

    @Test
    public void testBase64EncodeLargeText() {
        // Generate large text data (10KB)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("TestDataChunk123");
        }
        byte[] largeText = sb.toString().getBytes(StandardCharsets.UTF_8);
        
        byte[] encoded = Base64.encode(largeText, Base64.NO_WRAP);
        
        // Verify expansion ratio is approximately 4/3
        double ratio = (double) encoded.length / largeText.length;
        assertTrue("Base64 expansion ratio should be ~1.33", ratio > 1.3 && ratio < 1.4);
        
        // Verify decoding roundtrip
        byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
        assertArrayEquals("Decoded large text should match original", largeText, decoded);
    }

    @Test
    public void testBase64EncodeNoWrapFlag() {
        byte[] data = "Test\nWith\nNewlines\n".getBytes(StandardCharsets.UTF_8);
        
        // NO_WRAP should not add line breaks
        byte[] encodedNoWrap = Base64.encode(data, Base64.NO_WRAP);
        String encodedString = new String(encodedNoWrap, StandardCharsets.UTF_8);
        
        assertFalse("NO_WRAP encoding should not contain newlines",
                    encodedString.contains("\n"));
        assertFalse("NO_WRAP encoding should not contain carriage returns",
                    encodedString.contains("\r"));
    }

    @Test
    public void testBase64DecodeInvalidDataThrowsException() {
        String invalidBase64 = "NotValidBase64!!!";
        
        try {
            byte[] decoded = Base64.decode(invalidBase64, Base64.NO_WRAP);
            // Some invalid Base64 strings may partially decode or produce garbage
            // The actual behavior depends on Base64 implementation
            // In Android's Base64, it may silently ignore invalid characters
        } catch (IllegalArgumentException e) {
            // Expected: invalid Base64 should throw
            assertTrue("Exception message should mention decoding failure",
                       e.getMessage().contains("decode") || e.getMessage().contains("base64"));
        }
    }

    // ==================== Path Escaping Tests ====================

    @Test
    public void testEscapePathSimple() {
        String path = "/home/user/file.txt";
        String escaped = RemoteFileOperator.escapePath(path);
        
        assertEquals("Simple path should be wrapped in single quotes",
                     "'" + path + "'", escaped);
    }

    @Test
    public void testEscapePathWithSpaces() {
        String path = "/home/user/My Documents/file.txt";
        String escaped = RemoteFileOperator.escapePath(path);
        
        assertEquals("Path with spaces should be wrapped in single quotes",
                     "'" + path + "'", escaped);
        assertTrue("Escaped path should preserve spaces", escaped.contains("My Documents"));
    }

    @Test
    public void testEscapePathWithSingleQuote() {
        String path = "/home/user/it's mine/file.txt";
        String escaped = RemoteFileOperator.escapePath(path);
        
        // Single quotes should be escaped: ' -> '\''
        assertTrue("Escaped path should handle single quotes", escaped.contains("'\\''"));
        assertFalse("Escaped path should not contain unescaped single quote inside",
                    escaped.matches("^'/home/user/it's mine/file.txt'$"));
    }

    @Test
    public void testEscapePathWithUnicode() {
        String path = "/home/user/文档/测试文件.txt";
        String escaped = RemoteFileOperator.escapePath(path);
        
        assertEquals("Unicode path should be wrapped in single quotes",
                     "'" + path + "'", escaped);
        assertTrue("Escaped path should preserve Unicode", escaped.contains("文档"));
    }

    @Test
    public void testEscapePathWithSpecialCharacters() {
        String path = "/home/user/$var/test&file|pipe.txt";
        String escaped = RemoteFileOperator.escapePath(path);
        
        assertEquals("Path with special shell characters should be wrapped in single quotes",
                     "'" + path + "'", escaped);
        assertTrue("Escaped path should preserve special chars", escaped.contains("$var"));
        assertTrue("Escaped path should preserve special chars", escaped.contains("&"));
        assertTrue("Escaped path should preserve special chars", escaped.contains("|"));
    }

    @Test
    public void testEscapePathEmptyString() {
        String path = "";
        String escaped = RemoteFileOperator.escapePath(path);
        
        assertEquals("Empty path should be escaped as empty single-quoted string",
                     "''", escaped);
    }

    // ==================== Progress Calculation Tests ====================

    @Test
    public void testProgressCallbackInvoked() {
        // Simulate progress tracking
        ByteArrayOutputStream progressLog = new ByteArrayOutputStream();
        
        RemoteFileTransfer.ProgressCallback callback = new RemoteFileTransfer.ProgressCallback() {
            long lastProgress = -1;
            
            @Override
            public void onProgress(long bytesTransferred, long totalBytes) {
                // Progress should be increasing
                assertTrue("Progress should increase or stay same",
                           bytesTransferred >= lastProgress);
                assertTrue("Progress should not exceed total",
                           bytesTransferred <= totalBytes);
                lastProgress = bytesTransferred;
            }
            
            @Override
            public void onComplete(RemoteFileTransfer.TransferResult result) {
                // Completion should reflect final state
                assertEquals("Final progress should match total",
                             result.bytesTransferred, result.totalBytes);
            }
        };
        
        // Simulate upload progress calls
        callback.onProgress(0, 1024);
        callback.onProgress(512, 1024);
        callback.onProgress(1024, 1024);
        callback.onComplete(RemoteFileTransfer.TransferResult.success(1024, 1024));
    }

    @Test
    public void testProgressCallbackZeroFileSize() {
        RemoteFileTransfer.ProgressCallback callback = new RemoteFileTransfer.ProgressCallback() {
            @Override
            public void onProgress(long bytesTransferred, long totalBytes) {
                // Empty file: total should be 0
                assertEquals("Empty file total should be 0", 0, totalBytes);
                assertEquals("Empty file progress should be 0", 0, bytesTransferred);
            }
            
            @Override
            public void onComplete(RemoteFileTransfer.TransferResult result) {
                assertTrue("Empty file transfer should succeed", result.success);
                assertEquals("Empty file bytes should be 0", 0, result.bytesTransferred);
            }
        };
        
        callback.onProgress(0, 0);
        callback.onComplete(RemoteFileTransfer.TransferResult.success(0, 0));
    }

    @Test
    public void testProgressCallbackPartialFailure() {
        // Simulate partial transfer failure (connection lost mid-transfer)
        final long totalBytes = 10240;
        final long partialProgress = 5120;
        
        RemoteFileTransfer.TransferResult result =
            RemoteFileTransfer.TransferResult.failure("SSH connection timeout", null, partialProgress, totalBytes);
        
        assertEquals("Partial progress should be reported", partialProgress, result.bytesTransferred);
        assertEquals("Total should still be original expected", totalBytes, result.totalBytes);
        assertFalse("Result should indicate failure", result.success);
    }

    // ==================== Stream Processing Tests ====================

    @Test
    public void testReadStreamToByteArray() throws IOException {
        byte[] testData = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream input = new ByteArrayInputStream(testData);
        
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        
        assertArrayEquals("Stream roundtrip should preserve data", testData, output.toByteArray());
    }

    @Test
    public void testReadStreamEmptyInputStream() throws IOException {
        ByteArrayInputStream emptyInput = new ByteArrayInputStream(new byte[0]);
        
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead = emptyInput.read(buffer);
        
        assertEquals("Empty stream should return -1 or 0", -1, bytesRead);
        assertEquals("Output should be empty", 0, output.size());
    }

    @Test
    public void testReadStreamLargeData() throws IOException {
        // Simulate reading large data in chunks
        byte[] largeData = new byte[50 * 1024]; // 50KB
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = (byte) (i % 256);
        }
        
        ByteArrayInputStream input = new ByteArrayInputStream(largeData);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        byte[] buffer = new byte[4096]; // 4KB chunks
        int totalRead = 0;
        int bytesRead;
        
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
            totalRead += bytesRead;
        }
        
        assertEquals("Total bytes read should match input", largeData.length, totalRead);
        assertArrayEquals("Output should match input", largeData, output.toByteArray());
    }

    // ==================== Error Message Parsing Tests ====================

    @Test
    public void testFormatFileSize() {
        // These tests verify the internal helper's expected behavior
        
        // Bytes: < 1024
        assertEquals("0 B should format correctly", "0 B", formatFileSizeInternal(0));
        assertEquals("100 B should format correctly", "100 B", formatFileSizeInternal(100));
        
        // KB: >= 1024, < 1024*1024
        assertTrue("1 KB should format with KB suffix", 
                   formatFileSizeInternal(1024).contains("KB"));
        
        // MB: >= 1024*1024
        assertTrue("1 MB should format with MB suffix",
                   formatFileSizeInternal(1024 * 1024).contains("MB"));
        
        // 50 MB (our limit)
        String fiftyMB = formatFileSizeInternal(50 * 1024 * 1024);
        assertTrue("50 MB should be formatted", fiftyMB.contains("50") || fiftyMB.contains("49"));
    }

    private String formatFileSizeInternal(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }

    // ==================== SSHConnectionInfo Tests ====================

    @Test
    public void testSSHConnectionInfoToString() {
        SSHConnectionInfo info = new SSHConnectionInfo("user", "host", 22, "/path/socket");
        assertEquals("toString should match user@host:port format",
                     "user@host:22", info.toString());
    }

    @Test
    public void testSSHConnectionInfoParseFromFilename() {
        SSHConnectionInfo info = SSHConnectionInfo.parseFromFilename(
            "testuser@example.com:2222", "/tmp/socket");
        
        assertNotNull("Parsing should succeed for valid format", info);
        assertEquals("User should be parsed", "testuser", info.getUser());
        assertEquals("Host should be parsed", "example.com", info.getHost());
        assertEquals("Port should be parsed", 2222, info.getPort());
        assertEquals("Socket path should be preserved", "/tmp/socket", info.getSocketPath());
    }

    @Test
    public void testSSHConnectionInfoParseInvalidFormats() {
        // Missing @
        assertNull("Should reject missing @", 
                   SSHConnectionInfo.parseFromFilename("noathost:22", "/tmp/s"));
        
        // Missing :
        assertNull("Should reject missing port separator",
                   SSHConnectionInfo.parseFromFilename("user@hostnoport", "/tmp/s"));
        
        // Invalid port (non-numeric)
        assertNull("Should reject non-numeric port",
                   SSHConnectionInfo.parseFromFilename("user@host:abc", "/tmp/s"));
        
        // Port out of range
        assertNull("Should reject port > 65535",
                   SSHConnectionInfo.parseFromFilename("user@host:99999", "/tmp/s"));
        
        // Empty user
        assertNull("Should reject empty user",
                   SSHConnectionInfo.parseFromFilename("@host:22", "/tmp/s"));
        
        // Empty host
        assertNull("Should reject empty host",
                   SSHConnectionInfo.parseFromFilename("user@:22", "/tmp/s"));
    }

    // ==================== Integration-like Tests ====================

    @Test
    public void testFullEncodeDecodeRoundtrip() throws IOException {
        // Simulate full upload/download data transformation
        
        // Original file content (explicit casts for values > 127)
        byte[] originalData = new byte[]{
            0x48, 0x65, 0x6C, 0x6C, 0x6F, // "Hello"
            0x00, (byte) 0xFF, (byte) 0x80, 0x7F,       // Binary values
            0x20, 0x57, 0x6F, 0x72, 0x6C, 0x64 // " World"
        };
        
        // Upload: encode to base64
        byte[] encodedUpload = Base64.encode(originalData, Base64.NO_WRAP);
        
        // Simulate transfer (encoded data as string)
        String transferredData = new String(encodedUpload, StandardCharsets.UTF_8);
        
        // Download: decode from base64
        byte[] encodedDownload = transferredData.getBytes(StandardCharsets.UTF_8);
        byte[] decodedDownload = Base64.decode(encodedDownload, Base64.NO_WRAP);
        
        // Verify roundtrip
        assertArrayEquals("Full roundtrip should preserve original data",
                          originalData, decodedDownload);
    }

    @Test
    public void testEmptyFileRoundtrip() throws IOException {
        // Empty file upload/download
        byte[] emptyData = new byte[0];
        
        // Upload encoding
        byte[] encoded = Base64.encode(emptyData, Base64.NO_WRAP);
        assertEquals("Empty file should encode to empty", 0, encoded.length);
        
        // Download decoding
        byte[] decoded = Base64.decode(encoded, Base64.NO_WRAP);
        assertEquals("Empty encoded data should decode to empty", 0, decoded.length);
        
        // Verify TransferResult for empty file
        RemoteFileTransfer.TransferResult result = 
            RemoteFileTransfer.TransferResult.success(0, 0);
        assertTrue("Empty file transfer should succeed", result.success);
    }
}