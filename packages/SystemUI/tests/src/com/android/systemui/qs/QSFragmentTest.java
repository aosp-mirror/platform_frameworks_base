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

import static org.mockito.Mockito.mock;

import com.android.systemui.Dependency;
import com.android.systemui.FragmentTestCase;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.phone.QuickStatusBarHeader;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.LayoutInflaterBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;
import android.widget.FrameLayout;

@RunWith(AndroidJUnit4.class)
public class QSFragmentTest extends FragmentTestCase {

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Before
    public void addLeakCheckDependencies() {
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                new LayoutInflaterBuilder(mContext)
                        .replace("com.android.systemui.statusbar.policy.SplitClockView",
                                FrameLayout.class)
                        .replace("TextClock", View.class)
                        .build());

        injectTestDependency(Dependency.BG_LOOPER, Looper.getMainLooper());
        injectMockDependency(UserSwitcherController.class);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testListening() {
        QSFragment qs = (QSFragment) mFragment;
        postAndWait(() -> mFragments.dispatchResume());
        QSTileHost host = new QSTileHost(mContext, null,
                mock(StatusBarIconController.class));
        qs.setHost(host);
        Handler h = new Handler((Looper) Dependency.get(Dependency.BG_LOOPER));

        qs.setListening(true);
        waitForIdleSync(h);

        qs.setListening(false);
        waitForIdleSync(h);

        // Manually push header through detach so it can handle standard cleanup it does on
        // removed from window.
        ((QuickStatusBarHeader) qs.getView().findViewById(R.id.header)).onDetachedFromWindow();

        host.destroy();
        // Ensure the tuner cleans up its persistent listeners.
        Dependency.get(TunerService.class).destroy();
    }
}
