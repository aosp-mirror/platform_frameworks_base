/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import com.ibm.icu4jni.text.RuleBasedNumberFormat;
import com.ibm.icu4jni.text.RuleBasedNumberFormat.RBNFType;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Some String tests.
 */
public class StringTest extends TestCase {
    private String germanSpelloutRule = "%alt-ones:" +
            "    -x: minus >>;" +
            "    x.x: << komma >>;" +
            "    null; eins; =%%main=;" +
            "%%main:" +
            "    null; ein; zwei; drei; vier; f\u00fcnf; sechs; sieben; acht; neun;\n" +
            "    zehn; elf; zwu00f6lf; >>zehn;" +
            "    20: [>>und]zwanzig;" +
            "    30: [>>und]dreiu00dfig;" +
            "    40: [>>und]vierzig;" +
            "    50: [>>und]fu00fcnfzig;" +
            "    60: [>>und]sechzig;" +
            "    70: [>>und]siebzig;" +
            "    80: [>>und]achtzig;" +
            "    90: [>>und]neunzig;" +
            "    100: hundert[>%alt-ones>];" +
            "    200: <<hundert[>%alt-ones>];" +
            "    1000: tausend[>%alt-ones>];" +
            "    1100: tausendein[>%alt-ones>];" +
            "    1200: tausend[>%alt-ones>];" +
            "    2000: <<tausend[>%alt-ones>];";

    @SmallTest
    public void testString() throws Exception {
        String test = "0123456789";
        String test1 = new String("0123456789");    // different object
        String test2 = new String("0123456780");    // different value
        String offset = new String("xxx0123456789yyy");
        String sub = offset.substring(3, 13);

        assertEquals(test, test);
        assertEquals(test, test1);
        assertFalse(test.equals(test2));

        assertEquals(0, test.compareTo(test1));
        assertTrue(test1.compareTo(test2) > 0);
        assertTrue(test2.compareTo(test1) < 0);

        /* compare string with a nonzero offset, in left/right side */
        assertEquals(0, test.compareTo(sub));
        assertEquals(0, sub.compareTo(test));
        assertEquals(test, sub);
        assertEquals(sub, test);
        /* same base, one is a substring */
        assertFalse(offset.equals(sub));
        assertFalse(sub.equals(offset));
        /* wrong class */
        assertFalse(test.equals(this));

        /* null ptr - throw */
        try {
            test.compareTo(null);
            fail("didn't get expected npe");
        } catch (NullPointerException npe) {
            // expected
        }
        /* null ptr - ok */
        assertFalse(test.equals(null));

        test = test.substring(1);
        assertEquals("123456789", test);
        assertFalse(test.equals(test1));

        test = test.substring(1);
        assertEquals("23456789", test);

        test = test.substring(1);
        assertEquals("3456789", test);

        test = test.substring(1);
        assertEquals("456789", test);

        test = test.substring(3, 5);
        assertEquals("78", test);

        test = "this/is/a/path";
        String[] strings = test.split("/");
        assertEquals(4, strings.length);

        assertEquals("this is a path", test.replaceAll("/", " "));
        assertEquals("this is a path", test.replace("/", " "));

        assertEquals(0, "abc".compareToIgnoreCase("ABC"));
        assertTrue("abc".compareToIgnoreCase("DEF") < 0);
        assertTrue("ABC".compareToIgnoreCase("def") < 0);
        assertTrue("Now".compareTo("Mow") > 0);
        assertTrue("Now".compareToIgnoreCase("Mow") > 0);

        // RuleBasedNumberFormnat tests
        RuleBasedNumberFormat format = new RuleBasedNumberFormat();
        format.open(RBNFType.SPELLOUT);
        String result = format.format(15);
        assertEquals("Expected spellout format: 'fifteen' but was "
                + result, "fifteen", result);
        format.close();

        format.open(RBNFType.DURATION);
        result = format.format(15);
        assertEquals("Expected spellout format: '15 sec.' but was "
                + result, "15 sec.", result);
        format.close();

        format.open(RBNFType.ORDINAL);
        result = format.format(15);
        assertEquals("Expected spellout format: '15th' but was "
                + result, "15th", result);
        format.close();
        format.open(germanSpelloutRule);

        result = format.format(1323);
        assertEquals("Expected spellout format: 'tausenddrei" +
                "hundertdreiundzwanzig' but was " + result, "tausend" +
                        "dreihundertdreiundzwanzig", result);
            int res = format.parse("tausenddreihundertdreiundzwanzig")
                    .intValue();
            assertEquals("Expected spellout format: 'tausend" +
                    "dreihundertdreiundzwanzig' but was " + res , 1323, res);
        format.close();
    }
}

