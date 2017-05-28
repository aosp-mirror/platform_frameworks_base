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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.phone.StatusBarIconController.DarkIconManager;
import com.android.systemui.statusbar.phone.StatusBarIconController.IconManager;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
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
        StatusBarIcon icon = mock(StatusBarIcon.class);

        manager.onIconAdded(0, "test_slot", false, icon);
        verify(manager.mMock).set(eq(icon));
    }

    @Test
    public void testSetCalledOnAdd_DarkIconManager() {
        LinearLayout layout = new LinearLayout(mContext);
        TestDarkIconManager manager = new TestDarkIconManager(layout);
        StatusBarIcon icon = mock(StatusBarIcon.class);

        manager.onIconAdded(0, "test_slot", false, icon);
        verify(manager.mMock).set(eq(icon));
    }

    private static class TestDarkIconManager extends DarkIconManager {

        private final StatusBarIconView mMock;

        public TestDarkIconManager(LinearLayout group) {
            super(group);
            mMock = mock(StatusBarIconView.class);
        }

        @Override
        protected StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
            return mMock;
        }
    }

    private static class TestIconManager extends IconManager {

        private final StatusBarIconView mMock;

        public TestIconManager(ViewGroup group) {
            super(group);
            mMock = mock(StatusBarIconView.class);
        }

        @Override
        protected StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
            return mMock;
        }
    }

}
