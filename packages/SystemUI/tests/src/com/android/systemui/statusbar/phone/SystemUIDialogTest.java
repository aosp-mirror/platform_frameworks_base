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

package com.android.systemui.statusbar.phone;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class SystemUIDialogTest extends SysuiTestCase {

    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mDependency.injectTestDependency(FeatureFlags.class, mFeatureFlags);
        mDependency.injectTestDependency(BroadcastDispatcher.class, mBroadcastDispatcher);
    }

    @Test
    public void testRegisterReceiver() {
        final SystemUIDialog dialog = new SystemUIDialog(mContext);
        final ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        dialog.show();
        verify(mBroadcastDispatcher).registerReceiver(broadcastReceiverCaptor.capture(),
                intentFilterCaptor.capture(), eq(null), any());
        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_SCREEN_OFF));
        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

        dialog.dismiss();
        verify(mBroadcastDispatcher).unregisterReceiver(eq(broadcastReceiverCaptor.getValue()));
    }


    @Test
    public void testNoRegisterReceiver() {
        final SystemUIDialog dialog = new SystemUIDialog(mContext, false);

        dialog.show();
        verify(mBroadcastDispatcher, never()).registerReceiver(any(), any(), eq(null), any());
        assertTrue(dialog.isShowing());

        dialog.dismiss();
        verify(mBroadcastDispatcher, never()).unregisterReceiver(any());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void usePredictiveBackAnimFlag() {
        when(mFeatureFlags.isEnabled(Flags.WM_ENABLE_PREDICTIVE_BACK_QS_DIALOG_ANIM))
                .thenReturn(true);
        final SystemUIDialog dialog = new SystemUIDialog(mContext);

        dialog.show();

        assertTrue(dialog.isShowing());
        verify(mFeatureFlags, atLeast(1))
                .isEnabled(Flags.WM_ENABLE_PREDICTIVE_BACK_QS_DIALOG_ANIM);

        dialog.dismiss();
        assertFalse(dialog.isShowing());
    }
}
