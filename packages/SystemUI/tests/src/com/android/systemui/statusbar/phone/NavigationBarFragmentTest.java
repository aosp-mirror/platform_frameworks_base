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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.LeakCheck.Tracker;
import android.view.Display;
import android.view.WindowManager;

import com.android.systemui.Dependency;

import com.android.systemui.OverviewProxyService;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.recents.Recents;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import android.testing.TestableLooper.RunWithLooper;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NavigationBarFragmentTest extends SysuiBaseFragmentTest {

    public NavigationBarFragmentTest() {
        super(NavigationBarFragment.class);
    }

    protected void createRootView() {
        mView = new NavigationBarFrame(mContext);
    }

    @Before
    public void setup() {
        mDependency.injectTestDependency(Dependency.BG_LOOPER, Looper.getMainLooper());
        mSysuiContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mSysuiContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mSysuiContext.putComponent(Recents.class, mock(Recents.class));
        mSysuiContext.putComponent(Divider.class, mock(Divider.class));
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mDependency.injectMockDependency(OverviewProxyService.class);
        WindowManager windowManager = mock(WindowManager.class);
        Display defaultDisplay = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(windowManager.getDefaultDisplay()).thenReturn(
                defaultDisplay);
        mContext.addMockSystemService(Context.WINDOW_SERVICE, windowManager);

        Tracker tracker = mLeakCheck.getTracker("accessibility_manager");
        AccessibilityManagerWrapper wrapper = new AccessibilityManagerWrapper(mContext) {
            @Override
            public void addCallback(AccessibilityServicesStateChangeListener listener) {
                tracker.getLeakInfo(listener).addAllocation(new Throwable());
            }

            @Override
            public void removeCallback(AccessibilityServicesStateChangeListener listener) {
                tracker.getLeakInfo(listener).clearAllocations();
            }
        };
        mDependency.injectTestDependency(AccessibilityManagerWrapper.class, wrapper);
    }

    @Test
    public void testHomeLongPress() {
        NavigationBarFragment navigationBarFragment = (NavigationBarFragment) mFragment;

        mFragments.dispatchResume();
        processAllMessages();
        navigationBarFragment.onHomeLongClick(navigationBarFragment.getView());
    }

}
