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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class LockscreenGestureParserTest extends ParserTest {

    public LockscreenGestureParserTest() {
        mParser = new LockscreenGestureParser();
    }

    public void testSwipeUpUnlock() throws Throwable {
        validate(MetricsEvent.ACTION_LS_UNLOCK, 1, 359, 6382);
    }

    public void testSwipeToShade() throws Throwable {
        validate(MetricsEvent.ACTION_LS_SHADE, 2, 324, 0);
    }

    public void testTapLockHint() throws Throwable {
        validate(MetricsEvent.ACTION_LS_HINT, 3, 0, 0);
    }

    public void testCamera() throws Throwable {
        validate(MetricsEvent.ACTION_LS_CAMERA, 4, 223, 1756);
    }

    public void testDialer() throws Throwable {
        validate(MetricsEvent.ACTION_LS_DIALER, 5, 163, 861);
    }

    public void testTapToLock() throws Throwable {
        validate(MetricsEvent.ACTION_LS_LOCK, 6, 0, 0);
    }

    public void testTapOnNotification() throws Throwable {
        validate(MetricsEvent.ACTION_LS_NOTE, 7, 0, 0);
    }

    public void testLockscreenQuickSettings() throws Throwable {
        validate(MetricsEvent.ACTION_LS_QS, 8, 284, 3824);
    }

    public void testShadePullQuickSettings() throws Throwable {
        validate(MetricsEvent.ACTION_SHADE_QS_PULL, 9, 175, 3444);
    }

    public void testShadeTapQuickSettings() throws Throwable {
        validate(MetricsEvent.ACTION_SHADE_QS_TAP, 10, 0, 0);
    }

    private void validate(int view, int type, int len, int vel) {
        int t = 1000;
        Object[] objects = new Object[3];
        objects[0] = type;
        objects[1] = len;
        objects[2] = vel;

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(t, proto.getTimestamp());
        assertEquals(view, proto.getCategory());
        assertEquals(MetricsEvent.TYPE_ACTION, proto.getType());
    }

    public void testIgnoreUnexpectedData() throws Throwable {
        int t = 1000;
        Object[] objects = new Object[4];
        objects[0] = 1;
        objects[1] = 0;
        objects[2] = 0;
        objects[3] = "foo";

        mParser.parseEvent(mLogger, t, objects);

        verify(mLogger, times(1)).addEvent((LogMaker) anyObject());
    }
}
