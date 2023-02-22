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

package com.android.internal.app;

import static android.util.PollingCheck.waitFor;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isSelected;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.ExpectedBlocker.NO_BLOCKER;
import static com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.ExpectedBlocker.PERSONAL_PROFILE_BLOCKER;
import static com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.ExpectedBlocker.WORK_PROFILE_BLOCKER;
import static com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.Tab.PERSONAL;
import static com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.Tab.WORK;
import static com.android.internal.app.ResolverWrapperActivity.sOverrides;

import static org.hamcrest.CoreMatchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.companion.DeviceFilter;
import android.content.Intent;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.ResolverActivityWorkProfileTest.TestCase.Tab;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@DeviceFilter.MediumType
@RunWith(Parameterized.class)
public class ResolverActivityWorkProfileTest {

    private static final UserHandle PERSONAL_USER_HANDLE = InstrumentationRegistry
            .getInstrumentation().getTargetContext().getUser();
    private static final UserHandle WORK_USER_HANDLE = UserHandle.of(10);

    @Rule
    public ActivityTestRule<ResolverWrapperActivity> mActivityRule =
            new ActivityTestRule<>(ResolverWrapperActivity.class, false,
                    false);
    private final TestCase mTestCase;

    public ResolverActivityWorkProfileTest(TestCase testCase) {
        mTestCase = testCase;
    }

    @Before
    public void cleanOverrideData() {
        sOverrides.reset();
    }

