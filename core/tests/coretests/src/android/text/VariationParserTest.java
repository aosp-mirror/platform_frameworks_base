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

import android.graphics.fonts.FontVariationAxis;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class VariationParserTest extends TestCase {
    private static final String[] INVALID_STYLE_VALUES = {
        "", "x", "\t", "\n"
    };

    @SmallTest
    public void testFromFontVariationSetting_InvalidStyleValue() {
        // Test with invalid styleValue
        for (String invalidStyle : INVALID_STYLE_VALUES) {
            try {
                FontVariationAxis.fromFontVariationSettings("'wdth' " + invalidStyle);
                fail();
            } catch (FontVariationAxis.InvalidFormatException e) {
                // pass
            }
        }
        for (String invalidStyle : INVALID_STYLE_VALUES) {
            try {
                FontVariationAxis.fromFontVariationSettings("'wght' 1, 'wdth' " + invalidStyle);
                fail();
            } catch (FontVariationAxis.InvalidFormatException e) {
                // pass
            }
        }
    }

    @SmallTest
    public void testOpenTypeTagValue() throws FontVariationAxis.InvalidFormatException {
      assertEquals(0x77647468, (new FontVariationAxis("wdth", 0).getOpenTypeTagValue()));
      assertEquals(0x41582020, (new FontVariationAxis("AX  ", 0).getOpenTypeTagValue()));
      assertEquals(0x20202020, (new FontVariationAxis("    ", 0).getOpenTypeTagValue()));
    }
}
