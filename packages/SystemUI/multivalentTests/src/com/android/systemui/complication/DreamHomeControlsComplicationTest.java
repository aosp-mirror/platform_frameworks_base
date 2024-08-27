/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.complication;

import static com.android.systemui.complication.Complication.COMPLICATION_TYPE_HOME_CONTROLS;
import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.res.Resources;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.view.LaunchableImageView;
import com.android.systemui.complication.dagger.DreamHomeControlsComplicationComponent;
import com.android.systemui.condition.SelfExecutingMonitor;
import com.android.systemui.controls.ControlsServiceInfo;
import com.android.systemui.controls.controller.ControlsController;
import com.android.systemui.controls.controller.StructureInfo;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamHomeControlsComplicationTest extends SysuiTestCase {
    @Mock
    private DreamHomeControlsComplication mComplication;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private Resources mResources;

    @Mock
    private ControlsComponent mControlsComponent;

    @Mock
    private ControlsController mControlsController;

    @Mock
    private ControlsListingController mControlsListingController;

    @Mock
    private DreamHomeControlsComplicationComponent.Factory mComponentFactory;

    @Captor
    private ArgumentCaptor<ControlsListingController.ControlsListingCallback> mCallbackCaptor;

    @Mock
    private LaunchableImageView mHomeControlsView;

    @Mock
    private ActivityStarter mActivityStarter;

    @Mock
    private UiEventLogger mUiEventLogger;

    @Mock
    private ConfigurationController mConfigurationController;

    @Captor
    private ArgumentCaptor<DreamOverlayStateController.Callback> mStateCallbackCaptor;

    private Monitor mMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mContext.ensureTestableResources();

        when(mControlsComponent.getControlsController()).thenReturn(
                Optional.of(mControlsController));
        when(mControlsComponent.getControlsListingController()).thenReturn(
                Optional.of(mControlsListingController));
        when(mControlsComponent.getVisibility()).thenReturn(AVAILABLE);

        mMonitor = SelfExecutingMonitor.createInstance();
    }

    @Test
    public void complicationType() {
        final DreamHomeControlsComplication complication =
                new DreamHomeControlsComplication(mResources, mComponentFactory);
        assertThat(complication.getRequiredTypeAvailability()).isEqualTo(
                COMPLICATION_TYPE_HOME_CONTROLS);
    }

    @Test
    public void complicationAvailability_serviceNotAvailable_noFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setHaveFavorites(false);
        setServiceAvailable(false);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceAvailable_noFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setHaveFavorites(false);
        setServiceAvailable(true);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceAvailable_noFavorites_panel_addComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setHaveFavorites(false);
        setServiceWithPanel();

        verify(mDreamOverlayStateController).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceNotAvailable_haveFavorites_doNotAddComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setHaveFavorites(true);
        setServiceAvailable(false);

        verify(mDreamOverlayStateController, never()).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_serviceAvailable_haveFavorites_addComplication() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setHaveFavorites(true);
        setServiceAvailable(true);

        verify(mDreamOverlayStateController).addComplication(mComplication);
    }

    @Test
    public void complicationAvailability_checkAvailabilityWhenDreamOverlayBecomesActive() {
        final DreamHomeControlsComplication.Registrant registrant =
                new DreamHomeControlsComplication.Registrant(mComplication,
                        mDreamOverlayStateController, mControlsComponent, mMonitor);
        registrant.start();

        setServiceAvailable(true);
        setHaveFavorites(false);

        // Complication not available on start.
        verify(mDreamOverlayStateController, never()).addComplication(mComplication);

        // Favorite controls added, complication should be available now.
        setHaveFavorites(true);

        // Dream overlay becomes active.
        setDreamOverlayActive(true);

        // Verify complication is added.
        verify(mDreamOverlayStateController).addComplication(mComplication);
    }

    /**
     * Ensures clicking home controls chip logs UiEvent.
     */
    @Test
    public void testClick_logsUiEvent() {
        final DreamHomeControlsComplication.DreamHomeControlsChipViewController viewController =
                new DreamHomeControlsComplication.DreamHomeControlsChipViewController(
                        mHomeControlsView,
                        mActivityStarter,
                        mContext,
                        mConfigurationController,
                        mControlsComponent,
                        mUiEventLogger);
        viewController.onViewAttached();

        final ArgumentCaptor<View.OnClickListener> clickListenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mHomeControlsView).setOnClickListener(clickListenerCaptor.capture());

        clickListenerCaptor.getValue().onClick(mHomeControlsView);
        verify(mUiEventLogger).log(DreamOverlayUiEvent.DREAM_HOME_CONTROLS_TAPPED);
    }

    @Test
    public void testUnregistersConfigurationCallback() {
        final DreamHomeControlsComplication.DreamHomeControlsChipViewController viewController =
                new DreamHomeControlsComplication.DreamHomeControlsChipViewController(
                        mHomeControlsView,
                        mActivityStarter,
                        mContext,
                        mConfigurationController,
                        mControlsComponent,
                        mUiEventLogger);
        viewController.onViewAttached();
        verify(mConfigurationController).addCallback(any());
        verify(mConfigurationController, never()).removeCallback(any());

        viewController.onViewDetached();
        verify(mConfigurationController).removeCallback(any());
    }

    private void setHaveFavorites(boolean value) {
        final List<StructureInfo> favorites = mock(List.class);
        when(favorites.isEmpty()).thenReturn(!value);
        when(mControlsController.getFavorites()).thenReturn(favorites);
    }

    private void setServiceAvailable(boolean value) {
        final List<ControlsServiceInfo> serviceInfos = mock(List.class);
        when(mControlsListingController.getCurrentServices()).thenReturn(serviceInfos);
        when(serviceInfos.isEmpty()).thenReturn(!value);
        triggerControlsListingCallback(serviceInfos);
    }

    private void setServiceWithPanel() {
        final List<ControlsServiceInfo> serviceInfos = new ArrayList<>();
        ControlsServiceInfo csi = mock(ControlsServiceInfo.class);
        serviceInfos.add(csi);
        when(csi.getPanelActivity()).thenReturn(new ComponentName("a", "b"));
        when(mControlsListingController.getCurrentServices()).thenReturn(serviceInfos);
        triggerControlsListingCallback(serviceInfos);
    }

    private void setDreamOverlayActive(boolean value) {
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(value);
        verify(mDreamOverlayStateController).addCallback(mStateCallbackCaptor.capture());
        mStateCallbackCaptor.getValue().onStateChanged();
    }

    private void triggerControlsListingCallback(List<ControlsServiceInfo> serviceInfos) {
        verify(mControlsListingController).addCallback(mCallbackCaptor.capture());
        mCallbackCaptor.getValue().onServicesUpdated(serviceInfos);
    }
}
