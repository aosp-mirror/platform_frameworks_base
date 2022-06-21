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

package com.android.internal.app;

import static android.app.Activity.RESULT_OK;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.internal.app.ChooserActivity.TARGET_TYPE_CHOOSER_TARGET;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_DEFAULT;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER;
import static com.android.internal.app.ChooserListAdapter.CALLER_TARGET_SCORE_BOOST;
import static com.android.internal.app.ChooserListAdapter.SHORTCUT_TARGET_SCORE_BOOST;
import static com.android.internal.app.MatcherUtils.first;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.usage.UsageStatsManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.chooser.ChooserTarget;
import android.view.View;

import androidx.annotation.CallSuper;
import androidx.test.espresso.matcher.BoundedDiagnosingMatcher;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.GridLayoutManager;
import com.android.internal.widget.RecyclerView;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Instrumentation tests for chooser activities that derive from the system
 * {@code com.android.internal.ChooserActivity}. This class is used directly to test the system
 * implementation, but clients can inherit from this test to apply the same suite of chooser tests
 * to their own ChooserActivity implementations. Clients should override
 * #getConcreteIntentForLaunch() to configure an intent that will launch their concrete
 * ChooserActivity subtype. Tests will assume that this subtype implements the IChooserWrapper
 * interface, which is only appropriate for testing. Clients will typically create their own
 * "ChooserWrapperActivity" by copy-and-pasting the system implementation, parenting to their own
 * ChooserActivity subclass instead of directly to the system implementation. Code comments in this
 * file provide direction for developers creating derived test suites, and eventually for removing
 * the extra complexity once we no longer need to support parallel ChooserActivity implementations.
 */
@RunWith(Parameterized.class)
public class ChooserActivityTest {

    /* --------
     * Subclasses should copy the following section verbatim (or alternatively could specify some
     * additional @Parameterized.Parameters, as long as the correct parameters are used to
     * initialize the ChooserActivityTest). The subclasses should also be @RunWith the
     * `Parameterized` runner.
     * --------
     */

    private static final Function<PackageManager, PackageManager> DEFAULT_PM = pm -> pm;
    private static final Function<PackageManager, PackageManager> NO_APP_PREDICTION_SERVICE_PM =
            pm -> {
                PackageManager mock = Mockito.spy(pm);
                when(mock.getAppPredictionServicePackageName()).thenReturn(null);
                return mock;
            };

    @Parameterized.Parameters
    public static Collection packageManagers() {
        return Arrays.asList(new Object[][] {
                {0, "Default PackageManager", DEFAULT_PM},
                {1, "No App Prediction Service", NO_APP_PREDICTION_SERVICE_PM}
        });
    }

    /* --------
     * Subclasses can override the following methods to customize test behavior.
     * --------
     */

    /**
     * Perform any necessary per-test initialization steps (subclasses may add additional steps
     * before and/or after calling up to the superclass implementation).
     */
    @CallSuper
    protected void setup() {
        cleanOverrideData();
    }

    /**
     * Given an intent that was constructed in a test, perform any additional configuration to
     * specify the appropriate concrete ChooserActivity subclass. The activity launched by this
     * intent must descend from android.internal.app.ChooserActivity (for our ActivityTestRule), and
     * must also implement the android.internal.app.IChooserWrapper interface (since test code will
     * assume the ability to make unsafe downcasts).
     */
    protected Intent getConcreteIntentForLaunch(Intent clientIntent) {
        clientIntent.setClass(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                com.android.internal.app.ChooserWrapperActivity.class);
        return clientIntent;
    }

    /**
     * Whether {@code #testIsAppPredictionServiceAvailable} should verify the behavior after
     * changing the availability conditions at runtime. In the unbundled chooser, the availability
     * is cached at start and will never be re-evaluated.
     * TODO: remove when we no longer want to test the system's on-the-fly evaluation.
     */
    protected boolean shouldTestTogglingAppPredictionServiceAvailabilityAtRuntime() {
        return false;
    }

    /* --------
     * The code in this section is unorthodox and can be simplified/reverted when we no longer need
     * to support the parallel chooser implementations.
     * --------
     */

    // Shared test code references the activity under test as ChooserActivity, the common ancestor
    // of any (inheritance-based) chooser implementation. For testing purposes, that activity will
    // usually be cast to IChooserWrapper to expose instrumentation.
    @Rule
    public ActivityTestRule<ChooserActivity> mActivityRule =
            new ActivityTestRule<>(ChooserActivity.class, false, false) {
                @Override
                public ChooserActivity launchActivity(Intent clientIntent) {
                    return super.launchActivity(getConcreteIntentForLaunch(clientIntent));
                }
            };

    @Before
    public final void doPolymorphicSetup() {
        // The base class needs a @Before-annotated setup for when it runs against the system
        // chooser, while subclasses need to be able to specify their own setup behavior. Notably
        // the unbundled chooser, running in user-space, needs to take additional steps before it
        // can run #cleanOverrideData() (which writes to DeviceConfig).
        setup();
    }

    /* --------
     * Subclasses can ignore the remaining code and inherit the full suite of tests.
     * --------
     */

    private static final String TEST_MIME_TYPE = "application/TestType";

    private static final int CONTENT_PREVIEW_IMAGE = 1;
    private static final int CONTENT_PREVIEW_FILE = 2;
    private static final int CONTENT_PREVIEW_TEXT = 3;
    private Function<PackageManager, PackageManager> mPackageManagerOverride;
    private int mTestNum;


    public ChooserActivityTest(
                int testNum,
                String testName,
                Function<PackageManager, PackageManager> packageManagerOverride) {
        mPackageManagerOverride = packageManagerOverride;
        mTestNum = testNum;
    }

