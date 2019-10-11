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
 * limitations under the License
 */

package android.widget;

import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.view.LayoutInflater;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;

import com.android.perftests.core.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;

@LargeTest
@RunWith(Parameterized.class)
public class TextViewFontFamilyLayoutPerfTest {
    @Parameters(name = "{0}")
    public static Collection layouts() {
        return Arrays.asList(new Object[][] {
                { "String fontFamily attribute", R.layout.test_textview_font_family_string},
                { "File fontFamily attribute", R.layout.test_textview_font_family_file},
                { "XML fontFamily attribute", R.layout.test_textview_font_family_xml},
        });
    }

    private int mLayoutId;

    public TextViewFontFamilyLayoutPerfTest(String key, int layoutId) {
        mLayoutId = layoutId;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testConstruction() throws Throwable {
        final Context context = InstrumentationRegistry.getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final LayoutInflater inflator =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        while (state.keepRunning()) {
            inflator.inflate(mLayoutId, null, false);
        }
    }
}