    @Test
    public void testBlocker() {
        setUpPersonalAndWorkComponentInfos();
        sOverrides.hasCrossProfileIntents = mTestCase.hasCrossProfileIntents();
        sOverrides.myUserId = mTestCase.getMyUserHandle().getIdentifier();

        launchActivity(/* callingUser= */ mTestCase.getExtraCallingUser());
        switchToTab(mTestCase.getTab());

        switch (mTestCase.getExpectedBlocker()) {
            case NO_BLOCKER:
                assertNoBlockerDisplayed();
                break;
            case PERSONAL_PROFILE_BLOCKER:
                assertCantAccessPersonalAppsBlockerDisplayed();
                break;
            case WORK_PROFILE_BLOCKER:
                assertCantAccessWorkAppsBlockerDisplayed();
                break;
        }
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection tests() {
        return Arrays.asList(
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ WORK_PROFILE_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ PERSONAL_PROFILE_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ null,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),

                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ WORK,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ WORK_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ true,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                ),
                new TestCase(
                        /* extraCallingUser= */ WORK_USER_HANDLE,
                        /* hasCrossProfileIntents= */ false,
                        /* myUserHandle= */ PERSONAL_USER_HANDLE,
                        /* tab= */ PERSONAL,
                        /* expectedBlocker= */ NO_BLOCKER
                )
        );
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults, int userId) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(
                    ResolverDataProvider.createResolvedComponentInfoWithOtherId(i, userId));
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTest(int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
        }
        return infoList;
    }

    private void setUpPersonalAndWorkComponentInfos() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3,
                        /* userId */ WORK_USER_HANDLE.getIdentifier());
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
    }

    private void setupResolverControllers(
            List<ResolvedComponentInfo> personalResolvedComponentInfos,
            List<ResolvedComponentInfo> workResolvedComponentInfos) {
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntentAsUser(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class),
                eq(UserHandle.SYSTEM)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void markWorkProfileUserAvailable() {
        ResolverWrapperActivity.sOverrides.workProfileUserHandle = WORK_USER_HANDLE;
    }

    private void assertCantAccessWorkAppsBlockerDisplayed() {
        onView(withText(R.string.resolver_cross_profile_blocked))
                .check(matches(isDisplayed()));
        onView(withText(R.string.resolver_cant_access_work_apps_explanation))
                .check(matches(isDisplayed()));
    }

    private void assertCantAccessPersonalAppsBlockerDisplayed() {
        onView(withText(R.string.resolver_cross_profile_blocked))
                .check(matches(isDisplayed()));
        onView(withText(R.string.resolver_cant_access_personal_apps_explanation))
                .check(matches(isDisplayed()));
    }

    private void assertNoBlockerDisplayed() {
        try {
            onView(withText(R.string.resolver_cross_profile_blocked))
                    .check(matches(not(isDisplayed())));
        } catch (NoMatchingViewException ignored) {
        }
    }

    private void switchToTab(Tab tab) {
        final int stringId = tab == Tab.WORK ? R.string.resolver_work_tab
                : R.string.resolver_personal_tab;

        waitFor(() -> {
            onView(withText(stringId)).perform(click());
            waitForIdle();

            try {
                onView(withText(stringId)).check(matches(isSelected()));
                return true;
            } catch (AssertionFailedError e) {
                return false;
            }
        });

        onView(withId(R.id.contentPanel))
                .perform(swipeUp());
        waitForIdle();
    }

    private Intent createSendImageIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("image/jpeg");
        return sendIntent;
    }

    private void launchActivity(UserHandle callingUser) {
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        if (callingUser != null) {
            sendIntent.putExtra(ResolverActivity.EXTRA_CALLING_USER, callingUser);
        }

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
    }

    public static class TestCase {
        @Nullable
        private final UserHandle mExtraCallingUser;
        private final boolean mHasCrossProfileIntents;
        private final UserHandle mMyUserHandle;
        private final Tab mTab;
        private final ExpectedBlocker mExpectedBlocker;

        public enum ExpectedBlocker {
            NO_BLOCKER,
            PERSONAL_PROFILE_BLOCKER,
            WORK_PROFILE_BLOCKER
        }

        public enum Tab {
            WORK,
            PERSONAL
        }

        public TestCase(@Nullable UserHandle extraCallingUser, boolean hasCrossProfileIntents,
                UserHandle myUserHandle, Tab tab, ExpectedBlocker expectedBlocker) {
            mExtraCallingUser = extraCallingUser;
            mHasCrossProfileIntents = hasCrossProfileIntents;
            mMyUserHandle = myUserHandle;
            mTab = tab;
            mExpectedBlocker = expectedBlocker;
        }

        @Nullable
        public UserHandle getExtraCallingUser() {
            return mExtraCallingUser;
        }

        public boolean hasCrossProfileIntents() {
            return mHasCrossProfileIntents;
        }

        public UserHandle getMyUserHandle() {
            return mMyUserHandle;
        }

        public Tab getTab() {
            return mTab;
        }

        public ExpectedBlocker getExpectedBlocker() {
            return mExpectedBlocker;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder("test");

            if (mTab == WORK) {
                result.append("WorkTab_");
            } else {
                result.append("PersonalTab_");
            }

            if (mExtraCallingUser != null
                    && !mExtraCallingUser.equals(PERSONAL_USER_HANDLE)) {
                result.append("callingUserIsNonPersonal_");
            } else {
                result.append("callingUserIsPersonal_");
            }

            if (mHasCrossProfileIntents) {
                result.append("hasCrossProfileIntents_");
            } else {
                result.append("doesNotHaveCrossProfileIntents_");
            }

            if (mMyUserHandle.equals(PERSONAL_USER_HANDLE)) {
                result.append("myUserIsPersonal_");
            } else {
                result.append("myUserIsWork_");
            }

            if (mExpectedBlocker == ExpectedBlocker.NO_BLOCKER) {
                result.append("thenNoBlocker");
            } else if (mExpectedBlocker == ExpectedBlocker.PERSONAL_PROFILE_BLOCKER) {
                result.append("thenBlockerOnPersonalProfile");
            } else if (mExpectedBlocker == WORK_PROFILE_BLOCKER) {
                result.append("thenBlockerOnWorkProfile");
            }

            return result.toString();
        }
    }
}
