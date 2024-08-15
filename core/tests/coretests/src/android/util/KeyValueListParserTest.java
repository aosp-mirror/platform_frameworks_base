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

package android.util;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link KeyValueListParser}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class KeyValueListParserTest {
    private static final String TAG = "KeyValueListParserTest";
    private static final int[] DEFAULT = {1, 2, 3, 4};

    private KeyValueListParser mParser;

    @Before
    public void setUp() {
        mParser = new KeyValueListParser(',');
    }

    @Test
    public void testParseIntArrayNullInput() throws Exception {
        mParser.setString(null);
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(DEFAULT, result);
    }

    @Test
    public void testParseIntArrayEmptyInput() throws Exception {
        mParser.setString("test=");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(DEFAULT, result);
    }

    @Test
    public void testParseIntArrayNullKey() throws Exception {
        mParser.setString("foo=bar,test=100:200,baz=123");
        int[] result = mParser.getIntArray(null, DEFAULT);
        assertEquals(DEFAULT, result);
    }

    @Test
    public void testParseIntArrayComplexInput() throws Exception {
        mParser.setString("foo=bar,test=100:200,baz=123");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(2, result.length);
        assertEquals(100, result[0]);  // respect order
        assertEquals(200, result[1]);
    }

    @Test
    public void testParseIntArrayLeadingSep() throws Exception {
        mParser.setString("test=:4:5:6");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(DEFAULT, result);
    }

    @Test
    public void testParseIntArrayEmptyItem() throws Exception {
        mParser.setString("test=:4::6");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(DEFAULT, result);
    }

    @Test
    public void testParseIntArrayTrailingSep() throws Exception {
        mParser.setString("test=4:5:6:");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(3, result.length);
        assertEquals(4, result[0]);  // respect order
        assertEquals(5, result[1]);
        assertEquals(6, result[2]);
    }

    @Test
    public void testParseIntArrayGoodData() throws Exception {
        mParser.setString("test=4:5:6");
        int[] result = mParser.getIntArray("test", DEFAULT);
        assertEquals(3, result.length);
        assertEquals(4, result[0]);  // respect order
        assertEquals(5, result[1]);
        assertEquals(6, result[2]);
    }
}
