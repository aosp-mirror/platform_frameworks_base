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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.dagger.DreamClockTimeComplicationComponent;
import com.android.systemui.condition.SelfExecutingMonitor;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Monitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamClockTimeComplicationTest extends SysuiTestCase {
    @SuppressWarnings("HidingField")
    @Mock
    private Context mContext;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private DreamClockTimeComplication mComplication;

    @Mock
    private DreamClockTimeComplicationComponent.Factory mComponentFactory;

    @Mock
    private DreamClockTimeComplicationComponent mComponent;

    @Mock
    private DreamClockTimeComplication.DreamClockTimeViewHolder
            mDreamClockTimeViewHolder;

    @Mock
    private ComplicationViewModel mComplicationViewModel;

    @Mock
    private View mView;

    @Mock
    private ComplicationLayoutParams mLayoutParams;

    @Mock
    private DreamClockTimeComplication.DreamClockTimeViewController mViewController;

    @Mock
    private UiEventLogger mUiEventLogger;

    private Monitor mMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mComponentFactory.create()).thenReturn(mComponent);
        when(mComponent.getViewHolder()).thenReturn(mDreamClockTimeViewHolder);
        mMonitor = SelfExecutingMonitor.createInstance();
    }

    /**
     * Ensures {@link DreamClockTimeComplication} is registered.
     */
    @Test
    public void testComplicationAdded() {
        final DreamClockTimeComplication.Registrant registrant =
                new DreamClockTimeComplication.Registrant(
                        mDreamOverlayStateController,
                        mComplication,
                        mMonitor);
        registrant.start();
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));
    }

    /**
     * Verifies {@link DreamClockTimeComplication} has the required type.
     */
    @Test
    public void testComplicationRequiredTypeAvailability() {
        final DreamClockTimeComplication complication =
                new DreamClockTimeComplication(mComponentFactory);
        assertEquals(Complication.COMPLICATION_TYPE_TIME,
                complication.getRequiredTypeAvailability());
    }

    /**
     * Verifies {@link DreamClockTimeComplication.DreamClockTimeViewHolder} is obtainable from its
     * component when the complication creates view.
     */
    @Test
    public void testComplicationViewHolderComponentOnCreateView() {
        final DreamClockTimeComplication complication =
                new DreamClockTimeComplication(mComponentFactory);
        final Complication.ViewHolder viewHolder = complication.createView(mComplicationViewModel);
        verify(mComponent).getViewHolder();
        assertThat(viewHolder).isEqualTo(mDreamClockTimeViewHolder);
    }

    /**
     * Verifies {@link DreamClockTimeComplication.DreamClockTimeViewHolder} has the intended view
     * and layout parameters from constructor.
     */
    @Test
    public void testComplicationViewHolderContentAccessors() {
        final DreamClockTimeComplication.DreamClockTimeViewHolder viewHolder =
                new DreamClockTimeComplication.DreamClockTimeViewHolder(mView, mLayoutParams,
                        mViewController);
        assertThat(viewHolder.getView()).isEqualTo(mView);
        assertThat(viewHolder.getLayoutParams()).isEqualTo(mLayoutParams);
    }
}
