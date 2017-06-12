/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.qs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.app.FragmentController;
import android.app.FragmentManagerNonConfig;
import android.os.Looper;
import android.support.test.filters.FlakyTest;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.CarrierText;
import com.android.systemui.Dependency;
import com.android.systemui.R;

import android.os.Parcelable;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import android.testing.LayoutInflaterBuilder;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
@Ignore("failing")
public class QSFragmentTest extends SysuiBaseFragmentTest {

    private MetricsLogger mMockMetricsLogger;

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Before
    public void addLeakCheckDependencies() {
        mMockMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                new LayoutInflaterBuilder(mContext)
                        .replace("com.android.systemui.statusbar.policy.SplitClockView",
                                FrameLayout.class)
                        .replace("TextClock", View.class)
                        .replace(CarrierText.class, View.class)
                        .replace(Clock.class, View.class)
                        .build());

        mDependency.injectTestDependency(Dependency.BG_LOOPER,
                TestableLooper.get(this).getLooper());
        mDependency.injectMockDependency(UserSwitcherController.class);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testListening() {
        assertEquals(Looper.myLooper(), Looper.getMainLooper());
        QSFragment qs = (QSFragment) mFragment;
        mFragments.dispatchResume();
        processAllMessages();
        QSTileHost host = new QSTileHost(mContext, null, mock(StatusBarIconController.class));
        qs.setHost(host);

        qs.setListening(true);
        processAllMessages();

        qs.setListening(false);
        processAllMessages();

        // Manually push header through detach so it can handle standard cleanup it does on
        // removed from window.
        ((QuickStatusBarHeader) qs.getView().findViewById(R.id.header)).onDetachedFromWindow();

        host.destroy();
        processAllMessages();
    }

    @Test
    public void testSaveState() {
        QSFragment qs = (QSFragment) mFragment;

        mFragments.dispatchResume();
        processAllMessages();

        qs.setListening(true);
        qs.setExpanded(true);
        processAllMessages();
        recreateFragment();
        processAllMessages();

        // Get the reference to the new fragment.
        qs = (QSFragment) mFragment;
        assertTrue(qs.isListening());
        assertTrue(qs.isExpanded());
    }
}
