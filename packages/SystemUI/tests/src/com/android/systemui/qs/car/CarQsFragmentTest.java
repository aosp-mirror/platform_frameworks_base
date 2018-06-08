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
package com.android.systemui.qs.car;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.LayoutInflaterBuilder;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.FrameLayout;

import com.android.keyguard.CarrierText;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.Clock;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link CarQSFragment}.
 */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
@Ignore("Flaky")
public class CarQsFragmentTest extends SysuiBaseFragmentTest {
    public CarQsFragmentTest() {
        super(CarQSFragment.class);
    }

    @Before
    public void initDependencies() {
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                new LayoutInflaterBuilder(mContext)
                        .replace("com.android.systemui.statusbar.policy.SplitClockView",
                                FrameLayout.class)
                        .replace("TextClock", View.class)
                        .replace(CarrierText.class, View.class)
                        .replace(Clock.class, View.class)
                        .build());
        mSysuiContext.putComponent(CommandQueue.class, mock(CommandQueue.class));
        mDependency.injectTestDependency(Dependency.BG_LOOPER,
                TestableLooper.get(this).getLooper());
    }

    @Test
    public void testLayoutInflation() {
        CarQSFragment fragment = (CarQSFragment) mFragment;
        mFragments.dispatchResume();

        assertNotNull(fragment.getHeader());
        assertNotNull(fragment.getFooter());
    }

    @Test
    public void testListening() {
        CarQSFragment qs = (CarQSFragment) mFragment;
        mFragments.dispatchResume();
        processAllMessages();

        qs.setListening(true);
        processAllMessages();

        qs.setListening(false);
        processAllMessages();
    }
}
