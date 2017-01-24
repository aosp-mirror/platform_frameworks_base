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

public class NotificationPanelHiddenParserTest extends ParserTest {

    public NotificationPanelHiddenParserTest() {
        mParser = new NotificationPanelHiddenParser();
    }

    public void testNoInput() throws Throwable {
        int t = 1000;
        Object[] objects = new Object[0];

        validateGoodData(t, objects);

    }

    public void testIgnoreExtraneousInput() throws Throwable {
        Object[] objects = new Object[1];
        objects[0] = "nothing to see here";

        validateGoodData(0, objects);
    }

    private void validateGoodData(int t, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_PANEL, proto.getCategory());
        assertEquals(MetricsEvent.TYPE_CLOSE, proto.getType());
    }
}
