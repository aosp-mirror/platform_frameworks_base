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

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import org.mockito.ArgumentCaptor;

public class NotificationAlertParserTest extends ParserTest {
    protected ArgumentCaptor<Boolean> mConfigCaptor;

    private final int mTime = 1000;

    public NotificationAlertParserTest() {
        mParser = new NotificationAlertParser();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mConfigCaptor = ArgumentCaptor.forClass(Boolean.class);
        when(mLogger.getConfig(anyString())).thenReturn(false);
    }

    public void testBuzzOnly() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 1;
        objects[2] = 0;
        objects[3] = 0;

        validateInteraction(true, false, false, objects);
    }

    public void testBeepOnly() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 0;
        objects[2] = 1;
        objects[3] = 0;

        validateInteraction(false, true, false, objects);
    }

    public void testBlinkOnly() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 0;
        objects[2] = 0;
        objects[3] = 1;

        validateInteraction(false, false, true, objects);
    }

    public void testBuzzBlink() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 1;
        objects[2] = 0;
        objects[3] = 1;

        validateInteraction(true, false, true, objects);
    }

    public void testBeepBlink() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 0;
        objects[2] = 1;
        objects[3] = 1;

        validateInteraction(false, true, true, objects);
    }

    public void testIgnoreExtraArgs() throws Throwable {
        Object[] objects = new Object[5];
        objects[0] = mTaggedKey;
        objects[1] = 0;
        objects[2] = 1;
        objects[3] = 1;
        objects[4] = "foo";

        validateInteraction(false, true, true, objects);
    }

    private void validateInteraction(boolean buzz, boolean beep, boolean blink, Object[] objects) {
        int flags = 0;
        int counts = 0;
        if (buzz) {
            counts++;
            flags |= NotificationAlertParser.BUZZ;
        }
        if (beep) {
            counts++;
            flags |= NotificationAlertParser.BEEP;
        }
        if (blink) {
            counts++;
            flags |= NotificationAlertParser.BLINK;
        }

        mParser.parseEvent(mLogger, mTime, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(mTime, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_ALERT, proto.getCategory());
        assertEquals(mKeyPackage, proto.getPackageName());
        validateNotificationIdAndTag(proto, mId, mTag);
        assertEquals(flags, proto.getSubtype());
        assertEquals(MetricsEvent.TYPE_OPEN, proto.getType());

    }
}
