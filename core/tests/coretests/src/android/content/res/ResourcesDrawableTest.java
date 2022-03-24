/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.ColorStateListDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ResourcesDrawableTest {

    @Test
    public void testLoadColorAsDrawable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();
        Drawable drawable = resources.getDrawable(R.color.color1);
        assertTrue(drawable instanceof ColorStateListDrawable);
    }

    @Test
    public void testLoadColorAsDrawableFailureThrowsOriginalException() throws Throwable {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();

        Exception exception = null;

        try {
            resources.getDrawable(R.color.drawable_in_color_dir_invalid);
        } catch (Exception e) {
            exception = e;
        }

        assertNotNull(
                "Loading drawable_in_color_dir_invalid should throw an exception",
                exception
        );

        assertEquals(
                "Can't find ColorStateList from drawable resource ID #0x"
                        + Integer.toHexString(R.color.drawable_in_color_dir_invalid),
                exception.getCause().getCause().getMessage()
        );
    }

    @Test
    public void testLoadNormalDrawableInColorDir() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources resources = context.getResources();
        Drawable drawable = resources.getDrawable(R.color.drawable_in_color_dir_valid);
        assertTrue(drawable instanceof LayerDrawable);
    }
}
