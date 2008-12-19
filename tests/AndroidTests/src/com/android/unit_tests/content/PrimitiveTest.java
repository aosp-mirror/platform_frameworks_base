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

package com.android.unit_tests.content;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.TypedValue;
import com.android.unit_tests.R;

public class PrimitiveTest extends AndroidTestCase {
    private Resources mResources;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResources = mContext.getResources();
    }

    private void tryEnum(int resid, int expected) {
        TypedArray sa =
                mContext.obtainStyledAttributes(resid, R.styleable.EnumStyle);
        int value = sa.getInt(R.styleable.EnumStyle_testEnum, -1);
        sa.recycle();

        assertEquals("Expecting value " + expected + " got " + value
                + ": in resource 0x" + Integer.toHexString(resid),
                expected, value);
    }

    @SmallTest
    public void testEnum() throws Exception {
        tryEnum(R.style.TestEnum1, 1);
        tryEnum(R.style.TestEnum2, 2);
        tryEnum(R.style.TestEnum10, 10);
        tryEnum(R.style.TestEnum1_EmptyInherit, 1);
    }

    private void tryFlag(int resid, int expected) {
        TypedArray sa =
                mContext.obtainStyledAttributes(resid, R.styleable.FlagStyle);
        int value = sa.getInt(R.styleable.FlagStyle_testFlags, -1);
        sa.recycle();

        assertEquals("Expecting value " + expected + " got " + value
                + ": in resource 0x" + Integer.toHexString(resid),
                expected, value);
    }

    @SmallTest
    public void testFlags() throws Exception {
        tryFlag(R.style.TestFlag1, 0x1);
        tryFlag(R.style.TestFlag2, 0x2);
        tryFlag(R.style.TestFlag31, 0x40000000);
        tryFlag(R.style.TestFlag1And2, 0x3);
        tryFlag(R.style.TestFlag1And2And31, 0x40000003);
    }

    private void tryBoolean(int resid, boolean expected) {
        TypedValue v = new TypedValue();
        mContext.getResources().getValue(resid, v, true);
        assertEquals(TypedValue.TYPE_INT_BOOLEAN, v.type);
        assertEquals("Expecting boolean value " + expected + " got " + v
                + " from TypedValue: in resource 0x" + Integer.toHexString(resid),
                expected, v.data != 0);
        assertEquals("Expecting boolean value " + expected + " got " + v
                + " from getBoolean(): in resource 0x" + Integer.toHexString(resid),
                expected, mContext.getResources().getBoolean(resid));
    }

    @SmallTest
    public void testBoolean() throws Exception {
        tryBoolean(R.bool.trueRes, true);
        tryBoolean(R.bool.falseRes, false);
    }

    private void tryString(int resid, String expected) {
        TypedValue v = new TypedValue();
        mContext.getResources().getValue(resid, v, true);
        assertEquals(TypedValue.TYPE_STRING, v.type);
        assertEquals("Expecting string value " + expected + " got " + v
                + ": in resource 0x" + Integer.toHexString(resid),
                expected, v.string);
    }

    @SmallTest
    public void testStringCoerce() throws Exception {
        tryString(R.string.coerceIntegerToString, "100");
        tryString(R.string.coerceBooleanToString, "true");
        tryString(R.string.coerceColorToString, "#fff");
        tryString(R.string.coerceFloatToString, "100.0");
        tryString(R.string.coerceDimensionToString, "100px");
        tryString(R.string.coerceFractionToString, "100%");
    }

    private static void checkString(int resid, String actual, String expected) {
        assertEquals("Expecting string value \"" + expected + "\" got \""
                + actual + "\" in resources 0x" + Integer.toHexString(resid),
                expected, actual);
    }

    @SmallTest
    public void testFormattedString() throws Exception {
        // Make sure the regular one doesn't format anything
        checkString(R.string.formattedStringNone,
                mResources.getString(R.string.formattedStringNone),
                "Format[]");
        checkString(R.string.formattedStringOne,
                mResources.getString(R.string.formattedStringOne),
                "Format[%d]");
        checkString(R.string.formattedStringTwo,
                mResources.getString(R.string.formattedStringTwo),
                "Format[%3$d,%2$s]");
        // Make sure the formatted one works
        checkString(R.string.formattedStringNone,
                mResources.getString(R.string.formattedStringNone),
                "Format[]");
        checkString(R.string.formattedStringOne,
                mResources.getString(R.string.formattedStringOne, 42),
                "Format[42]");
        checkString(R.string.formattedStringTwo,
                mResources.getString(R.string.formattedStringTwo, "unused", "hi", 43),
                "Format[43,hi]");
    }
}

