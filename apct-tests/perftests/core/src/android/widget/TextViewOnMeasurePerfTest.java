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

import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.UNSPECIFIED;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.TextAppearanceSpan;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;
import java.util.Random;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class TextViewOnMeasurePerfTest {
    private static final String MULTILINE_TEXT =
        "Lorem ipsum dolor sit amet, \n"+
        "consectetur adipiscing elit, \n" +
        "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. \n" +
        "Ut enim ad minim veniam, \n" +
        "quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.\n" +
        "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat " +
        "nulla pariatur.\n" +
        "Excepteur sint occaecat cupidatat non proident, \n" +
        "sunt in culpa qui officia deserunt mollit anim id est laborum.\n";

    private static final int VIEW_WIDTH = 1000;
    private static final int VIEW_HEIGHT = 1000;
    private static final CharSequence COMPLEX_MULTILINE_TEXT;
    static {
        final SpannableStringBuilder ssb = new SpannableStringBuilder();

        // To emphasize, append multiline text 10 times.
        for (int i = 0; i < 10; ++i) {
            ssb.append(MULTILINE_TEXT);
        }

        final ColorStateList[] COLORS = {
                ColorStateList.valueOf(0xFFFF0000),  // RED
                ColorStateList.valueOf(0xFF00FF00),  // GREEN
                ColorStateList.valueOf(0xFF0000FF),  // BLUE
        };

        final int[] STYLES = {
                Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC
        };

        final String[] FAMILIES = { "sans-serif", "serif", "monospace" };

        // Append random span to text.
        final Random random = new Random(0);
        for (int pos = 0; pos < ssb.length();) {
            final TextAppearanceSpan span = new TextAppearanceSpan(
                FAMILIES[random.nextInt(FAMILIES.length)],
                STYLES[random.nextInt(STYLES.length)],
                24 + random.nextInt(32),  // text size. minimum 24
                COLORS[random.nextInt(COLORS.length)],
                COLORS[random.nextInt(COLORS.length)]);
            int spanLength = 1 + random.nextInt(9);  // Up to 9 span length.
            if (pos + spanLength > ssb.length()) {
                spanLength = ssb.length() - pos;
            }
            ssb.setSpan(span, pos, pos + spanLength, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            pos += spanLength;
        }
        COMPLEX_MULTILINE_TEXT = ssb;
    }

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void testMeasure_AtMost() throws Throwable {
        final Context context = InstrumentationRegistry.getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        final TextView textView = new TextView(context);
        textView.setText(COMPLEX_MULTILINE_TEXT);

        while (state.keepRunning()) {
            // Changing locale to invalidate internal layout.
            textView.setTextLocale(Locale.UK);
            textView.setTextLocale(Locale.US);

            textView.measure(AT_MOST | VIEW_WIDTH, AT_MOST | VIEW_HEIGHT);
        }
    }

    @Test
    public void testMeasure_Exactly() throws Throwable {
        final Context context = InstrumentationRegistry.getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        final TextView textView = new TextView(context);
        textView.setText(COMPLEX_MULTILINE_TEXT);

        while (state.keepRunning()) {
            // Changing locale to invalidate internal layout.
            textView.setTextLocale(Locale.UK);
            textView.setTextLocale(Locale.US);

            textView.measure(EXACTLY | VIEW_WIDTH, EXACTLY | VIEW_HEIGHT);
        }
    }

    @Test
    public void testMeasure_Unspecified() throws Throwable {
        final Context context = InstrumentationRegistry.getTargetContext();
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();

        final TextView textView = new TextView(context);
        textView.setText(COMPLEX_MULTILINE_TEXT);

        while (state.keepRunning()) {
            // Changing locale to invalidate internal layout.
            textView.setTextLocale(Locale.UK);
            textView.setTextLocale(Locale.US);

            textView.measure(UNSPECIFIED, UNSPECIFIED);
        }
    }
}
