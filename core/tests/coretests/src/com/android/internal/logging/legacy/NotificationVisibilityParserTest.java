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

import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class NotificationVisibilityParserTest extends ParserTest {
    private final int mCreationTime = 23124;
    private final int mUpdateTime = 3412;
    private final int mTime = 1000;

    public NotificationVisibilityParserTest() {
        mParser = new NotificationVisibilityParser();
    }

    public void testReveal() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 1;
        objects[2] = mCreationTime;
        objects[3] = mUpdateTime;

        validateInteraction(true, mUpdateTime, 0, objects);
    }

    public void testHide() throws Throwable {
        Object[] objects = new Object[4];
        objects[0] = mTaggedKey;
        objects[1] = 0;
        objects[2] = mCreationTime;
        objects[3] = mUpdateTime;

        validateInteraction(false, mUpdateTime, 0, objects);
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        Object[] objects = new Object[5];
        objects[0] = mTaggedKey;
        objects[1] = 1;
        objects[2] = mCreationTime;
        objects[3] = mUpdateTime;
        objects[4] = "foo";

        validateInteraction(true, mUpdateTime, 0, objects);
    }

    public void testMarshmallowIndexData() throws Throwable {
        Object[] objects = new Object[6];
        objects[0] = mTaggedKey;
        objects[1] = 1;
        objects[2] = mCreationTime;
        objects[3] = mUpdateTime;
        objects[4] = 0;
        objects[5] = 3;

        validateInteraction(true, mUpdateTime, 3, objects);
    }

    private void validateInteraction(boolean visible, int freshness, int index, Object[] objects)    {
        mParser.parseEvent(mLogger, mTime, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(mTime, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_ITEM, proto.getCategory());
        assertEquals(mKeyPackage, proto.getPackageName());
        validateNotificationIdAndTag(proto, mId, mTag);
        validateNotificationTimes(proto, mCreationTime, mUpdateTime);
        assertEquals(index, proto.getTaggedData(MetricsEvent.NOTIFICATION_SHADE_INDEX));
        assertEquals(visible ? MetricsEvent.TYPE_OPEN : MetricsEvent.TYPE_CLOSE, proto.getType());
    }
}
