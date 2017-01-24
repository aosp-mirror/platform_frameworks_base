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

import static org.mockito.Mockito.anyObject;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class NotificationCanceledParserTest extends ParserTest {

    public NotificationCanceledParserTest() {
        mParser = new NotificationCanceledParser();
    }

    public void testGoodProto() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[2];
        objects[0] = mKey;
        objects[1] = reason;

        validateGoodData(t, "", reason, objects);
    }

    public void testTagged() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[2];
        objects[0] = mTaggedKey;
        objects[1] = reason;

        validateGoodData(t, mTag, reason, objects);
    }

    private void validateGoodData(int t, String tag, int reason, Object[] objects) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(MetricsEvent.NOTIFICATION_ITEM, proto.getCategory());
        assertEquals(mKeyPackage, proto.getPackageName());
        validateNotificationIdAndTag(proto, mId, tag);
        assertEquals(MetricsEvent.TYPE_DISMISS, proto.getType());
        assertEquals(reason, proto.getSubtype());
    }

    public void testLifetime() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = reason;
        objects[2] = mSinceCreationMillis;

        validateTimers(t, objects, mSinceCreationMillis, 0, 0);
    }

    public void testExposure() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[4];
        objects[0] = mKey;
        objects[1] = reason;
        objects[2] = mSinceCreationMillis;
        objects[3] = mSinceVisibleMillis;


        validateTimers(t, objects, mSinceCreationMillis, 0, mSinceVisibleMillis);
    }

    public void testFreshness() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[5];
        objects[0] = mKey;
        objects[1] = reason;
        objects[2] = mSinceCreationMillis;
        objects[3] = mSinceUpdateMillis;
        objects[4] = mSinceVisibleMillis;

        validateTimers(t, objects, mSinceCreationMillis, mSinceUpdateMillis, mSinceVisibleMillis);
    }

    private void validateTimers(int t, Object[] objects, int life, int freshness, int exposure) {
        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        validateNotificationTimes(proto, life, freshness, exposure);
    }

    public void verifyReason(int reason, boolean intentional, boolean important, String counter)
            throws Throwable {
        Object[] objects = new Object[2];
        objects[0] = mKey;
        objects[1] = reason;

        mParser.parseEvent(mLogger, 0, objects);

        if (intentional) {
            verify(mLogger, times(1)).addEvent((LogMaker) anyObject());
        }
    }

    public void testDelegateCancel() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_DELEGATE_CANCEL,
                true, true, TronCounters.TRON_NOTE_DISMISS_BY_USER);
    }

    public void testDelegateCancelAll() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_DELEGATE_CANCEL_ALL,
                true, true, TronCounters.TRON_NOTE_DISMISS_BY_USER);
    }

    public void testListenerCancel() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_LISTENER_CANCEL,
                false, true, TronCounters.TRON_NOTE_DISMISS_BY_LISTENER);
    }

    public void testListenerCancelAll() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_LISTENER_CANCEL_ALL,
                false, true, TronCounters.TRON_NOTE_DISMISS_BY_LISTENER);
    }

    public void testDelegateClick() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_DELEGATE_CLICK,
                true, true, TronCounters.TRON_NOTE_DISMISS_BY_CLICK);
    }

    public void testBanned() throws Throwable {
        verifyReason(NotificationCanceledParser.REASON_PACKAGE_BANNED,
                false, true, TronCounters.TRON_NOTE_DISMISS_BY_BAN);
    }

    public void testUnknownReason() throws Throwable {
        verifyReason(1001010, false, false, TronCounters.TRON_NOTE_DISMISS_BY_BAN);
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

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        int reason = NotificationCanceledParser.REASON_DELEGATE_CANCEL;
        Object[] objects = new Object[3];
        objects[0] = mKey;
        objects[1] = reason;
        objects[2] = "foo";

        validateGoodData(t, "", reason, objects);
    }
}
