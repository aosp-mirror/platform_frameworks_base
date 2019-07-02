/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.widget;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActionBar;
import android.app.Activity;
import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.core.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ActionBarShadowControllerTest {

    @Mock
    private RecyclerView mRecyclerView;
    @Mock
    private View mScrollView;
    @Mock
    private Activity mActivity;
    @Mock
    private ActionBar mActionBar;
    private Lifecycle mLifecycle;
    private LifecycleOwner mLifecycleOwner;
    private View mView;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mActivity.getActionBar()).thenReturn(mActionBar);
        mView = new View(RuntimeEnvironment.application);
        mLifecycleOwner = () -> mLifecycle;
        mLifecycle = new Lifecycle(mLifecycleOwner);
    }

    @Test
    public void attachToView_shouldAddScrollWatcherAndUpdateActionBar() {
        when(mRecyclerView.canScrollVertically(-1)).thenReturn(false);

        ActionBarShadowController.attachToView(mActivity, mLifecycle, mRecyclerView);

        verify(mActionBar).setElevation(ActionBarShadowController.ELEVATION_LOW);
    }

    @Test
    public void attachToView_scrollView_shouldAddScrollWatcherAndUpdateActionBar() {
        when(mScrollView.canScrollVertically(-1)).thenReturn(false);

        ActionBarShadowController.attachToView(mActivity, mLifecycle, mScrollView);

        verify(mActionBar).setElevation(ActionBarShadowController.ELEVATION_LOW);
    }

    @Test
    public void attachToView_customViewAsActionBar_shouldUpdateElevationOnScroll() {
        // Setup
        mView.setElevation(50);
        when(mRecyclerView.canScrollVertically(-1)).thenReturn(false);
        final ActionBarShadowController controller =
                ActionBarShadowController.attachToView(mView, mLifecycle, mRecyclerView);
        assertThat(mView.getElevation()).isEqualTo(ActionBarShadowController.ELEVATION_LOW);

        // Scroll
        when(mRecyclerView.canScrollVertically(-1)).thenReturn(true);
        controller.mScrollChangeWatcher.onScrollChange(mRecyclerView, 10, 10, 0, 0);
        assertThat(mView.getElevation()).isEqualTo(ActionBarShadowController.ELEVATION_HIGH);
    }

    @Test
    public void attachToView_lifecycleChange_shouldAttachDetach() {
        ActionBarShadowController.attachToView(mActivity, mLifecycle, mRecyclerView);

        verify(mRecyclerView).setOnScrollChangeListener(any());

        mLifecycle.handleLifecycleEvent(ON_START);
        mLifecycle.handleLifecycleEvent(ON_STOP);
        verify(mRecyclerView).setOnScrollChangeListener(isNull());

        mLifecycle.handleLifecycleEvent(ON_START);
        verify(mRecyclerView, times(3)).setOnScrollChangeListener(any());
    }

    @Test
    public void onScrolled_nullAnchorViewAndActivity_shouldNotCrash() {
        final Activity activity = null;
        final ActionBarShadowController controller =
                ActionBarShadowController.attachToView(activity, mLifecycle, mRecyclerView);

        // Scroll
        controller.mScrollChangeWatcher.onScrollChange(mRecyclerView, 10, 10, 0, 0);
        // no crash
    }
}
