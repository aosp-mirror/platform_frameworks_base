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

package com.android.internal.widget;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.Context;
import android.content.res.Resources;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.test.AndroidTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.flags.Flags;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ViewGroupFader}.
 */
public class ViewGroupFaderTest extends AndroidTestCase {

    private Context mContext;
    private ViewGroupFader mViewGroupFader;
    private Resources mResources;

    @Mock
    private ViewGroup mViewGroup,mViewGroup1;

    @Mock
    private ViewGroupFader mockViewGroupFader;

    @Mock
    private ViewGroupFader.AnimationCallback mAnimationCallback;

    @Mock
    private ViewGroupFader.ChildViewBoundsProvider mChildViewBoundsProvider;

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        final Context mContext = getInstrumentation().getContext();
        mResources = spy(mContext.getResources());
        when(mResources.getBoolean(com.android.internal.R.bool.config_enableViewGroupScalingFading))
                .thenReturn(true);
        when(mViewGroup.getResources()).thenReturn(mResources);

        mViewGroupFader = new ViewGroupFader(
                mViewGroup,
                mAnimationCallback,
                mChildViewBoundsProvider);
    }

    /** This test checks that for each child of the parent viewgroup,
     * updateListElementFades is called for each of its child, when the Flag is set to true
     */
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_FADING_VIEW_GROUP)
    public void testFadingAndScrollingAnimationWorking_FlagOn() {
        mViewGroup.addView(mViewGroup1);
        mViewGroupFader.updateFade();

        for (int i = 0; i < mViewGroup.getChildCount(); i++) {
            View child = mViewGroup.getChildAt(i);
            verify(mockViewGroupFader).updateListElementFades((ViewGroup)child,true);
        }
    }

    /** This test checks that for each child of the parent viewgroup,
     * updateListElementFades is never called for each of its child, when the Flag is set to false
     */
    @Test
    public void testFadingAndScrollingAnimationNotWorking_FlagOff() {
        mViewGroup.addView(mViewGroup1);
        mViewGroupFader.updateFade();

        for (int i = 0; i < mViewGroup.getChildCount(); i++) {
            View child = mViewGroup.getChildAt(i);
            verify(mockViewGroupFader,never()).updateListElementFades((ViewGroup)child,true);
        }
    }
}