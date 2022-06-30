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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.widget.FrameLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    public void testViewDetachCancelsRenderNodeAnimator() {
        // Start a RenderNodeAnimator with a long duration time, then detach the target view
        // before the animation completes. Detaching of a View from a window should force cancel all
        // RenderNodeAnimators
        CountDownLatch latch = new CountDownLatch(1);

        FrameLayout container = new FrameLayout(getContext());
        View view = new View(getContext());

        getActivity().runOnUiThread(() -> {
            container.addView(view, new FrameLayout.LayoutParams(100, 100));
            getActivity().setContentView(container);
        });
        getActivity().runOnUiThread(() -> {
            RenderNodeAnimator anim = new RenderNodeAnimator(0, 0, 10f, 30f);
            anim.setDuration(10000);
            anim.setTarget(view);
            anim.addListener(new AnimatorListenerAdapter() {

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    latch.countDown();
                }
            });

            anim.start();
        });

        getActivity().runOnUiThread(()-> {
            container.removeView(view);
        });

        try {
            Assert.assertTrue("onAnimationEnd not invoked",
                    latch.await(3000, TimeUnit.MILLISECONDS));
        } catch (InterruptedException excep) {
            Assert.fail("Interrupted waiting for onAnimationEnd callback");
        }
    }
}
