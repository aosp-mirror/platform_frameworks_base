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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.lifecycle.Observer;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.log.core.FakeLogBuffer;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.reference.FakeWeakReferenceFactory;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collection;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class ComplicationCollectionLiveDataTest extends SysuiTestCase {

    private FakeExecutor mExecutor;
    private DreamOverlayStateController mStateController;
    private ComplicationCollectionLiveData mLiveData;
    private FakeFeatureFlags mFeatureFlags;
    @Mock
    private Observer mObserver;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mFeatureFlags = new FakeFeatureFlags();
        mExecutor = new FakeExecutor(new FakeSystemClock());
        mFeatureFlags.set(Flags.ALWAYS_SHOW_HOME_CONTROLS_ON_DREAMS, true);
        mStateController = new DreamOverlayStateController(
                mExecutor,
                /* overlayEnabled= */ true,
                mFeatureFlags,
                FakeLogBuffer.Factory.Companion.create(),
                new FakeWeakReferenceFactory());
        mLiveData = new ComplicationCollectionLiveData(mStateController);
    }

    @Test
    /**
     * Ensures registration and callback lifecycles are respected.
     */
    public void testLifecycle() {
        final HashSet<Complication> complications = new HashSet<>();
        mLiveData.observeForever(mObserver);
        mExecutor.runAllReady();
        // Verify observer called with empty complications
        assertObserverCalledWith(complications);

        addComplication(mock(Complication.class), complications);
        assertObserverCalledWith(complications);

        addComplication(mock(Complication.class), complications);
        assertObserverCalledWith(complications);

        mStateController.setAvailableComplicationTypes(0);
        mExecutor.runAllReady();
        assertObserverCalledWith(complications);
        mLiveData.removeObserver(mObserver);
    }

    private void assertObserverCalledWith(Collection<Complication> targetCollection) {
        ArgumentCaptor<Collection<Complication>> collectionCaptor =
                ArgumentCaptor.forClass(Collection.class);

        verify(mObserver).onChanged(collectionCaptor.capture());

        final Collection<Complication> collection = collectionCaptor.getValue();

        assertThat(collection.containsAll(targetCollection)
                && targetCollection.containsAll(collection)).isTrue();
        Mockito.clearInvocations(mObserver);
    }

    private void addComplication(Complication complication,
            Collection<Complication> complications) {
        complications.add(complication);
        mStateController.addComplication(complication);
        mExecutor.runAllReady();
    }
}
