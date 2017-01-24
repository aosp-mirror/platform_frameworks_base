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

public class PowerScreenStateParserTest extends ParserTest {

    public PowerScreenStateParserTest() {
        mParser = new PowerScreenStateParser();
    }

    public void testScreenOn() throws Throwable {
        validate(MetricsEvent.TYPE_OPEN, 0, "1,0,0,0");
    }

    public void testTimeout() throws Throwable {
        validate(MetricsEvent.TYPE_CLOSE, 3, "0,3,0,0");
    }

    public void testUser() throws Throwable {
        validate(MetricsEvent.TYPE_CLOSE, 2, "0,2,0,0");
    }

    public void testAdmin() throws Throwable {
        validate(MetricsEvent.TYPE_CLOSE, 1, "0,1,0,0");
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        validate(MetricsEvent.TYPE_OPEN, 0, "1,0,0,0,5");
    }

    private void validate(int type, int subType, String log) {
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
        assertEquals(type, proto.getType());
        assertEquals(MetricsEvent.SCREEN, proto.getCategory());
        assertEquals(subType, proto.getSubtype());
    }
}
