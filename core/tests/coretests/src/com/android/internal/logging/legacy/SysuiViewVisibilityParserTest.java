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

public class SysuiViewVisibilityParserTest extends ParserTest {

    public SysuiViewVisibilityParserTest() {
        mParser = new SysuiViewVisibilityParser();
    }

    public void testViewReveal() throws Throwable {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[2];
        objects[0] = view;
        objects[1] = 100;

        validateViewReveal(t, view, objects);
    }

    private void validateViewReveal(int t, int view, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(MetricsEvent.TYPE_OPEN, proto.getType());
    }

    public void testViewHidden() throws Throwable {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[2];
        objects[0] = view;
        objects[1] = 0;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(MetricsEvent.TYPE_CLOSE, proto.getType());
    }

    public void testIgnoreMissingInput() throws Throwable {
        Object[] objects = new Object[0];

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreStringInARgOne() throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = "nothing to see here";
        objects[1] = 100;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreStringInArgTwo() throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = 100;
        objects[1] = "nothing to see here";

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testOneInput() throws Throwable {
        Object[] objects = new Object[1];
        objects[0] = 100;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
        verify(mLogger, never()).incrementBy(anyString(), anyInt());
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        int view = 10;
        Object[] objects = new Object[3];
        objects[0] = view;
        objects[1] = 100;
        objects[2] = "foo";

        validateViewReveal(t, view, objects);
    }
}
