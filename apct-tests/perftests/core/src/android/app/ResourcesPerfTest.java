/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app;

import static org.junit.Assert.fail;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Benchmarks for {@link android.content.res.Resources}.
 */
@LargeTest
public class ResourcesPerfTest {
    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private AssetManager mAsset;
    private Resources mRes;

    private int mTextId;
    private int mColorId;
    private int mIntegerId;
    private int mLayoutId;

    @Before
    public void setUp() {
        mAsset = new AssetManager();
        mAsset.addAssetPath("/system/framework/framework-res.apk");
        mRes = new Resources(mAsset, null, null);

        mTextId = mRes.getIdentifier("cancel", "string", "android");
        mColorId = mRes.getIdentifier("transparent", "color", "android");
        mIntegerId = mRes.getIdentifier("config_shortAnimTime", "integer", "android");
        mLayoutId = mRes.getIdentifier("two_line_list_item", "layout", "android");
    }

    @After
    public void tearDown() {
        mAsset.close();
    }

    @Test
    public void getText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getText(mTextId);
        }
    }

    @Test
    public void getColor() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getColor(mColorId, null);
        }
    }

    @Test
    public void getInteger() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getInteger(mIntegerId);
        }
    }

    @Test
    public void getLayoutAndTravese() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            try (XmlResourceParser parser = mRes.getLayout(mLayoutId)) {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    // Walk the entire tree
                }
            } catch (IOException | XmlPullParserException exception) {
                fail("Parsing of the layout failed. Something is really broken");
            }
        }
    }
}
