/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.test.InstrumentationTestCase;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.text.flags.Flags;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * PaintTest tests {@link Paint}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PaintFontVariationTest extends InstrumentationTestCase {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @RequiresFlagsEnabled(Flags.FLAG_TYPEFACE_CACHE_FOR_VAR_SETTINGS)
    @Test
    public void testDerivedFromSameTypeface() {
        final Paint p = new Paint();

        p.setTypeface(Typeface.SANS_SERIF);
        assertThat(p.setFontVariationSettings("'wght' 450")).isTrue();
        Typeface first = p.getTypeface();

        p.setTypeface(Typeface.SANS_SERIF);
        assertThat(p.setFontVariationSettings("'wght' 480")).isTrue();
        Typeface second = p.getTypeface();

        assertThat(first.getDerivedFrom()).isSameInstanceAs(second.getDerivedFrom());
    }

    @RequiresFlagsEnabled(Flags.FLAG_TYPEFACE_CACHE_FOR_VAR_SETTINGS)
    @Test
    public void testDerivedFromChained() {
        final Paint p = new Paint();

        p.setTypeface(Typeface.SANS_SERIF);
        assertThat(p.setFontVariationSettings("'wght' 450")).isTrue();
        Typeface first = p.getTypeface();

        assertThat(p.setFontVariationSettings("'wght' 480")).isTrue();
        Typeface second = p.getTypeface();

        assertThat(first.getDerivedFrom()).isSameInstanceAs(second.getDerivedFrom());
    }
}
