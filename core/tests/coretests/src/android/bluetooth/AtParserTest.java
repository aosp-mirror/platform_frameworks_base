/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.bluetooth;

import android.bluetooth.AtCommandHandler;
import android.bluetooth.AtCommandResult;
import android.bluetooth.AtParser;

import java.util.*;
import junit.framework.*;

public class AtParserTest extends TestCase {

    /* An AtCommandHandler instrumented for testing purposes
     */
    private class HandlerTest extends AtCommandHandler {
        boolean mBasicCalled, mActionCalled, mReadCalled, mTestCalled, 
                mSetCalled;
        int mBasicReturn, mActionReturn, mReadReturn, mTestReturn, mSetReturn;
        Object[] mSetArgs;
        String mBasicArgs;

        HandlerTest() {
            this(AtCommandResult.ERROR, AtCommandResult.ERROR,
                 AtCommandResult.ERROR, AtCommandResult.ERROR,
                 AtCommandResult.ERROR);
        }

        HandlerTest(int a, int b, int c, int d, int e) {
            mBasicReturn = a;
            mActionReturn = b;
            mReadReturn = c;
            mSetReturn = d;
            mTestReturn = e;
            reset();
        }
        public void reset() {
            mBasicCalled = false;
            mActionCalled = false;
            mReadCalled = false;
            mSetCalled = false;
            mTestCalled = false;
            mSetArgs = null;
            mBasicArgs = null;
        }
        public boolean wasCalled() {   // helper
            return mBasicCalled || mActionCalled || mReadCalled ||
                    mTestCalled || mSetCalled;
        }
        @Override
        public AtCommandResult handleBasicCommand(String args) {
            mBasicCalled = true;
            mBasicArgs = args;
            return new AtCommandResult(mBasicReturn);
        }
        @Override
        public AtCommandResult handleActionCommand() {
            mActionCalled = true;
            return new AtCommandResult(mActionReturn);
        }
        @Override
        public AtCommandResult handleReadCommand() {
            mReadCalled = true;
            return new AtCommandResult(mReadReturn);
        }
        @Override
        public AtCommandResult handleSetCommand(Object[] args) {
            mSetCalled = true;
            mSetArgs = args;
            return new AtCommandResult(mSetReturn);
        }
        @Override
        public AtCommandResult handleTestCommand() {
            mTestCalled = true;
            return new AtCommandResult(mTestReturn);
        }
    }

    private AtParser mParser;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mParser = new AtParser();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }


    /* Test that the right method is being called
     */
