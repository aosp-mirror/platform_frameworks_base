/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_NUM_STATUS_ICONS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_STATUS_ICONS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent
        .RESERVED_FOR_LOGBUILDER_LATENCY_MILLIS;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.lang.Thread.sleep;

import android.metrics.LogMaker;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.MessageHandler;
import android.testing.TestableLooper.RunWithLooper;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class IconLoggerImplTest extends SysuiTestCase {

    private MetricsLogger mMetricsLogger;
    private IconLoggerImpl mIconLogger;
    private TestableLooper mTestableLooper;
    private MessageHandler mMessageHandler;

    @Before
    public void setup() {
        IconLoggerImpl.MIN_LOG_INTERVAL = 5; // Low interval for testing
        mMetricsLogger = mock(MetricsLogger.class);
        mTestableLooper = TestableLooper.get(this);
        mMessageHandler = mock(MessageHandler.class);
        mTestableLooper.setMessageHandler(mMessageHandler);
        String[] iconArray = new String[] {
                "test_icon_1",
                "test_icon_2",
        };
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.array.config_statusBarIcons, iconArray);
        mIconLogger = new IconLoggerImpl(mContext, mTestableLooper.getLooper(), mMetricsLogger);
        when(mMessageHandler.onMessageHandled(any())).thenReturn(true);
        clearInvocations(mMetricsLogger);
    }

    @Test
    public void testIconShown() throws InterruptedException {
        // Should only get one message, for the same icon shown twice.
        mIconLogger.onIconShown("test_icon_2");
        mIconLogger.onIconShown("test_icon_2");

        // There should be some delay before execute.
        mTestableLooper.processAllMessages();
        verify(mMessageHandler, never()).onMessageHandled(any());

        sleep(10);
        mTestableLooper.processAllMessages();
        verify(mMessageHandler, times(1)).onMessageHandled(any());
    }

    @Test
    public void testIconHidden() throws InterruptedException {
        // Add the icon so that it can be removed.
        mIconLogger.onIconShown("test_icon_2");
        sleep(10);
        mTestableLooper.processAllMessages();
        clearInvocations(mMessageHandler);

        // Should only get one message, for the same icon shown twice.
        mIconLogger.onIconHidden("test_icon_2");
        mIconLogger.onIconHidden("test_icon_2");

        // There should be some delay before execute.
        mTestableLooper.processAllMessages();
        verify(mMessageHandler, never()).onMessageHandled(any());

        sleep(10);
        mTestableLooper.processAllMessages();
        verify(mMessageHandler, times(1)).onMessageHandled(any());
    }

    @Test
    public void testLog() throws InterruptedException {
        mIconLogger.onIconShown("test_icon_2");
        sleep(10);
        mTestableLooper.processAllMessages();

        verify(mMetricsLogger).write(argThat(maker -> {
            if (IconLoggerImpl.MIN_LOG_INTERVAL >
                    (long) maker.getTaggedData(RESERVED_FOR_LOGBUILDER_LATENCY_MILLIS)) {
                Log.e("IconLoggerImplTest", "Invalid latency "
                        + maker.getTaggedData(RESERVED_FOR_LOGBUILDER_LATENCY_MILLIS));
                return false;
            }
            if (1 != (int) maker.getTaggedData(FIELD_NUM_STATUS_ICONS)) {
                Log.e("IconLoggerImplTest", "Invalid icon count "
                        + maker.getTaggedData(FIELD_NUM_STATUS_ICONS));
                return false;
            }
            return true;
        }));
    }

    @Test
    public void testBitField() throws InterruptedException {
        mIconLogger.onIconShown("test_icon_2");
        sleep(10);
        mTestableLooper.processAllMessages();

        verify(mMetricsLogger).write(argThat(maker -> {
            if ((1 << 1) != (int) maker.getTaggedData(FIELD_STATUS_ICONS)) {
                Log.e("IconLoggerImplTest", "Invalid bitfield " + Integer.toHexString(
                        (Integer) maker.getTaggedData(FIELD_NUM_STATUS_ICONS)));
                return false;
            }
            return true;
        }));

        mIconLogger.onIconShown("test_icon_1");
        sleep(10);
        mTestableLooper.processAllMessages();

        verify(mMetricsLogger).write(argThat(maker -> {
            if ((1 << 1 | 1 << 0) != (int) maker.getTaggedData(FIELD_STATUS_ICONS)) {
                Log.e("IconLoggerImplTest", "Invalid bitfield " + Integer.toHexString(
                        (Integer) maker.getTaggedData(FIELD_NUM_STATUS_ICONS)));
                return false;
            }
            return true;
        }));

        mIconLogger.onIconHidden("test_icon_2");
        sleep(10);
        mTestableLooper.processAllMessages();

        verify(mMetricsLogger).write(argThat(maker -> {
            if ((1 << 0) != (int) maker.getTaggedData(FIELD_STATUS_ICONS)) {
                Log.e("IconLoggerImplTest", "Invalid bitfield " + Integer.toHexString(
                        (Integer) maker.getTaggedData(FIELD_STATUS_ICONS)));
                return false;
            }
            return true;
        }));
    }
}
