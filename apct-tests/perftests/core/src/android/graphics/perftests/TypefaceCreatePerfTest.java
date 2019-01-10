/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics.perftests;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TypefaceCreatePerfTest {
    // A font file name in asset directory.
    private static final String TEST_FONT_NAME = "DancingScript-Regular.ttf";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testCreate_fromFamily() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            Typeface face = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
        }
    }

    @Test
    public void testCreate_fromFamilyName() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        while (state.keepRunning()) {
            Typeface face = Typeface.create("monospace", Typeface.NORMAL);
        }
    }

    @Test
    public void testCreate_fromAsset() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getContext();
        final AssetManager am = context.getAssets();

        while (state.keepRunning()) {
            Typeface face = Typeface.createFromAsset(am, TEST_FONT_NAME);
        }
    }

    @Test
    public void testCreate_fromFile() {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final Context context = InstrumentationRegistry.getContext();
        final AssetManager am = context.getAssets();

        File outFile = null;
        try {
            outFile = File.createTempFile("example", "ttf", context.getCacheDir());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (InputStream in = am.open(TEST_FONT_NAME);
                OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[1024];
            int n = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        while (state.keepRunning()) {
            Typeface face = Typeface.createFromFile(outFile);
        }

        outFile.delete();
    }
}
