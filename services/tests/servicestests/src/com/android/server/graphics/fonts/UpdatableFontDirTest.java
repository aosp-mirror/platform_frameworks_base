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

package com.android.server.graphics.fonts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.fonts.FontManager;
import android.os.FileUtils;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class UpdatableFontDirTest {

    /**
     * A {@link UpdatableFontDir.FontFileParser} for testing. Instead of using real font files,
     * this test uses fake font files. A fake font file has its PostScript naem and revision as the
     * file content.
     */
    private static class FakeFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public String getPostScriptName(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return content.split(",")[0];
        }

        @Override
        public long getRevision(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return Long.parseLong(content.split(",")[1]);
        }
    }

    // FakeFsverityUtil will successfully set up fake fs-verity if the signature is GOOD_SIGNATURE.
    private static final String GOOD_SIGNATURE = "Good signature";

    /** A fake FsverityUtil to keep fake verity bit in memory. */
    private static class FakeFsverityUtil implements UpdatableFontDir.FsverityUtil {
        private final Set<String> mHasFsverityPaths = new HashSet<>();

        public void remove(String name) {
            mHasFsverityPaths.remove(name);
        }

        @Override
        public boolean hasFsverity(String path) {
            return mHasFsverityPaths.contains(path);
        }

        @Override
        public void setUpFsverity(String path, byte[] pkcs7Signature) throws IOException {
            String fakeSignature = new String(pkcs7Signature, StandardCharsets.UTF_8);
            if (GOOD_SIGNATURE.equals(fakeSignature)) {
                mHasFsverityPaths.add(path);
            } else {
                throw new IOException("Failed to set up fake fs-verity");
            }
        }

        @Override
        public boolean rename(File src, File dest) {
            if (src.renameTo(dest)) {
                mHasFsverityPaths.remove(src.getAbsolutePath());
                mHasFsverityPaths.add(dest.getAbsolutePath());
                return true;
            }
            return false;
        }
    }

    private File mCacheDir;
    private File mUpdatableFontFilesDir;
    private File mConfigFile;
    private List<File> mPreinstalledFontDirs;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mCacheDir = new File(context.getCacheDir(), "UpdatableFontDirTest");
        FileUtils.deleteContentsAndDir(mCacheDir);
        mCacheDir.mkdirs();
        mUpdatableFontFilesDir = new File(mCacheDir, "updatable_fonts");
        mUpdatableFontFilesDir.mkdir();
        mPreinstalledFontDirs = new ArrayList<>();
        mPreinstalledFontDirs.add(new File(mCacheDir, "system_fonts"));
        mPreinstalledFontDirs.add(new File(mCacheDir, "product_fonts"));
        for (File dir : mPreinstalledFontDirs) {
            dir.mkdir();
        }
        mConfigFile = new File(mCacheDir, "config.xml");
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mCacheDir);
    }

    @Test
    public void construct() throws Exception {
        long expectedModifiedDate = 1234567890;
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedDate = expectedModifiedDate;
        writeConfig(config, mConfigFile);
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedDate())
                .isEqualTo(expectedModifiedDate);
        installFontFile(dirForPreparation, "foo,1", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,2", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "foo,3", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,4", GOOD_SIGNATURE);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);
        //
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedDate())
                .isNotEqualTo(expectedModifiedDate);

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        assertThat(dir.getFontFileMap()).containsKey("foo.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("foo.ttf"))).isEqualTo(3);
        assertThat(dir.getFontFileMap()).containsKey("bar.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("bar.ttf"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(2);
    }

    @Test
    public void construct_empty() {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void construct_missingFsverity() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        installFontFile(dirForPreparation, "foo,1", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,2", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "foo,3", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,4", GOOD_SIGNATURE);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        fakeFsverityUtil.remove(
                dirForPreparation.getFontFileMap().get("foo.ttf").getAbsolutePath());
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        assertThat(dir.getFontFileMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
    }

    @Test
    public void construct_fontNameMismatch() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        installFontFile(dirForPreparation, "foo,1", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,2", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "foo,3", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,4", GOOD_SIGNATURE);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Overwrite "foo.ttf" with wrong contents.
        FileUtils.stringToFile(dirForPreparation.getFontFileMap().get("foo.ttf"), "bar,4");

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        assertThat(dir.getFontFileMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
    }

    @Test
    public void construct_olderThanPreinstalledFont() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        installFontFile(dirForPreparation, "foo,1", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,2", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "foo,3", GOOD_SIGNATURE);
        installFontFile(dirForPreparation, "bar,4", GOOD_SIGNATURE);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Add preinstalled fonts.
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "foo.ttf"), "foo,5");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "bar.ttf"), "bar,1");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(1), "bar.ttf"), "bar,2");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);
        // For foo.ttf, preinstalled font (revision 5) should be used.
        assertThat(dir.getFontFileMap()).doesNotContainKey("foo.ttf");
        // For bar.ttf, updated font (revision 4) should be used.
        assertThat(dir.getFontFileMap()).containsKey("bar.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("bar.ttf"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        // We don't delete bar.ttf in this case, because it's normal that OTA updates preinstalled
        // fonts.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(1);
    }

    @Test
    public void construct_failedToLoadConfig() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                new File("/dev/null"));
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        installFontFile(dir, "test,1", GOOD_SIGNATURE);
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("test.ttf"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_upgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        installFontFile(dir, "test,1", GOOD_SIGNATURE);
        Map<String, File> mapBeforeUpgrade = dir.getFontFileMap();
        installFontFile(dir, "test,2", GOOD_SIGNATURE);
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("test.ttf"))).isEqualTo(2);
        assertThat(mapBeforeUpgrade).containsKey("test.ttf");
        assertWithMessage("Older fonts should not be deleted until next loadFontFileMap")
                .that(parser.getRevision(mapBeforeUpgrade.get("test.ttf"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_downgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        installFontFile(dir, "test,2", GOOD_SIGNATURE);
        try {
            installFontFile(dir, "test,1", GOOD_SIGNATURE);
            fail("Expect IllegalArgumentException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.ERROR_CODE_DOWNGRADING);
        }
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertWithMessage("Font should not be downgraded to an older revision")
                .that(parser.getRevision(dir.getFontFileMap().get("test.ttf"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_multiple() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        installFontFile(dir, "foo,1", GOOD_SIGNATURE);
        installFontFile(dir, "bar,2", GOOD_SIGNATURE);
        assertThat(dir.getFontFileMap()).containsKey("foo.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("foo.ttf"))).isEqualTo(1);
        assertThat(dir.getFontFileMap()).containsKey("bar.ttf");
        assertThat(parser.getRevision(dir.getFontFileMap().get("bar.ttf"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_invalidSignature() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        try {
            installFontFile(dir, "test,1", "Invalid signature");
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.ERROR_CODE_VERIFICATION_FAILURE);
        }
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile_olderThanPreinstalledFont() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"), "test,1");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        try {
            installFontFile(dir, "test,1", GOOD_SIGNATURE);
            fail("Expect IllegalArgumentException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.ERROR_CODE_DOWNGRADING);
        }
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToWriteConfigXml() throws Exception {
        long expectedModifiedDate = 1234567890;
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"), "test,1");

        File readonlyDir = new File(mCacheDir, "readonly");
        assertThat(readonlyDir.mkdir()).isTrue();
        File readonlyFile = new File(readonlyDir, "readonly_config.xml");

        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedDate = expectedModifiedDate;
        writeConfig(config, readonlyFile);

        assertThat(readonlyDir.setWritable(false, false)).isTrue();
        try {
            UpdatableFontDir dir = new UpdatableFontDir(
                    mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                    readonlyFile);

            try {
                installFontFile(dir, "test,2", GOOD_SIGNATURE);
            } catch (FontManagerService.SystemFontException e) {
                assertThat(e.getErrorCode())
                        .isEqualTo(FontManager.ERROR_CODE_FAILED_TO_CREATE_CONFIG_FILE);
            }
            assertThat(dir.getSystemFontConfig().getLastModifiedDate())
                    .isEqualTo(expectedModifiedDate);
            assertThat(dir.getFontFileMap()).isEmpty();
        } finally {
            assertThat(readonlyDir.setWritable(true, true)).isTrue();
        }
    }

    @Test
    public void installFontFile_failedToParsePostScript() throws Exception {
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        return null;
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }
                }, fakeFsverityUtil, mConfigFile);

        try {
            installFontFile(dir, "foo,1", GOOD_SIGNATURE);
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.ERROR_CODE_MISSING_POST_SCRIPT_NAME);
        }
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToParsePostScriptName_invalidFont() throws Exception {
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }
                }, fakeFsverityUtil, mConfigFile);

        try {
            installFontFile(dir, "foo,1", GOOD_SIGNATURE);
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.ERROR_CODE_INVALID_FONT_FILE);
        }
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile_renameToPsNameFailure() throws Exception {
        UpdatableFontDir.FsverityUtil fakeFsverityUtil = new UpdatableFontDir.FsverityUtil() {
            private final FakeFsverityUtil mFake = new FakeFsverityUtil();

            @Override
            public boolean hasFsverity(String path) {
                return mFake.hasFsverity(path);
            }

            @Override
            public void setUpFsverity(String path, byte[] pkcs7Signature) throws IOException {
                mFake.setUpFsverity(path, pkcs7Signature);
            }

            @Override
            public boolean rename(File src, File dest) {
                return false;
            }
        };
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mPreinstalledFontDirs, parser, fakeFsverityUtil,
                mConfigFile);

        try {
            installFontFile(dir, "foo,1", GOOD_SIGNATURE);
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.ERROR_CODE_FAILED_TO_WRITE_FONT_FILE);
        }
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    private void installFontFile(UpdatableFontDir dir, String content, String signature)
            throws Exception {
        File file = File.createTempFile("font", "ttf", mCacheDir);
        FileUtils.stringToFile(file, content);
        try (FileInputStream in = new FileInputStream(file)) {
            dir.installFontFile(in.getFD(), signature.getBytes());
        }
    }

    private void writeConfig(PersistentSystemFontConfig.Config config,
            File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            PersistentSystemFontConfig.writeToXml(fos, config);
        }
    }
}
