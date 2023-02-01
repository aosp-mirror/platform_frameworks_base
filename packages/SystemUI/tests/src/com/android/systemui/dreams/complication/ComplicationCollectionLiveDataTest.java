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

package com.android.systemui.dreams.complication;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.lifecycle.Observer;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ComplicationCollectionLiveDataTest extends SysuiTestCase {
    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
    }

    @Test
    /**
     * Ensures registration and callback lifecycles are respected.
     */
    public void testLifecycle() {
        getContext().getMainExecutor().execute(() -> {
            final DreamOverlayStateController stateController =
                    Mockito.mock(DreamOverlayStateController.class);
            final ComplicationCollectionLiveData liveData =
                    new ComplicationCollectionLiveData(stateController);
            final HashSet<Complication> complications = new HashSet<>();
            final Observer<Collection<Complication>> observer = Mockito.mock(Observer.class);
            complications.add(Mockito.mock(Complication.class));

            when(stateController.getComplications()).thenReturn(complications);

            liveData.observeForever(observer);
            ArgumentCaptor<DreamOverlayStateController.Callback> callbackCaptor =
                    ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);

            verify(stateController).addCallback(callbackCaptor.capture());
            verifyUpdate(observer, complications);

            complications.add(Mockito.mock(Complication.class));
            callbackCaptor.getValue().onComplicationsChanged();

            verifyUpdate(observer, complications);

            callbackCaptor.getValue().onAvailableComplicationTypesChanged();

            verifyUpdate(observer, complications);
        });
    }

    void verifyUpdate(Observer<Collection<Complication>> observer,
            Collection<Complication> targetCollection) {
        ArgumentCaptor<Collection<Complication>> collectionCaptor =
                ArgumentCaptor.forClass(Collection.class);

        verify(observer).onChanged(collectionCaptor.capture());

        final Collection collection =  collectionCaptor.getValue();
        assertThat(collection.containsAll(targetCollection)
                && targetCollection.containsAll(collection)).isTrue();
        Mockito.clearInvocations(observer);
    }
}