    public void cleanOverrideData() {
        ChooserActivityOverrideData.getInstance().reset();
        ChooserActivityOverrideData.getInstance().createPackageManager = mPackageManagerOverride;
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.APPLY_SHARING_APP_LIMITS_IN_SYSUI,
                Boolean.toString(true),
                true /* makeDefault*/);
    }

    @Test
    public void customTitle() throws InterruptedException {
        Intent viewIntent = createViewTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        final IChooserWrapper activity = (IChooserWrapper) mActivityRule.launchActivity(
                Intent.createChooser(viewIntent, "chooser test"));

        waitForIdle();
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getAdapter().getServiceTargetCount(), is(0));
        onView(withIdFromRuntimeResource("title")).check(matches(withText("chooser test")));
    }

    @Test
    public void customTitleIgnoredForSendIntents() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "chooser test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("title"))
                .check(matches(withTextFromRuntimeResource("whichSendApplication")));
    }

    @Test
    public void emptyTitle() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("title"))
                .check(matches(withTextFromRuntimeResource("whichSendApplication")));
    }

    @Test
    public void emptyPreviewTitleAndThumbnail() throws InterruptedException {
        Intent sendIntent = createSendTextIntentWithPreview(null, null);
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_title"))
                .check(matches(not(isDisplayed())));
        onView(withIdFromRuntimeResource("content_preview_thumbnail"))
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleWithoutThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle, null);
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_title"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_title"))
                .check(matches(withText(previewTitle)));
        onView(withIdFromRuntimeResource("content_preview_thumbnail"))
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleWithInvalidThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle,
                Uri.parse("tel:(+49)12345789"));
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_title")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_thumbnail"))
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleAndThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle,
                Uri.parse("android.resource://com.android.frameworks.coretests/"
                        + com.android.frameworks.coretests.R.drawable.test320x240));
        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_title")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_thumbnail"))
                .check(matches(isDisplayed()));
    }

    @Test @Ignore
    public void twoOptionsAndUserSelectsOne() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        assertThat(activity.getAdapter().getCount(), is(2));
        onView(withIdFromRuntimeResource("profile_button")).check(doesNotExist());

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test @Ignore
    public void fourOptionsStackedIntoOneTarget() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();

        // create just enough targets to ensure the a-z list should be shown
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(1);

        // next create 4 targets in a single app that should be stacked into a single target
        String packageName = "xxx.yyy";
        String appName = "aaa";
        ComponentName cn = new ComponentName(packageName, appName);
        Intent intent = new Intent("fakeIntent");
        List<ResolvedComponentInfo> infosToStack = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ResolveInfo resolveInfo = ResolverDataProvider.createResolveInfo(i,
                    UserHandle.USER_CURRENT);
            resolveInfo.activityInfo.applicationInfo.name = appName;
            resolveInfo.activityInfo.applicationInfo.packageName = packageName;
            resolveInfo.activityInfo.packageName = packageName;
            resolveInfo.activityInfo.name = "ccc" + i;
            infosToStack.add(new ResolvedComponentInfo(cn, intent, resolveInfo));
        }
        resolvedComponentInfos.addAll(infosToStack);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // expect 1 unique targets + 1 group + 4 ranked app targets
        assertThat(activity.getAdapter().getCount(), is(6));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        onView(allOf(withText(appName), hasSibling(withText("")))).perform(click());
        waitForIdle();

        // clicking will launch a dialog to choose the activity within the app
        onView(withText(appName)).check(matches(isDisplayed()));
        int i = 0;
        for (ResolvedComponentInfo rci: infosToStack) {
            onView(withText("ccc" + i)).check(matches(isDisplayed()));
            ++i;
        }
    }

    @Test @Ignore
    public void updateChooserCountsAndModelAfterUserSelection() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        UsageStatsManager usm = activity.getUsageStatsManager();
        verify(ChooserActivityOverrideData.getInstance().resolverListController, times(1))
                .topK(any(List.class), anyInt());
        assertThat(activity.getIsSelected(), is(false));
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            return true;
        };
        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        waitForIdle();
        verify(ChooserActivityOverrideData.getInstance().resolverListController, times(1))
                .updateChooserCounts(Mockito.anyString(), anyInt(), Mockito.anyString());
        verify(ChooserActivityOverrideData.getInstance().resolverListController, times(1))
                .updateModel(toChoose.activityInfo.getComponentName());
        assertThat(activity.getIsSelected(), is(true));
    }

    @Ignore // b/148158199
    @Test
    public void noResultsFromPackageManager() {
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(null);
        Intent sendIntent = createSendTextIntent();
        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;

        waitForIdle();
        assertThat(activity.isFinishing(), is(false));

        onView(withIdFromRuntimeResource("empty")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("profile_pager")).check(matches(not(isDisplayed())));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> wrapper.getAdapter().handlePackagesChanged()
        );
        // backward compatibility. looks like we finish when data is empty after package change
        assertThat(activity.isFinishing(), is(true));
    }

    @Test
    public void autoLaunchSingleResult() throws InterruptedException {
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(1);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        Intent sendIntent = createSendTextIntent();
        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        assertThat(chosen[0], is(resolvedComponentInfos.get(0).getResolveInfoAt(0)));
        assertThat(activity.isFinishing(), is(true));
    }

    @Test @Ignore
    public void hasOtherProfileOneOption() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        markWorkProfileUserAvailable();

        ResolveInfo toChoose = personalResolvedComponentInfos.get(1).getResolveInfoAt(0);
        Intent sendIntent = createSendTextIntent();
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2, /* userId= */ 10);
        waitForIdle();
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        onView(first(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name)))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test @Ignore
    public void hasOtherProfileTwoOptionsAndUserSelectsOne() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(ChooserActivityOverrideData.getInstance().resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(3);
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test @Ignore
    public void hasLastChosenActivityAndOtherProfile() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(3);
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void copyTextToClipboard() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
            Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withIdFromRuntimeResource("chooser_copy_button")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("chooser_copy_button")).perform(click());
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboard.getPrimaryClip();
        assertThat("testing intent sending", is(clipData.getItemAt(0).getText()));

        ClipDescription clipDescription = clipData.getDescription();
        assertThat("text/plain", is(clipDescription.getMimeType(0)));

        assertEquals(mActivityRule.getActivityResult().getResultCode(), RESULT_OK);
    }

    @Test
    public void copyTextToClipboardLogging() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
            Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withIdFromRuntimeResource("chooser_copy_button")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("chooser_copy_button")).perform(click());

        verify(mockLogger, atLeastOnce()).write(logMakerCaptor.capture());

        // The last captured event is the selection of the target.
        assertThat(logMakerCaptor.getValue().getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SYSTEM_TARGET));
        assertThat(logMakerCaptor.getValue().getSubtype(), is(1));
    }


    @Test
    @Ignore
    public void testNearbyShareLogging() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withIdFromRuntimeResource("chooser_nearby_button")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("chooser_nearby_button")).perform(click());

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("text/plain"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next are just artifacts of test set-up:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_EXPANDED.getId()));

        // SHARESHEET_NEARBY_TARGET_SELECTED:
        assertThat(logger.get(5).atomId, is(FrameworkStatsLog.RANKING_SELECTED));
        assertThat(logger.get(5).targetType,
                is(ChooserActivityLogger
                        .SharesheetTargetSelectedEvent.SHARESHEET_NEARBY_TARGET_SELECTED.getId()));

        // No more events.
        assertThat(logger.numCalls(), is(6));
    }



    @Test @Ignore
    public void testEditImageLogs() throws Exception {
        Intent sendIntent = createSendImageIntent(
                Uri.parse("android.resource://com.android.frameworks.coretests/"
                        + com.android.frameworks.coretests.R.drawable.test320x240));

        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        ChooserActivityOverrideData.getInstance().isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withIdFromRuntimeResource("chooser_edit_button")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("chooser_edit_button")).perform(click());

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("image/png"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(1));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next are just artifacts of test set-up:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_EXPANDED.getId()));

        // SHARESHEET_EDIT_TARGET_SELECTED:
        assertThat(logger.get(5).atomId, is(FrameworkStatsLog.RANKING_SELECTED));
        assertThat(logger.get(5).targetType,
                is(ChooserActivityLogger
                        .SharesheetTargetSelectedEvent.SHARESHEET_EDIT_TARGET_SELECTED.getId()));

        // No more events.
        assertThat(logger.numCalls(), is(6));
    }


    @Test
    public void oneVisibleImagePreview() throws InterruptedException {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        ChooserActivityOverrideData.getInstance().isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_image_1_large"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_image_2_large"))
                .check(matches(not(isDisplayed())));
        onView(withIdFromRuntimeResource("content_preview_image_2_small"))
                .check(matches(not(isDisplayed())));
        onView(withIdFromRuntimeResource("content_preview_image_3_small"))
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void twoVisibleImagePreview() throws InterruptedException {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        ChooserActivityOverrideData.getInstance().isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_image_1_large"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_image_2_large"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_image_2_small"))
                .check(matches(not(isDisplayed())));
        onView(withIdFromRuntimeResource("content_preview_image_3_small"))
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void threeOrMoreVisibleImagePreview() throws InterruptedException {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);
        uris.add(uri);
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        ChooserActivityOverrideData.getInstance().isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_image_1_large"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_image_2_large"))
                .check(matches(not(isDisplayed())));
        onView(withIdFromRuntimeResource("content_preview_image_2_small"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_image_3_small"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testOnCreateLogging() {
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "logger test"));
        waitForIdle();
        verify(mockLogger, atLeastOnce()).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(0).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN));
        assertThat(logMakerCaptor
                .getAllValues().get(0)
                .getTaggedData(MetricsEvent.FIELD_TIME_TO_APP_TARGETS),
                is(notNullValue()));
        assertThat(logMakerCaptor
                .getAllValues().get(0)
                .getTaggedData(MetricsEvent.FIELD_SHARESHEET_MIMETYPE),
                is(TEST_MIME_TYPE));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getSubtype(),
                is(MetricsEvent.PARENT_PROFILE));
    }

    @Test
    public void testOnCreateLoggingFromWorkProfile() {
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ChooserActivityOverrideData.getInstance().alternateProfileSetting =
                MetricsEvent.MANAGED_PROFILE;
        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "logger test"));
        waitForIdle();
        verify(mockLogger, atLeastOnce()).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(0).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getTaggedData(MetricsEvent.FIELD_TIME_TO_APP_TARGETS),
                is(notNullValue()));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getTaggedData(MetricsEvent.FIELD_SHARESHEET_MIMETYPE),
                is(TEST_MIME_TYPE));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getSubtype(),
                is(MetricsEvent.MANAGED_PROFILE));
    }

    @Test
    public void testEmptyPreviewLogging() {
        Intent sendIntent = createSendTextIntentWithPreview(null, null);

        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "empty preview logger test"));
        waitForIdle();

        verify(mockLogger, Mockito.times(1)).write(logMakerCaptor.capture());
        // First invocation is from onCreate
        assertThat(logMakerCaptor.getAllValues().get(0).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN));
    }

    @Test
    public void testTitlePreviewLogging() {
        Intent sendIntent = createSendTextIntentWithPreview("TestTitle", null);

        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
            Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        // Second invocation is from onCreate
        verify(mockLogger, Mockito.times(2)).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(0).getSubtype(),
                is(CONTENT_PREVIEW_TEXT));
        assertThat(logMakerCaptor.getAllValues().get(0).getCategory(),
                is(MetricsEvent.ACTION_SHARE_WITH_PREVIEW));
    }

    @Test
    public void testImagePreviewLogging() {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        ChooserActivityOverrideData.getInstance().previewThumbnail = createBitmap();
        ChooserActivityOverrideData.getInstance().isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        verify(mockLogger, Mockito.times(2)).write(logMakerCaptor.capture());
        // First invocation is from onCreate
        assertThat(logMakerCaptor.getAllValues().get(0).getSubtype(),
                is(CONTENT_PREVIEW_IMAGE));
        assertThat(logMakerCaptor.getAllValues().get(0).getCategory(),
                is(MetricsEvent.ACTION_SHARE_WITH_PREVIEW));
    }

    @Test
    public void oneVisibleFilePreview() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                    .getInstance()
                    .resolverListController
                    .getResolversForIntent(
                            Mockito.anyBoolean(),
                            Mockito.anyBoolean(),
                            Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_filename")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_filename"))
                .check(matches(withText("app.pdf")));
        onView(withIdFromRuntimeResource("content_preview_file_icon"))
                .check(matches(isDisplayed()));
    }


    @Test
    public void moreThanOneVisibleFilePreview() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_filename"))
                .check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_filename"))
                .check(matches(withText("app.pdf + 2 files")));
        onView(withIdFromRuntimeResource("content_preview_file_icon"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void contentProviderThrowSecurityException() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        ChooserActivityOverrideData.getInstance().resolverForceException = true;

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_filename")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_filename"))
                .check(matches(withText("app.pdf")));
        onView(withIdFromRuntimeResource("content_preview_file_icon"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void contentProviderReturnsNoColumns() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        Cursor cursor = mock(Cursor.class);
        when(cursor.getCount()).thenReturn(1);
        Mockito.doNothing().when(cursor).close();
        when(cursor.moveToFirst()).thenReturn(true);
        when(cursor.getColumnIndex(Mockito.anyString())).thenReturn(-1);

        ChooserActivityOverrideData.getInstance().resolverCursor = cursor;

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withIdFromRuntimeResource("content_preview_filename")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("content_preview_filename"))
                .check(matches(withText("app.pdf + 1 file")));
        onView(withIdFromRuntimeResource("content_preview_file_icon"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testGetBaseScore() {
        final float testBaseScore = 0.89f;

        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getScore(Mockito.isA(DisplayResolveInfo.class)))
                .thenReturn(testBaseScore);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        final DisplayResolveInfo testDri =
                activity.createTestDisplayResolveInfo(sendIntent,
                ResolverDataProvider.createResolveInfo(3, 0), "testLabel", "testInfo", sendIntent,
                /* resolveInfoPresentationGetter */ null);
        final ChooserListAdapter adapter = activity.getAdapter();

        assertThat(adapter.getBaseScore(null, 0), is(CALLER_TARGET_SCORE_BOOST));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_DEFAULT), is(testBaseScore));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_CHOOSER_TARGET), is(testBaseScore));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE),
                is(testBaseScore * SHORTCUT_TARGET_SCORE_BOOST));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER),
                is(testBaseScore * SHORTCUT_TARGET_SCORE_BOOST));
    }

    @Test
    public void testConvertToChooserTarget_predictionService() {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        List<ShareShortcutInfo> shortcuts = createShortcuts(activity);

        int[] expectedOrderAllShortcuts = {0, 1, 2, 3};
        float[] expectedScoreAllShortcuts = {1.0f, 0.99f, 0.98f, 0.97f};

        List<ChooserTarget> chooserTargets = activity.convertToChooserTarget(shortcuts, shortcuts,
                null, TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);
        assertCorrectShortcutToChooserTargetConversion(shortcuts, chooserTargets,
                expectedOrderAllShortcuts, expectedScoreAllShortcuts);

        List<ShareShortcutInfo> subset = new ArrayList<>();
        subset.add(shortcuts.get(1));
        subset.add(shortcuts.get(2));
        subset.add(shortcuts.get(3));

        int[] expectedOrderSubset = {1, 2, 3};
        float[] expectedScoreSubset = {0.99f, 0.98f, 0.97f};

        chooserTargets = activity.convertToChooserTarget(subset, shortcuts, null,
                TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);
        assertCorrectShortcutToChooserTargetConversion(shortcuts, chooserTargets,
                expectedOrderSubset, expectedScoreSubset);
    }

    @Test
    public void testConvertToChooserTarget_shortcutManager() {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        List<ShareShortcutInfo> shortcuts = createShortcuts(activity);

        int[] expectedOrderAllShortcuts = {2, 0, 3, 1};
        float[] expectedScoreAllShortcuts = {1.0f, 0.99f, 0.99f, 0.98f};

        List<ChooserTarget> chooserTargets = activity.convertToChooserTarget(shortcuts, shortcuts,
                null, TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER);
        assertCorrectShortcutToChooserTargetConversion(shortcuts, chooserTargets,
                expectedOrderAllShortcuts, expectedScoreAllShortcuts);

        List<ShareShortcutInfo> subset = new ArrayList<>();
        subset.add(shortcuts.get(1));
        subset.add(shortcuts.get(2));
        subset.add(shortcuts.get(3));

        int[] expectedOrderSubset = {2, 3, 1};
        float[] expectedScoreSubset = {1.0f, 0.99f, 0.98f};

        chooserTargets = activity.convertToChooserTarget(subset, shortcuts, null,
                TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER);
        assertCorrectShortcutToChooserTargetConversion(shortcuts, chooserTargets,
                expectedOrderSubset, expectedScoreSubset);
    }

    // This test is too long and too slow and should not be taken as an example for future tests.
    @Test @Ignore
    public void testDirectTargetSelectionLogging() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1, "");
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));

        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        directShareToShortcutInfos.put(serviceTargets.get(0), null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET,
                        directShareToShortcutInfos)
        );

        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat("Chooser should have 3 targets (2 apps, 1 direct)",
                activity.getAdapter().getCount(), is(3));
        assertThat("Chooser should have exactly one selectable direct target",
                activity.getAdapter().getSelectableServiceTargetCount(), is(1));
        assertThat("The resolver info must match the resolver info used to create the target",
                activity.getAdapter().getItem(0).getResolveInfo(), is(ri));

        // Click on the direct target
        String name = serviceTargets.get(0).getTitle().toString();
        onView(withText(name))
                .perform(click());
        waitForIdle();

        // Currently we're seeing 3 invocations
        //      1. ChooserActivity.onCreate()
        //      2. ChooserActivity$ChooserRowAdapter.createContentPreviewView()
        //      3. ChooserActivity.startSelected -- which is the one we're after
        verify(mockLogger, Mockito.times(3)).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(2).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET));
        String hashedName = (String) logMakerCaptor
                .getAllValues().get(2).getTaggedData(MetricsEvent.FIELD_HASHED_TARGET_NAME);
        assertThat("Hash is not predictable but must be obfuscated",
                hashedName, is(not(name)));
        assertThat("The packages shouldn't match for app target and direct target", logMakerCaptor
                .getAllValues().get(2).getTaggedData(MetricsEvent.FIELD_RANKED_POSITION), is(-1));
    }

    // This test is too long and too slow and should not be taken as an example for future tests.
    @Test @Ignore
    public void testDirectTargetLoggingWithRankedAppTarget() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1,
                resolvedComponentInfos.get(0).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));

        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        directShareToShortcutInfos.put(serviceTargets.get(0), null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET,
                        directShareToShortcutInfos)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat("Chooser should have 3 targets (2 apps, 1 direct)",
                activity.getAdapter().getCount(), is(3));
        assertThat("Chooser should have exactly one selectable direct target",
                activity.getAdapter().getSelectableServiceTargetCount(), is(1));
        assertThat("The resolver info must match the resolver info used to create the target",
                activity.getAdapter().getItem(0).getResolveInfo(), is(ri));

        // Click on the direct target
        String name = serviceTargets.get(0).getTitle().toString();
        onView(withText(name))
                .perform(click());
        waitForIdle();

        // Currently we're seeing 3 invocations
        //      1. ChooserActivity.onCreate()
        //      2. ChooserActivity$ChooserRowAdapter.createContentPreviewView()
        //      3. ChooserActivity.startSelected -- which is the one we're after
        verify(mockLogger, Mockito.times(3)).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(2).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET));
        assertThat("The packages should match for app target and direct target", logMakerCaptor
                .getAllValues().get(2).getTaggedData(MetricsEvent.FIELD_RANKED_POSITION), is(0));
    }

    @Test @Ignore
    public void testShortcutTargetWithApplyAppLimits() throws InterruptedException {
        // Set up resources
        ChooserActivityOverrideData.getInstance().resources = Mockito.spy(
                InstrumentationRegistry.getInstrumentation().getContext().getResources());
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resources
                        .getInteger(
                              getRuntimeResourceId("config_maxShortcutTargetsPerApp", "integer")))
                .thenReturn(1);
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(2,
                resolvedComponentInfos.get(0).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;

        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        List<ShareShortcutInfo> shortcutInfos = createShortcuts(activity);
        directShareToShortcutInfos.put(serviceTargets.get(0),
                shortcutInfos.get(0).getShortcutInfo());
        directShareToShortcutInfos.put(serviceTargets.get(1),
                shortcutInfos.get(1).getShortcutInfo());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> wrapper.getAdapter().addServiceResults(
                        wrapper.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE,
                        directShareToShortcutInfos)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat("Chooser should have 3 targets (2 apps, 1 direct)",
                wrapper.getAdapter().getCount(), is(3));
        assertThat("Chooser should have exactly one selectable direct target",
                wrapper.getAdapter().getSelectableServiceTargetCount(), is(1));
        assertThat("The resolver info must match the resolver info used to create the target",
                wrapper.getAdapter().getItem(0).getResolveInfo(), is(ri));
        assertThat("The display label must match",
                wrapper.getAdapter().getItem(0).getDisplayLabel(), is("testTitle0"));
    }

    @Test @Ignore
    public void testShortcutTargetWithoutApplyAppLimits() throws InterruptedException {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.APPLY_SHARING_APP_LIMITS_IN_SYSUI,
                Boolean.toString(false),
                true /* makeDefault*/);
        // Set up resources
        ChooserActivityOverrideData.getInstance().resources = Mockito.spy(
                InstrumentationRegistry.getInstrumentation().getContext().getResources());
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resources
                        .getInteger(
                              getRuntimeResourceId("config_maxShortcutTargetsPerApp", "integer")))
                .thenReturn(1);
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(2,
                resolvedComponentInfos.get(0).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;

        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        List<ShareShortcutInfo> shortcutInfos = createShortcuts(activity);
        directShareToShortcutInfos.put(serviceTargets.get(0),
                shortcutInfos.get(0).getShortcutInfo());
        directShareToShortcutInfos.put(serviceTargets.get(1),
                shortcutInfos.get(1).getShortcutInfo());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> wrapper.getAdapter().addServiceResults(
                        wrapper.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE,
                        directShareToShortcutInfos)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat("Chooser should have 4 targets (2 apps, 2 direct)",
                wrapper.getAdapter().getCount(), is(4));
        assertThat("Chooser should have exactly two selectable direct target",
                wrapper.getAdapter().getSelectableServiceTargetCount(), is(2));
        assertThat("The resolver info must match the resolver info used to create the target",
                wrapper.getAdapter().getItem(0).getResolveInfo(), is(ri));
        assertThat("The display label must match",
                wrapper.getAdapter().getItem(0).getDisplayLabel(), is("testTitle0"));
        assertThat("The display label must match",
                wrapper.getAdapter().getItem(1).getDisplayLabel(), is("testTitle1"));
    }

    @Test
    public void testUpdateMaxTargetsPerRow_columnCountIsUpdated() throws InterruptedException {
        updateMaxTargetsPerRowResource(/* targetsPerRow= */ 4);
        givenAppTargets(/* appCount= */ 16);
        Intent sendIntent = createSendTextIntent();
        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));

        updateMaxTargetsPerRowResource(/* targetsPerRow= */ 6);
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> activity.onConfigurationChanged(
                        InstrumentationRegistry.getInstrumentation()
                                .getContext().getResources().getConfiguration()));

        waitForIdle();
        onView(withIdFromRuntimeResource("resolver_list"))
                .check(matches(withGridColumnCount(6)));
    }

    // This test is too long and too slow and should not be taken as an example for future tests.
    @Test @Ignore
    public void testDirectTargetLoggingWithAppTargetNotRankedPortrait()
            throws InterruptedException {
        testDirectTargetLoggingWithAppTargetNotRanked(Configuration.ORIENTATION_PORTRAIT, 4);
    }

    @Test @Ignore
    public void testDirectTargetLoggingWithAppTargetNotRankedLandscape()
            throws InterruptedException {
        testDirectTargetLoggingWithAppTargetNotRanked(Configuration.ORIENTATION_LANDSCAPE, 8);
    }

    private void testDirectTargetLoggingWithAppTargetNotRanked(
            int orientation, int appTargetsExpected
    ) throws InterruptedException {
        Configuration configuration =
                new Configuration(InstrumentationRegistry.getInstrumentation().getContext()
                        .getResources().getConfiguration());
        configuration.orientation = orientation;

        ChooserActivityOverrideData.getInstance().resources = Mockito.spy(
                InstrumentationRegistry.getInstrumentation().getContext().getResources());
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resources
                        .getConfiguration())
                .thenReturn(configuration);

        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(15);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = ChooserActivityOverrideData.getInstance().metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1,
                resolvedComponentInfos.get(14).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(16, 0);

        // Start activity
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;
        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        directShareToShortcutInfos.put(serviceTargets.get(0), null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> wrapper.getAdapter().addServiceResults(
                        wrapper.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET,
                        directShareToShortcutInfos)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat(
                String.format("Chooser should have %d targets (%d apps, 1 direct, 15 A-Z)",
                        appTargetsExpected + 16, appTargetsExpected),
                wrapper.getAdapter().getCount(), is(appTargetsExpected + 16));
        assertThat("Chooser should have exactly one selectable direct target",
                wrapper.getAdapter().getSelectableServiceTargetCount(), is(1));
        assertThat("The resolver info must match the resolver info used to create the target",
                wrapper.getAdapter().getItem(0).getResolveInfo(), is(ri));

        // Click on the direct target
        String name = serviceTargets.get(0).getTitle().toString();
        onView(withText(name))
                .perform(click());
        waitForIdle();

        // Currently we're seeing 3 invocations
        //      1. ChooserActivity.onCreate()
        //      2. ChooserActivity$ChooserRowAdapter.createContentPreviewView()
        //      3. ChooserActivity.startSelected -- which is the one we're after
        verify(mockLogger, Mockito.times(3)).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getAllValues().get(2).getCategory(),
                is(MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET));
        assertThat("The packages shouldn't match for app target and direct target", logMakerCaptor
                .getAllValues().get(2).getTaggedData(MetricsEvent.FIELD_RANKED_POSITION), is(-1));
    }

    @Test
    public void testWorkTab_displayedWhenWorkProfileUserAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        markWorkProfileUserAvailable();

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();

        onView(withIdFromRuntimeResource("tabs")).check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_hiddenWhenWorkProfileUserNotAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();

        onView(withIdFromRuntimeResource("tabs")).check(matches(not(isDisplayed())));
    }

    @Test
    public void testWorkTab_eachTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        int personalProfileTargets = 3;
        int otherProfileTargets = 1;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(
                        personalProfileTargets + otherProfileTargets, /* userID */ 10);
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(
                workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        markWorkProfileUserAvailable();

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(0));
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(10));
        assertThat(activity.getPersonalListAdapter().getCount(), is(personalProfileTargets));
        assertThat(activity.getWorkListAdapter().getCount(), is(workProfileTargets));
    }

    @Test
    public void testWorkTab_workProfileHasExpectedNumberOfTargets() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertThat(activity.getWorkListAdapter().getCount(), is(workProfileTargets));
    }

    @Test @Ignore
    public void testWorkTab_selectingWorkTabAppOpensAppInWorkProfile() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();
        // wait for the share sheet to expand
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        onView(first(allOf(
                withText(workResolvedComponentInfos.get(0)
                        .getResolveInfoAt(0).activityInfo.applicationInfo.name),
                isDisplayed())))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(workResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_crossProfileIntentsDisabled_personalToWork_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        ChooserActivityOverrideData.getInstance().hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());

        onView(withTextFromRuntimeResource("resolver_cross_profile_blocked"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_workProfileDisabled_emptyStateShown() {
        // enable the work tab feature flag
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        ChooserActivityOverrideData.getInstance().isQuietModeEnabled = true;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        ResolverActivity.ENABLE_TABBED_VIEW = true;
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        onView(withTextFromRuntimeResource("resolver_turn_on_work_apps"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_noWorkAppsAvailable_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        onView(withTextFromRuntimeResource("resolver_no_work_apps_available"))
                .check(matches(isDisplayed()));
    }

    @Ignore // b/220067877
    @Test
    public void testWorkTab_xProfileOff_noAppsAvailable_workOff_xProfileOffEmptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        ChooserActivityOverrideData.getInstance().isQuietModeEnabled = true;
        ChooserActivityOverrideData.getInstance().hasCrossProfileIntents = false;
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        onView(withTextFromRuntimeResource("resolver_cross_profile_blocked"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_noAppsAvailable_workOff_noAppsAvailableEmptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        ChooserActivityOverrideData.getInstance().isQuietModeEnabled = true;
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        onView(withTextFromRuntimeResource("resolver_no_work_apps_available"))
                .check(matches(isDisplayed()));
    }

    @Test @Ignore("b/222124533")
    public void testAppTargetLogging() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // TODO(b/222124533): other test cases use a timeout to make sure that the UI is fully
        // populated; without one, this test flakes. Ideally we should address the need for a
        // timeout everywhere instead of introducing one to fix this particular test.

        assertThat(activity.getAdapter().getCount(), is(2));
        onView(withIdFromRuntimeResource("profile_button")).check(doesNotExist());

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        waitForIdle();

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("text/plain"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next are just artifacts of test set-up:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_EXPANDED.getId()));

        // SHARESHEET_APP_TARGET_SELECTED:
        assertThat(logger.get(5).atomId, is(FrameworkStatsLog.RANKING_SELECTED));
        assertThat(logger.get(5).targetType,
                is(ChooserActivityLogger
                        .SharesheetTargetSelectedEvent.SHARESHEET_APP_TARGET_SELECTED.getId()));

        // No more events.
        assertThat(logger.numCalls(), is(6));
    }

    @Test @Ignore
    public void testDirectTargetLogging() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1,
                resolvedComponentInfos.get(0).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));

        // Insert the direct share target
        Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos = new HashMap<>();
        directShareToShortcutInfos.put(serviceTargets.get(0), null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent,
                                /* resolveInfoPresentationGetter */ null),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET,
                        directShareToShortcutInfos)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(((ChooserActivity) activity).mListViewUpdateDelayMs);

        assertThat("Chooser should have 3 targets (2 apps, 1 direct)",
                activity.getAdapter().getCount(), is(3));
        assertThat("Chooser should have exactly one selectable direct target",
                activity.getAdapter().getSelectableServiceTargetCount(), is(1));
        assertThat("The resolver info must match the resolver info used to create the target",
                activity.getAdapter().getItem(0).getResolveInfo(), is(ri));

        // Click on the direct target
        String name = serviceTargets.get(0).getTitle().toString();
        onView(withText(name))
                .perform(click());
        waitForIdle();

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();
        assertThat(logger.numCalls(), is(6));
        // first one should be SHARESHEET_TRIGGERED uievent
        assertThat(logger.get(0).atomId, is(FrameworkStatsLog.UI_EVENT_REPORTED));
        assertThat(logger.get(0).event.getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));
        // second one should be SHARESHEET_STARTED event
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("text/plain"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));
        // third one should be SHARESHEET_APP_LOAD_COMPLETE uievent
        assertThat(logger.get(2).atomId, is(FrameworkStatsLog.UI_EVENT_REPORTED));
        assertThat(logger.get(2).event.getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));
        // fourth and fifth are just artifacts of test set-up
        // sixth one should be ranking atom with SHARESHEET_COPY_TARGET_SELECTED event
        assertThat(logger.get(5).atomId, is(FrameworkStatsLog.RANKING_SELECTED));
        assertThat(logger.get(5).targetType,
                is(ChooserActivityLogger
                        .SharesheetTargetSelectedEvent.SHARESHEET_SERVICE_TARGET_SELECTED.getId()));
    }

    @Test @Ignore
    public void testEmptyDirectRowLogging() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        // Start activity
        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));

        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        Thread.sleep(3000);

        assertThat("Chooser should have 2 app targets",
                activity.getAdapter().getCount(), is(2));
        assertThat("Chooser should have no direct targets",
                activity.getAdapter().getSelectableServiceTargetCount(), is(0));

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("text/plain"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // SHARESHEET_EMPTY_DIRECT_SHARE_ROW:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));

        // Next is just an artifact of test set-up:
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_EXPANDED.getId()));

        assertThat(logger.numCalls(), is(5));
    }

    @Ignore // b/220067877
    @Test
    public void testCopyTextToClipboardLogging() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withIdFromRuntimeResource("chooser_copy_button")).check(matches(isDisplayed()));
        onView(withIdFromRuntimeResource("chooser_copy_button")).perform(click());

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is("text/plain"));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next are just artifacts of test set-up:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_EXPANDED.getId()));

        // SHARESHEET_COPY_TARGET_SELECTED:
        assertThat(logger.get(5).atomId, is(FrameworkStatsLog.RANKING_SELECTED));
        assertThat(logger.get(5).targetType,
                is(ChooserActivityLogger
                        .SharesheetTargetSelectedEvent.SHARESHEET_COPY_TARGET_SELECTED.getId()));

        // No more events.
        assertThat(logger.numCalls(), is(6));
    }

    @Test @Ignore("b/222124533")
    public void testSwitchProfileLogging() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        final IChooserWrapper activity = (IChooserWrapper)
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_personal_tab")).perform(click());
        waitForIdle();

        ChooserActivityLoggerFake logger =
                (ChooserActivityLoggerFake) activity.getChooserActivityLogger();

        // TODO(b/211669337): Determine the expected SHARESHEET_DIRECT_LOAD_COMPLETE events.
        logger.removeCallsForUiEventsOfType(
                ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_DIRECT_LOAD_COMPLETE.getId());

        // SHARESHEET_TRIGGERED:
        assertThat(logger.event(0).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent.SHARESHEET_TRIGGERED.getId()));

        // SHARESHEET_STARTED:
        assertThat(logger.get(1).atomId, is(FrameworkStatsLog.SHARESHEET_STARTED));
        assertThat(logger.get(1).intent, is(Intent.ACTION_SEND));
        assertThat(logger.get(1).mimeType, is(TEST_MIME_TYPE));
        assertThat(logger.get(1).packageName, is(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName()));
        assertThat(logger.get(1).appProvidedApp, is(0));
        assertThat(logger.get(1).appProvidedDirect, is(0));
        assertThat(logger.get(1).isWorkprofile, is(false));
        assertThat(logger.get(1).previewType, is(3));

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(2).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next is just an artifact of test set-up:
        assertThat(logger.event(3).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));

        // SHARESHEET_PROFILE_CHANGED:
        assertThat(logger.event(4).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_PROFILE_CHANGED.getId()));

        // Repeat the loading steps in the new profile:

        // SHARESHEET_APP_LOAD_COMPLETE:
        assertThat(logger.event(5).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_APP_LOAD_COMPLETE.getId()));

        // Next is again an artifact of test set-up:
        assertThat(logger.event(6).getId(),
                is(ChooserActivityLogger
                        .SharesheetStandardEvent.SHARESHEET_EMPTY_DIRECT_SHARE_ROW.getId()));

        // SHARESHEET_PROFILE_CHANGED:
        assertThat(logger.event(7).getId(),
                is(ChooserActivityLogger.SharesheetStandardEvent
                        .SHARESHEET_PROFILE_CHANGED.getId()));

        // No more events (this profile was already loaded).
        assertThat(logger.numCalls(), is(8));
    }

    @Test
    public void testAutolaunch_singleTarget_wifthWorkProfileAndTabbedViewOff_noAutolaunch() {
        ResolverActivity.ENABLE_TABBED_VIEW = false;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, /* userId */ 10);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };
        waitForIdle();

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();

        assertTrue(chosen[0] == null);
    }

    @Test
    public void testAutolaunch_singleTarget_noWorkProfile_autolaunch() {
        ResolverActivity.ENABLE_TABBED_VIEW = false;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(1);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };
        waitForIdle();

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();

        assertThat(chosen[0], is(personalResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_onePersonalTarget_emptyStateOnWorkTarget_autolaunch() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        ChooserActivityOverrideData.getInstance().hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(chosen[0], is(personalResolvedComponentInfos.get(1).getResolveInfoAt(0)));
    }

    @Test
    public void testOneInitialIntent_noAutolaunch() {
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(1);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        Intent chooserIntent = createChooserIntent(createSendTextIntent(),
                new Intent[] {new Intent("action.fake")});
        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserActivityOverrideData.getInstance().onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };
        ChooserActivityOverrideData.getInstance().packageManager = mock(PackageManager.class);
        ResolveInfo ri = createFakeResolveInfo();
        when(
                ChooserActivityOverrideData
                        .getInstance().packageManager
                        .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(ri);
        waitForIdle();

        IChooserWrapper activity = (IChooserWrapper) mActivityRule.launchActivity(chooserIntent);
        waitForIdle();

        assertNull(chosen[0]);
        assertThat(activity
                .getPersonalListAdapter().getCallerTargetCount(), is(1));
    }

    @Test
    public void testWorkTab_withInitialIntents_workTabDoesNotIncludePersonalInitialIntents() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 1;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent[] initialIntents = {
                new Intent("action.fake1"),
                new Intent("action.fake2")
        };
        Intent chooserIntent = createChooserIntent(createSendTextIntent(), initialIntents);
        ChooserActivityOverrideData.getInstance().packageManager = mock(PackageManager.class);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .packageManager
                        .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(createFakeResolveInfo());
        waitForIdle();

        IChooserWrapper activity = (IChooserWrapper) mActivityRule.launchActivity(chooserIntent);
        waitForIdle();

        assertThat(activity.getPersonalListAdapter().getCallerTargetCount(), is(2));
        assertThat(activity.getWorkListAdapter().getCallerTargetCount(), is(0));
    }

    @Test
    public void testWorkTab_xProfileIntentsDisabled_personalToWork_nonSendIntent_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets);
        ChooserActivityOverrideData.getInstance().hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent[] initialIntents = {
                new Intent("action.fake1"),
                new Intent("action.fake2")
        };
        Intent chooserIntent = createChooserIntent(new Intent(), initialIntents);
        ChooserActivityOverrideData.getInstance().packageManager = mock(PackageManager.class);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .packageManager
                        .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(createFakeResolveInfo());

        mActivityRule.launchActivity(chooserIntent);
        waitForIdle();
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());

        onView(withTextFromRuntimeResource("resolver_cross_profile_blocked"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_noWorkAppsAvailable_nonSendIntent_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent[] initialIntents = {
                new Intent("action.fake1"),
                new Intent("action.fake2")
        };
        Intent chooserIntent = createChooserIntent(new Intent(), initialIntents);
        ChooserActivityOverrideData.getInstance().packageManager = mock(PackageManager.class);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .packageManager
                        .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(createFakeResolveInfo());

        mActivityRule.launchActivity(chooserIntent);
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        onView(withTextFromRuntimeResource("resolver_no_work_apps_available"))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testDeduplicateCallerTargetRankedTarget() {
        // Create 4 ranked app targets.
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(4);
        when(ChooserActivityOverrideData.getInstance().resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        // Create caller target which is duplicate with one of app targets
        Intent chooserIntent = createChooserIntent(createSendTextIntent(),
                new Intent[] {new Intent("action.fake")});
        ChooserActivityOverrideData.getInstance().packageManager = mock(PackageManager.class);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(0,
                UserHandle.USER_CURRENT);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .packageManager
                        .resolveActivity(any(Intent.class), anyInt()))
                .thenReturn(ri);
        waitForIdle();

        IChooserWrapper activity = (IChooserWrapper) mActivityRule.launchActivity(chooserIntent);
        waitForIdle();

        // Total 4 targets (1 caller target, 3 ranked targets)
        assertThat(activity.getAdapter().getCount(), is(4));
        assertThat(activity.getAdapter().getCallerTargetCount(), is(1));
        assertThat(activity.getAdapter().getRankedTargetCount(), is(3));
    }

    @Test
    public void testWorkTab_selectingWorkTabWithPausedWorkProfile_directShareTargetsNotQueried() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        ChooserActivityOverrideData.getInstance().isQuietModeEnabled = true;
        boolean[] isQueryDirectShareCalledOnWorkProfile = new boolean[] { false };
        ChooserActivityOverrideData.getInstance().onQueryDirectShareTargets =
                chooserListAdapter -> {
                    isQueryDirectShareCalledOnWorkProfile[0] =
                            (chooserListAdapter.getUserHandle().getIdentifier() == 10);
                    return null;
                };
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertFalse("Direct share targets were queried on a paused work profile",
                isQueryDirectShareCalledOnWorkProfile[0]);
    }

    @Test
    public void testWorkTab_selectingWorkTabWithNotRunningWorkUser_directShareTargetsNotQueried() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        ChooserActivityOverrideData.getInstance().isWorkProfileUserRunning = false;
        boolean[] isQueryDirectShareCalledOnWorkProfile = new boolean[] { false };
        ChooserActivityOverrideData.getInstance().onQueryDirectShareTargets =
                chooserListAdapter -> {
                    isQueryDirectShareCalledOnWorkProfile[0] =
                            (chooserListAdapter.getUserHandle().getIdentifier() == 10);
                    return null;
                };
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertFalse("Direct share targets were queried on a locked work profile user",
                isQueryDirectShareCalledOnWorkProfile[0]);
    }

    @Test
    public void testWorkTab_workUserNotRunning_workTargetsShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ChooserActivityOverrideData.getInstance().isWorkProfileUserRunning = false;

        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel")).perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertEquals(3, wrapper.getWorkListAdapter().getCount());
    }

    @Test
    public void testWorkTab_selectingWorkTabWithLockedWorkUser_directShareTargetsNotQueried() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        ChooserActivityOverrideData.getInstance().isWorkProfileUserUnlocked = false;
        boolean[] isQueryDirectShareCalledOnWorkProfile = new boolean[] { false };
        ChooserActivityOverrideData.getInstance().onQueryDirectShareTargets =
                chooserListAdapter -> {
                    isQueryDirectShareCalledOnWorkProfile[0] =
                            (chooserListAdapter.getUserHandle().getIdentifier() == 10);
                    return null;
                };
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertFalse("Direct share targets were queried on a locked work profile user",
                isQueryDirectShareCalledOnWorkProfile[0]);
    }

    @Test
    public void testWorkTab_workUserLocked_workTargetsShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, /* userId */ 10);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType(TEST_MIME_TYPE);
        ChooserActivityOverrideData.getInstance().isWorkProfileUserUnlocked = false;

        final ChooserActivity activity =
                mActivityRule.launchActivity(Intent.createChooser(sendIntent, "work tab test"));
        final IChooserWrapper wrapper = (IChooserWrapper) activity;
        waitForIdle();
        onView(withIdFromRuntimeResource("contentPanel"))
                .perform(swipeUp());
        onView(withTextFromRuntimeResource("resolver_work_tab")).perform(click());
        waitForIdle();

        assertEquals(3, wrapper.getWorkListAdapter().getCount());
    }

    private Intent createChooserIntent(Intent intent, Intent[] initialIntents) {
        Intent chooserIntent = new Intent();
        chooserIntent.setAction(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "some title");
        chooserIntent.putExtra(Intent.EXTRA_INTENT, intent);
        chooserIntent.setType("text/plain");
        if (initialIntents != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents);
        }
        return chooserIntent;
    }

    private ResolveInfo createFakeResolveInfo() {
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.name = "FakeActivityName";
        ri.activityInfo.packageName = "fake.package.name";
        ri.activityInfo.applicationInfo = new ApplicationInfo();
        ri.activityInfo.applicationInfo.packageName = "fake.package.name";
        return ri;
    }

    private Intent createSendTextIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("text/plain");
        return sendIntent;
    }

    private Intent createSendImageIntent(Uri imageThumbnail) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_STREAM, imageThumbnail);
        sendIntent.setType("image/png");
        if (imageThumbnail != null) {
            ClipData.Item clipItem = new ClipData.Item(imageThumbnail);
            sendIntent.setClipData(new ClipData("Clip Label", new String[]{"image/png"}, clipItem));
        }

        return sendIntent;
    }

    private Intent createSendTextIntentWithPreview(String title, Uri imageThumbnail) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.putExtra(Intent.EXTRA_TITLE, title);
        if (imageThumbnail != null) {
            ClipData.Item clipItem = new ClipData.Item(imageThumbnail);
            sendIntent.setClipData(new ClipData("Clip Label", new String[]{"image/png"}, clipItem));
        }

        return sendIntent;
    }

    private Intent createSendUriIntentWithPreview(ArrayList<Uri> uris) {
        Intent sendIntent = new Intent();

        if (uris.size() > 1) {
            sendIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
            sendIntent.putExtra(Intent.EXTRA_STREAM, uris);
        } else {
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }

        return sendIntent;
    }

    private Intent createViewTextIntent() {
        Intent viewIntent = new Intent();
        viewIntent.setAction(Intent.ACTION_VIEW);
        viewIntent.putExtra(Intent.EXTRA_TEXT, "testing intent viewing");
        return viewIntent;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTest(int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            if (i == 0) {
                infoList.add(ResolverDataProvider.createResolvedComponentInfoWithOtherId(i));
            } else {
                infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
            }
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults, int userId) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            if (i == 0) {
                infoList.add(
                        ResolverDataProvider.createResolvedComponentInfoWithOtherId(i, userId));
            } else {
                infoList.add(ResolverDataProvider.createResolvedComponentInfo(i));
            }
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithUserId(
            int numberOfResults, int userId) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfoWithOtherId(i, userId));
        }
        return infoList;
    }

    private List<ChooserTarget> createDirectShareTargets(int numberOfResults, String packageName) {
        Icon icon = Icon.createWithBitmap(createBitmap());
        String testTitle = "testTitle";
        List<ChooserTarget> targets = new ArrayList<>();
        for (int i = 0; i < numberOfResults; i++) {
            ComponentName componentName;
            if (packageName.isEmpty()) {
                componentName = ResolverDataProvider.createComponentName(i);
            } else {
                componentName = new ComponentName(packageName, packageName + ".class");
            }
            ChooserTarget tempTarget = new ChooserTarget(
                    testTitle + i,
                    icon,
                    (float) (1 - ((i + 1) / 10.0)),
                    componentName,
                    null);
            targets.add(tempTarget);
        }
        return targets;
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private Bitmap createBitmap() {
        int width = 200;
        int height = 200;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPaint(paint);

        paint.setColor(Color.WHITE);
        paint.setAntiAlias(true);
        paint.setTextSize(14.f);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("Hi!", (width / 2.f), (height / 2.f), paint);

        return bitmap;
    }

    private List<ShareShortcutInfo> createShortcuts(Context context) {
        Intent testIntent = new Intent("TestIntent");

        List<ShareShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(new ShareShortcutInfo(
                new ShortcutInfo.Builder(context, "shortcut1")
                        .setIntent(testIntent).setShortLabel("label1").setRank(3).build(), // 0  2
                new ComponentName("package1", "class1")));
        shortcuts.add(new ShareShortcutInfo(
                new ShortcutInfo.Builder(context, "shortcut2")
                        .setIntent(testIntent).setShortLabel("label2").setRank(7).build(), // 1  3
                new ComponentName("package2", "class2")));
        shortcuts.add(new ShareShortcutInfo(
                new ShortcutInfo.Builder(context, "shortcut3")
                        .setIntent(testIntent).setShortLabel("label3").setRank(1).build(), // 2  0
                new ComponentName("package3", "class3")));
        shortcuts.add(new ShareShortcutInfo(
                new ShortcutInfo.Builder(context, "shortcut4")
                        .setIntent(testIntent).setShortLabel("label4").setRank(3).build(), // 3  2
                new ComponentName("package4", "class4")));

        return shortcuts;
    }

    private void assertCorrectShortcutToChooserTargetConversion(List<ShareShortcutInfo> shortcuts,
            List<ChooserTarget> chooserTargets, int[] expectedOrder, float[] expectedScores) {
        assertEquals(expectedOrder.length, chooserTargets.size());
        for (int i = 0; i < chooserTargets.size(); i++) {
            ChooserTarget ct = chooserTargets.get(i);
            ShortcutInfo si = shortcuts.get(expectedOrder[i]).getShortcutInfo();
            ComponentName cn = shortcuts.get(expectedOrder[i]).getTargetComponent();

            assertEquals(si.getId(), ct.getIntentExtras().getString(Intent.EXTRA_SHORTCUT_ID));
            assertEquals(si.getShortLabel(), ct.getTitle());
            assertThat(Math.abs(expectedScores[i] - ct.getScore()) < 0.000001, is(true));
            assertEquals(cn.flattenToString(), ct.getComponentName().flattenToString());
        }
    }

    private void markWorkProfileUserAvailable() {
        ChooserActivityOverrideData.getInstance().workProfileUserHandle = UserHandle.of(10);
    }

    private void setupResolverControllers(
            List<ResolvedComponentInfo> personalResolvedComponentInfos,
            List<ResolvedComponentInfo> workResolvedComponentInfos) {
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .workResolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(workResolvedComponentInfos));
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .workResolverListController
                        .getResolversForIntentAsUser(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class),
                                eq(UserHandle.SYSTEM)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
    }

    private Matcher<View> withIdFromRuntimeResource(String id) {
        return withId(getRuntimeResourceId(id, "id"));
    }

    private Matcher<View> withTextFromRuntimeResource(String id) {
        return withText(getRuntimeResourceId(id, "string"));
    }

    private static GridRecyclerSpanCountMatcher withGridColumnCount(int columnCount) {
        return new GridRecyclerSpanCountMatcher(Matchers.is(columnCount));
    }

    private static class GridRecyclerSpanCountMatcher extends
            BoundedDiagnosingMatcher<View, RecyclerView> {

        private final Matcher<Integer> mIntegerMatcher;

        private GridRecyclerSpanCountMatcher(Matcher<Integer> integerMatcher) {
            super(RecyclerView.class);
            this.mIntegerMatcher = integerMatcher;
        }

        @Override
        protected void describeMoreTo(Description description) {
            description.appendText("RecyclerView grid layout span count to match: ");
            this.mIntegerMatcher.describeTo(description);
        }

        @Override
        protected boolean matchesSafely(RecyclerView view, Description mismatchDescription) {
            int spanCount = ((GridLayoutManager) view.getLayoutManager()).getSpanCount();
            if (this.mIntegerMatcher.matches(spanCount)) {
                return true;
            } else {
                mismatchDescription.appendText("RecyclerView grid layout span count was ")
                        .appendValue(spanCount);
                return false;
            }
        }
    }

    private void givenAppTargets(int appCount) {
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTest(appCount);
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resolverListController
                        .getResolversForIntent(
                                Mockito.anyBoolean(),
                                Mockito.anyBoolean(),
                                Mockito.isA(List.class)))
                .thenReturn(resolvedComponentInfos);
    }

    private void updateMaxTargetsPerRowResource(int targetsPerRow) {
        ChooserActivityOverrideData.getInstance().resources = Mockito.spy(
                InstrumentationRegistry.getInstrumentation().getContext().getResources());
        when(
                ChooserActivityOverrideData
                        .getInstance()
                        .resources
                        .getInteger(R.integer.config_chooser_max_targets_per_row))
                .thenReturn(targetsPerRow);
    }

    // ChooserWrapperActivity inherits from the framework ChooserActivity, so if the framework
    // resources have been updated since the framework was last built/pushed, the inherited behavior
    // (which is the focus of our testing) will still be implemented in terms of the old resource
    // IDs; then when we try to assert those IDs in tests (e.g. `onView(withText(R.string.foo))`),
    // the expected values won't match. The tests can instead call this method (with the same
    // general semantics as Resources#getIdentifier() e.g. `getRuntimeResourceId("foo", "string")`)
    // to refer to the resource by that name in the runtime chooser, regardless of whether the
    // framework code on the device is up-to-date.
    // TODO: is there a better way to do this? (Other than abandoning inheritance-based DI wrapper?)
    private int getRuntimeResourceId(String name, String defType) {
        int id = -1;
        if (ChooserActivityOverrideData.getInstance().resources != null) {
            id = ChooserActivityOverrideData.getInstance().resources.getIdentifier(
                  name, defType, "android");
        } else {
            id = mActivityRule.getActivity().getResources().getIdentifier(name, defType, "android");
        }
        assertThat(id, greaterThan(0));

        return id;
    }
}
