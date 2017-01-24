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

import static org.mockito.Matchers.anyObject;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class NotificationActionClickedParserTest extends ParserTest {

    public NotificationActionClickedParserTest() {
        mParser = new NotificationActionClickedParser();
    }

    public void testGoodData() throws Throwable {
        int t = 1000;
        int index = 1;
        Object[] objects = new Object[2];
        objects[0] = mKey;
        objects[1] = index;

        validateGoodData(t, "", index, objects);
    }

    public void testTagged() throws Throwable {
        int t = 1000;
        int index = 1;
        Object[] objects = new Object[2];
        objects[0] = mTaggedKey;
        objects[1] = index;

        validateGoodData(t, mTag, index, objects);
    }

    private LogMaker validateGoodData(int t, String tag, int index, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_ITEM_ACTION, proto.getCategory());
        assertEquals(mKeyPackage, proto.getPackageName());
        validateNotificationIdAndTag(proto, mId, tag);
        assertEquals(MetricsEvent.TYPE_ACTION, proto.getType());
        assertEquals(index, proto.getSubtype());
        return proto;
    }

    public void testMissingData() throws Throwable {
        Object[] objects = new Object[0];

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testWrongType() throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = 2;
        objects[1] = 5;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testBadKey() throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = "foo";
        objects[1] = 5;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testMncTimestamps() throws Throwable {
        int t = 1000;
        int index = 1;
        Object[] objects = new Object[5];
        objects[0] = mKey;
        objects[1] = index;
        objects[2] = mSinceCreationMillis;
        objects[3] = mSinceUpdateMillis;
        objects[4] = mSinceVisibleMillis;

        LogMaker proto = validateGoodData(t, "", index, objects);
        validateNotificationTimes(proto, mSinceCreationMillis, mSinceUpdateMillis,
                mSinceVisibleMillis);
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        int index = 1;
        Object[] objects = new Object[6];
        objects[0] = mKey;
        objects[1] = index;
        objects[2] = mSinceCreationMillis;
        objects[3] = mSinceUpdateMillis;
        objects[4] = mSinceVisibleMillis;
        objects[5] = "foo";

        validateGoodData(t, "", index, objects);
    }
}
