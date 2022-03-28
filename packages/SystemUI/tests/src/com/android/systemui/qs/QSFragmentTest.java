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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Fragment;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.qs.external.CustomTileStatePersister;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.qs.external.TileServiceRequestController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.animation.UniqueObjectHostView;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QSFragmentTest extends SysuiBaseFragmentTest {

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
    private FalsingManager mFalsingManager;
    @Mock
    private TileServiceRequestController.Builder mTileServiceRequestControllerBuilder;
    @Mock
    private TileServiceRequestController mTileServiceRequestController;
    @Mock
    private QSCustomizerController mQsCustomizerController;
    @Mock
    private QuickQSPanelController mQuickQSPanelController;
    @Mock
    private FooterActionsController mQSFooterActionController;
    @Mock
    private QSContainerImplController mQSContainerImplController;
    @Mock
    private QSContainerImpl mContainer;
    @Mock
    private QSFooter mFooter;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private NonInterceptingScrollView mQSPanelScrollView;
    @Mock
    private QuickStatusBarHeader mHeader;
    @Mock
    private QSPanel.QSTileLayout mQsTileLayout;
    @Mock
    private QSPanel.QSTileLayout mQQsTileLayout;
    private View mQsFragmentView;

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testListening() {
        QSFragment qs = (QSFragment) mFragment;
        mFragments.dispatchResume();
        processAllMessages();

        QSTileHost host = new QSTileHost(mContext, mock(StatusBarIconController.class),
                mock(QSFactoryImpl.class), new Handler(), Looper.myLooper(),
                mock(PluginManager.class), mock(TunerService.class),
                () -> mock(AutoTileManager.class), mock(DumpManager.class),
                mock(BroadcastDispatcher.class), Optional.of(mock(CentralSurfaces.class)),
                mock(QSLogger.class), mock(UiEventLogger.class), mock(UserTracker.class),
                mock(SecureSettings.class), mock(CustomTileStatePersister.class),
                mTileServiceRequestControllerBuilder, mock(TileLifecycleManager.Factory.class));

        qs.setListening(true);
        processAllMessages();

        qs.setListening(false);
        processAllMessages();
        host.destroy();
        processAllMessages();
    }

    @Test
    public void testSaveState() {
        mFragments.dispatchResume();
        processAllMessages();

        QSFragment qs = (QSFragment) mFragment;
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
        MockitoAnnotations.initMocks(this);
        CommandQueue commandQueue = new CommandQueue(context);

        setupQsComponent();
        setUpViews();
        setUpInflater();
        setUpMedia();
        setUpOther();

        return new QSFragment(
                new RemoteInputQuickSettingsDisabler(context, commandQueue,
                        mock(ConfigurationController.class)),
                mock(QSTileHost.class),
                mock(StatusBarStateController.class),
                commandQueue,
                mQSMediaHost,
                mQQSMediaHost,
                mBypassController,
                mQsComponentFactory,
                mock(QSFragmentDisableFlagsLogger.class),
                mFalsingManager,
                mock(DumpManager.class));
    }

    private void setUpOther() {
        when(mTileServiceRequestControllerBuilder.create(any()))
                .thenReturn(mTileServiceRequestController);
        when(mQSContainerImplController.getView()).thenReturn(mContainer);
        when(mQSPanelController.getTileLayout()).thenReturn(mQQsTileLayout);
        when(mQuickQSPanelController.getTileLayout()).thenReturn(mQsTileLayout);
    }

    private void setUpMedia() {
        when(mQSMediaHost.getCurrentClipping()).thenReturn(new Rect());
        when(mQSMediaHost.getHostView()).thenReturn(new UniqueObjectHostView(mContext));
        when(mQQSMediaHost.getHostView()).thenReturn(new UniqueObjectHostView(mContext));
    }

    private void setUpViews() {
        mQsFragmentView = spy(new View(mContext));
        when(mQsFragmentView.findViewById(R.id.expanded_qs_scroll_view)).thenReturn(
                mQSPanelScrollView);
        when(mQsFragmentView.findViewById(R.id.header)).thenReturn(mHeader);
        when(mQsFragmentView.findViewById(android.R.id.edit)).thenReturn(new View(mContext));
    }

    private void setUpInflater() {
        when(mLayoutInflater.cloneInContext(any(Context.class))).thenReturn(mLayoutInflater);
        when(mLayoutInflater.inflate(anyInt(), any(ViewGroup.class), anyBoolean()))
                .thenReturn(mQsFragmentView);
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE,
                mLayoutInflater);
    }

    private void setupQsComponent() {
        when(mQsComponentFactory.create(any(QSFragment.class))).thenReturn(mQsFragmentComponent);
        when(mQsFragmentComponent.getQSPanelController()).thenReturn(mQSPanelController);
        when(mQsFragmentComponent.getQuickQSPanelController()).thenReturn(mQuickQSPanelController);
        when(mQsFragmentComponent.getQSCustomizerController()).thenReturn(mQsCustomizerController);
        when(mQsFragmentComponent.getQSContainerImplController()).thenReturn(
                mQSContainerImplController);
        when(mQsFragmentComponent.getQSFooter()).thenReturn(mFooter);
        when(mQsFragmentComponent.getQSFooterActionController()).thenReturn(
                mQSFooterActionController);
    }
}
