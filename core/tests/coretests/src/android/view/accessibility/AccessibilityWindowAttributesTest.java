/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.accessibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;

import android.os.LocaleList;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * Class for testing {@link AccessibilityWindowAttributes}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AccessibilityWindowAttributesTest {
    private static final String TEST_WINDOW_TITLE = "test window title";
    private static final LocaleList TEST_LOCALES = new LocaleList(Locale.ROOT);

    @SmallTest
    @Test
    public void testParceling() {
        final AccessibilityWindowAttributes windowAttributes = createInstance(
                TEST_WINDOW_TITLE, TEST_LOCALES);
        Parcel parcel = Parcel.obtain();
        windowAttributes.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final AccessibilityWindowAttributes attributes2 =
                AccessibilityWindowAttributes.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        assertNotSame(windowAttributes, attributes2);
        assertEquals(windowAttributes, attributes2);
    }

    @SmallTest
    @Test
    public void testNonequality() {
        final AccessibilityWindowAttributes windowAttributes = createInstance(
                null, TEST_LOCALES);
        final AccessibilityWindowAttributes windowAttributes1 = createInstance(
                TEST_WINDOW_TITLE, TEST_LOCALES);
        final AccessibilityWindowAttributes windowAttributes2 = createInstance(
                TEST_WINDOW_TITLE, null);
        assertNotEquals(windowAttributes, windowAttributes1);
        assertNotEquals(windowAttributes, windowAttributes2);
        assertNotEquals(windowAttributes1, windowAttributes2);
    }

    private static AccessibilityWindowAttributes createInstance(
            String windowTitle, LocaleList locales) {
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
        layoutParams.accessibilityTitle = windowTitle;
        return new AccessibilityWindowAttributes(layoutParams, locales);
    }
}
