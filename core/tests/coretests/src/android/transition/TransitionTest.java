/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.transition;

import android.animation.AnimatorSetActivity;
import android.app.Activity;
import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase2;
import android.transition.Transition.EpicenterCallback;
import android.util.ArrayMap;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

public class TransitionTest extends ActivityInstrumentationTestCase2<AnimatorSetActivity> {
    Activity mActivity;
    public TransitionTest() {
        super(AnimatorSetActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mActivity = getActivity();
    }

    public void testClone() throws Throwable {
        View square1 = mActivity.findViewById(R.id.square1);
        View square2 = mActivity.findViewById(R.id.square2);
        View square3 = mActivity.findViewById(R.id.square3);
        Fade fade = new Fade();
        fade.setStartDelay(1000);
        fade.setDuration(1001);

        fade.addTarget(square1);
        fade.excludeTarget(square2, true);
        fade.excludeChildren(square3, true);

        fade.addTarget(R.id.square4);
        fade.excludeTarget(R.id.square3, true);
        fade.excludeChildren(R.id.square2, true);

        fade.addTarget("hello");
        fade.excludeTarget("world", true);

        fade.addTarget(View.class);
        fade.excludeTarget(TextView.class, true);

        fade.setMatchOrder(Transition.MATCH_ID);
        fade.setPropagation(new CircularPropagation());
        fade.setPathMotion(new ArcMotion());
        fade.setInterpolator(new AccelerateInterpolator());
        fade.setNameOverrides(new ArrayMap<>());

        EpicenterCallback epicenterCallback = new EpicenterCallback() {
            @Override
            public Rect onGetEpicenter(Transition transition) {
                return null;
            }
        };

        fade.setEpicenterCallback(epicenterCallback);

        Fade clone = (Fade) fade.clone();
        assertEquals(fade.mStartDelay, clone.mStartDelay);
        assertEquals(fade.mDuration, clone.mDuration);
        assertEquals(fade.mInterpolator, clone.mInterpolator);
        assertEquals(fade.mPropagation, clone.mPropagation);
        assertEquals(fade.getPathMotion(), clone.getPathMotion());
        assertEquals(fade.getEpicenterCallback(), clone.getEpicenterCallback());
        assertEquals(fade.mNameOverrides, clone.mNameOverrides);
        assertEquals(fade.mMatchOrder, clone.mMatchOrder);

        assertEquals(fade.mTargets, clone.mTargets);
        assertEquals(fade.mTargetExcludes, clone.mTargetExcludes);
        assertEquals(fade.mTargetChildExcludes, clone.mTargetChildExcludes);

        assertEquals(fade.mTargetIds, clone.mTargetIds);
        assertEquals(fade.mTargetIdExcludes, clone.mTargetIdExcludes);
        assertEquals(fade.mTargetIdChildExcludes, clone.mTargetIdChildExcludes);

        assertEquals(fade.mTargetNames, clone.mTargetNames);
        assertEquals(fade.mTargetNameExcludes, clone.mTargetNameExcludes);

        assertEquals(fade.mTargetTypes, clone.mTargetTypes);
        assertEquals(fade.mTargetTypeExcludes, clone.mTargetTypeExcludes);
    }
}
