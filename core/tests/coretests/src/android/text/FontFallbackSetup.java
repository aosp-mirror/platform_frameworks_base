/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.FontListParser;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.SystemFonts;

import androidx.test.InstrumentationRegistry;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public class FontFallbackSetup implements AutoCloseable {
    private final String[] mTestFontFiles;
    private final String mXml;
    private final String mTestFontsDir;
    private final Map<String, Typeface> mFontMap;

    public FontFallbackSetup(@NonNull String testSubDir, @NonNull String[] testFontFiles,
            @NonNull String xml) {
        mTestFontFiles = testFontFiles;
        mXml = xml;

        final Context targetCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File cacheDir = new File(targetCtx.getCacheDir(), testSubDir);
        if (!cacheDir.isDirectory()) {
            final boolean dirsCreated = cacheDir.mkdirs();
            if (!dirsCreated) {
                throw new RuntimeException("Creating test directories for fonts failed.");
            }
        }
        mTestFontsDir = cacheDir.getAbsolutePath() + "/";

        final String testFontsXml = new File(mTestFontsDir, "fonts.xml").getAbsolutePath();
        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        for (String fontFile : mTestFontFiles) {
            final String sourceInAsset = "fonts/" + fontFile;
            final File outInCache = new File(mTestFontsDir, fontFile);
            try (InputStream is = am.open(sourceInAsset)) {
                Files.copy(is, outInCache.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try (FileOutputStream fos = new FileOutputStream(testFontsXml)) {
            fos.write(mXml.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        FontConfig fontConfig;
        try {
            fontConfig = FontListParser.parse(testFontsXml, mTestFontsDir, null, null, null, 0, 0);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException(e);
        }

        Map<String, FontFamily[]> fallbackMap = SystemFonts.buildSystemFallback(fontConfig);
        mFontMap = SystemFonts.buildSystemTypefaces(fontConfig, fallbackMap);
    }

    @NonNull
    public Typeface getTypefaceFor(@NonNull String fontName) {
        return mFontMap.get(fontName);
    }

    @NonNull
    public TextPaint getPaintFor(@NonNull String fontName) {
        final TextPaint paint = new TextPaint();
        paint.setTypeface(getTypefaceFor(fontName));
        return paint;
    }

    @Override
    public void close() {
        for (String fontFile : mTestFontFiles) {
            final File outInCache = new File(mTestFontsDir, fontFile);
            outInCache.delete();
        }
    }
}
