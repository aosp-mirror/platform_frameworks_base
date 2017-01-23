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

package android.graphics;

import android.test.suitebuilder.annotation.SmallTest;
import android.text.FontConfig;
import junit.framework.TestCase;

import java.util.List;


public class VariationParserTest extends TestCase {

    @SmallTest
    public void testParseFontVariationSetting() {
        int tag = FontListParser.makeTag('w', 'd', 't', 'h');
        FontConfig.Axis[] axis = FontListParser.parseFontVariationSettings("'wdth' 1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("\"wdth\" 100");
        assertEquals(tag, axis[0].getTag());
        assertEquals(100.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("   'wdth' 100");
        assertEquals(tag, axis[0].getTag());
        assertEquals(100.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("\t'wdth' 0.5");
        assertEquals(tag, axis[0].getTag());
        assertEquals(0.5f, axis[0].getStyleValue());

        tag = FontListParser.makeTag('A', 'X', ' ', ' ');
        axis = FontListParser.parseFontVariationSettings("'AX  ' 1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("'AX  '\t1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("'AX  '\n1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("'AX  '\r1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        axis = FontListParser.parseFontVariationSettings("'AX  '\r\t\n 1");
        assertEquals(tag, axis[0].getTag());
        assertEquals(1.0f, axis[0].getStyleValue());

        // Test for invalid input
        axis = FontListParser.parseFontVariationSettings("");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("invalid_form");
        assertEquals(0, axis.length);

        // Test with invalid tag
        axis = FontListParser.parseFontVariationSettings("'' 1");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("'invalid' 1");
        assertEquals(0, axis.length);

        // Test with invalid styleValue
        axis = FontListParser.parseFontVariationSettings("'wdth' ");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("'wdth' x");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("'wdth' \t");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("'wdth' \n\r");
        assertEquals(0, axis.length);
    }

    @SmallTest
    public void testParseFontVariationStyleSettings() {
        FontConfig.Axis[] axis =
                FontListParser.parseFontVariationSettings("'wdth' 10,'AX  '\r1");
        int tag1 = FontListParser.makeTag('w', 'd', 't', 'h');
        int tag2 = FontListParser.makeTag('A', 'X', ' ', ' ');
        assertEquals(tag1, axis[0].getTag());
        assertEquals(10.0f, axis[0].getStyleValue());
        assertEquals(tag2, axis[1].getTag());
        assertEquals(1.0f, axis[1].getStyleValue());

        // Test only spacers are allowed before tag
        axis = FontListParser.parseFontVariationSettings("     'wdth' 10,ab'wdth' 1");
        tag1 = FontListParser.makeTag('w', 'd', 't', 'h');
        assertEquals(tag1, axis[0].getTag());
        assertEquals(10.0f, axis[0].getStyleValue());
        assertEquals(1, axis.length);
    }

    @SmallTest
    public void testInvalidTagCharacters() {
        FontConfig.Axis[] axis =
                FontListParser.parseFontVariationSettings("'\u0000\u0000\u0000\u0000' 10");
        assertEquals(0, axis.length);
        axis = FontListParser.parseFontVariationSettings("'\u3042\u3044\u3046\u3048' 10");
        assertEquals(0, axis.length);
    }

    @SmallTest
    public void testMakeTag() {
      assertEquals(0x77647468, FontListParser.makeTag('w', 'd', 't', 'h'));
      assertEquals(0x41582020, FontListParser.makeTag('A', 'X', ' ', ' '));
      assertEquals(0x20202020, FontListParser.makeTag(' ', ' ', ' ', ' '));
    }
}
