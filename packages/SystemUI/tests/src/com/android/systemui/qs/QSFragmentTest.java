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

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.LayoutInflaterBuilder;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.FrameLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.CarrierText;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
@Ignore
public class QSFragmentTest extends SysuiBaseFragmentTest {

    private MetricsLogger mMockMetricsLogger;

    public QSFragmentTest() {
        super(QSFragment.class);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Before
    @Ignore("failing")
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
    }

    @Test
    @Ignore("failing")
    public void testListening() {
        assertEquals(Looper.myLooper(), Looper.getMainLooper());
        QSFragment qs = (QSFragment) mFragment;
        mFragments.dispatchResume();
        processAllMessages();
        QSTileHost host = new QSTileHost(mContext, mock(StatusBarIconController.class),
                mock(QSFactoryImpl.class), new Handler(), Looper.myLooper(),
                mock(PluginManager.class), mock(TunerService.class),
                () -> mock(AutoTileManager.class));
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
    @Ignore("failing")
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

    @Override
    protected Fragment instantiate(Context context, String className, Bundle arguments) {
        return new QSFragment(
                new RemoteInputQuickSettingsDisabler(context, mock(ConfigurationController.class)),
                new InjectionInflationController(SystemUIFactory.getInstance().getRootComponent()),
                context,
                mock(QSTileHost.class));
    }
}
