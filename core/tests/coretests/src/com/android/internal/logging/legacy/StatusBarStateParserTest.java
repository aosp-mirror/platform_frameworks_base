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

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class StatusBarStateParserTest extends ParserTest {

    public StatusBarStateParserTest() {
        mParser = new StatusBarStateParser();
    }

    public void testLockScreen() throws Throwable {
        validate(MetricsEvent.LOCKSCREEN, MetricsEvent.TYPE_OPEN, 1, "1,1,0,0,1,0");
    }

    public void testBounce() throws Throwable {
        validate(MetricsEvent.BOUNCER, MetricsEvent.TYPE_OPEN, 1, "1,1,0,1,1,0");
    }

    public void testUnlock() throws Throwable {
        validate(MetricsEvent.LOCKSCREEN, MetricsEvent.TYPE_CLOSE, 1, "0,0,0,0,1,0");
    }

    public void testSecure() throws Throwable {
        validate(MetricsEvent.BOUNCER, MetricsEvent.TYPE_OPEN, 1, "2,1,0,1,1,0");
    }

    public void testInsecure() throws Throwable {
        validate(MetricsEvent.LOCKSCREEN, MetricsEvent.TYPE_OPEN, 0, "1,1,0,0,0,0");
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        validate(MetricsEvent.LOCKSCREEN, MetricsEvent.TYPE_OPEN, 0, "1,1,0,0,0,0,5");
    }

    private void validate(int view, int type, int subType, String log) {
        String[] parts = log.split(",");
        int t = 1000;
        Object[] objects = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) {
            objects[i] = Integer.valueOf(parts[i]);
        }

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(type, proto.getType());
        assertEquals(subType, proto.getSubtype());
    }
}
