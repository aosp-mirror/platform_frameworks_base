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
import android.support.test.filters.LargeTest;
import android.test.ActivityInstrumentationTestCase2;
import android.transition.Transition.EpicenterCallback;
import android.util.ArrayMap;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

import java.lang.reflect.Field;

@LargeTest
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
        assertFieldEquals(fade, clone, "mStartDelay");
        assertFieldEquals(fade, clone, "mDuration");
        assertFieldEquals(fade, clone, "mInterpolator");
        assertFieldEquals(fade, clone, "mPropagation");
        assertEquals(fade.getPathMotion(), clone.getPathMotion());
        assertEquals(fade.getEpicenterCallback(), clone.getEpicenterCallback());
        assertFieldEquals(fade, clone, "mNameOverrides");
        assertFieldEquals(fade, clone, "mMatchOrder");

        assertFieldEquals(fade, clone, "mTargets");
        assertFieldEquals(fade, clone, "mTargetExcludes");
        assertFieldEquals(fade, clone, "mTargetChildExcludes");

        assertFieldEquals(fade, clone, "mTargetIds");
        assertFieldEquals(fade, clone, "mTargetIdExcludes");
        assertFieldEquals(fade, clone, "mTargetIdChildExcludes");

        assertFieldEquals(fade, clone, "mTargetNames");
        assertFieldEquals(fade, clone, "mTargetNameExcludes");

        assertFieldEquals(fade, clone, "mTargetTypes");
        assertFieldEquals(fade, clone, "mTargetTypeExcludes");
    }

    private static void assertFieldEquals(Fade fade1, Fade fade2, String fieldName)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(Fade.class, fieldName);
        field.setAccessible(true);
        assertEquals("Field '" + fieldName + "' value mismatch", field.get(fade1),
                field.get(fade2));
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                // try the parent
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
