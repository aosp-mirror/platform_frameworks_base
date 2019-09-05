/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.util.FloatProperty;
import android.util.Property;
import android.view.View;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.stack.AnimationFilter;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ViewState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class PropertyAnimatorTest extends SysuiTestCase {

    private View mView;
    private FloatProperty<View> mEffectiveProperty = new FloatProperty<View>("TEST") {
        public float mValue = 100;

        @Override
        public void setValue(View view, float value) {
            mValue = value;
        }

        @Override
        public Float get(View object) {
            return mValue;
        }
    };
    private AnimatableProperty mProperty
            = new AnimatableProperty() {

        @Override
        public int getAnimationStartTag() {
            return R.id.scale_x_animator_start_value_tag;
        }

        @Override
        public int getAnimationEndTag() {
            return R.id.scale_x_animator_end_value_tag;
        }

        @Override
        public int getAnimatorTag() {
            return R.id.scale_x_animator_tag;
        }

        @Override
        public Property getProperty() {
            return mEffectiveProperty;
        }
    };
    private AnimatorListenerAdapter mFinishListener = mock(AnimatorListenerAdapter.class);
    private AnimationProperties mAnimationProperties = new AnimationProperties() {
        @Override
        public AnimationFilter getAnimationFilter() {
            return mAnimationFilter;
        }

        @Override
        public AnimatorListenerAdapter getAnimationFinishListener() {
            return mFinishListener;
        }
    }.setDuration(200);
    private AnimationFilter mAnimationFilter = new AnimationFilter();
    private Interpolator mTestInterpolator = Interpolators.ALPHA_IN;


    @Before
    public void setUp() {
        mView = new View(getContext());
    }

    @Test
    public void testAnimationStarted() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200, mAnimationProperties);
        assertTrue(ViewState.isAnimating(mView, mProperty));
    }

    @Test
    public void testNoAnimationStarted() {
        mAnimationFilter.reset();
        PropertyAnimator.startAnimation(mView, mProperty, 200, mAnimationProperties);
        assertFalse(ViewState.isAnimating(mView, mProperty));
    }

    @Test
    public void testEndValueUpdated() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        assertEquals(ViewState.getChildTag(mView, mProperty.getAnimationEndTag()),
                Float.valueOf(200f));
    }

    @Test
    public void testStartTagUpdated() {
        mEffectiveProperty.set(mView, 100f);
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        assertEquals(ViewState.getChildTag(mView, mProperty.getAnimationStartTag()),
                Float.valueOf(100f));
    }

    @Test
    public void testValueIsSetUnAnimated() {
        mAnimationFilter.reset();
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        assertEquals(Float.valueOf(200f), mEffectiveProperty.get(mView));
    }

    @Test
    public void testAnimationToRightValueUpdated() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        mAnimationFilter.reset();
        PropertyAnimator.startAnimation(mView, mProperty, 220f, mAnimationProperties);
        assertTrue(ViewState.isAnimating(mView, mProperty));
        assertEquals(ViewState.getChildTag(mView, mProperty.getAnimationEndTag()),
                Float.valueOf(220f));
    }

    @Test
    public void testAnimationToRightValueUpdateAnimated() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 220f, mAnimationProperties);
        assertTrue(ViewState.isAnimating(mView, mProperty));
        assertEquals(ViewState.getChildTag(mView, mProperty.getAnimationEndTag()),
                Float.valueOf(220f));
    }

    @Test
    public void testStartTagShiftedWhenChanging() {
        mEffectiveProperty.set(mView, 100f);
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        mAnimationFilter.reset();
        PropertyAnimator.startAnimation(mView, mProperty, 220f, mAnimationProperties);
        assertEquals(ViewState.getChildTag(mView, mProperty.getAnimationStartTag()),
                Float.valueOf(120f));
    }

    @Test
    public void testUsingDuration() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        mAnimationProperties.setDuration(500);
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        ValueAnimator animator = ViewState.getChildTag(mView, mProperty.getAnimatorTag());
        assertNotNull(animator);
        assertEquals(animator.getDuration(), 500);
    }

    @Test
    public void testUsingDelay() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        mAnimationProperties.setDelay(200);
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        ValueAnimator animator = ViewState.getChildTag(mView, mProperty.getAnimatorTag());
        assertNotNull(animator);
        assertEquals(animator.getStartDelay(), 200);
    }

    @Test
    public void testUsingInterpolator() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        mAnimationProperties.setCustomInterpolator(mEffectiveProperty, mTestInterpolator);
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        ValueAnimator animator = ViewState.getChildTag(mView, mProperty.getAnimatorTag());
        assertNotNull(animator);
        assertEquals(animator.getInterpolator(), mTestInterpolator);
    }

    @Test
    public void testUsingListener() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        mAnimationProperties.setCustomInterpolator(mEffectiveProperty, mTestInterpolator);
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        ValueAnimator animator = ViewState.getChildTag(mView, mProperty.getAnimatorTag());
        assertNotNull(animator);
        assertTrue(animator.getListeners().contains(mFinishListener));
    }

    @Test
    public void testIsAnimating() {
        mAnimationFilter.reset();
        mAnimationFilter.animate(mProperty.getProperty());
        assertFalse(PropertyAnimator.isAnimating(mView, mProperty));
        PropertyAnimator.startAnimation(mView, mProperty, 200f, mAnimationProperties);
        assertTrue(PropertyAnimator.isAnimating(mView, mProperty));
    }
}
