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

package android.widget;

import android.app.Activity;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.perftests.utils.StubActivity;
import android.view.KeyEvent;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;

import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

@LargeTest
@RunWith(Parameterized.class)
public class EditTextLongTextPerfTest {
    @Parameters(name = "{0}")
    public static Collection cases() {
        return Arrays.asList(new Object[][] {
            { "10x30K", 10, 30000 },
            { "300x1K", 300, 1000 },
        });
    }

    private final String mMetricKey;
    private final int mChars;
    private final int mLines;

    public EditTextLongTextPerfTest(String metricKey, int chars, int lines) {
        mMetricKey = metricKey;
        mChars = chars;
        mLines = lines;
    }

    @Rule
    public ActivityTestRule<StubActivity> mActivityRule = new ActivityTestRule(StubActivity.class);

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private EditText setupEditText() {
        final EditText editText = new EditText(mActivityRule.getActivity());

        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        final long seed = 1234567890;
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mLines; i++) {
            for (int j = 0; j < mChars; j++) {
                char c = alphabet.charAt(r.nextInt(alphabet.length()));
                sb.append(c);
            }
            sb.append('\n');
        }

        final int height = 1000;
        final int width = 1000;
        editText.setHeight(height);
        editText.setWidth(width);
        editText.setLayoutParams(new ViewGroup.LayoutParams(width, height));

        Activity activity = mActivityRule.getActivity();
        activity.setContentView(editText);

        editText.setText(sb.toString(), TextView.BufferType.EDITABLE);
        editText.invalidate();
        editText.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                         MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        editText.layout(0, 0, height, width);

        return editText;
    }

    @Test
    public void testEditText() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            final EditText editText = setupEditText();
            final KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER);
            final int steps = 100;
            while (state.keepRunning()) {
                for (int i = 0; i < steps; i++) {
                    int offset = (editText.getText().length() * i) / steps;
                    editText.setSelection(offset);
                    editText.bringPointIntoView(offset);
                    editText.onKeyDown(keyEvent.getKeyCode(), keyEvent);
                    editText.updateDisplayListIfDirty();
                }
            }
        });
    }
}
