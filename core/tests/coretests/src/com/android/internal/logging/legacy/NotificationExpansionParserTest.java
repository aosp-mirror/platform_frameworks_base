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

public class NotificationExpansionParserTest extends ParserTest {

    public NotificationExpansionParserTest() {
        mParser = new NotificationExpansionParser();
    }

    public void testExpandedByUser() throws Throwable {
        int t = 1000;
        int byUser = 1;
        int expanded = 1;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;

        validateGoodData(t, "", objects);
    }

    public void testTagged() throws Throwable {
        int t = 1000;
        int byUser = 1;
        int expanded = 1;
        Object[] objects = new Object[3];
        objects[0] = mTaggedKey;
        objects[1] = byUser;
        objects[2] = expanded;

        validateGoodData(t, mTag, objects);
    }

    private LogMaker validateGoodData(int t, String tag, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_ITEM, proto.getCategory());
        assertEquals(mKeyPackage, proto.getPackageName());
        validateNotificationIdAndTag(proto, mId, tag);
        assertEquals(MetricsEvent.TYPE_DETAIL, proto.getType());
        return proto;
    }

    public void testAutoExpand() throws Throwable {
        int t = 1000;
        int byUser = 0;
        int expanded = 1;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testCollapsedByUser() throws Throwable {
        int t = 1000;
        int byUser = 1;
        int expanded = 0;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testAutoCollapsed() throws Throwable {
        int t = 1000;
        int byUser = 0;
        int expanded = 0;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testMissingData() throws Throwable {
        Object[] objects = new Object[0];

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testWrongType() throws Throwable {
        Object[] objects = new Object[3];
        objects[0] = 2;
        objects[1] = 5;
        objects[2] = 7;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testBadKey() throws Throwable {
        Object[] objects = new Object[3];
        objects[0] = "foo";
        objects[1] = 5;
        objects[2] = 2;

        mParser.parseEvent(mLogger, 0, objects);

        verify(mLogger, never()).addEvent((LogMaker) anyObject());
    }

    public void testMncTimestamps() throws Throwable {
        int t = 1000;
        int byUser = 1;
        int expanded = 1;
        Object[] objects = new Object[6];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;
        objects[3] = mSinceCreationMillis;
        objects[4] = mSinceUpdateMillis;
        objects[5] = mSinceVisibleMillis;

        LogMaker proto = validateGoodData(t, "", objects);
        validateNotificationTimes(proto, mSinceCreationMillis, mSinceUpdateMillis,
                mSinceVisibleMillis);
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        int byUser = 1;
        int expanded = 1;
        Object[] objects = new Object[7];
        objects[0] = mKey;
        objects[1] = byUser;
        objects[2] = expanded;
        objects[3] = mSinceCreationMillis;
        objects[4] = mSinceUpdateMillis;
        objects[5] = mSinceVisibleMillis;
        objects[6] = "foo";

        validateGoodData(t, "", objects);
    }
}
