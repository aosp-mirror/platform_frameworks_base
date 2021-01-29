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

package com.android.systemui.statusbar.phone;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.policy.AccessibilityController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class LockscreenIconControllerTest extends SysuiTestCase {
    @Mock
    private LockscreenGestureLogger mLockscreenGestureLogger;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private AccessibilityController mAccessibilityController;
    @Mock
    private KeyguardIndicationController mKeyguardIndicationController;
    @Mock
    private LockIcon mLockIcon;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private NotificationWakeUpCoordinator mNotificationWakeUpCoordinator;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private DockManager mDockManager;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private Resources mResources;
    @Mock
    private HeadsUpManagerPhone mHeadsUpManagerPhone;

    private LockscreenLockIconController mLockIconController;

    @Captor ArgumentCaptor<OnAttachStateChangeListener> mOnAttachStateChangeCaptor;
    @Captor ArgumentCaptor<StatusBarStateController.StateListener> mStateListenerCaptor;

    private OnAttachStateChangeListener mOnAttachStateChangeListener;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mKeyguardUpdateMonitor.canShowLockIcon()).thenReturn(true);
        when(mLockIcon.getContext()).thenReturn(mContext);
        mLockIconController = new LockscreenLockIconController(mLockIcon,
                mLockscreenGestureLogger, mKeyguardUpdateMonitor, mLockPatternUtils,
                mShadeController, mAccessibilityController, mKeyguardIndicationController,
                mStatusBarStateController, mConfigurationController, mNotificationWakeUpCoordinator,
                mKeyguardBypassController, mDockManager, mKeyguardStateController, mResources,
                mHeadsUpManagerPhone);

        when(mLockIcon.isAttachedToWindow()).thenReturn(true);
        mLockIconController.init();

        verify(mLockIcon).addOnAttachStateChangeListener(
                mOnAttachStateChangeCaptor.capture());
        mOnAttachStateChangeListener = mOnAttachStateChangeCaptor.getValue();
        verify(mStatusBarStateController).addCallback(mStateListenerCaptor.capture());
        mStatusBarStateListener = mStateListenerCaptor.getValue();
    }

    @Test
    public void lockIcon_click() {
        ArgumentCaptor<View.OnLongClickListener> longClickCaptor = ArgumentCaptor.forClass(
                View.OnLongClickListener.class);
        ArgumentCaptor<View.OnClickListener> clickCaptor = ArgumentCaptor.forClass(
                View.OnClickListener.class);

        // TODO: once we use a real LockIcon instead of a mock, remove all this.
        verify(mLockIcon).setOnLongClickListener(longClickCaptor.capture());
        verify(mLockIcon).setOnClickListener(clickCaptor.capture());

        when(mAccessibilityController.isAccessibilityEnabled()).thenReturn(true);
        clickCaptor.getValue().onClick(new View(mContext));
        verify(mShadeController).animateCollapsePanels(anyInt(), eq(true));

        longClickCaptor.getValue().onLongClick(new View(mContext));
        verify(mLockPatternUtils).requireCredentialEntry(anyInt());
        verify(mKeyguardUpdateMonitor).onLockIconPressed();
    }

    @Test
    public void testVisibility_Dozing() {
        when(mStatusBarStateController.isDozing()).thenReturn(true);
        mStatusBarStateListener.onDozingChanged(true);

        verify(mLockIcon).updateIconVisibility(false);
    }

    @Test
    public void testVisibility_doNotShowLockIcon() {
        when(mKeyguardUpdateMonitor.canShowLockIcon()).thenReturn(false);
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);

        verify(mLockIcon).setVisibility(View.GONE);
    }
}
