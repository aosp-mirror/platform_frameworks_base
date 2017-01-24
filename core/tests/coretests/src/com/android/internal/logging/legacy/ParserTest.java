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

import android.metrics.LogMaker;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import junit.framework.TestCase;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.OngoingStubbing;

/**
 * Common functions and temporaries for parser tests.
 */
public class ParserTest extends TestCase {
    @Mock
    protected TronLogger mLogger;

    protected TagParser mParser;

    protected LogMaker[] mProto;
    protected ArgumentCaptor<LogMaker> mProtoCaptor;
    protected ArgumentCaptor<String> mNameCaptor;
    protected ArgumentCaptor<Integer> mCountCaptor;
    protected String mKey = "0|com.android.example.notificationshowcase|31338|null|10090";
    protected String mTaggedKey = "0|com.android.example.notificationshowcase|31338|badger|10090";
    protected String mKeyPackage = "com.android.example.notificationshowcase";
    protected String mTag = "badger";
    protected int mId = 31338;
    protected int mSinceCreationMillis = 5000;
    protected int mSinceUpdateMillis = 1012;
    protected int mSinceVisibleMillis = 323;


    public ParserTest() {
        mProto = new LogMaker[5];
        for (int i = 0; i < mProto.length; i++) {
            mProto[i] = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        MockitoAnnotations.initMocks(this);

        mProtoCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mNameCaptor = ArgumentCaptor.forClass(String.class);
        mCountCaptor = ArgumentCaptor.forClass(Integer.class);

        OngoingStubbing<LogMaker> stub = when(mLogger.obtain()).thenReturn(mProto[0]);
        for (int i = 1; i < mProto.length; i++) {
            stub.thenReturn(mProto[i]);
        }
        doNothing().when(mLogger).addEvent(any(LogMaker.class));
        doNothing().when(mLogger).incrementBy(anyString(), anyInt());
    }

    protected void validateNotificationTimes(LogMaker proto, int life, int freshness,
            int exposure) {
        validateNotificationTimes(proto, life, freshness);
        if (exposure != 0) {
            assertEquals(exposure,
                proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS));
        } else {
            assertNull(proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_VISIBLE_MILLIS));
        }
    }

    protected void validateNotificationTimes(LogMaker proto, int life, int freshness) {
        if (life != 0) {
            assertEquals(life,
                proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS));
        } else {
            assertNull(proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_CREATE_MILLIS));
        }
        if (freshness != 0) {
            assertEquals(freshness,
                proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS));
        } else {
            assertNull(proto.getTaggedData(MetricsEvent.NOTIFICATION_SINCE_UPDATE_MILLIS));
        }
    }

    protected void validateNotificationIdAndTag(LogMaker proto, int id, String tag) {
        assertEquals(tag, proto.getTaggedData(MetricsEvent.NOTIFICATION_TAG));
        assertEquals(id, proto.getTaggedData(MetricsEvent.NOTIFICATION_ID));
    }
}
