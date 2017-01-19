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
package com.android.internal.logging.legacy;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


public class HistogramParserTest extends ParserTest {

    public HistogramParserTest() {
        mParser = new HistogramParser();
    }

    public void testGoodData() throws Throwable {
        String name = "foo";
        int bucket = 5;
        Object[] objects = new Object[2];
        objects[0] = name;
        objects[1] = bucket;

        validateGoodData(name, bucket, objects);
    }

    private void validateGoodData(String name, int bucket, Object[] objects) {
        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, times(1))
                .incrementIntHistogram(mNameCaptor.capture(), mCountCaptor.capture());

        assertEquals(TronCounters.TRON_AOSP_PREFIX + name, mNameCaptor.getValue());
        assertEquals(bucket, mCountCaptor.getValue().intValue());
    }

    public void testMissingName() throws Throwable {
        Object[] objects = new Object[1];
        objects[0] = 5;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testWrongTypes() throws Throwable {
        String name = "foo";
        int value = 5;
        Object[] objects = new Object[2];
        objects[0] = value;
        objects[1] = name;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreMissingInput() throws Throwable {
        Object[] objects = new Object[0];

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        String name = "foo";
        int bucket = 5;
        Object[] objects = new Object[3];
        objects[0] = name;
        objects[1] = bucket;
        objects[2] = "foo";

        validateGoodData(name, bucket, objects);
    }
}
