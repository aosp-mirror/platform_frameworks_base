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
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class SysuiActionParserTest extends ParserTest {

    public SysuiActionParserTest() {
        mParser = new SysuiActionParser();
    }

    public void testGoodDatal() throws Throwable {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[1];
        objects[0] = view;

        validateGoodData(t, view, objects);
    }

    private void validateGoodData(int t, int view, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(MetricsEvent.TYPE_ACTION, proto.getType());
    }

    public void testGoodDataWithPackage() throws Throwable {
        int t = 1000;
        int view = 10;
        String packageName = "com.foo";
        Object[] objects = new Object[2];
        objects[0] = view;
        objects[1] = packageName;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(packageName, proto.getPackageName());
        assertEquals(MetricsEvent.TYPE_ACTION, proto.getType());
    }

    public void testGoodDataWithTrue() throws Throwable {
        validateSubType(Boolean.toString(true), 1);
    }

    public void testGoodDataWithFalse() throws Throwable {
        validateSubType(Boolean.toString(false), 0);
    }

    public void testGoodDataWithIntZero() throws Throwable {
        validateSubType(Integer.toString(0), 0);
    }

    public void testGoodDataWithIntONe() throws Throwable {
        validateSubType(Integer.toString(1), 1);
    }

    public void testGoodDataWithIntTwo() throws Throwable {
        validateSubType(Integer.toString(2), 2);
    }

    public void testGoodDataWithNegativeInt() throws Throwable {
        validateSubType(Integer.toString(-1), -1);
    }

    public void testGoodDataWithIntLarge() throws Throwable {
        validateSubType(Integer.toString(120312), 120312);
    }

    public void testGoodDataWithNegativeIntLarge() throws Throwable {
        validateSubType(Integer.toString(-120312), -120312);
    }

    private void validateSubType(String arg, int expectedValue) {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[2];
        objects[0] = view;
        objects[1] = arg;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(expectedValue, proto.getSubtype());
        assertNull(proto.getPackageName());
        assertEquals(MetricsEvent.TYPE_ACTION, proto.getType());
    }

    public void testIgnoreMissingInput() throws Throwable {
        Object[] objects = new Object[0];

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreWrongInputs() throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = "nothing to see here";
        objects[1] = 10;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreStringViewInput() throws Throwable {
        Object[] objects = new Object[1];
        objects[0] = "this is not the input you are looking for";

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[2];
        objects[0] = view;
        objects[1] = "foo";

        validateGoodData(t, view, objects);
    }
}
