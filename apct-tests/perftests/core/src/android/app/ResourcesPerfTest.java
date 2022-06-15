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

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.TypedValue;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.perftests.core.R;

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

    private Resources mRes;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getTargetContext();
        mRes = context.getResources();
    }

    @Test
    public void getValue() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        TypedValue value = new TypedValue();
        while (state.keepRunning()) {
            mRes.getValue(R.integer.forty_two, value, false /* resolve_refs */);
        }
    }

    @Test
    public void getFrameworkValue() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        TypedValue value = new TypedValue();
        while (state.keepRunning()) {
            mRes.getValue(com.android.internal.R.integer.autofill_max_visible_datasets, value,
                    false /* resolve_refs */);
        }
    }

    @Test
    public void getValueString() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        TypedValue value = new TypedValue();
        while (state.keepRunning()) {
            mRes.getValue(R.string.long_text, value, false /* resolve_refs */);
        }
    }

    @Test
    public void getFrameworkStringValue() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        TypedValue value = new TypedValue();
        while (state.keepRunning()) {
            mRes.getValue(com.android.internal.R.string.cancel, value, false /* resolve_refs */);
        }
    }

    @Test
    public void getValueManyConfigurations() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        TypedValue value = new TypedValue();
        while (state.keepRunning()) {
            mRes.getValue(com.android.internal.R.string.mmcc_illegal_me, value,
                    false /* resolve_refs */);
        }
    }

    @Test
    public void getText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getText(R.string.long_text);
        }
    }


    @Test
    public void getFont() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getFont(R.font.samplefont);
        }
    }

    @Test
    public void getString() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getString(R.string.long_text);
        }
    }

    @Test
    public void getQuantityString() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getQuantityString(R.plurals.plurals_text, 5);
        }
    }

    @Test
    public void getQuantityText() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getQuantityText(R.plurals.plurals_text, 5);
        }
    }

    @Test
    public void getTextArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getTextArray(R.array.strings);
        }
    }

    @Test
    public void getStringArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getStringArray(R.array.strings);
        }
    }

    @Test
    public void getIntegerArray() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getIntArray(R.array.ints);
        }
    }

    @Test
    public void getColor() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getColor(R.color.white, null);
        }
    }

    @Test
    public void getColorStateList() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getColorStateList(R.color.color_state_list, null);
        }
    }

    @Test
    public void getVectorDrawable() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            mRes.getDrawable(R.drawable.vector_drawable01, null);
        }
    }

    @Test
    public void getLayoutAndTravese() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            try (XmlResourceParser parser = mRes.getLayout(R.layout.test_relative_layout)) {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    // Walk the entire tree
                }
            } catch (IOException | XmlPullParserException exception) {
                fail("Parsing of the layout failed. Something is really broken");
            }
        }
    }

    @Test
    public void getLayoutAndTraverseInvalidateCaches() {
        mRes.flushLayoutCache();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            try (XmlResourceParser parser = mRes.getLayout(R.layout.test_relative_layout)) {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    // Walk the entire tree
                }
            } catch (IOException | XmlPullParserException exception) {
                fail("Parsing of the layout failed. Something is really broken");
            }

            state.pauseTiming();
            mRes.flushLayoutCache();
            state.resumeTiming();
        }
    }
}