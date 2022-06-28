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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamClockDateComplicationTest extends SysuiTestCase {
    @SuppressWarnings("HidingField")
    @Mock
    private Context mContext;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private DreamClockDateComplication mComplication;

    @Mock
    private Provider<DreamClockDateComplication.DreamClockDateViewHolder>
            mDreamClockDateViewHolderProvider;

    @Mock
    private DreamClockDateComplication.DreamClockDateViewHolder
            mDreamClockDateViewHolder;

    @Mock
    private ComplicationViewModel mComplicationViewModel;

    @Mock
    private View mView;

    @Mock
    private ComplicationLayoutParams mLayoutParams;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mDreamClockDateViewHolderProvider.get()).thenReturn(mDreamClockDateViewHolder);

    }

    /**
     * Ensures {@link DreamClockDateComplication} is registered.
     */
    @Test
    public void testComplicationAdded() {
        final DreamClockDateComplication.Registrant registrant =
                new DreamClockDateComplication.Registrant(
                        mContext,
                        mDreamOverlayStateController,
                        mComplication);
        registrant.start();
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));
    }

    /**
     * Verifies {@link DreamClockDateComplication} has the required type.
     */
    @Test
    public void testComplicationRequiredTypeAvailability() {
        final DreamClockDateComplication complication =
                new DreamClockDateComplication(mDreamClockDateViewHolderProvider);
        assertEquals(Complication.COMPLICATION_TYPE_DATE,
                complication.getRequiredTypeAvailability());
    }

    /**
     * Verifies {@link DreamClockDateComplication.DreamClockDateViewHolder} is obtainable from its
     * provider when the complication creates view.
     */
    @Test
    public void testComplicationViewHolderProviderOnCreateView() {
        final DreamClockDateComplication complication =
                new DreamClockDateComplication(mDreamClockDateViewHolderProvider);
        final Complication.ViewHolder viewHolder = complication.createView(mComplicationViewModel);
        verify(mDreamClockDateViewHolderProvider).get();
        assertThat(viewHolder).isEqualTo(mDreamClockDateViewHolder);
    }

    /**
     * Verifies {@link DreamClockDateComplication.DreamClockDateViewHolder} has the intended view
     * and layout parameters from constructor.
     */
    @Test
    public void testComplicationViewHolderContentAccessors() {
        final DreamClockDateComplication.DreamClockDateViewHolder viewHolder =
                new DreamClockDateComplication.DreamClockDateViewHolder(mView, mLayoutParams);
        assertThat(viewHolder.getView()).isEqualTo(mView);
        assertThat(viewHolder.getLayoutParams()).isEqualTo(mLayoutParams);
    }
}
