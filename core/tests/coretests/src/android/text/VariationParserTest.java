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

package android.text;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.graphics.fonts.FontVariationAxis;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VariationParserTest {
    private static final String[] INVALID_STYLE_VALUES = {
        "", "x", "\t", "\n"
    };

    @Test
    public void testFromFontVariationSetting_InvalidStyleValue() {
        // Test with invalid styleValue
        for (String invalidStyle : INVALID_STYLE_VALUES) {
            try {
                FontVariationAxis.fromFontVariationSettings("'wdth' " + invalidStyle);
                fail();
            } catch (IllegalArgumentException e) {
                // pass
            }
        }
        for (String invalidStyle : INVALID_STYLE_VALUES) {
            try {
                FontVariationAxis.fromFontVariationSettings("'wght' 1, 'wdth' "
                    + invalidStyle);
                fail();
            } catch (IllegalArgumentException e) {
                // pass
            }
        }
    }

    @Test
    public void testOpenTypeTagValue() {
      assertEquals(0x77647468,
          new FontVariationAxis("wdth", 0).getOpenTypeTagValue());
      assertEquals(0x41582020,
          new FontVariationAxis("AX  ", 0).getOpenTypeTagValue());
      assertEquals(0x20202020,
          new FontVariationAxis("    ", 0).getOpenTypeTagValue());
    }
}
