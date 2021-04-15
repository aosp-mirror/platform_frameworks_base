/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm.dex;

import static org.mockito.Mockito.inOrder;

import com.android.internal.art.ArtStatsLog;
import com.android.server.pm.dex.ArtStatsLogUtils.ArtStatsLogger;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Unit tests for {@link com.android.server.pm.dex.ArtStatsLogUtils}.
 *
 * Run with "atest ArtStatsLogUtilsTest".
 */
@RunWith(JUnit4.class)
public final class ArtStatsLogUtilsTest {
    private static final String TAG = ArtStatsLogUtilsTest.class.getSimpleName();
    private static final String COMPILER_FILTER = "space-profile";
    private static final String PROFILE_DEX_METADATA = "primary.prof";
    private static final String VDEX_DEX_METADATA = "primary.vdex";
    private static final String INSTRUCTION_SET = "arm64";
    private static final String BASE_APK = "base.apk";
    private static final String SPLIT_APK = "split.apk";
    private static final byte[] DEX_CONTENT = "dexData".getBytes();
    private static final int COMPILATION_REASON = 1;
    private static final int RESULT_CODE = 222;
    private static final int UID = 111;
    private static final long COMPILE_TIME = 333L;
    private static final long SESSION_ID = 444L;

    @Mock
    ArtStatsLogger mockLogger;

    private static Path TEST_DIR;
    private static Path DEX;
    private static Path NON_DEX;

    @BeforeClass
    public static void setUpAll() throws IOException {
        TEST_DIR = Files.createTempDirectory(null);
        DEX = Files.createFile(TEST_DIR.resolve("classes.dex"));
        NON_DEX = Files.createFile(TEST_DIR.resolve("test.dex"));
        Files.write(DEX, DEX_CONTENT);
        Files.write(NON_DEX, "empty".getBytes());
    }

