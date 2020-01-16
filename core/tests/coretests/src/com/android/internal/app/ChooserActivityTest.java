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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.internal.app.ChooserActivity.CALLER_TARGET_SCORE_BOOST;
import static com.android.internal.app.ChooserActivity.SHORTCUT_TARGET_SCORE_BOOST;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_CHOOSER_TARGET;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_DEFAULT;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER;
import static com.android.internal.app.ChooserWrapperActivity.sOverrides;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager.ShareShortcutInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.metrics.LogMaker;
import android.net.Uri;
import android.service.chooser.ChooserTarget;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Chooser activity instrumentation tests
 */
@RunWith(Parameterized.class)
public class ChooserActivityTest {

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

    private static final int CONTENT_PREVIEW_IMAGE = 1;
    private static final int CONTENT_PREVIEW_FILE = 2;
    private static final int CONTENT_PREVIEW_TEXT = 3;
    private Function<PackageManager, PackageManager> mPackageManagerOverride;
    private int mTestNum;

    @Rule
    public ActivityTestRule<ChooserWrapperActivity> mActivityRule =
            new ActivityTestRule<>(ChooserWrapperActivity.class, false,
                    false);

    public ChooserActivityTest(
                int testNum,
                String testName,
                Function<PackageManager, PackageManager> packageManagerOverride) {
        mPackageManagerOverride = packageManagerOverride;
        mTestNum = testNum;
    }

    @Before
    public void cleanOverrideData() {
        sOverrides.reset();
        sOverrides.createPackageManager = mPackageManagerOverride;
    }

