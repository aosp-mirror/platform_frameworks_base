/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.SmartActionsReceiver.EXTRA_ACTION_TYPE;
import static com.android.systemui.screenshot.SmartActionsReceiver.EXTRA_ID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SmartActionsReceiverTest extends SysuiTestCase {

    @Mock
    private ScreenshotSmartActions mMockScreenshotSmartActions;
    @Mock
    private PendingIntent mMockPendingIntent;

    private SmartActionsReceiver mSmartActionsReceiver;
    private Intent mIntent;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSmartActionsReceiver = new SmartActionsReceiver(mMockScreenshotSmartActions);
        mIntent = new Intent(mContext, SmartActionsReceiver.class)
                .putExtra(SmartActionsReceiver.EXTRA_ACTION_INTENT, mMockPendingIntent);
    }

    @Test
    public void testSmartActionIntent() throws PendingIntent.CanceledException {
        String testId = "testID";
        String testActionType = "testActionType";
        mIntent.putExtra(EXTRA_ID, testId);
        mIntent.putExtra(EXTRA_ACTION_TYPE, testActionType);
        Intent intent = new Intent();
        when(mMockPendingIntent.getIntent()).thenReturn(intent);

        mSmartActionsReceiver.onReceive(mContext, mIntent);

        verify(mMockPendingIntent).send(
                eq(mContext), eq(0), isNull(), isNull(), isNull(), isNull(), any(Bundle.class));
        verify(mMockScreenshotSmartActions).notifyScreenshotAction(
                testId, testActionType, true, intent);
    }
}
