/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.dreams;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;


import android.content.Context;
import android.content.res.Resources;
import android.service.dreams.utils.DreamAccessibility;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamAccessibilityTest {

    @Mock
    private View mView;

    @Mock
    private Context mContext;

    @Mock
    private Resources mResources;

    @Mock
    private AccessibilityNodeInfo mAccessibilityNodeInfo;

    @Captor
    private ArgumentCaptor<View.AccessibilityDelegate> mAccessibilityDelegateArgumentCaptor;

    private DreamAccessibility mDreamAccessibility;
    private static final String CUSTOM_ACTION = "Custom Action";
    private static final String EXISTING_ACTION = "Existing Action";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Runnable mDismissCallback = () -> {};
        mDreamAccessibility = new DreamAccessibility(mContext, mView, mDismissCallback);

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getString(R.string.dream_accessibility_action_click))
                .thenReturn(CUSTOM_ACTION);
    }
    /**
     * Test to verify the configuration of accessibility actions within a view delegate.
     */
    @Test
    public void testConfigureAccessibilityActions() {
        when(mView.getAccessibilityDelegate()).thenReturn(null);

        mDreamAccessibility.updateAccessibilityConfiguration();

        verify(mView).setAccessibilityDelegate(mAccessibilityDelegateArgumentCaptor.capture());
        View.AccessibilityDelegate capturedDelegate = mAccessibilityDelegateArgumentCaptor
                .getValue();

        capturedDelegate.onInitializeAccessibilityNodeInfo(mView, mAccessibilityNodeInfo);

        verify(mAccessibilityNodeInfo).addAction(argThat(action ->
                action.getId() == AccessibilityNodeInfo.ACTION_DISMISS
                        && TextUtils.equals(action.getLabel(), CUSTOM_ACTION)));
    }

    /**
     * Test to verify no accessibility configuration is added if one exist.
     */
    @Test
    public void testNotAddingDuplicateAccessibilityConfiguration() {
        View.AccessibilityDelegate existingDelegate = mock(View.AccessibilityDelegate.class);
        when(mView.getAccessibilityDelegate()).thenReturn(existingDelegate);

        mDreamAccessibility.updateAccessibilityConfiguration();

        verify(mView, never()).setAccessibilityDelegate(any());
    }

    /**
     * Test to verify dismiss callback is called
     */
    @Test
    public void testPerformAccessibilityAction() {
        Runnable mockDismissCallback = mock(Runnable.class);
        DreamAccessibility dreamAccessibility = new DreamAccessibility(mContext,
                mView, mockDismissCallback);

        dreamAccessibility.updateAccessibilityConfiguration();

        verify(mView).setAccessibilityDelegate(mAccessibilityDelegateArgumentCaptor.capture());
        View.AccessibilityDelegate capturedDelegate = mAccessibilityDelegateArgumentCaptor
                .getValue();

        boolean result = capturedDelegate.performAccessibilityAction(mView,
                AccessibilityNodeInfo.ACTION_DISMISS, null);

        assertTrue(result);
        verify(mockDismissCallback).run();
    }

}

