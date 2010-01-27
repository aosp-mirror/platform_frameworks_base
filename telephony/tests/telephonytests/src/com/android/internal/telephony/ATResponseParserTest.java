/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class ATResponseParserTest extends TestCase {
    @SmallTest
    public void testBasic() throws Exception {
        ATResponseParser p = new ATResponseParser("+CREG: 0");

        assertEquals(0, p.nextInt());

        assertFalse(p.hasMore());

        try {
            p.nextInt();
            fail("exception expected");
        } catch (ATParseEx ex) {
            //test pass
        }

        p = new ATResponseParser("+CREG: 0,1");
        assertEquals(0, p.nextInt());
        assertEquals(1, p.nextInt());
        assertFalse(p.hasMore());

        p = new ATResponseParser("+CREG: 0, 1");
        assertEquals(0, p.nextInt());
        assertEquals(1, p.nextInt());
        assertFalse(p.hasMore());

        p = new ATResponseParser("+CREG: 0, 1,");
        assertEquals(0, p.nextInt());
        assertEquals(1, p.nextInt());
        // this seems odd but is probably OK
        assertFalse(p.hasMore());
        try {
            p.nextInt();
            fail("exception expected");
        } catch (ATParseEx ex) {
            //test pass
        }

        p = new ATResponseParser("+CREG: 0, 1 ");
        assertEquals(0, p.nextInt());
        assertEquals(1, p.nextInt());
        assertFalse(p.hasMore());

        p = new ATResponseParser("0, 1 ");
        // no prefix -> exception
        try {
            p.nextInt();
            fail("exception expected");
        } catch (ATParseEx ex) {
            //test pass
        }

        p = new ATResponseParser("+CREG: 0, 1, 5");
        assertFalse(p.nextBoolean());
        assertTrue(p.nextBoolean());
        try {
            // is this over-constraining?
            p.nextBoolean();
            fail("exception expected");
        } catch (ATParseEx ex) {
            //test pass
        }

        p = new ATResponseParser("+CLCC: 1,0,2,0,0,\"+18005551212\",145");

        assertEquals(1, p.nextInt());
        assertFalse(p.nextBoolean());
        assertEquals(2, p.nextInt());
        assertEquals(0, p.nextInt());
        assertEquals(0, p.nextInt());
        assertEquals("+18005551212", p.nextString());
        assertEquals(145, p.nextInt());
        assertFalse(p.hasMore());

        p = new ATResponseParser("+CLCC: 1,0,2,0,0,\"+18005551212,145");

        assertEquals(1, p.nextInt());
        assertFalse(p.nextBoolean());
        assertEquals(2, p.nextInt());
        assertEquals(0, p.nextInt());
        assertEquals(0, p.nextInt());
        try {
            p.nextString();
            fail("expected ex");
        } catch (ATParseEx ex) {
            //test pass
        }

        p = new ATResponseParser("+FOO: \"\"");
        assertEquals("", p.nextString());
    }
}