    @Test
    public void customTitle() throws InterruptedException {
        Intent viewIntent = createViewTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        final ChooserWrapperActivity activity = mActivityRule.launchActivity(
                Intent.createChooser(viewIntent, "chooser test"));

        waitForIdle();
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getAdapter().getServiceTargetCount(), is(0));
        onView(withId(R.id.title)).check(matches(withText("chooser test")));
    }

    @Test
    public void customTitleIgnoredForSendIntents() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, "chooser test"));
        waitForIdle();
        onView(withId(R.id.title)).check(matches(withText(R.string.whichSendApplication)));
    }

    @Test
    public void emptyTitle() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.title))
                .check(matches(withText(R.string.whichSendApplication)));
    }

    @Test
    public void emptyPreviewTitleAndThumbnail() throws InterruptedException {
        Intent sendIntent = createSendTextIntentWithPreview(null, null);
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_title)).check(matches(not(isDisplayed())));
        onView(withId(R.id.content_preview_thumbnail)).check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleWithoutThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle, null);
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_title)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_title)).check(matches(withText(previewTitle)));
        onView(withId(R.id.content_preview_thumbnail)).check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleWithInvalidThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle,
                Uri.parse("tel:(+49)12345789"));
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_title)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_thumbnail)).check(matches(not(isDisplayed())));
    }

    @Test
    public void visiblePreviewTitleAndThumbnail() throws InterruptedException {
        String previewTitle = "My Content Preview Title";
        Intent sendIntent = createSendTextIntentWithPreview(previewTitle,
                Uri.parse("android.resource://com.android.frameworks.coretests/"
                        + com.android.frameworks.coretests.R.drawable.test320x240));
        sOverrides.previewThumbnail = createBitmap();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_title)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_thumbnail)).check(matches(isDisplayed()));
    }

    @Test
    public void twoOptionsAndUserSelectsOne() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        assertThat(activity.getAdapter().getCount(), is(2));
        onView(withId(R.id.profile_button)).check(doesNotExist());

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void updateChooserCountsAndModelAfterUserSelection() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        UsageStatsManager usm = activity.getUsageStatsManager();
        verify(sOverrides.resolverListController, times(1))
                .sort(Mockito.any(List.class));
        assertThat(activity.getIsSelected(), is(false));
        sOverrides.onSafelyStartCallback = targetInfo -> {
            return true;
        };
        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        waitForIdle();
        verify(sOverrides.resolverListController, times(1))
                .updateChooserCounts(Mockito.anyString(), Mockito.anyInt(), Mockito.anyString());
        verify(sOverrides.resolverListController, times(1))
                .updateModel(toChoose.activityInfo.getComponentName());
        assertThat(activity.getIsSelected(), is(true));
    }

    @Test
    public void noResultsFromPackageManager() {
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(null);
        Intent sendIntent = createSendTextIntent();
        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        assertThat(activity.isFinishing(), is(false));

        onView(withId(R.id.empty)).check(matches(isDisplayed()));
        onView(withId(R.id.resolver_list)).check(matches(not(isDisplayed())));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().handlePackagesChanged()
        );
        // backward compatibility. looks like we finish when data is empty after package change
        assertThat(activity.isFinishing(), is(true));
    }

    @Test
    public void autoLaunchSingleResult() throws InterruptedException {
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(1);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        Intent sendIntent = createSendTextIntent();
        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        assertThat(chosen[0], is(resolvedComponentInfos.get(0).getResolveInfoAt(0)));
        assertThat(activity.isFinishing(), is(true));
    }

    @Test
    public void hasOtherProfileOneOption() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(ChooserWrapperActivity.sOverrides.resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserWrapperActivity.sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2);
        // Check that the "Other Profile" activity is put in the right spot
        onView(withId(R.id.profile_button)).check(matches(
                withText(stableCopy.get(0).getResolveInfoAt(0).activityInfo.name)));
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasOtherProfileTwoOptionsAndUserSelectsOne() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(ChooserWrapperActivity.sOverrides.resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(ChooserWrapperActivity.sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserWrapperActivity.sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(3);
        // Check that the "Other Profile" activity is put in the right spot
        onView(withId(R.id.profile_button)).check(matches(
                withText(stableCopy.get(0).getResolveInfoAt(0).activityInfo.name)));
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasLastChosenActivityAndOtherProfile() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(ChooserWrapperActivity.sOverrides.resolverListController.getResolversForIntent(
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        ChooserWrapperActivity.sOverrides.onSafelyStartCallback = targetInfo -> {
            chosen[0] = targetInfo.getResolveInfo();
            return true;
        };

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(3);
        // Check that the "Other Profile" activity is put in the right spot
        onView(withId(R.id.profile_button)).check(matches(
                withText(stableCopy.get(0).getResolveInfoAt(0).activityInfo.name)));
        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void copyTextToClipboard() throws Exception {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserWrapperActivity.sOverrides.resolverListController.getResolversForIntent(
            Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        onView(withId(R.id.chooser_copy_button)).check(matches(isDisplayed()));
        onView(withId(R.id.chooser_copy_button)).perform(click());
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(
                Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboard.getPrimaryClip();
        assertThat("testing intent sending", is(clipData.getItemAt(0).getText()));

        ClipDescription clipDescription = clipData.getDescription();
        assertThat("text/plain", is(clipDescription.getMimeType(0)));
    }

    @Test
    public void oneVisibleImagePreview() throws InterruptedException {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        sOverrides.previewThumbnail = createBitmap();
        sOverrides.isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_image_1_large)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_image_2_large)).check(matches(not(isDisplayed())));
        onView(withId(R.id.content_preview_image_2_small)).check(matches(not(isDisplayed())));
        onView(withId(R.id.content_preview_image_3_small)).check(matches(not(isDisplayed())));
    }

    @Test
    public void twoVisibleImagePreview() throws InterruptedException {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        sOverrides.previewThumbnail = createBitmap();
        sOverrides.isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_image_1_large)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_image_2_large)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_image_2_small)).check(matches(not(isDisplayed())));
        onView(withId(R.id.content_preview_image_3_small)).check(matches(not(isDisplayed())));
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
        sOverrides.previewThumbnail = createBitmap();
        sOverrides.isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_image_1_large)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_image_2_large)).check(matches(not(isDisplayed())));
        onView(withId(R.id.content_preview_image_2_small)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_image_3_small)).check(matches(isDisplayed()));
    }

    @Test
    public void testOnCreateLogging() {
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType("TestType");

        MetricsLogger mockLogger = sOverrides.metricsLogger;
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
                is("TestType"));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getSubtype(),
                is(MetricsEvent.PARENT_PROFILE));
    }

    @Test
    public void testOnCreateLoggingFromWorkProfile() {
        Intent sendIntent = createSendTextIntent();
        sendIntent.setType("TestType");
        sOverrides.alternateProfileSetting = MetricsEvent.MANAGED_PROFILE;
        MetricsLogger mockLogger = sOverrides.metricsLogger;
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
                is("TestType"));
        assertThat(logMakerCaptor
                        .getAllValues().get(0)
                        .getSubtype(),
                is(MetricsEvent.MANAGED_PROFILE));
    }

    @Test
    public void testEmptyPreviewLogging() {
        Intent sendIntent = createSendTextIntentWithPreview(null, null);

        MetricsLogger mockLogger = sOverrides.metricsLogger;
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

        MetricsLogger mockLogger = sOverrides.metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(ChooserWrapperActivity.sOverrides.resolverListController.getResolversForIntent(
            Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        verify(mockLogger, Mockito.times(2)).write(logMakerCaptor.capture());
        // First invocation is from onCreate
        assertThat(logMakerCaptor.getAllValues().get(1).getCategory(),
                is(MetricsEvent.ACTION_SHARE_WITH_PREVIEW));
        assertThat(logMakerCaptor.getAllValues().get(1).getSubtype(),
                is(CONTENT_PREVIEW_TEXT));
    }

    @Test
    public void testImagePreviewLogging() {
        Uri uri = Uri.parse("android.resource://com.android.frameworks.coretests/"
                + com.android.frameworks.coretests.R.drawable.test320x240);

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);
        sOverrides.previewThumbnail = createBitmap();
        sOverrides.isImageType = true;

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
            Mockito.anyBoolean(),
            Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        MetricsLogger mockLogger = sOverrides.metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        verify(mockLogger, Mockito.times(2)).write(logMakerCaptor.capture());
        // First invocation is from onCreate
        assertThat(logMakerCaptor.getAllValues().get(1).getCategory(),
                is(MetricsEvent.ACTION_SHARE_WITH_PREVIEW));
        assertThat(logMakerCaptor.getAllValues().get(1).getSubtype(),
                is(CONTENT_PREVIEW_IMAGE));
    }

    @Test
    public void oneVisibleFilePreview() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_filename)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_filename)).check(matches(withText("app.pdf")));
        onView(withId(R.id.content_preview_file_icon)).check(matches(isDisplayed()));
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

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_filename)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_filename)).check(matches(withText("app.pdf + 2 files")));
        onView(withId(R.id.content_preview_file_icon)).check(matches(isDisplayed()));
    }

    @Test
    public void contentProviderThrowSecurityException() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        sOverrides.resolverForceException = true;

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_filename)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_filename)).check(matches(withText("app.pdf")));
        onView(withId(R.id.content_preview_file_icon)).check(matches(isDisplayed()));
    }

    @Test
    public void contentProviderReturnsNoColumns() throws InterruptedException {
        Uri uri = Uri.parse("content://com.android.frameworks.coretests/app.pdf");

        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        uris.add(uri);

        Intent sendIntent = createSendUriIntentWithPreview(uris);

        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        Cursor cursor = mock(Cursor.class);
        when(cursor.getCount()).thenReturn(1);
        Mockito.doNothing().when(cursor).close();
        when(cursor.moveToFirst()).thenReturn(true);
        when(cursor.getColumnIndex(Mockito.anyString())).thenReturn(-1);

        sOverrides.resolverCursor = cursor;

        mActivityRule.launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();
        onView(withId(R.id.content_preview_filename)).check(matches(isDisplayed()));
        onView(withId(R.id.content_preview_filename)).check(matches(withText("app.pdf + 1 file")));
        onView(withId(R.id.content_preview_file_icon)).check(matches(isDisplayed()));
    }

    @Test
    public void testGetBaseScore() {
        final float testBaseScore = 0.89f;

        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getScore(Mockito.isA(
                ResolverActivity.DisplayResolveInfo.class))).thenReturn(testBaseScore);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        final ResolverActivity.DisplayResolveInfo testDri =
                activity.createTestDisplayResolveInfo(sendIntent,
                ResolverDataProvider.createResolveInfo(3, 0), "testLabel", "testInfo", sendIntent);
        final ChooserActivity.ChooserListAdapter adapter = activity.getAdapter();

        assertThat(adapter.getBaseScore(null, 0), is(CALLER_TARGET_SCORE_BOOST));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_DEFAULT), is(testBaseScore));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_CHOOSER_TARGET), is(testBaseScore));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE),
                is(SHORTCUT_TARGET_SCORE_BOOST));
        assertThat(adapter.getBaseScore(testDri, TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER),
                is(testBaseScore * SHORTCUT_TARGET_SCORE_BOOST));
    }

    /**
     * The case when AppPrediction service is not defined in PackageManager is already covered
     * as a test parameter {@link ChooserActivityTest#packageManagers}. This test is checking the
     * case when the prediction service is defined but the component is not available on the device.
     */
    @Test
    public void testIsAppPredictionServiceAvailable() {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        waitForIdle();

        if (activity.getPackageManager().getAppPredictionServicePackageName() == null) {
            assertThat(activity.isAppPredictionServiceAvailable(), is(false));
        } else {
            assertThat(activity.isAppPredictionServiceAvailable(), is(true));

            sOverrides.resources = Mockito.spy(activity.getResources());
            when(sOverrides.resources.getString(R.string.config_defaultAppPredictionService))
                    .thenReturn("ComponentNameThatDoesNotExist");

            assertThat(activity.isAppPredictionServiceAvailable(), is(false));
        }
    }

    @Test
    public void testConvertToChooserTarget_predictionService() {
        Intent sendIntent = createSendTextIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
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
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
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
    @Test
    public void testDirectTargetSelectionLogging() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = sOverrides.metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1, "");
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));

        // Insert the direct share target
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(ChooserActivity.LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);

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
    @Test
    public void testDirectTargetLoggingWithRankedAppTarget() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = sOverrides.metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1,
                resolvedComponentInfos.get(0).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(3, 0);

        // Start activity
        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));

        // Insert the direct share target
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(ChooserActivity.LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);

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

    // This test is too long and too slow and should not be taken as an example for future tests.
    @Test
    public void testDirectTargetLoggingWithAppTargetNotRanked() throws InterruptedException {
        Intent sendIntent = createSendTextIntent();
        // We need app targets for direct targets to get displayed
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(15);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        // Set up resources
        MetricsLogger mockLogger = sOverrides.metricsLogger;
        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        // Create direct share target
        List<ChooserTarget> serviceTargets = createDirectShareTargets(1,
                resolvedComponentInfos.get(14).getResolveInfoAt(0).activityInfo.packageName);
        ResolveInfo ri = ResolverDataProvider.createResolveInfo(16, 0);

        // Start activity
        final ChooserWrapperActivity activity = mActivityRule
                .launchActivity(Intent.createChooser(sendIntent, null));
        // Insert the direct share target
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> activity.getAdapter().addServiceResults(
                        activity.createTestDisplayResolveInfo(sendIntent,
                                ri,
                                "testLabel",
                                "testInfo",
                                sendIntent),
                        serviceTargets,
                        TARGET_TYPE_CHOOSER_TARGET)
        );
        // Thread.sleep shouldn't be a thing in an integration test but it's
        // necessary here because of the way the code is structured
        // TODO: restructure the tests b/129870719
        Thread.sleep(ChooserActivity.LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);

        assertThat("Chooser should have 20 targets (4 apps, 1 direct, 15 A-Z)",
                activity.getAdapter().getCount(), is(20));
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
        assertThat("The packages shouldn't match for app target and direct target", logMakerCaptor
                .getAllValues().get(2).getTaggedData(MetricsEvent.FIELD_RANKED_POSITION), is(-1));
    }

    private Intent createSendTextIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("text/plain");
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
}
