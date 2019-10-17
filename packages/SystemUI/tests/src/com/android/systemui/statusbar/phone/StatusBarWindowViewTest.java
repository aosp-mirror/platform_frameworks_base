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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;

import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class StatusBarWindowViewTest extends SysuiTestCase {

    private StatusBarWindowView mView;
    private StatusBarWindowViewController mController;

    @Mock private NotificationWakeUpCoordinator mCoordinator;
    @Mock private PulseExpansionHandler mPulseExpansionHandler;
    @Mock private DynamicPrivacyController mDynamicPrivacyController;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private PluginManager mPluginManager;
    @Mock private TunerService mTunerService;
    @Mock private DragDownHelper mDragDownHelper;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private ShadeController mShadeController;
    @Mock private NotificationLockscreenUserManager mNotificationLockScreenUserManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private StatusBar mStatusBar;
    @Mock private DozeLog mDozeLog;
    @Mock private DozeParameters mDozeParameters;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mView = new StatusBarWindowView(getContext(), null);
        mContext.putComponent(StatusBar.class, mStatusBar);
        when(mStatusBar.isDozing()).thenReturn(false);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);

        mController = new StatusBarWindowViewController.Builder(
                new InjectionInflationController(
                        SystemUIFactory.getInstance().getRootComponent()),
                mCoordinator,
                mPulseExpansionHandler,
                mDynamicPrivacyController,
                mBypassController,
                new FalsingManagerFake(),
                mPluginManager,
                mTunerService,
                mNotificationLockScreenUserManager,
                mNotificationEntryManager,
                mKeyguardStateController,
                mStatusBarStateController,
                mDozeLog,
                mDozeParameters)
                .setShadeController(mShadeController)
                .setStatusBarWindowView(mView)
                .build();
        mController.setService(mStatusBar);
        mController.setDragDownHelper(mDragDownHelper);

    }

    @Test
    public void testDragDownHelperCalledWhenDraggingDown() {
        when(mDragDownHelper.isDraggingDown()).thenReturn(true);
        long now = SystemClock.elapsedRealtime();
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0 /* x */, 0 /* y */,
                0 /* meta */);
        mView.onTouchEvent(ev);
        verify(mDragDownHelper).onTouchEvent(ev);
        ev.recycle();
    }
}
