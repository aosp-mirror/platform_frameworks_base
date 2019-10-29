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
 * limitations under the License.
 */

package android.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.res.AssetManager;
import android.graphics.fonts.Font;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TypefaceEqualsTest {
    @Test
    public void testFontEqualWithLocale() throws IOException {
        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();

        Font masterFont = new Font.Builder(am, "fonts/a3em.ttf").build();

        Font jaFont = new Font.Builder(masterFont.getBuffer(), new File("fonts/a3em.ttf"), "ja")
                .build();
        Font jaFont2 = new Font.Builder(masterFont.getBuffer(), new File("fonts/a3em.ttf"), "ja")
                .build();
        Font koFont = new Font.Builder(masterFont.getBuffer(), new File("fonts/a3em.ttf"), "ko")
                .build();

        assertEquals(jaFont, jaFont2);
        assertNotEquals(jaFont, koFont);
        assertNotEquals(jaFont, masterFont);
    }
}
