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
import static org.junit.Assert.assertNotNull;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ColorSpaceRendererTest {

    @Test
    public void testRendererSize() {
        Bitmap b = ColorSpace.createRenderer()
                .size(0)
                .render();
        assertEquals(128, b.getWidth());
        assertEquals(128, b.getHeight());

        b = ColorSpace.createRenderer()
                .size(768)
                .render();
        assertEquals(768, b.getWidth());
        assertEquals(768, b.getHeight());
    }

    @Test
    public void testRenderer() {
        Bitmap b = ColorSpace.createRenderer()
                .size(1024)
                .clip(true)
                .showWhitePoint(false)
                .add(ColorSpace.get(ColorSpace.Named.SRGB), 0xffffffff)
                .add(ColorSpace.get(ColorSpace.Named.DCI_P3), 0xffffffff)
                .add(ColorSpace.get(ColorSpace.Named.PRO_PHOTO_RGB), 0.1f, 0.5f, 0.1f, 0xff000000)
                .add(ColorSpace.get(ColorSpace.Named.ADOBE_RGB), 0.1f, 0.5f, 0.1f, 0xff000000)
                .render();
        assertNotNull(b);
    }

    @Test
    public void testUcsRenderer() {
        Bitmap b = ColorSpace.createRenderer()
                .size(1024)
                .clip(true)
                .showWhitePoint(false)
                .uniformChromaticityScale(true)
                .add(ColorSpace.get(ColorSpace.Named.SRGB), 0xffffffff)
                .add(ColorSpace.get(ColorSpace.Named.DCI_P3), 0xffffffff)
                .add(ColorSpace.get(ColorSpace.Named.PRO_PHOTO_RGB), 0.1f, 0.5f, 0.1f, 0xff000000)
                .add(ColorSpace.get(ColorSpace.Named.ADOBE_RGB), 0.1f, 0.5f, 0.1f, 0xff000000)
                .render();
        assertNotNull(b);
    }
}
