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

package android.service.games;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isClickable;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.ext.truth.content.IntentSubject.assertThat;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.espresso.NoActivityResumedException;
import androidx.test.filters.SmallTest;

import com.android.internal.infra.AndroidFuture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Unit tests for the {@link GameSessionTrampolineActivity}.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@Presubmit
public class GameSessionTrampolineActivityTest {

    @Before
    public void setUp() {
        setAlwaysFinishActivities(false);
    }

    @After
    public void tearDown() {
        setAlwaysFinishActivities(false);
    }

    @Test
    public void launch_launchesTargetActivity() {
        AndroidFuture<GameSessionActivityResult> unusedResultFuture =
                startTestActivityViaGameSessionTrampolineActivity();

        TestActivityPage.assertPageIsLaunched();
    }

    @Test
    public void launch_targetActivityFinishesSuccessfully_futureCompletedWithSameResults() {
        AndroidFuture<GameSessionActivityResult> resultFuture =
                startTestActivityViaGameSessionTrampolineActivity();

        TestActivityPage.assertPageIsLaunched();
        TestActivityPage.clickFinish();

        GameSessionActivityResult expectedResult =
                new GameSessionActivityResult(Activity.RESULT_OK, TestActivity.RESULT_INTENT);

        assertEquals(resultFuture, expectedResult);

        TestActivityPage.assertPageIsNotLaunched();
    }

    @Test
    public void launch_trampolineActivityProcessDeath_futureCompletedWithSameResults() {
        setAlwaysFinishActivities(true);

        AndroidFuture<GameSessionActivityResult> resultFuture =
                startTestActivityViaGameSessionTrampolineActivity();

        TestActivityPage.assertPageIsLaunched();
        TestActivityPage.clickFinish();

        GameSessionActivityResult expectedResult =
                new GameSessionActivityResult(Activity.RESULT_OK, TestActivity.RESULT_INTENT);

        assertEquals(resultFuture, expectedResult);

        TestActivityPage.assertPageIsNotLaunched();
    }

    private static void assertEquals(
            AndroidFuture<GameSessionActivityResult> actualFuture,
            GameSessionActivityResult expected) {
        try {
            assertEquals(actualFuture.get(20, TimeUnit.SECONDS), expected);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void assertEquals(
            GameSessionActivityResult actual,
            GameSessionActivityResult expected) {
        assertThat(actual.getResultCode()).isEqualTo(expected.getResultCode());
        assertThat(actual.getData()).filtersEquallyTo(actual.getData());
    }

    private static void setAlwaysFinishActivities(boolean isEnabled) {
        try {
            ActivityManager.getService().setAlwaysFinish(isEnabled);
        } catch (RemoteException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AndroidFuture<GameSessionActivityResult>
            startTestActivityViaGameSessionTrampolineActivity() {
        Intent testActivityIntent = new Intent();
        testActivityIntent.setClass(getInstrumentation().getTargetContext(), TestActivity.class);

        return startGameSessionTrampolineActivity(testActivityIntent);
    }

    private static AndroidFuture<GameSessionActivityResult> startGameSessionTrampolineActivity(
            Intent targetIntent) {
        AndroidFuture<GameSessionActivityResult> resultFuture = new AndroidFuture<>();
        Intent trampolineActivityIntent = GameSessionTrampolineActivity.createIntent(targetIntent,
                null, resultFuture);
        trampolineActivityIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getInstrumentation().getTargetContext().startActivity(trampolineActivityIntent);
        getInstrumentation().waitForIdleSync();

        return resultFuture;
    }


    private static class TestActivityPage {
        private TestActivityPage() {}

        public static void assertPageIsLaunched() {
            onView(withText(TestActivity.PAGE_TITLE_TEXT)).check(matches(isDisplayed()));
        }

        public static void assertPageIsNotLaunched() {
            try {
                onView(withText(TestActivity.PAGE_TITLE_TEXT)).check(doesNotExist());
            } catch (NoActivityResumedException ex) {
                // Do nothing
            }
        }

        public static void clickFinish() {
            onView(allOf(withText(TestActivity.FINISH_BUTTON_TEXT), isClickable())).perform(
                    click());
            getInstrumentation().waitForIdleSync();
        }
    }

    public static class TestActivity extends Activity {
        private static final String PAGE_TITLE_TEXT = "GameSessionTestActivity";
        private static final String FINISH_BUTTON_TEXT = "Finish Test Activity";
        private static final Intent RESULT_INTENT = new Intent("com.test.action.VIEW");

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            LinearLayout contentLayout = new LinearLayout(this);
            contentLayout.setOrientation(LinearLayout.VERTICAL);

            TextView titleTextView = new TextView(this);
            titleTextView.setText(PAGE_TITLE_TEXT);
            contentLayout.addView(titleTextView);

            Button finishActivityButton = new Button(this);
            finishActivityButton.setText(FINISH_BUTTON_TEXT);
            finishActivityButton.setOnClickListener((unused) -> {
                setResult(Activity.RESULT_OK, RESULT_INTENT);
                finish();
            });


            contentLayout.addView(finishActivityButton);
            setContentView(contentLayout);
        }
    }
}
