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

import android.content.Context;
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
import java.io.IOException;
import java.util.Map;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class UpdatableFontDirTest {

    /**
     * A {@link UpdatableFontDir.FontFileParser} for testing. Instead of using real font files,
     * this test uses fake font files. A fake font file has its version as its file content.
     */
    private static class FakeFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public long getVersion(File file) throws IOException {
            return Long.parseLong(FileUtils.readTextFile(file, 100, ""));
        }
    }

    private File mCacheDir;
    private File mUpdatableFontFilesDir;

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mCacheDir = new File(context.getCacheDir(), "UpdatableFontDirTest");
        FileUtils.deleteContentsAndDir(mCacheDir);
        mCacheDir.mkdirs();
        mUpdatableFontFilesDir = new File(mCacheDir, "updatable_fonts");
        mUpdatableFontFilesDir.mkdir();
    }

    @After
    public void tearDown() {
        FileUtils.deleteContentsAndDir(mCacheDir);
    }

    @Test
    public void construct() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dirForPreparation = new UpdatableFontDir(mUpdatableFontFilesDir, parser);
        installFontFile(dirForPreparation, "foo.ttf", "1");
        installFontFile(dirForPreparation, "bar.ttf", "2");
        installFontFile(dirForPreparation, "foo.ttf", "3");
        installFontFile(dirForPreparation, "bar.ttf", "4");
        // Four font dirs are created.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(4);

        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);
        assertThat(dir.getFontFileMap()).containsKey("foo.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("foo.ttf"))).isEqualTo(3);
        assertThat(dir.getFontFileMap()).containsKey("bar.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("bar.ttf"))).isEqualTo(4);
        // Outdated font dir should be deleted.
        assertThat(mUpdatableFontFilesDir.list()).hasLength(2);
    }

    @Test
    public void construct_empty() {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);
        assertThat(dir.getFontFileMap()).isEmpty();
    }

    @Test
    public void installFontFile() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);

        installFontFile(dir, "test.ttf", "1");
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("test.ttf"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_upgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);

        installFontFile(dir, "test.ttf", "1");
        Map<String, File> mapBeforeUpgrade = dir.getFontFileMap();
        installFontFile(dir, "test.ttf", "2");
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("test.ttf"))).isEqualTo(2);
        assertThat(mapBeforeUpgrade).containsKey("test.ttf");
        assertWithMessage("Older fonts should not be deleted until next loadFontFileMap")
                .that(parser.getVersion(mapBeforeUpgrade.get("test.ttf"))).isEqualTo(1);
    }

    @Test
    public void installFontFile_downgrade() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);

        installFontFile(dir, "test.ttf", "2");
        installFontFile(dir, "test.ttf", "1");
        assertThat(dir.getFontFileMap()).containsKey("test.ttf");
        assertWithMessage("Font should not be downgraded to an older version")
                .that(parser.getVersion(dir.getFontFileMap().get("test.ttf"))).isEqualTo(2);
    }

    @Test
    public void installFontFile_multiple() throws Exception {
        FakeFontFileParser parser = new FakeFontFileParser();
        UpdatableFontDir dir = new UpdatableFontDir(mUpdatableFontFilesDir, parser);

        installFontFile(dir, "foo.ttf", "1");
        installFontFile(dir, "bar.ttf", "2");
        assertThat(dir.getFontFileMap()).containsKey("foo.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("foo.ttf"))).isEqualTo(1);
        assertThat(dir.getFontFileMap()).containsKey("bar.ttf");
        assertThat(parser.getVersion(dir.getFontFileMap().get("bar.ttf"))).isEqualTo(2);
    }

    private void installFontFile(UpdatableFontDir dir, String name, String content)
            throws IOException {
        File file = File.createTempFile(name, "", mCacheDir);
        FileUtils.stringToFile(file, content);
        try (FileInputStream in = new FileInputStream(file)) {
            dir.installFontFile(name, in.getFD());
        }
    }
}
