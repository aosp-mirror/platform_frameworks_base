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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.smartspace.SmartspaceTarget;
import android.testing.AndroidTestingRunner;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.condition.SelfExecutingMonitor;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.smartspace.DreamSmartspaceController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.shared.condition.Monitor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SmartSpaceComplicationTest extends SysuiTestCase {

    @Mock
    private DreamSmartspaceController mSmartspaceController;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private SmartSpaceComplication mComplication;

    @Mock
    private View mBcSmartspaceView;

    @Mock
    private FeatureFlags mFeatureFlags;

    private Monitor mMonitor;

    private final Set<Condition> mPreconditions = new HashSet<>();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMonitor = SelfExecutingMonitor.createInstance();

        when(mFeatureFlags.isEnabled(Flags.HIDE_SMARTSPACE_ON_DREAM_OVERLAY)).thenReturn(false);
    }

    /**
     * Ensures {@link SmartSpaceComplication} isn't registered right away on start.
     */
    @Test
    public void testRegistrantStart_doesNotAddComplication() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();
        verify(mDreamOverlayStateController, never()).addComplication(eq(mComplication));
    }

    @Test
    public void testRegistrantStart_featureEnabled_addOverlayStateCallback() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        verify(mDreamOverlayStateController).addCallback(any());
    }

    @Test
    public void testRegistrantStart_featureDisabled_doesNotAddOverlayStateCallback() {
        when(mFeatureFlags.isEnabled(Flags.HIDE_SMARTSPACE_ON_DREAM_OVERLAY)).thenReturn(true);

        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        verify(mDreamOverlayStateController, never()).addCallback(any());
    }

    @Test
    public void testOverlayActive_addsTargetListener() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        final ArgumentCaptor<DreamOverlayStateController.Callback> dreamCallbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(dreamCallbackCaptor.capture());

        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        dreamCallbackCaptor.getValue().onStateChanged();

        // Test
        final ArgumentCaptor<BcSmartspaceDataPlugin.SmartspaceTargetListener> listenerCaptor =
                ArgumentCaptor.forClass(BcSmartspaceDataPlugin.SmartspaceTargetListener.class);
        verify(mSmartspaceController).addListener(listenerCaptor.capture());
    }

    @Test
    public void testOverlayActive_targetsNonEmpty_addsComplication() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        final ArgumentCaptor<DreamOverlayStateController.Callback> dreamCallbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(dreamCallbackCaptor.capture());

        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        dreamCallbackCaptor.getValue().onStateChanged();

        final ArgumentCaptor<BcSmartspaceDataPlugin.SmartspaceTargetListener> listenerCaptor =
                ArgumentCaptor.forClass(BcSmartspaceDataPlugin.SmartspaceTargetListener.class);
        verify(mSmartspaceController).addListener(listenerCaptor.capture());

        // Test
        final SmartspaceTarget target = Mockito.mock(SmartspaceTarget.class);
        listenerCaptor.getValue().onSmartspaceTargetsUpdated(Collections.singletonList(target));
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));
    }

    @Test
    public void testOverlayActive_targetsEmpty_addsComplication() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        final ArgumentCaptor<DreamOverlayStateController.Callback> dreamCallbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(dreamCallbackCaptor.capture());

        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        dreamCallbackCaptor.getValue().onStateChanged();

        final ArgumentCaptor<BcSmartspaceDataPlugin.SmartspaceTargetListener> listenerCaptor =
                ArgumentCaptor.forClass(BcSmartspaceDataPlugin.SmartspaceTargetListener.class);
        verify(mSmartspaceController).addListener(listenerCaptor.capture());

        // Test
        listenerCaptor.getValue().onSmartspaceTargetsUpdated(Collections.emptyList());
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));
    }

    @Test
    public void testOverlayInActive_removesTargetListener_removesComplication() {
        final SmartSpaceComplication.Registrant registrant = getRegistrant();
        registrant.start();

        final ArgumentCaptor<DreamOverlayStateController.Callback> dreamCallbackCaptor =
                ArgumentCaptor.forClass(DreamOverlayStateController.Callback.class);
        verify(mDreamOverlayStateController).addCallback(dreamCallbackCaptor.capture());

        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        dreamCallbackCaptor.getValue().onStateChanged();

        final ArgumentCaptor<BcSmartspaceDataPlugin.SmartspaceTargetListener> listenerCaptor =
                ArgumentCaptor.forClass(BcSmartspaceDataPlugin.SmartspaceTargetListener.class);
        verify(mSmartspaceController).addListener(listenerCaptor.capture());

        listenerCaptor.getValue().onSmartspaceTargetsUpdated(Collections.emptyList());
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));

        // Test
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(false);
        dreamCallbackCaptor.getValue().onStateChanged();
        verify(mSmartspaceController).removeListener(listenerCaptor.getValue());
        verify(mDreamOverlayStateController).removeComplication(eq(mComplication));
    }

    @Test
    public void testGetView_reusesSameView() {
        final Complication.ViewHolder viewHolder =
                new SmartSpaceComplication.SmartSpaceComplicationViewHolder(getContext(),
                        mSmartspaceController, mock(ComplicationLayoutParams.class));
        when(mSmartspaceController.buildAndConnectView(any())).thenReturn(mBcSmartspaceView);
        assertEquals(viewHolder.getView(), viewHolder.getView());
    }

    private SmartSpaceComplication.Registrant getRegistrant() {
        return new SmartSpaceComplication.Registrant(
                mDreamOverlayStateController,
                mComplication,
                mSmartspaceController,
                mMonitor,
                mFeatureFlags);
    }
}
