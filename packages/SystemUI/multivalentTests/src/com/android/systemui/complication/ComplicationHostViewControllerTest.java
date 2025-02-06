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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableLooper;
import android.testing.ViewUtils;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class ComplicationHostViewControllerTest extends SysuiTestCase {
    ConstraintLayout mComplicationHostView;

    @Mock
    LifecycleOwner mLifecycleOwner;

    @Mock
    LiveData<Collection<ComplicationViewModel>> mComplicationViewModelLiveData;

    @Mock
    ComplicationCollectionViewModel mViewModel;

    @Mock
    ComplicationViewModel mComplicationViewModel;

    @Mock
    ComplicationLayoutEngine mLayoutEngine;

    @Mock
    ComplicationId mComplicationId;

    @Mock
    Complication mComplication;

    @Mock
    Complication.ViewHolder mViewHolder;

    @Mock
    View mComplicationView;

    @Mock
    ComplicationLayoutParams mComplicationLayoutParams;

    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    @Captor
    private ArgumentCaptor<Observer<Collection<ComplicationViewModel>>> mObserverCaptor;

    @Complication.Category
    static final int COMPLICATION_CATEGORY = Complication.CATEGORY_SYSTEM;

    private ComplicationHostViewController mController;

    private SecureSettings mSecureSettings;

    private KosmosJavaAdapter mKosmos;

    private TestableLooper mLooper;

    private static final int CURRENT_USER_ID = UserHandle.USER_SYSTEM;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mViewModel.getComplications()).thenReturn(mComplicationViewModelLiveData);
        when(mComplicationViewModel.getId()).thenReturn(mComplicationId);
        when(mComplicationViewModel.getComplication()).thenReturn(mComplication);
        when(mComplication.createView(eq(mComplicationViewModel))).thenReturn(mViewHolder);
        when(mViewHolder.getView()).thenReturn(mComplicationView);
        when(mViewHolder.getCategory()).thenReturn(COMPLICATION_CATEGORY);
        when(mViewHolder.getLayoutParams()).thenReturn(mComplicationLayoutParams);
        when(mComplicationView.getParent()).thenReturn(mComplicationHostView);

        mLooper = TestableLooper.get(this);
        mKosmos = new KosmosJavaAdapter(this);
        mSecureSettings = new FakeSettings();
        mComplicationHostView = new ConstraintLayout(getContext());
        mSecureSettings.putFloatForUser(
                Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f, CURRENT_USER_ID);

        mController = new ComplicationHostViewController(
                mComplicationHostView,
                mLayoutEngine,
                mDreamOverlayStateController,
                mLifecycleOwner,
                mViewModel,
                mSecureSettings,
                mKosmos.getConfigurationInteractor(),
                mKosmos.getTestDispatcher()
        );

        mController.init();
    }

    /**
     * Ensures layout engine update is called on configuration change.
     */
    @Test
    public void testUpdateLayoutEngineOnConfigurationChange() {
        mController.onViewAttached();
        // Attach the complication host view so flows collecting on it start running.
        ViewUtils.attachView(mComplicationHostView);
        mLooper.processAllMessages();

        // emit configuration change
        Rect bounds = new Rect(0, 0, 2000, 2000);
        Configuration config = new Configuration();
        config.windowConfiguration.setMaxBounds(bounds);
        mKosmos.getConfigurationRepository().onConfigurationChange(config);
        mKosmos.getTestScope().getTestScheduler().runCurrent();

        verify(mLayoutEngine).updateLayoutEngine(bounds);
    }

    /**
     * Ensures the lifecycle of complications is properly handled.
     */
    @Test
    public void testViewModelObservation() {
        mController.onViewAttached();
        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();

        // Add a complication and ensure it is added to the view.
        final HashSet<ComplicationViewModel> complications = new HashSet<>(
                Collections.singletonList(mComplicationViewModel));
        observer.onChanged(complications);

        verify(mLayoutEngine).addComplication(eq(mComplicationId), eq(mComplicationView),
                eq(mComplicationLayoutParams), eq(COMPLICATION_CATEGORY));

        // Remove complication and ensure it is removed from the view by id.
        observer.onChanged(new HashSet<>());

        verify(mLayoutEngine).removeComplication(eq(mComplicationId));
    }

    @Test
    public void testMalformedComplicationAddition() {
        mController.onViewAttached();
        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();

        // Add a complication and ensure it is added to the view.
        final HashSet<ComplicationViewModel> complications = new HashSet<>(
                Collections.singletonList(mComplicationViewModel));
        when(mViewHolder.getView()).thenReturn(null);
        observer.onChanged(complications);

        verify(mLayoutEngine, never()).addComplication(any(), any(), any(), anyInt());

    }

    @Test
    public void testNewComplicationsBeforeEntryAnimationsFinishSetToInvisible() {
        mController.onViewAttached();
        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();

        // Add a complication before entry animations are finished.
        final HashSet<ComplicationViewModel> complications = new HashSet<>(
                Collections.singletonList(mComplicationViewModel));
        observer.onChanged(complications);

        // The complication view should be set to invisible.
        verify(mComplicationView).setVisibility(View.INVISIBLE);
    }

    @Test
    public void testNewComplicationsAfterEntryAnimationsFinishNotSetToInvisible() {
        mController.onViewAttached();
        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();

        // Dream entry animations finished.
        when(mDreamOverlayStateController.areEntryAnimationsFinished()).thenReturn(true);

        // Add a complication after entry animations are finished.
        final HashSet<ComplicationViewModel> complications = new HashSet<>(
                Collections.singletonList(mComplicationViewModel));
        observer.onChanged(complications);

        // The complication view should not be set to invisible.
        verify(mComplicationView, never()).setVisibility(View.INVISIBLE);
    }

    @Test
    public void testAnimationsDisabled_ComplicationsNeverSetToInvisible() {
        mController.onViewAttached();
        //Disable animations
        mController.mIsAnimationEnabled = false;

        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();

        // Add a complication before entry animations are finished.
        final HashSet<ComplicationViewModel> complications = new HashSet<>(
                Collections.singletonList(mComplicationViewModel));
        observer.onChanged(complications);

        // The complication view should not be set to invisible.
        verify(mComplicationView, never()).setVisibility(View.INVISIBLE);
    }

    @Test
    public void testLifecycleObserve_activeOnlyDuringAttachedState() {
        verify(mComplicationViewModelLiveData, never()).observe(any(), any());
        mController.onViewAttached();
        final Observer<Collection<ComplicationViewModel>> observer =
                captureComplicationViewModelsObserver();
        mController.onViewDetached();
        verify(mComplicationViewModelLiveData).removeObserver(eq(observer));
    }

    private Observer<Collection<ComplicationViewModel>> captureComplicationViewModelsObserver() {
        verify(mComplicationViewModelLiveData).observe(eq(mLifecycleOwner),
                mObserverCaptor.capture());
        return mObserverCaptor.getValue();
    }
}
