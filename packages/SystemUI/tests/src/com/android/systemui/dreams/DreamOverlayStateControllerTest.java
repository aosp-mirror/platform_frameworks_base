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

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collection;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayStateControllerTest extends SysuiTestCase {
    @Mock
    DreamOverlayStateController.Callback mCallback;

    @Mock
    Complication mComplication;

    final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testStateChange_overlayActive() {
        final DreamOverlayStateController stateController = new DreamOverlayStateController(
                mExecutor);
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
        final DreamOverlayStateController stateController = new DreamOverlayStateController(
                mExecutor);
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
        final DreamOverlayStateController stateController =
                new DreamOverlayStateController(mExecutor);

        stateController.addComplication(mComplication);
        mExecutor.runAllReady();

        // Verify callback occurs on add when an overlay is already present.
        stateController.addCallback(mCallback);
        mExecutor.runAllReady();
        verify(mCallback, times(1)).onComplicationsChanged();
    }

    @Test
    public void testComplicationFilteringWhenShouldShowComplications() {
        final DreamOverlayStateController stateController =
                new DreamOverlayStateController(mExecutor);
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
        final DreamOverlayStateController stateController =
                new DreamOverlayStateController(mExecutor);
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
}
