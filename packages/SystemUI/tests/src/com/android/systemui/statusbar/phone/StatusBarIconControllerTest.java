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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.StatusBarMobileView;
import com.android.systemui.statusbar.StatusBarWifiView;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.connectivity.ui.MobileContextProvider;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.StatusBarIconController.IconManager;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.MobileIconState;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.WifiIconState;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter;
import com.android.systemui.statusbar.pipeline.wifi.ui.WifiUiAdapter;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class StatusBarIconControllerTest extends LeakCheckedTest {

    private MobileContextProvider mMobileContextProvider = mock(MobileContextProvider.class);

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
        // For testing, ignore context overrides
        when(mMobileContextProvider.getMobileContextForSub(anyInt(), any())).thenReturn(mContext);
    }

    @Test
    public void testSetCalledOnAdd_IconManager() {
        LinearLayout layout = new LinearLayout(mContext);
        TestIconManager manager = new TestIconManager(layout, mMobileContextProvider);
        testCallOnAdd_forManager(manager);
    }

    @Test
    public void testSetCalledOnAdd_DarkIconManager() {
        LinearLayout layout = new LinearLayout(mContext);
        TestDarkIconManager manager = new TestDarkIconManager(
                layout,
                StatusBarLocation.HOME,
                mock(StatusBarPipelineFlags.class),
                mock(WifiUiAdapter.class),
                mock(MobileUiAdapter.class),
                mMobileContextProvider,
                mock(DarkIconDispatcher.class));
        testCallOnAdd_forManager(manager);
    }

    @Test
    public void testRemoveIcon_ignoredForNewPipeline() {
        IconManager manager = mock(IconManager.class);

        // GIVEN the new pipeline is on
        StatusBarPipelineFlags flags = mock(StatusBarPipelineFlags.class);
        when(flags.isIconControlledByFlags("test_icon")).thenReturn(true);

        StatusBarIconController iconController = new StatusBarIconControllerImpl(
                mContext,
                mock(CommandQueue.class),
                mock(DemoModeController.class),
                mock(ConfigurationController.class),
                mock(TunerService.class),
                mock(DumpManager.class),
                mock(StatusBarIconList.class),
                flags
        );

        iconController.addIconGroup(manager);

        // WHEN a request to remove a new icon is sent
        iconController.removeIcon("test_icon", 0);

        // THEN it is not removed for those icons
        verify(manager, never()).onRemoveIcon(anyInt());
    }

    @Test
    public void testRemoveAllIconsForSlot_ignoredForNewPipeline() {
        IconManager manager = mock(IconManager.class);

        // GIVEN the new pipeline is on
        StatusBarPipelineFlags flags = mock(StatusBarPipelineFlags.class);
        when(flags.isIconControlledByFlags("test_icon")).thenReturn(true);

        StatusBarIconController iconController = new StatusBarIconControllerImpl(
                mContext,
                mock(CommandQueue.class),
                mock(DemoModeController.class),
                mock(ConfigurationController.class),
                mock(TunerService.class),
                mock(DumpManager.class),
                mock(StatusBarIconList.class),
                flags
        );

        iconController.addIconGroup(manager);

        // WHEN a request to remove a new icon is sent
        iconController.removeAllIconsForSlot("test_icon");

        // THEN it is not removed for those icons
        verify(manager, never()).onRemoveIcon(anyInt());
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

        TestDarkIconManager(
                LinearLayout group,
                StatusBarLocation location,
                StatusBarPipelineFlags statusBarPipelineFlags,
                WifiUiAdapter wifiUiAdapter,
                MobileUiAdapter mobileUiAdapter,
                MobileContextProvider contextProvider,
                DarkIconDispatcher darkIconDispatcher) {
            super(group,
                    location,
                    statusBarPipelineFlags,
                    wifiUiAdapter,
                    mobileUiAdapter,
                    contextProvider,
                    darkIconDispatcher);
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
        protected StatusBarWifiView addWifiIcon(int index, String slot, WifiIconState state) {
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
        TestIconManager(ViewGroup group, MobileContextProvider contextProvider) {
            super(group,
                    StatusBarLocation.HOME,
                    mock(StatusBarPipelineFlags.class),
                    mock(WifiUiAdapter.class),
                    mock(MobileUiAdapter.class),
                    contextProvider);
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
        protected StatusBarWifiView addWifiIcon(int index, String slot, WifiIconState state) {
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
