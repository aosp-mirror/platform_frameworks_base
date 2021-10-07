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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.LayoutInflaterBuilder;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.CarrierText;
import com.android.systemui.Dependency;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.qs.external.CustomTileStatePersister;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.InjectionInflationController;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
@Suppress
public class QSFragmentTest extends SysuiBaseFragmentTest {

    private MetricsLogger mMockMetricsLogger;
    @Mock
    private QSFragmentComponent.Factory mQsComponentFactory;
    @Mock
    private QSFragmentComponent mQsFragmentComponent;
    @Mock
    private QSPanelController mQSPanelController;
    @Mock
    private MediaHost mQSMediaHost;
    @Mock
    private MediaHost mQQSMediaHost;
    @Mock
    private KeyguardBypassController mBypassController;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Mock
    private FalsingManager mFalsingManager;

    public QSFragmentTest() {
        super(QSFragment.class);
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Before
    @Ignore("failing")
    public void addLeakCheckDependencies() {
        MockitoAnnotations.initMocks(this);
        when(mQsComponentFactory.create(any(QSFragment.class))).thenReturn(mQsFragmentComponent);
        when(mQsFragmentComponent.getQSPanelController()).thenReturn(mQSPanelController);

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
                () -> mock(AutoTileManager.class), mock(DumpManager.class),
                mock(BroadcastDispatcher.class), Optional.of(mock(StatusBar.class)),
                mock(QSLogger.class), mock(UiEventLogger.class), mock(UserTracker.class),
                mock(SecureSettings.class), mock(CustomTileStatePersister.class), mFeatureFlags);
        qs.setHost(host);

        qs.setListening(true);
        processAllMessages();

        qs.setListening(false);
        processAllMessages();
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
        CommandQueue commandQueue = new CommandQueue(context);
        return new QSFragment(
                new RemoteInputQuickSettingsDisabler(context, mock(ConfigurationController.class),
                        commandQueue),
                new InjectionInflationController(
                        SystemUIFactory.getInstance()
                                .getSysUIComponent()
                                .createViewInstanceCreatorFactory()),
                mock(QSTileHost.class),
                mock(StatusBarStateController.class),
                commandQueue,
                new QSDetailDisplayer(),
                mQSMediaHost,
                mQQSMediaHost,
                mBypassController,
                mQsComponentFactory,
                mFeatureFlags,
                mFalsingManager,
                mock(DumpManager.class));
    }
}
