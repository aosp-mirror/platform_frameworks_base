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
import android.graphics.FontListParser;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.system.Os;
import android.text.FontConfig;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.text.flags.Flags;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class UpdatableFontDirTest {

    private static final String LEGACY_FONTS_XML = "/system/etc/fonts.xml";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    /**
     * A {@link UpdatableFontDir.FontFileParser} for testing. Instead of using real font files,
     * this test uses fake font files. A fake font file has its PostScript naem and revision as the
     * file content.
     */
    private static class FakeFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public String getPostScriptName(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return content.split(",")[2];
        }

        @Override
        public String buildFontFileName(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return content.split(",")[0];
        }

        @Override
        public long getRevision(File file) throws IOException {
            String content = FileUtils.readTextFile(file, 100, "");
            return Long.parseLong(content.split(",")[1]);
        }

        @Override
        public void tryToCreateTypeface(File file) throws Throwable {
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
        public boolean isFromTrustedProvider(String path, byte[] signature) {
            if (!mHasFsverityPaths.contains(path)) {
                return false;
            }
            String fakeSignature = new String(signature, StandardCharsets.UTF_8);
            return GOOD_SIGNATURE.equals(fakeSignature);
        }

        @Override
        public void setUpFsverity(String path) throws IOException {
            mHasFsverityPaths.add(path);
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

    private static final long CURRENT_TIME = 1234567890L;

    private File mCacheDir;
    private File mUpdatableFontFilesDir;
    private File mConfigFile;
    private List<File> mPreinstalledFontDirs;
    private final Supplier<Long> mCurrentTimeSupplier = () -> CURRENT_TIME;
    private final Function<Map<String, File>, FontConfig> mConfigSupplier =
            (map) -> SystemFonts.getSystemFontConfigForTesting(LEGACY_FONTS_XML, map, 0, 0);
    private FakeFontFileParser mParser;
    private FakeFsverityUtil mFakeFsverityUtil;

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
        mParser = new FakeFontFileParser();
        mFakeFsverityUtil = new FakeFsverityUtil();
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mCacheDir);
    }

    @Test
    public void construct() throws Exception {
        long expectedModifiedDate = CURRENT_TIME / 2;
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedMillis = expectedModifiedDate;
        writeConfig(config, mConfigFile);
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isEqualTo(expectedModifiedDate);
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Verifies that getLastModifiedTimeMillis() returns the value of currentTimeMillis.
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isEqualTo(CURRENT_TIME);
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);
        assertThat(dirForPreparation.getSystemFontConfig().getLastModifiedTimeMillis())
                .isNotEqualTo(expectedModifiedDate);

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(3);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(2);
        assertNamedFamilyExists(dir.getSystemFontConfig(), "foobar");
        assertThat(dir.getFontFamilyMap()).containsKey("foobar");
        assertThat(dir.getFontFamilyMap().get("foobar").getFamilies().size()).isEqualTo(1);
        FontConfig.FontFamily foobar = dir.getFontFamilyMap().get("foobar").getFamilies().get(0);
        assertThat(foobar.getFontList()).hasSize(2);
        assertThat(foobar.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("foo"));
        assertThat(foobar.getFontList().get(1).getFile())
                .isEqualTo(dir.getPostScriptMap().get("bar"));
    }

    @Test
    public void construct_empty() {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_missingFsverity() throws Exception {
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        mFakeFsverityUtil.remove(
                dirForPreparation.getPostScriptMap().get("foo").getAbsolutePath());
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_fontNameMismatch() throws Exception {
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Overwrite "foo.ttf" with wrong contents.
        FileUtils.stringToFile(dirForPreparation.getPostScriptMap().get("foo"), "bar,4");

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        // All font dirs (including dir for "bar.ttf") should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_missingSignatureFile() throws Exception {
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE)));
        assertThat(mUpdatableFontFilesDir.list()).hasLength(1);

        // Remove signature file next to the font file.
        File fontDir = dirForPreparation.getPostScriptMap().get("foo");
        File sigFile = new File(fontDir.getParentFile(), "font.fsv_sig");
        assertThat(sigFile.exists()).isTrue();
        sigFile.delete();

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        // The font file should be removed and should not be loaded.
        assertThat(dir.getPostScriptMap()).isEmpty();
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_olderThanPreinstalledFont() throws Exception {
        Function<Map<String, File>, FontConfig> configSupplier = (map) -> {
            FontConfig.Font fooFont = new FontConfig.Font(
                    new File(mPreinstalledFontDirs.get(0), "foo.ttf"), null, "foo",
                    new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null, null,
                    FontConfig.Font.VAR_TYPE_AXES_NONE);
            FontConfig.Font barFont = new FontConfig.Font(
                    new File(mPreinstalledFontDirs.get(1), "bar.ttf"), null, "bar",
                    new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null, null,
                    FontConfig.Font.VAR_TYPE_AXES_NONE);

            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Arrays.asList(fooFont, barFont), null,
                    FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.singletonList(new FontConfig.NamedFamilyList(
                            Collections.singletonList(family), "sans-serif")),
                    Collections.emptyList(), 0, 1);
        };

        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, configSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE),
                newFontUpdateRequest("foo.ttf,3,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,4,bar", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "  <font>bar.ttf</font>"
                        + "</family>")));
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        // Add preinstalled fonts.
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "foo.ttf"), "foo,5,foo");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(1), "bar.ttf"), "bar,1,bar");
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(1), "bar.ttf"), "bar,2,bar");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, configSupplier);
        dir.loadFontFileMap();
        // For foo.ttf, preinstalled font (revision 5) should be used.
        assertThat(dir.getPostScriptMap()).doesNotContainKey("foo");
        // For bar.ttf, updated font (revision 4) should be used.
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        // We don't delete bar.ttf in this case, because it's normal that OTA updates preinstalled
        // fonts.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(1);
        // Font family depending on obsoleted font should be removed.
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_failedToLoadConfig() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                new File("/dev/null"), mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap()).isEmpty();
        assertThat(dir.getFontFamilyMap()).isEmpty();
    }

    @Test
    public void construct_afterBatchFailure() throws Exception {
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='foobar'>"
                        + "  <font>foo.ttf</font>"
                        + "</family>")));
        try {
            dirForPreparation.update(Arrays.asList(
                    newFontUpdateRequest("foo.ttf,2,foo", GOOD_SIGNATURE),
                    newFontUpdateRequest("bar.ttf,2,bar", "Invalid signature"),
                    newAddFontFamilyRequest("<family name='foobar'>"
                            + "  <font>foo.ttf</font>"
                            + "  <font>bar.ttf</font>"
                            + "</family>")));
            fail("Batch update with invalid signature should fail");
        } catch (FontManagerService.SystemFontException e) {
            // Expected
        }

        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        // The state should be rolled back as a whole if one of the update requests fail.
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getFontFamilyMap()).containsKey("foobar");
        assertThat(dir.getFontFamilyMap().get("foobar").getFamilies().size()).isEqualTo(1);
        FontConfig.FontFamily foobar = dir.getFontFamilyMap().get("foobar").getFamilies().get(0);
        assertThat(foobar.getFontList()).hasSize(1);
        assertThat(foobar.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("foo"));
    }

    @Test
    public void loadFontFileMap_twice() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        File fontFile = dir.getPostScriptMap().get("test");
        dir.loadFontFileMap();
        assertThat(dir.getPostScriptMap().get("test")).isEqualTo(fontFile);
    }

    @Test
    public void installFontFile() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
        File fontFile = dir.getPostScriptMap().get("test");
        assertThat(Os.stat(fontFile.getAbsolutePath()).st_mode & 0777).isEqualTo(0644);
        File fontDir = fontFile.getParentFile();
        assertThat(Os.stat(fontDir.getAbsolutePath()).st_mode & 0777).isEqualTo(0711);
    }

    @Test
    public void installFontFile_upgrade() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        Map<String, File> mapBeforeUpgrade = dir.getPostScriptMap();
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
        assertThat(mapBeforeUpgrade).containsKey("test");
        assertWithMessage("Older fonts should not be deleted until next loadFontFileMap")
                .that(mParser.getRevision(mapBeforeUpgrade.get("test"))).isEqualTo(1);
        // Check that updatedFontDirs is pruned.
        assertWithMessage("config.updatedFontDirs should only list latest active dirs")
                .that(readConfig(mConfigFile).updatedFontDirs)
                .containsExactly(dir.getPostScriptMap().get("test").getParentFile().getName());
    }

    @Test
    public void installFontFile_systemFontHasPSNameDifferentFromFileName() throws Exception {

        // Setup the environment that the system installed font file named "foo.ttf" has PostScript
        // name "bar".
        File file = new File(mPreinstalledFontDirs.get(0), "foo.ttf");
        FileUtils.stringToFile(file, "foo.ttf,1,bar");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, (map) -> {
            FontConfig.Font font = new FontConfig.Font(
                    file, null, "bar", new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT),
                    0, null, null, FontConfig.Font.VAR_TYPE_AXES_NONE);
            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Collections.singletonList(font), null, FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.singletonList(new FontConfig.NamedFamilyList(
                            Collections.singletonList(family), "sans-serif")),
                    Collections.emptyList(), 0, 1);
        });
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("bar.ttf,2,bar",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(dir.getPostScriptMap().size()).isEqualTo(1);
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
        File fontFile = dir.getPostScriptMap().get("bar");
        assertThat(Os.stat(fontFile.getAbsolutePath()).st_mode & 0777).isEqualTo(0644);
        File fontDir = fontFile.getParentFile();
        assertThat(Os.stat(fontDir.getAbsolutePath()).st_mode & 0777).isEqualTo(0711);
    }

    @Test
    public void installFontFile_sameVersion() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_downgrade() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
        }
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertWithMessage("Font should not be downgraded to an older revision")
                .that(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
        // Check that updatedFontDirs is not updated.
        assertWithMessage("config.updatedFontDirs should only list latest active dirs")
                .that(readConfig(mConfigFile).updatedFontDirs)
                .containsExactly(dir.getPostScriptMap().get("test").getParentFile().getName());
    }

    @Test
    public void installFontFile_multiple() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                GOOD_SIGNATURE)));
        dir.update(Collections.singletonList(newFontUpdateRequest("bar.ttf,2,bar",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_batch() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Arrays.asList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE),
                newFontUpdateRequest("bar.ttf,2,bar", GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
        assertThat(dir.getPostScriptMap()).containsKey("bar");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_invalidSignature() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(
                    Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                            "Invalid signature")));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_VERIFICATION_FAILURE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_preinstalled_upgrade() throws Exception {
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_preinstalled_sameVersion() throws Exception {
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                GOOD_SIGNATURE)));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("test"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_preinstalled_downgrade() throws Exception {
        File file = new File(mPreinstalledFontDirs.get(0), "test.ttf");
        FileUtils.stringToFile(file, "test.ttf,2,test");
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, (map) -> {
            FontConfig.Font font = new FontConfig.Font(
                    file, null, "test", new FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT), 0, null,
                    null, FontConfig.Font.VAR_TYPE_AXES_NONE);
            FontConfig.FontFamily family = new FontConfig.FontFamily(
                    Collections.singletonList(font), null, FontConfig.FontFamily.VARIANT_DEFAULT);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonList(new FontConfig.NamedFamilyList(
                            Collections.singletonList(family), "sans-serif")),
                    Collections.emptyList(), 0, 1);
        });
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("test.ttf,1,test",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode()).isEqualTo(FontManager.RESULT_ERROR_DOWNGRADING);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToWriteConfigXml() throws Exception {
        long expectedModifiedDate = 1234567890;
        FileUtils.stringToFile(new File(mPreinstalledFontDirs.get(0), "test.ttf"),
                "test.ttf,1,test");

        File readonlyDir = new File(mCacheDir, "readonly");
        assertThat(readonlyDir.mkdir()).isTrue();
        File readonlyFile = new File(readonlyDir, "readonly_config.xml");

        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedMillis = expectedModifiedDate;
        writeConfig(config, readonlyFile);

        assertThat(readonlyDir.setWritable(false, false)).isTrue();
        try {
            UpdatableFontDir dir = new UpdatableFontDir(
                    mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                    readonlyFile, mCurrentTimeSupplier, mConfigSupplier);
            dir.loadFontFileMap();

            try {
                dir.update(
                        Collections.singletonList(newFontUpdateRequest("test.ttf,2,test",
                                GOOD_SIGNATURE)));
            } catch (FontManagerService.SystemFontException e) {
                assertThat(e.getErrorCode())
                        .isEqualTo(FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG);
            }
            assertThat(dir.getSystemFontConfig().getLastModifiedTimeMillis())
                    .isEqualTo(expectedModifiedDate);
            assertThat(dir.getPostScriptMap()).isEmpty();
        } finally {
            assertThat(readonlyDir.setWritable(true, true)).isTrue();
        }
    }

    @Test
    public void installFontFile_failedToParsePostScript() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {

                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        return null;
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        return null;
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                    }
                }, mFakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_NAME);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToParsePostScriptName_invalidFont() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        throw new IOException();
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return 0;
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                    }
                }, mFakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_failedToCreateTypeface() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir,
                new UpdatableFontDir.FontFileParser() {
                    @Override
                    public String getPostScriptName(File file) throws IOException {
                        return mParser.getPostScriptName(file);
                    }

                    @Override
                    public String buildFontFileName(File file) throws IOException {
                        return mParser.buildFontFileName(file);
                    }

                    @Override
                    public long getRevision(File file) throws IOException {
                        return mParser.getRevision(file);
                    }

                    @Override
                    public void tryToCreateTypeface(File file) throws IOException {
                        throw new IOException();
                    }
                }, mFakeFsverityUtil, mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_INVALID_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_renameToPsNameFailure() throws Exception {
        UpdatableFontDir.FsverityUtil fakeFsverityUtil = new UpdatableFontDir.FsverityUtil() {

            @Override
            public boolean isFromTrustedProvider(String path, byte[] signature) {
                return mFakeFsverityUtil.isFromTrustedProvider(path, signature);
            }

            @Override
            public void setUpFsverity(String path) throws IOException {
                mFakeFsverityUtil.setUpFsverity(path);
            }

            @Override
            public boolean rename(File src, File dest) {
                return false;
            }
        };
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                    GOOD_SIGNATURE)));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE);
        }
        assertThat(dir.getPostScriptMap()).isEmpty();
    }

    @Test
    public void installFontFile_batchFailure() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Collections.singletonList(newFontUpdateRequest("foo.ttf,1,foo",
                GOOD_SIGNATURE)));
        try {
            dir.update(Arrays.asList(
                    newFontUpdateRequest("foo.ttf,2,foo", GOOD_SIGNATURE),
                    newFontUpdateRequest("bar.ttf,2,bar", "Invalid signature")));
            fail("Batch update with invalid signature should fail");
        } catch (FontManagerService.SystemFontException e) {
            // Expected
        }
        // The state should be rolled back as a whole if one of the update requests fail.
        assertThat(dir.getPostScriptMap()).containsKey("foo");
        assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(1);
    }

    @Test
    public void addFontFamily() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='test'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        assertThat(dir.getPostScriptMap()).containsKey("test");
        assertThat(dir.getFontFamilyMap()).containsKey("test");
        assertThat(dir.getFontFamilyMap().get("test").getFamilies().size()).isEqualTo(1);
        FontConfig.FontFamily test = dir.getFontFamilyMap().get("test").getFamilies().get(0);
        assertThat(test.getFontList()).hasSize(1);
        assertThat(test.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void addFontFamily_noName() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        List<FontUpdateRequest> requests = Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family lang='en'>"
                        + "  <font>test.ttf</font>"
                        + "</family>"));
        dir.update(requests);
    }

    @Test
    public void addFontFamily_fontNotAvailable() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();

        try {
            dir.update(Arrays.asList(newAddFontFamilyRequest("<family name='test'>"
                    + "  <font>test.ttf</font>"
                    + "</family>")));
            fail("Expect SystemFontException");
        } catch (FontManagerService.SystemFontException e) {
            assertThat(e.getErrorCode())
                    .isEqualTo(FontManager.RESULT_ERROR_FONT_NOT_FOUND);
        }
    }

    @Test
    public void getSystemFontConfig() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        // We assume we have monospace.
        assertNamedFamilyExists(dir.getSystemFontConfig(), "monospace");

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                // Updating an existing font family.
                newAddFontFamilyRequest("<family name='monospace'>"
                        + "  <font>test.ttf</font>"
                        + "</family>"),
                // Adding a new font family.
                newAddFontFamilyRequest("<family name='test'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        FontConfig fontConfig = dir.getSystemFontConfig();
        assertNamedFamilyExists(fontConfig, "monospace");
        FontConfig.FontFamily monospace = getLastFamily(fontConfig, "monospace");
        assertThat(monospace.getFontList()).hasSize(1);
        assertThat(monospace.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
        assertNamedFamilyExists(fontConfig, "test");
        assertThat(getLastFamily(fontConfig, "test").getFontList())
                .isEqualTo(monospace.getFontList());
    }

    @Test
    public void getSystemFontConfig_preserveFirstFontFamily() throws Exception {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        assertThat(dir.getSystemFontConfig().getFontFamilies()).isNotEmpty();
        FontConfig.FontFamily firstFontFamily = dir.getSystemFontConfig().getFontFamilies().get(0);

        dir.update(Arrays.asList(
                newFontUpdateRequest("test.ttf,1,test", GOOD_SIGNATURE),
                newAddFontFamilyRequest("<family name='sans-serif'>"
                        + "  <font>test.ttf</font>"
                        + "</family>")));
        FontConfig fontConfig = dir.getSystemFontConfig();
        assertThat(dir.getSystemFontConfig().getFontFamilies()).isNotEmpty();
        assertThat(fontConfig.getFontFamilies().get(0)).isEqualTo(firstFontFamily);
        FontConfig.FontFamily updated = getLastFamily(fontConfig, "sans-serif");
        assertThat(updated.getFontList()).hasSize(1);
        assertThat(updated.getFontList().get(0).getFile())
                .isEqualTo(dir.getPostScriptMap().get("test"));
        assertThat(updated).isNotEqualTo(firstFontFamily);
    }

    @Test
    public void deleteAllFiles() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        FakeFsverityUtil fakeFsverityUtil = new FakeFsverityUtil();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(
                mUpdatableFontFilesDir, parser, fakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dirForPreparation.loadFontFileMap();
        dirForPreparation.update(Collections.singletonList(
                newFontUpdateRequest("foo.ttf,1,foo", GOOD_SIGNATURE)));
        assertThat(mConfigFile.exists()).isTrue();
        assertThat(mUpdatableFontFilesDir.list()).hasLength(1);

        UpdatableFontDir.deleteAllFiles(mUpdatableFontFilesDir, mConfigFile);
        assertThat(mConfigFile.exists()).isFalse();
        assertThat(mUpdatableFontFilesDir.list()).hasLength(0);
    }

    private UpdatableFontDir createNewUpdateDir() {
        UpdatableFontDir dir = new UpdatableFontDir(
                mUpdatableFontFilesDir, mParser, mFakeFsverityUtil,
                mConfigFile, mCurrentTimeSupplier, mConfigSupplier);
        dir.loadFontFileMap();
        return dir;
    }

    private UpdatableFontDir installTestFontFamilies(int version) {
        UpdatableFontDir dir = createNewUpdateDir();
        try {
            dir.update(Arrays.asList(
                    newFontUpdateRequest("foo.ttf," + version + ",foo", GOOD_SIGNATURE),
                    newFontUpdateRequest("bar.ttf," + version + ",bar", GOOD_SIGNATURE),
                    newAddFontFamilyRequest("<family name='foobar'>"
                            + "  <font>foo.ttf</font>"
                            + "  <font>bar.ttf</font>"
                            + "</family>")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private UpdatableFontDir installTestFontFile(int numFonts, int version) {
        UpdatableFontDir dir = createNewUpdateDir();
        List<FontUpdateRequest> requests = new ArrayList<>();
        if (numFonts <= 0 || numFonts > 3) {
            throw new IllegalArgumentException("numFont must be 1, 2 or 3");
        }
        try {
            requests.add(newFontUpdateRequest("foo.ttf," + version + ",foo", GOOD_SIGNATURE));
            if (numFonts >= 2) {
                requests.add(newFontUpdateRequest("bar.ttf," + version + ",bar", GOOD_SIGNATURE));
            }
            if (numFonts == 3) {
                requests.add(newFontUpdateRequest("baz.ttf," + version + ",baz", GOOD_SIGNATURE));
            }
            dir.update(requests);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return dir;
    }

    private List<File> collectSignatureFiles() {
        return Arrays.stream(mUpdatableFontFilesDir.listFiles())
                .map((file) -> file.listFiles((unused, s) -> s.endsWith(".fsv_sig")))
                .flatMap(Arrays::stream)
                .toList();
    }

    private List<File> collectFontFiles() {
        return Arrays.stream(mUpdatableFontFilesDir.listFiles())
                .map((file) -> file.listFiles((unused, s) -> s.endsWith(".ttf")))
                .flatMap(Arrays::stream)
                .toList();
    }

    private void removeAll(List<File> files) {
        files.forEach((File file) -> {
            if (file.isDirectory()) {
                removeAll(List.of(file.listFiles()));
            } else {
                assertThat(file.delete()).isTrue();
            }
        });
    }

    private void assertTestFontFamilyInstalled(UpdatableFontDir dir, int version) {
        try {
            assertNamedFamilyExists(dir.getSystemFontConfig(), "foobar");
            assertThat(dir.getFontFamilyMap()).containsKey("foobar");
            assertThat(dir.getFontFamilyMap().get("foobar").getFamilies().size()).isEqualTo(1);
            FontConfig.FontFamily foobar = dir.getFontFamilyMap().get("foobar").getFamilies()
                    .get(0);
            assertThat(foobar.getFontList()).hasSize(2);
            assertThat(foobar.getFontList().get(0).getFile())
                    .isEqualTo(dir.getPostScriptMap().get("foo"));
            assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(version);
            assertThat(foobar.getFontList().get(1).getFile())
                    .isEqualTo(dir.getPostScriptMap().get("bar"));
            assertThat(mParser.getRevision(dir.getPostScriptMap().get("bar"))).isEqualTo(version);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertTestFontInstalled(UpdatableFontDir dir, int version) {
        try {
            assertThat(dir.getPostScriptMap().containsKey("foo")).isTrue();
            assertThat(mParser.getRevision(dir.getPostScriptMap().get("foo"))).isEqualTo(version);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete one signature file
        assertThat(collectSignatureFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1);

        // Delete one signature file
        assertThat(collectSignatureFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf and bar.ttf
        installTestFontFile(2 /* numFonts */, 1 /* version */);

        // Delete one signature file
        assertThat(collectSignatureFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf and bar.ttf
        installTestFontFile(2 /* numFonts */, 1 /* version */);

        // Delete one signature file
        assertThat(collectSignatureFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(2 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureAllMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all signature files
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureAllMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all signature files
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureAllMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all signature files
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void signatureAllMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all signature files
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete one font file
        assertThat(collectFontFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1);

        // Delete one font file
        assertThat(collectFontFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf and bar.ttf
        installTestFontFile(2 /* numFonts */, 1 /* version */);

        // Delete one font file
        assertThat(collectFontFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf and bar.ttf
        installTestFontFile(2 /* numFonts */, 1 /* version */);

        // Delete one font file
        assertThat(collectFontFiles().get(0).delete()).isTrue();

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(2 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontAllMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontAllMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontAllMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontAllMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontDirAllMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(List.of(mUpdatableFontFilesDir.listFiles()));

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontDirAllMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(List.of(mUpdatableFontFilesDir.listFiles()));

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontDirAllMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(List.of(mUpdatableFontFilesDir.listFiles()));

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void fontDirAllMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(List.of(mUpdatableFontFilesDir.listFiles()));

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void dirContentAllMissingCase_fontFamilyInstalled_fontFamilyInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void dirContentAllMissingCase_fontFamilyInstalled_fontInstallLater() {
        // Install font families, foo.ttf, bar.ttf.
        installTestFontFamilies(1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void dirContentAllMissingCase_fontFileInstalled_fontFamilyInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFamilies(2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontFamilyInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontFamilyInstalled(nextDir, 2 /* version */);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_FIX_FONT_UPDATE_FAILURE)
    public void dirContentAllMissingCase_fontFileInstalled_fontFileInstallLater() {
        // Install font file, foo.ttf
        installTestFontFile(1 /* numFonts */, 1 /* version */);

        // Delete all font files
        removeAll(collectFontFiles());
        removeAll(collectSignatureFiles());

        // New instance of UpdatableFontDir, this emulate a device reboot.
        UpdatableFontDir dir = installTestFontFile(1 /* numFonts */, 2 /* version */);

        // Make sure the font installation succeeds.
        assertTestFontInstalled(dir, 2 /* version */);

        // Make sure after the reboot, the configuration remains.
        UpdatableFontDir nextDir = createNewUpdateDir();
        assertTestFontInstalled(nextDir, 2 /* version */);
    }

    private FontUpdateRequest newFontUpdateRequest(String content, String signature)
            throws Exception {
        File file = File.createTempFile("font", "ttf", mCacheDir);
        FileUtils.stringToFile(file, content);
        return new FontUpdateRequest(
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
                signature.getBytes());
    }

    private static FontUpdateRequest newAddFontFamilyRequest(String xml) throws Exception {
        XmlPullParser mParser = Xml.newPullParser();
        ByteArrayInputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        mParser.setInput(is, "UTF-8");
        mParser.nextTag();

        FontConfig.NamedFamilyList namedFamilyList = FontListParser.readNamedFamily(
                mParser, "", null, true);
        FontConfig.FontFamily fontFamily = namedFamilyList.getFamilies().get(0);
        List<FontUpdateRequest.Font> fonts = new ArrayList<>();
        for (FontConfig.Font font : fontFamily.getFontList()) {
            String name = font.getFile().getName();
            String psName = name.substring(0, name.length() - 4);  // drop suffix
            FontUpdateRequest.Font updateFont = new FontUpdateRequest.Font(
                    psName, font.getStyle(), font.getTtcIndex(), font.getFontVariationSettings());
            fonts.add(updateFont);
        }
        FontUpdateRequest.Family family = new FontUpdateRequest.Family(
                namedFamilyList.getName(), fonts);
        return new FontUpdateRequest(family);
    }

    private static PersistentSystemFontConfig.Config readConfig(File file) throws Exception {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        try (InputStream is = new FileInputStream(file)) {
            PersistentSystemFontConfig.loadFromXml(is, config);
        }
        return config;
    }

    private static void writeConfig(PersistentSystemFontConfig.Config config,
            File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            PersistentSystemFontConfig.writeToXml(fos, config);
        }
    }

    // Returns the last family with the given name, which will be used for creating Typeface.
    private static FontConfig.FontFamily getLastFamily(FontConfig fontConfig, String familyName) {
        List<FontConfig.NamedFamilyList> namedFamilyLists = fontConfig.getNamedFamilyLists();
        for (int i = namedFamilyLists.size() - 1; i >= 0; i--) {
            if (familyName.equals(namedFamilyLists.get(i).getName())) {
                return namedFamilyLists.get(i).getFamilies().get(0);
            }
        }
        return null;
    }

    private static void assertNamedFamilyExists(FontConfig fontConfig, String familyName) {
        assertThat(fontConfig.getNamedFamilyLists().stream()
                .map(FontConfig.NamedFamilyList::getName)
                .collect(Collectors.toSet())).contains(familyName);
    }
}
