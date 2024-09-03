/*
 * Copyright 2022 The Android Open Source Project
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

package android.app.activity;

import static com.google.common.truth.Truth.assertThat;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.transition.Fade;
import android.view.View;
import android.view.Window;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test for verifying Activity Transitions Drawable behavior
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
@Presubmit
public class ActivityTransitionDrawableTest {
    private static final String LAUNCH_ON_START = "launch on start";

    @Rule
    public final ActivityTestRule<TestActivity> mActivityTestRule =
            new ActivityTestRule<>(TestActivity.class, true);

    @Test
    public void stopTransitionDrawableAlphaRestored() throws Throwable {
        mActivityTestRule.runOnUiThread(() -> {
            Activity activity = mActivityTestRule.getActivity();
            Intent intent = new Intent(activity, TestActivity.class);
            intent.putExtra(LAUNCH_ON_START, true);
            Bundle bundle = ActivityOptions.makeSceneTransitionAnimation(activity).toBundle();
            activity.startActivity(intent, bundle);
        });

        assertThat(TestActivity.activityAdded.await(5, TimeUnit.SECONDS)).isTrue();
        TestActivity topActivity = TestActivity.sInstances.get(2);
        TestActivity middleActivity = TestActivity.sInstances.get(1);
        assertThat(topActivity.startedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(middleActivity.stoppedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        mActivityTestRule.runOnUiThread(() -> {
            assertThat(middleActivity.getWindow().getDecorView().getBackground().getAlpha())
                    .isEqualTo(255);
        });
    }

    public static class TestActivity extends Activity {
        public static final ArrayList<TestActivity> sInstances = new ArrayList<TestActivity>();
        public static CountDownLatch activityAdded = new CountDownLatch(3);

        private boolean mLaunchOnStart = false;
        public CountDownLatch startedLatch = new CountDownLatch(1);
        public CountDownLatch stoppedLatch = new CountDownLatch(1);

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            getWindow().requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
            getWindow().setAllowEnterTransitionOverlap(false);
            setContentView(new View(this));
            Fade longFade = new Fade();
            longFade.setDuration(2000);
            getWindow().setEnterTransition(longFade);
            getWindow().setExitTransition(longFade);
            super.onCreate(savedInstanceState);
            mLaunchOnStart = getIntent().getBooleanExtra(LAUNCH_ON_START, false);
            sInstances.add(this);
            activityAdded.countDown();
        }

        @Override
        protected void onStart() {
            super.onStart();
            if (mLaunchOnStart) {
                mLaunchOnStart = false;
                Intent intent = new Intent(this, TestActivity.class);
                startActivity(intent);
            }
            startedLatch.countDown();
        }

        @Override
        protected void onStop() {
            super.onStop();
            stoppedLatch.countDown();
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            sInstances.remove(this);
        }
    }
}
