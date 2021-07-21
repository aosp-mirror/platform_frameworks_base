/*
 * Copyright 2018 The Android Open Source Project
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

package android.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.fonts.SystemFonts;
import android.util.Log;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

@SmallTest
public class FontFileUtilTest {
    private static final String TAG = "FontFileUtilTest";
    private static final String CACHE_FILE_PREFIX = ".font";

    private static File getTempFile() {
        Context ctx = InstrumentationRegistry.getTargetContext();
        final String prefix = CACHE_FILE_PREFIX;
        for (int i = 0; i < 100; ++i) {
            final File file = new File(ctx.getCacheDir(), prefix + i);
            try {
                if (file.createNewFile()) {
                    return file;
                }
            } catch (IOException e) {
                // ignore. Try next file.
            }
        }
        return null;
    }

    private static ByteBuffer mmap(AssetManager am, String path) {
        File file = getTempFile();
        try (InputStream is = am.open(path)) {
            if (!copyToFile(file, is)) {
                return null;
            }
            return mmap(file);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open assets");
            return null;
        } finally {
            file.delete();
        }
    }

    private static ByteBuffer mmap(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            FileChannel channel = fis.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean copyToFile(File file, InputStream is) {
        try (FileOutputStream os = new FileOutputStream(file, false)) {
            byte[] buffer = new byte[1024];
            int readLen;
            while ((readLen = is.read(buffer)) != -1) {
                os.write(buffer, 0, readLen);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copying resource contents to temp file: " + e.getMessage());
            return false;
        }
    }

    @Test
    public void testRegularFonts() throws IOException {
        AssetManager am = InstrumentationRegistry.getTargetContext().getAssets();
        for (Pair<Integer, Boolean> style : FontTestUtil.getAllStyles()) {
            int weight = style.first.intValue();
            boolean italic = style.second.booleanValue();
            String path = FontTestUtil.getFontPathFromStyle(weight, italic);

            ByteBuffer buffer = mmap(am, path);
            int packed = FontFileUtil.analyzeStyle(buffer, 0, null);
            assertEquals(path, weight, FontFileUtil.unpackWeight(packed));
            assertEquals(path, italic, FontFileUtil.unpackItalic(packed));
        }
    }

    @Test
    public void testTtcFont() throws IOException {
        AssetManager am = InstrumentationRegistry.getTargetContext().getAssets();
        String path = FontTestUtil.getTtcFontFileInAsset();
        ByteBuffer buffer = mmap(am, path);
        for (Pair<Integer, Boolean> style : FontTestUtil.getAllStyles()) {
            int weight = style.first.intValue();
            boolean italic = style.second.booleanValue();
            int ttcIndex = FontTestUtil.getTtcIndexFromStyle(weight, italic);

            int packed = FontFileUtil.analyzeStyle(buffer, ttcIndex, null);
            assertEquals(path + "#" + ttcIndex, weight, FontFileUtil.unpackWeight(packed));
            assertEquals(path + "#" + ttcIndex, italic, FontFileUtil.unpackItalic(packed));
        }
    }

    @Test
    public void testVariationFont() throws IOException {
        AssetManager am = InstrumentationRegistry.getTargetContext().getAssets();
        String path = FontTestUtil.getVFFontInAsset();
        ByteBuffer buffer = mmap(am, path);
        for (Pair<Integer, Boolean> style : FontTestUtil.getAllStyles()) {
            int weight = style.first.intValue();
            boolean italic = style.second.booleanValue();
            String axes = FontTestUtil.getVarSettingsFromStyle(weight, italic);

            int packed = FontFileUtil.analyzeStyle(buffer, 0,
                    FontVariationAxis.fromFontVariationSettings(axes));
            assertEquals(path + "#" + axes, weight, FontFileUtil.unpackWeight(packed));
            assertEquals(path + "#" + axes, italic, FontFileUtil.unpackItalic(packed));
        }
    }

    @Test
    public void testExtension() throws IOException {
        for (Font f : SystemFonts.getAvailableFonts()) {
            String name = f.getFile().getName();
            if (!name.endsWith(".ttf") && !name.endsWith(".otf")) {
                continue;  // Only test ttf/otf file
            }
            int isOtfFile = FontFileUtil.isPostScriptType1Font(f.getBuffer(), 0);
            assertNotEquals(-1, isOtfFile);
            String extension = isOtfFile == 1 ? ".otf" : ".ttf";
            assertTrue(name.endsWith(extension));
        }
    }
}
