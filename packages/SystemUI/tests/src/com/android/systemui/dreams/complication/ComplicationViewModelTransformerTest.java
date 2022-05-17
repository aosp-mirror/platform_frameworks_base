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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.complication.dagger.ComplicationViewModelComponent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ComplicationViewModelTransformerTest extends SysuiTestCase {
    @Mock
    ComplicationViewModelComponent.Factory mFactory;

    @Mock
    ComplicationViewModelComponent mComponent;

    @Mock
    ComplicationViewModelProvider mViewModelProvider;

    @Mock
    ComplicationViewModel mViewModel;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mFactory.create(Mockito.any(), Mockito.any())).thenReturn(mComponent);
        when(mComponent.getViewModelProvider()).thenReturn(mViewModelProvider);
        when(mViewModelProvider.get(Mockito.any(), Mockito.any())).thenReturn(mViewModel);
    }

    /**
     * Ensure the same id is returned for the same complication across invocations.
     */
    @Test
    public void testStableIds() {
        final ComplicationViewModelTransformer transformer =
                new ComplicationViewModelTransformer(mFactory);

        final Complication complication = Mockito.mock(Complication.class);

        ArgumentCaptor<ComplicationId> idCaptor = ArgumentCaptor.forClass(ComplicationId.class);

        transformer.getViewModel(complication);
        verify(mFactory).create(Mockito.any(), idCaptor.capture());
        final ComplicationId firstId = idCaptor.getValue();

        Mockito.clearInvocations(mFactory);

        transformer.getViewModel(complication);
        verify(mFactory).create(Mockito.any(), idCaptor.capture());
        final ComplicationId secondId = idCaptor.getValue();

        assertEquals(secondId, firstId);
    }

    /**
     * Ensure unique ids are assigned to different complications.
     */
    @Test
    public void testUniqueIds() {
        final ComplicationViewModelTransformer transformer =
                new ComplicationViewModelTransformer(mFactory);

        final Complication firstComplication = Mockito.mock(Complication.class);
        final Complication secondComplication = Mockito.mock(Complication.class);

        ArgumentCaptor<ComplicationId> idCaptor = ArgumentCaptor.forClass(ComplicationId.class);

        transformer.getViewModel(firstComplication);
        verify(mFactory).create(Mockito.any(), idCaptor.capture());
        final ComplicationId firstId = idCaptor.getValue();

        Mockito.clearInvocations(mFactory);

        transformer.getViewModel(secondComplication);
        verify(mFactory).create(Mockito.any(), idCaptor.capture());
        final ComplicationId secondId = idCaptor.getValue();

        assertNotEquals(secondId, firstId);
    }
}
