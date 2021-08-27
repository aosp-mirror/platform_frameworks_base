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

import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_ICON;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_MOBILE;
import static com.android.systemui.statusbar.phone.StatusBarIconHolder.TYPE_WIFI;

import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.mock;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarMobileView;
import com.android.systemui.statusbar.StatusBarWifiView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.StatusBarIconController.IconManager;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class StatusBarIconControllerTest extends LeakCheckedTest {

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        mDependency.injectMockDependency(DarkIconDispatcher.class);
    }

    @Test
    public void testSetCalledOnAdd_IconManager() {
        LinearLayout layout = new LinearLayout(mContext);
        TestIconManager manager = new TestIconManager(layout);
        testCallOnAdd_forManager(manager);
    }

    @Test
    public void testSetCalledOnAdd_DarkIconManager() {
        LinearLayout layout = new LinearLayout(mContext);
        TestDarkIconManager manager = new TestDarkIconManager(layout, mock(FeatureFlags.class));
        testCallOnAdd_forManager(manager);
    }


    private <T extends IconManager & TestableIconManager> void testCallOnAdd_forManager(T manager) {
        StatusBarIconHolder holder = holderForType(TYPE_ICON);
        manager.onIconAdded(0, "test_slot", false, holder);
        assertTrue("Expected StatusBarIconView",
                (manager.getViewAt(0) instanceof StatusBarIconView));

        holder = holderForType(TYPE_WIFI);
        manager.onIconAdded(1, "test_wifi", false, holder);
        assertTrue(manager.getViewAt(1) instanceof StatusBarWifiView);

        holder = holderForType(TYPE_MOBILE);
        manager.onIconAdded(2, "test_mobile", false, holder);
        assertTrue(manager.getViewAt(2) instanceof StatusBarMobileView);
    }

    private StatusBarIconHolder holderForType(int type) {
        switch (type) {
            case TYPE_MOBILE:
                return StatusBarIconHolder.fromMobileIconState(mock(MobileIconState.class));

            case TYPE_WIFI:
                return StatusBarIconHolder.fromWifiIconState(mock(WifiIconState.class));

            case TYPE_ICON:
            default:
                return StatusBarIconHolder.fromIcon(mock(StatusBarIcon.class));
        }
    }

    private static class TestDarkIconManager extends DarkIconManager
            implements TestableIconManager {

        TestDarkIconManager(LinearLayout group, FeatureFlags featureFlags) {
            super(group, featureFlags);
        }

        @Override
        public StatusIconDisplayable getViewAt(int index) {
            return (StatusIconDisplayable) mGroup.getChildAt(index);
        }

        @Override
        protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            StatusBarIconView mock = mock(StatusBarIconView.class);
            mGroup.addView(mock, index);

            return mock;
        }

        @Override
        protected StatusBarWifiView addSignalIcon(int index, String slot, WifiIconState state) {
            StatusBarWifiView mock = mock(StatusBarWifiView.class);
            mGroup.addView(mock, index);
            return mock;
        }

        @Override
        protected StatusBarMobileView addMobileIcon(int index, String slot, MobileIconState state) {
            StatusBarMobileView mock = mock(StatusBarMobileView.class);
            mGroup.addView(mock, index);

            return mock;
        }
    }

    private static class TestIconManager extends IconManager implements TestableIconManager {
        TestIconManager(ViewGroup group) {
            super(group, mock(FeatureFlags.class));
        }

        @Override
        public StatusIconDisplayable getViewAt(int index) {
            return (StatusIconDisplayable) mGroup.getChildAt(index);
        }

        @Override
        protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            StatusBarIconView mock = mock(StatusBarIconView.class);
            mGroup.addView(mock, index);

            return mock;
        }

        @Override
        protected StatusBarWifiView addSignalIcon(int index, String slot, WifiIconState state) {
            StatusBarWifiView mock = mock(StatusBarWifiView.class);
            mGroup.addView(mock, index);
            return mock;
        }

        @Override
        protected StatusBarMobileView addMobileIcon(int index, String slot, MobileIconState state) {
            StatusBarMobileView mock = mock(StatusBarMobileView.class);
            mGroup.addView(mock, index);

            return mock;
        }
    }

    private interface TestableIconManager {
        StatusIconDisplayable getViewAt(int index);
    }
}