/*    public void testBasic1() throws Exception {
        HandlerTest D = new HandlerTest(0, 1, 1, 1, 1);
        HandlerTest A = new HandlerTest(0, 1, 1, 1, 1);
        mParser.register('D', D);
        mParser.register('A', A);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("  A T D = ? T 1 2 3  4   ").toStrings()));
        assertTrue(D.mBasicCalled);
        assertFalse(D.mActionCalled);
        assertFalse(D.mTestCalled);
        assertFalse(D.mSetCalled);
        assertFalse(D.mReadCalled);
        assertFalse(A.wasCalled());
        assertEquals("=?T1234", D.mBasicArgs);
    }
*/
    /* Test some crazy strings
     *//*
    public void testBasic2() throws Exception {
        HandlerTest A = new HandlerTest(0, 1, 1, 1, 1);
        mParser.register('A', A);

        assertTrue(Arrays.equals(
                   new String[]{},
                   mParser.process("     ").toStrings()));

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("  a T a t \"\"  1 2 3 a 4   ")
                           .toStrings()));
        assertEquals("T\"\"123A4", A.mBasicArgs);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("  a T a t  \"foo BaR12Z\" 1 2 3 a 4   ")
                           .toStrings()));
        assertEquals("T\"foo BaR12Z\"123A4", A.mBasicArgs);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("ATA\"").toStrings()));
        assertEquals("\"\"", A.mBasicArgs);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("ATA\"a").toStrings()));
        assertEquals("\"a\"", A.mBasicArgs);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("ATa\" ").toStrings()));
        assertEquals("\" \"", A.mBasicArgs);

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("ATA  \"one \" two \"t hr ee ")
                           .toStrings()));
        assertEquals("\"one \"TWO\"t hr ee \"", A.mBasicArgs);
    }*/

    /* Simple extended commands
     *//*
    public void testExt1() throws Exception {
        HandlerTest A = new HandlerTest(1, 0, 0, 0, 0);
        mParser.register("+A", A);

        assertTrue(Arrays.equals(
                   new String[]{"ERROR"},
                   mParser.process("AT+B").toStrings()));
        assertFalse(A.wasCalled());

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+A").toStrings()));
        assertTrue(A.mActionCalled);
        A.mActionCalled = false;
        assertFalse(A.wasCalled());
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+A=").toStrings()));
        assertTrue(A.mSetCalled);
        A.mSetCalled = false;
        assertFalse(A.wasCalled());
        assertEquals(1, A.mSetArgs.length);
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+A=?").toStrings()));
        assertTrue(A.mTestCalled);
        A.mTestCalled = false;
        assertFalse(A.wasCalled());
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+A?").toStrings()));
        assertTrue(A.mReadCalled);
        A.mReadCalled = false;
        assertFalse(A.wasCalled());
        A.reset();
    }
*/


    /* Test chained commands
     *//*
    public void testChain1() throws Exception {
        HandlerTest A = new HandlerTest(0, 1, 1, 1, 1);
        HandlerTest B = new HandlerTest(1, 0, 0, 0, 0);
        HandlerTest C = new HandlerTest(1, 1, 1, 1, 1);
        mParser.register('A', A);
        mParser.register("+B", B);
        mParser.register("+C", C);

        assertTrue(Arrays.equals(
                   new String[]{"ERROR"},
                   mParser.process("AT+B;+C").toStrings()));
        assertTrue(B.mActionCalled);
        assertTrue(C.mActionCalled);
        B.reset();
        C.reset();

        assertTrue(Arrays.equals(
                   new String[]{"ERROR"},
                   mParser.process("AT+C;+B").toStrings()));
        assertFalse(B.wasCalled());
        assertTrue(C.mActionCalled);
        B.reset();
        C.reset();
    }*/

    /* Test Set command
     *//*
    public void testSet1() throws Exception {
        HandlerTest A = new HandlerTest(1, 1, 1, 0, 1);
        mParser.register("+AAAA", A);
        Object[] expectedResult;

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=1").toStrings()));
        expectedResult = new Object[]{(Integer)1};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=1,2,3").toStrings()));
        expectedResult = new Object[]{(Integer)1, (Integer)2, (Integer)3};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=3,0,0,1").toStrings()));
        expectedResult = new Object[]{(Integer)3, (Integer)0, (Integer)0,
                                      (Integer)1};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=\"foo\",1,\"b,ar").toStrings()));
        expectedResult = new Object[]{"\"foo\"", 1, "\"b,ar\""};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=").toStrings()));
        expectedResult = new Object[]{""};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=,").toStrings()));
        expectedResult = new Object[]{"", ""};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=,,,").toStrings()));
        expectedResult = new Object[]{"", "", "", ""};
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("AT+AAAA=,1,,\"foo\",").toStrings()));
        expectedResult = new Object[]{"", 1, "", "\"foo\"", ""};
        assertEquals(5, A.mSetArgs.length);
        assertTrue(Arrays.equals(expectedResult, A.mSetArgs));
        A.reset();
    }*/

    /* Test repeat command "A/"
     *//*
    public void testRepeat() throws Exception {
        HandlerTest A = new HandlerTest(0, 0, 0, 0, 0);
        mParser.register('A', A);

        // Try repeated command on fresh parser
        assertTrue(Arrays.equals(
                   new String[]{},
                   mParser.process("A/").toStrings()));
        assertFalse(A.wasCalled());
        A.reset();

        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("ATA").toStrings()));
        assertTrue(A.mBasicCalled);
        assertEquals("", A.mBasicArgs);
        A.reset();

        // Now repeat the command
        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("A/").toStrings()));
        assertTrue(A.mBasicCalled);
        assertEquals("", A.mBasicArgs);
        A.reset();

        // Multiple repeats
        assertTrue(Arrays.equals(
                   new String[]{"OK"},
                   mParser.process("A/").toStrings()));
        assertTrue(A.mBasicCalled);
        assertEquals("", A.mBasicArgs);
        A.reset();

    }*/
}
