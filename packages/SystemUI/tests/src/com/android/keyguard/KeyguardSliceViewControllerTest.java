/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.keyguard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class KeyguardSliceViewControllerTest extends SysuiTestCase {
    @Mock
    private KeyguardSliceView mView;
    @Mock
    private TunerService mTunerService;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private ActivityStarter mActivityStarter;
    private DumpManager mDumpManager = new DumpManager();

    private KeyguardSliceViewController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mView.isAttachedToWindow()).thenReturn(true);
        when(mView.getContext()).thenReturn(mContext);
        mController = new KeyguardSliceViewController(
                mView, mActivityStarter, mConfigurationController,
                mTunerService, mDumpManager);
        mController.setupUri(KeyguardSliceProvider.KEYGUARD_SLICE_URI);
    }

    @Test
    public void refresh_replacesSliceContentAndNotifiesListener() {
        mController.refresh();
        verify(mView).hideSlice();
    }

    @Test
    public void onAttachedToWindow_registersListeners() {
        mController.init();
        verify(mTunerService).addTunable(any(TunerService.Tunable.class), anyString());
        verify(mConfigurationController).addCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }

    @Test
    public void onDetachedFromWindow_unregistersListeners() {
        ArgumentCaptor<View.OnAttachStateChangeListener> attachListenerArgumentCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

        mController.init();
        verify(mView).addOnAttachStateChangeListener(attachListenerArgumentCaptor.capture());

        attachListenerArgumentCaptor.getValue().onViewDetachedFromWindow(mView);

        verify(mTunerService).removeTunable(any(TunerService.Tunable.class));
        verify(mConfigurationController).removeCallback(
                any(ConfigurationController.ConfigurationListener.class));
    }
}
