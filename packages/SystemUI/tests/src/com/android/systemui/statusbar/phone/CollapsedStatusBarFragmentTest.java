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

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.view.View;
import android.view.ViewPropertyAnimator;

import com.android.systemui.FragmentTestCase;
import com.android.systemui.R;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class CollapsedStatusBarFragmentTest extends FragmentTestCase {

    private NotificationIconAreaController mMockNotificiationAreaController;
    private View mNotificationAreaInner;

    public CollapsedStatusBarFragmentTest() {
        super(CollapsedStatusBarFragment.class);
    }

    @Before
    public void setup() {
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.putComponent(TunerService.class, mock(TunerService.class));
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mMockNotificiationAreaController = mock(NotificationIconAreaController.class);
        mNotificationAreaInner = mock(View.class);
        when(mNotificationAreaInner.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mMockNotificiationAreaController.getNotificationInnerAreaView()).thenReturn(
                mNotificationAreaInner);
    }

    @Test
    public void testDisableNone() throws Exception {
        postAndWait(() -> mFragments.dispatchResume());

        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;
        fragment.initNotificationIconArea(mMockNotificiationAreaController);
        fragment.disable(0, 0, false);

        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());
    }

    @Test
    public void testDisableSystemInfo() throws Exception {
        postAndWait(() -> mFragments.dispatchResume());

        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;
        fragment.initNotificationIconArea(mMockNotificiationAreaController);
        fragment.disable(StatusBarManager.DISABLE_SYSTEM_INFO, 0, false);

        assertEquals(View.INVISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());

        fragment.disable(0, 0, false);

        assertEquals(View.VISIBLE, mFragment.getView().findViewById(R.id.system_icon_area)
                .getVisibility());
    }

    @Test
    public void testDisableNotifications() throws Exception {
        postAndWait(() -> mFragments.dispatchResume());

        CollapsedStatusBarFragment fragment = (CollapsedStatusBarFragment) mFragment;
        fragment.initNotificationIconArea(mMockNotificiationAreaController);
        fragment.disable(StatusBarManager.DISABLE_NOTIFICATION_ICONS, 0, false);

        Mockito.verify(mNotificationAreaInner).setVisibility(eq(View.INVISIBLE));

        fragment.disable(0, 0, false);

        Mockito.verify(mNotificationAreaInner).setVisibility(eq(View.VISIBLE));
    }
}
