/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.text.format;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DateFormatTest {

    @Test
    public void testHasDesignator() {
        assertTrue(DateFormat.hasDesignator("hh:mm:ss", DateFormat.MINUTE));
        assertTrue(DateFormat.hasDesignator("myyyy", DateFormat.MINUTE));
        assertTrue(DateFormat.hasDesignator("mmm", DateFormat.MINUTE));

        assertFalse(DateFormat.hasDesignator("hh:MM:ss", DateFormat.MINUTE));
    }

    @Test
    public void testHasDesignatorEscaped() {
        assertTrue(DateFormat.hasDesignator("hh:mm 'LOL'", DateFormat.MINUTE));

        assertFalse(DateFormat.hasDesignator("hh:mm 'yyyy'", DateFormat.YEAR));
    }

    @Test
    public void testIs24HourLocale() {
        assertFalse(DateFormat.is24HourLocale(Locale.US));
        assertTrue(DateFormat.is24HourLocale(Locale.GERMANY));
    }
}
