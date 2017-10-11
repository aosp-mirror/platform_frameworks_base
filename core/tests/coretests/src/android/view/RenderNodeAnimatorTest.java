/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.MediumTest;
import android.support.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

@MediumTest
public class RenderNodeAnimatorTest  {
    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    private Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    private Activity getActivity() {
        return mActivityRule.getActivity();
    }

    @UiThreadTest
    @Test
    public void testAlphaTransformationInfo() throws Throwable {
        View view = new View(getContext());

        // attach the view, since otherwise the RenderNodeAnimator won't accept view as target
        getActivity().setContentView(view);

        RenderNodeAnimator anim = new RenderNodeAnimator(RenderNodeAnimator.ALPHA, 0.5f);
        anim.setTarget(view);
        assertNull(view.mTransformationInfo);
        anim.start(); // should initialize mTransformationInfo
        assertNotNull(view.mTransformationInfo);
    }
}