    @AfterClass
    public static void tearnDownAll() {
        deleteSliently(DEX);
        deleteSliently(NON_DEX);
        deleteSliently(TEST_DIR);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testProfileAndVdexDexMetadata() throws IOException {
        // Setup
        Path dexMetadataPath = null;
        Path apk = null;
        try {
            dexMetadataPath = createDexMetadata(PROFILE_DEX_METADATA, VDEX_DEX_METADATA);
            apk = zipFiles(".apk", DEX, NON_DEX, dexMetadataPath);

            // Act
            ArtStatsLogUtils.writeStatsLog(
                    mockLogger,
                    SESSION_ID,
                    COMPILER_FILTER,
                    UID,
                    COMPILE_TIME,
                    dexMetadataPath.toString(),
                    COMPILATION_REASON,
                    RESULT_CODE,
                    ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                    INSTRUCTION_SET,
                    apk.toString());

            // Assert
            verifyWrites(ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_PROFILE_AND_VDEX);
        } finally {
            deleteSliently(dexMetadataPath);
            deleteSliently(apk);
        }
    }

    @Test
    public void testProfileOnlyDexMetadata() throws IOException {
        // Setup
        Path dexMetadataPath = null;
        Path apk = null;
        try {
            dexMetadataPath = createDexMetadata(PROFILE_DEX_METADATA);
            apk = zipFiles(".apk", DEX, NON_DEX, dexMetadataPath);

            // Act
            ArtStatsLogUtils.writeStatsLog(
                    mockLogger,
                    SESSION_ID,
                    COMPILER_FILTER,
                    UID,
                    COMPILE_TIME,
                    dexMetadataPath.toString(),
                    COMPILATION_REASON,
                    RESULT_CODE,
                    ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                    INSTRUCTION_SET,
                    apk.toString());

            // Assert
            verifyWrites(ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_PROFILE);
        } finally {
            deleteSliently(dexMetadataPath);
            deleteSliently(apk);
        }
    }

    @Test
    public void testVdexOnlyDexMetadata() throws IOException {
        // Setup
        Path dexMetadataPath = null;
        Path apk = null;
        try {
            dexMetadataPath = createDexMetadata(VDEX_DEX_METADATA);
            apk = zipFiles(".apk", DEX, NON_DEX, dexMetadataPath);

            // Act
            ArtStatsLogUtils.writeStatsLog(
                    mockLogger,
                    SESSION_ID,
                    COMPILER_FILTER,
                    UID,
                    COMPILE_TIME,
                    dexMetadataPath.toString(),
                    COMPILATION_REASON,
                    RESULT_CODE,
                    ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                    INSTRUCTION_SET,
                    apk.toString());

            // Assert
            verifyWrites(ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_VDEX);
        } finally {
            deleteSliently(dexMetadataPath);
            deleteSliently(apk);
        }
    }

    @Test
    public void testNoneDexMetadata() throws IOException {
        // Setup
        Path apk = null;
        try {
            apk = zipFiles(".apk", DEX, NON_DEX);

            // Act
            ArtStatsLogUtils.writeStatsLog(
                    mockLogger,
                    SESSION_ID,
                    COMPILER_FILTER,
                    UID,
                    COMPILE_TIME,
                    /*dexMetadataPath=*/ null,
                    COMPILATION_REASON,
                    RESULT_CODE,
                    ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                    INSTRUCTION_SET,
                    apk.toString());

            // Assert
            verifyWrites(ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_NONE);
        } finally {
            deleteSliently(apk);
        }
    }

    @Test
    public void testUnKnownDexMetadata() throws IOException {
        // Setup
        Path dexMetadataPath = null;
        Path apk = null;
        try {
            dexMetadataPath = createDexMetadata("unknown");
            apk = zipFiles(".apk", DEX, NON_DEX, dexMetadataPath);

            // Act
            ArtStatsLogUtils.writeStatsLog(
                    mockLogger,
                    SESSION_ID,
                    COMPILER_FILTER,
                    UID,
                    COMPILE_TIME,
                    dexMetadataPath.toString(),
                    COMPILATION_REASON,
                    RESULT_CODE,
                    ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                    INSTRUCTION_SET,
                    apk.toString());

            // Assert
            verifyWrites(ArtStatsLog.
                    ART_DATUM_REPORTED__DEX_METADATA_TYPE__ART_DEX_METADATA_TYPE_UNKNOWN);
        } finally {
            deleteSliently(dexMetadataPath);
            deleteSliently(apk);
        }
    }

    @Test
    public void testGetApkType() {
        // Act
        int result1 = ArtStatsLogUtils.getApkType(BASE_APK);
        int result2 = ArtStatsLogUtils.getApkType(SPLIT_APK);

        // Assert
        Assert.assertEquals(result1, ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE);
        Assert.assertEquals(result2, ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_SPLIT);
    }

    private void verifyWrites(int dexMetadataType) {
        InOrder inorder = inOrder(mockLogger);
        inorder.verify(mockLogger).write(
                SESSION_ID, UID,
                COMPILATION_REASON,
                COMPILER_FILTER,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_RESULT_CODE,
                RESULT_CODE,
                dexMetadataType,
                ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                INSTRUCTION_SET);
        inorder.verify(mockLogger).write(
                SESSION_ID,
                UID,
                COMPILATION_REASON,
                COMPILER_FILTER,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_DEX_CODE_BYTES,
                DEX_CONTENT.length,
                dexMetadataType,
                ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                INSTRUCTION_SET);
        inorder.verify(mockLogger).write(
                SESSION_ID,
                UID,
                COMPILATION_REASON,
                COMPILER_FILTER,
                ArtStatsLog.ART_DATUM_REPORTED__KIND__ART_DATUM_DEX2OAT_TOTAL_TIME,
                COMPILE_TIME,
                dexMetadataType,
                ArtStatsLog.ART_DATUM_REPORTED__APK_TYPE__ART_APK_TYPE_BASE,
                INSTRUCTION_SET);
    }

    private Path zipFiles(String suffix, Path... files) throws IOException {
        Path zipFile = Files.createTempFile(null, suffix);
        try (final OutputStream os = Files.newOutputStream(zipFile)) {
            try (final ZipOutputStream zos = new ZipOutputStream(os)) {
                for (Path file : files) {
                    ZipEntry zipEntry = new ZipEntry(file.getFileName().toString());
                    zos.putNextEntry(zipEntry);
                    zos.write(Files.readAllBytes(file));
                    zos.closeEntry();
                }
            }
        }
        return zipFile;
    }

    private Path createDexMetadata(String... entryNames) throws IOException {
        Path zipFile = Files.createTempFile(null, ".dm");
        try (final OutputStream os = Files.newOutputStream(zipFile)) {
            try (final ZipOutputStream zos = new ZipOutputStream(os)) {
                for (String entryName : entryNames) {
                    ZipEntry zipEntry = new ZipEntry(entryName);
                    zos.putNextEntry(zipEntry);
                    zos.write(entryName.getBytes());
                    zos.closeEntry();
                }
            }
        }
        return zipFile;
    }

    private static void deleteSliently(Path file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
