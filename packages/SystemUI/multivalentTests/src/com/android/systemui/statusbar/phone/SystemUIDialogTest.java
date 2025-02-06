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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_DIALOG_SHOWING;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.ravenwood.RavenwoodRule;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.back.BackAnimationSpec;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.model.SysUiState;
import com.android.systemui.settings.FakeDisplayTracker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
@RunWithLooper
@SmallTest
public class SystemUIDialogTest extends SysuiTestCase {

    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private SystemUIDialog.Delegate mDelegate;
    private SysUiState mSysUiState;

    // TODO(b/292141694): build out Ravenwood support for DeviceFlagsValueProvider
    // Ravenwood already has solid support for SetFlagsRule, but CheckFlagsRule will be added soon
    @Rule
    public final CheckFlagsRule mCheckFlagsRule = RavenwoodRule.isOnRavenwood() ? null
            : DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        KosmosJavaAdapter kosmos = new KosmosJavaAdapter(this);
        FakeDisplayTracker displayTracker = new FakeDisplayTracker(mContext);
        mSysUiState = new SysUiState(displayTracker, kosmos.getSceneContainerPlugin());
        mDependency.injectTestDependency(BroadcastDispatcher.class, mBroadcastDispatcher);
        when(mDelegate.getBackAnimationSpec(ArgumentMatchers.any()))
                .thenReturn(mock(BackAnimationSpec.class));
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
                intentFilterCaptor.capture(), ArgumentMatchers.eq(null), ArgumentMatchers.any());
        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_SCREEN_OFF));
        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));

        dialog.dismiss();
        verify(mBroadcastDispatcher).unregisterReceiver(
                ArgumentMatchers.eq(broadcastReceiverCaptor.getValue()));
    }


    @Test
    public void testNoRegisterReceiver() {
        final SystemUIDialog dialog = new SystemUIDialog(mContext, 0, false);

        dialog.show();
        verify(mBroadcastDispatcher, never()).registerReceiver(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.eq(null), ArgumentMatchers.any());
        assertTrue(dialog.isShowing());

        dialog.dismiss();
        verify(mBroadcastDispatcher, never()).unregisterReceiver(ArgumentMatchers.any());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void testRegisterReceiverWithoutAcsd() {
        SystemUIDialog dialog = createDialogWithDelegate(mContext, mDelegate,
                false /* shouldAcsdDismissDialog */);
        final ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        final ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        dialog.show();
        verify(mBroadcastDispatcher).registerReceiver(broadcastReceiverCaptor.capture(),
                intentFilterCaptor.capture(), ArgumentMatchers.eq(null), ArgumentMatchers.any());
        assertTrue(intentFilterCaptor.getValue().hasAction(Intent.ACTION_SCREEN_OFF));
        assertFalse(intentFilterCaptor.getValue().hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    @Test
    public void usePredictiveBackAnimFlag() {
        final SystemUIDialog dialog = new SystemUIDialog(mContext);

        dialog.show();

        assertTrue(dialog.isShowing());

        dialog.dismiss();
        assertFalse(dialog.isShowing());
    }

    @Test public void startAndStopAreCalled() {
        AtomicBoolean calledStart = new AtomicBoolean(false);
        AtomicBoolean calledStop = new AtomicBoolean(false);
        SystemUIDialog dialog = new SystemUIDialog(mContext) {
            @Override
            protected void start() {
                calledStart.set(true);
            }

            @Override
            protected void stop() {
                calledStop.set(true);
            }
        };

        assertThat(calledStart.get()).isFalse();
        assertThat(calledStop.get()).isFalse();

        dialog.show();
        assertThat(calledStart.get()).isTrue();
        assertThat(calledStop.get()).isFalse();

        dialog.dismiss();
        assertThat(calledStart.get()).isTrue();
        assertThat(calledStop.get()).isTrue();
    }

    /** Regression test for b/386871258 */
    @Test
    public void sysuiStateUpdated() {
        SystemUIDialog dialog1 =
                createDialogWithDelegate(mContext, mDelegate, /* shouldAcsDismissDialog */ true);
        SystemUIDialog dialog2 =
                createDialogWithDelegate(mContext, mDelegate, /* shouldAcsDismissDialog */ true);

        dialog1.show();
        assertThat((mSysUiState.getFlags() & SYSUI_STATE_DIALOG_SHOWING) != 0).isTrue();

        dialog2.show();
        assertThat((mSysUiState.getFlags() & SYSUI_STATE_DIALOG_SHOWING) != 0).isTrue();

        dialog2.dismiss();
        // explicitly call onWindowFocusChanged to simulate dialog 1 regaining focus
        dialog1.onWindowFocusChanged(/* hasFocus= */ true);
        assertThat((mSysUiState.getFlags() & SYSUI_STATE_DIALOG_SHOWING) != 0).isTrue();

        dialog1.dismiss();
        assertThat((mSysUiState.getFlags() & SYSUI_STATE_DIALOG_SHOWING) != 0).isFalse();
    }


    @Test
    public void delegateIsCalled_inCorrectOrder() {
        Configuration configuration = new Configuration();
        InOrder inOrder = Mockito.inOrder(mDelegate);
        SystemUIDialog dialog = createDialogWithDelegate(mContext, mDelegate,
                true /* shouldAcsdDismissDialog */);

        dialog.show();
        dialog.onWindowFocusChanged(/* hasFocus= */ true);
        dialog.onConfigurationChanged(configuration);
        dialog.dismiss();

        inOrder.verify(mDelegate).beforeCreate(dialog, /* savedInstanceState= */ null);
        inOrder.verify(mDelegate).onCreate(dialog, /* savedInstanceState= */ null);
        inOrder.verify(mDelegate).onStart(dialog);
        inOrder.verify(mDelegate).onWindowFocusChanged(dialog, /* hasFocus= */ true);
        inOrder.verify(mDelegate).onConfigurationChanged(dialog, configuration);
        inOrder.verify(mDelegate).onStop(dialog);
    }

    private SystemUIDialog createDialogWithDelegate(Context context,
            SystemUIDialog.Delegate delegate, boolean shouldAcsdDismissDialog) {
        SystemUIDialog.Factory factory = new SystemUIDialog.Factory(
                getContext(),
                Dependency.get(SystemUIDialogManager.class),
                mSysUiState,
                Dependency.get(BroadcastDispatcher.class),
                Dependency.get(DialogTransitionAnimator.class)
        );
        return factory.create(delegate, context, shouldAcsdDismissDialog);
    }
}
