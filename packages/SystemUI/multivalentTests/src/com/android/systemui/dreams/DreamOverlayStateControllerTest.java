/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.Complication;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.FakeLogBuffer;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.reference.FakeWeakReferenceFactory;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collection;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamOverlayStateControllerTest extends SysuiTestCase {
    @Mock
    DreamOverlayStateController.Callback mCallback;

    @Mock
    Complication mComplication;

    @Mock
    private FeatureFlags mFeatureFlags;

    private final LogBuffer mLogBuffer = FakeLogBuffer.Factory.Companion.create();

    final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    final FakeWeakReferenceFactory mWeakReferenceFactory = new FakeWeakReferenceFactory();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mFeatureFlags.isEnabled(Flags.ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS)).thenReturn(false);
    }

    @Test
    public void testStateChange_overlayActive() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.addCallback(mCallback);
        stateController.setOverlayActive(true);
        mExecutor.runAllReady();

        verify(mCallback).onStateChanged();
        assertThat(stateController.isOverlayActive()).isTrue();

        Mockito.clearInvocations(mCallback);
        stateController.setOverlayActive(true);
        mExecutor.runAllReady();
        verify(mCallback, never()).onStateChanged();

        stateController.setOverlayActive(false);
        mExecutor.runAllReady();
        verify(mCallback).onStateChanged();
        assertThat(stateController.isOverlayActive()).isFalse();
    }

    @Test
    public void testCallback() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.addCallback(mCallback);

        // Add complication and verify callback is notified.
        stateController.addComplication(mComplication);

        mExecutor.runAllReady();

        verify(mCallback, times(1)).onComplicationsChanged();

        final Collection<Complication> complications = stateController.getComplications();
        assertEquals(complications.size(), 1);
        assertTrue(complications.contains(mComplication));

        clearInvocations(mCallback);

        // Remove complication and verify callback is notified.
        stateController.removeComplication(mComplication);
        mExecutor.runAllReady();
        verify(mCallback, times(1)).onComplicationsChanged();
        assertTrue(stateController.getComplications().isEmpty());
    }

    @Test
    public void testNotifyOnCallbackAdd() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addComplication(mComplication);
        mExecutor.runAllReady();

        // Verify callback occurs on add when an overlay is already present.
        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        verify(mCallback, times(1)).onComplicationsChanged();
    }

    @Test
    public void testNotifyOnCallbackAddOverlayDisabled() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(false);

        stateController.addComplication(mComplication);
        mExecutor.runAllReady();

        // Verify callback occurs on add when an overlay is already present.
        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        verify(mCallback, never()).onComplicationsChanged();
    }


    @Test
    public void testComplicationFilteringWhenShouldShowComplications() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.setShouldShowComplications(true);

        final Complication alwaysAvailableComplication = Mockito.mock(Complication.class);
        final Complication weatherComplication = Mockito.mock(Complication.class);
        when(alwaysAvailableComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_NONE);
        when(weatherComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_WEATHER);

        stateController.addComplication(alwaysAvailableComplication);
        stateController.addComplication(weatherComplication);

        final DreamOverlayStateController.Callback callback =
                Mockito.mock(DreamOverlayStateController.Callback.class);

        stateController.addCallback(callback);
        mExecutor.runAllReady();

        {
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(alwaysAvailableComplication)).isTrue();
            assertThat(complications.contains(weatherComplication)).isFalse();
        }

        stateController.setAvailableComplicationTypes(Complication.COMPLICATION_TYPE_WEATHER);
        mExecutor.runAllReady();
        verify(callback).onAvailableComplicationTypesChanged();

        {
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(alwaysAvailableComplication)).isTrue();
            assertThat(complications.contains(weatherComplication)).isTrue();
        }

    }

    @Test
    public void testComplicationFilteringWhenShouldHideComplications() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.setShouldShowComplications(true);

        final Complication alwaysAvailableComplication = Mockito.mock(Complication.class);
        final Complication weatherComplication = Mockito.mock(Complication.class);
        when(alwaysAvailableComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_NONE);
        when(weatherComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_WEATHER);

        stateController.addComplication(alwaysAvailableComplication);
        stateController.addComplication(weatherComplication);

        final DreamOverlayStateController.Callback callback =
                Mockito.mock(DreamOverlayStateController.Callback.class);

        stateController.setAvailableComplicationTypes(Complication.COMPLICATION_TYPE_WEATHER);
        stateController.addCallback(callback);
        mExecutor.runAllReady();

        {
            clearInvocations(callback);
            stateController.setShouldShowComplications(true);
            mExecutor.runAllReady();

            verify(callback).onAvailableComplicationTypesChanged();
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(alwaysAvailableComplication)).isTrue();
            assertThat(complications.contains(weatherComplication)).isTrue();
        }

        {
            clearInvocations(callback);
            stateController.setShouldShowComplications(false);
            mExecutor.runAllReady();

            verify(callback).onAvailableComplicationTypesChanged();
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(alwaysAvailableComplication)).isTrue();
            assertThat(complications.contains(weatherComplication)).isFalse();
        }
    }

    @Test
    public void testComplicationWithNoTypeNotFiltered() {
        final Complication complication = Mockito.mock(Complication.class);
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.addComplication(complication);
        mExecutor.runAllReady();
        assertThat(stateController.getComplications(true).contains(complication))
                .isTrue();
    }

    @Test
    public void testComplicationsNotShownForLowLight() {
        final Complication complication = Mockito.mock(Complication.class);
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        // Add a complication and verify it's returned in getComplications.
        stateController.addComplication(complication);
        mExecutor.runAllReady();
        assertThat(stateController.getComplications().contains(complication))
                .isTrue();

        stateController.setLowLightActive(true);
        mExecutor.runAllReady();

        assertThat(stateController.getComplications()).isEmpty();
    }

    @Test
    public void testNotifyLowLightChanged() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        assertThat(stateController.isLowLightActive()).isFalse();

        stateController.setLowLightActive(true);

        mExecutor.runAllReady();
        verify(mCallback, times(1)).onStateChanged();
        assertThat(stateController.isLowLightActive()).isTrue();
    }

    @Test
    public void testNotifyLowLightExit() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        assertThat(stateController.isLowLightActive()).isFalse();

        // Turn low light on then off to trigger the exiting callback.
        stateController.setLowLightActive(true);
        stateController.setLowLightActive(false);

        // Callback was only called once, when
        mExecutor.runAllReady();
        verify(mCallback, times(1)).onExitLowLight();
        assertThat(stateController.isLowLightActive()).isFalse();

        // Set with false again, which should not cause the callback to trigger again.
        stateController.setLowLightActive(false);
        mExecutor.runAllReady();
        verify(mCallback, times(1)).onExitLowLight();
    }

    @Test
    public void testNotifyEntryAnimationsFinishedChanged() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        assertThat(stateController.areEntryAnimationsFinished()).isFalse();

        stateController.setEntryAnimationsFinished(true);
        mExecutor.runAllReady();

        verify(mCallback, times(1)).onStateChanged();
        assertThat(stateController.areEntryAnimationsFinished()).isTrue();
    }

    @Test
    public void testNotifyDreamOverlayStatusBarVisibleChanged() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        assertThat(stateController.isDreamOverlayStatusBarVisible()).isFalse();

        stateController.setDreamOverlayStatusBarVisible(true);
        mExecutor.runAllReady();

        verify(mCallback, times(1)).onStateChanged();
        assertThat(stateController.isDreamOverlayStatusBarVisible()).isTrue();
    }

    @Test
    public void testNotifyHasAssistantAttentionChanged() {
        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);

        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        assertThat(stateController.hasAssistantAttention()).isFalse();

        stateController.setHasAssistantAttention(true);
        mExecutor.runAllReady();

        verify(mCallback, times(1)).onStateChanged();
        assertThat(stateController.hasAssistantAttention()).isTrue();
    }

    @Test
    public void testShouldShowComplicationsSetToFalse_stillShowsHomeControls_featureEnabled() {
        when(mFeatureFlags.isEnabled(Flags.ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS)).thenReturn(true);

        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.setShouldShowComplications(true);

        final Complication homeControlsComplication = Mockito.mock(Complication.class);
        when(homeControlsComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_HOME_CONTROLS);

        stateController.addComplication(homeControlsComplication);

        final DreamOverlayStateController.Callback callback =
                Mockito.mock(DreamOverlayStateController.Callback.class);

        stateController.setAvailableComplicationTypes(
                Complication.COMPLICATION_TYPE_HOME_CONTROLS);
        stateController.addCallback(callback);
        mExecutor.runAllReady();

        {
            clearInvocations(callback);
            stateController.setShouldShowComplications(true);
            mExecutor.runAllReady();

            verify(callback).onAvailableComplicationTypesChanged();
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(homeControlsComplication)).isTrue();
        }

        {
            clearInvocations(callback);
            stateController.setShouldShowComplications(false);
            mExecutor.runAllReady();

            verify(callback).onAvailableComplicationTypesChanged();
            final Collection<Complication> complications = stateController.getComplications();
            assertThat(complications.contains(homeControlsComplication)).isTrue();
        }
    }

    @Test
    public void testHomeControlsDoNotShowIfNotAvailable_featureEnabled() {
        when(mFeatureFlags.isEnabled(Flags.ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS)).thenReturn(true);

        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.setShouldShowComplications(true);

        final Complication homeControlsComplication = Mockito.mock(Complication.class);
        when(homeControlsComplication.getRequiredTypeAvailability())
                .thenReturn(Complication.COMPLICATION_TYPE_HOME_CONTROLS);

        stateController.addComplication(homeControlsComplication);

        final DreamOverlayStateController.Callback callback =
                Mockito.mock(DreamOverlayStateController.Callback.class);

        stateController.addCallback(callback);
        mExecutor.runAllReady();

        // No home controls since it is not available.
        assertThat(stateController.getComplications()).doesNotContain(homeControlsComplication);

        stateController.setAvailableComplicationTypes(Complication.COMPLICATION_TYPE_HOME_CONTROLS
                | Complication.COMPLICATION_TYPE_WEATHER);
        mExecutor.runAllReady();
        assertThat(stateController.getComplications()).contains(homeControlsComplication);
    }

    @Test
    public void testCallbacksIgnoredWhenWeakReferenceCleared() {
        final DreamOverlayStateController.Callback callback1 = Mockito.mock(
                DreamOverlayStateController.Callback.class);
        final DreamOverlayStateController.Callback callback2 = Mockito.mock(
                DreamOverlayStateController.Callback.class);

        final DreamOverlayStateController stateController = getDreamOverlayStateController(true);
        stateController.addCallback(callback1);
        stateController.addCallback(callback2);
        mExecutor.runAllReady();

        // Simulate callback1 getting GC'd by clearing the reference
        mWeakReferenceFactory.clear(callback1);
        stateController.setOverlayActive(true);
        mExecutor.runAllReady();

        // Callback2 should still be called, but never callback1
        verify(callback1, never()).onStateChanged();
        verify(callback2).onStateChanged();
        assertThat(stateController.isOverlayActive()).isTrue();
    }

    private DreamOverlayStateController getDreamOverlayStateController(boolean overlayEnabled) {
        return new DreamOverlayStateController(
                mExecutor,
                overlayEnabled,
                mFeatureFlags,
                mLogBuffer,
                mWeakReferenceFactory
        );
    }
}
