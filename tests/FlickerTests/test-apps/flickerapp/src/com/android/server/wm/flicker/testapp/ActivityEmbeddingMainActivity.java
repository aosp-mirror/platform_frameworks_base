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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.window.embedding.ActivityFilter;
import androidx.window.embedding.ActivityRule;
import androidx.window.embedding.EmbeddingAspectRatio;
import androidx.window.embedding.RuleController;
import androidx.window.embedding.SplitAttributes;
import androidx.window.embedding.SplitAttributes.LayoutDirection;
import androidx.window.embedding.SplitController;
import androidx.window.embedding.SplitPairFilter;
import androidx.window.embedding.SplitPairRule;
import androidx.window.embedding.SplitPlaceholderRule;
import androidx.window.embedding.SplitRule;

import java.util.HashSet;
import java.util.Set;

/** Main activity of the ActivityEmbedding test app to launch other embedding activities. */
public class ActivityEmbeddingMainActivity extends Activity {
    private static final String TAG = "ActivityEmbeddingMainActivity";
    private static final float DEFAULT_SPLIT_RATIO = 0.5f;
    private RuleController mRuleController;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_embedding_main_layout);
        final SplitController.SplitSupportStatus status = SplitController.getInstance(
                this).getSplitSupportStatus();
        if (status != SplitController.SplitSupportStatus.SPLIT_AVAILABLE) {
            throw new RuntimeException(
                    "Unable to initiate SplitController in ActivityEmbeddingMainActivity, "
                            + "splitSupportStatus = " + status);
        }
        mRuleController = RuleController.getInstance(this);
    }

    /** R.id.launch_trampoline_button onClick */
    public void launchTrampolineActivity(View view) {
        final String layoutDirection = view.getTag().toString();
        mRuleController.clearRules();
        mRuleController.addRule(createSplitPairRules(layoutDirection));
        startActivity(new Intent().setComponent(
                ActivityOptions.ActivityEmbedding.TrampolineActivity.COMPONENT));
    }

    /** R.id.launch_secondary_activity_button onClick */
    public void launchSecondaryActivity(View view) {
        final String layoutDirection = view.getTag().toString();
        mRuleController.clearRules();
        mRuleController.addRule(createSplitPairRules(layoutDirection));
        startActivity(new Intent().setComponent(
                ActivityOptions.ActivityEmbedding.SecondaryActivity.COMPONENT));
    }

    /** R.id.launch_always_expand_activity_button onClick */
    public void launchAlwaysExpandActivity(View view) {
        final Set<ActivityFilter> activityFilters = new HashSet<>();
        activityFilters.add(
                new ActivityFilter(ActivityOptions.ActivityEmbedding.AlwaysExpandActivity.COMPONENT,
                        null));
        final ActivityRule activityRule = new ActivityRule.Builder(activityFilters)
                .setAlwaysExpand(true)
                .build();

        RuleController rc = RuleController.getInstance(this);

        rc.addRule(activityRule);
        startActivity(new Intent().setComponent(
                ActivityOptions.ActivityEmbedding.AlwaysExpandActivity.COMPONENT));
    }

    /** R.id.launch_placeholder_split_button onClick */
    public void launchPlaceholderSplit(View view) {
        final String layoutDirection = view.getTag().toString();
        mRuleController.clearRules();
        mRuleController.addRule(createSplitPlaceholderRules(layoutDirection));
        startActivity(new Intent().setComponent(
                ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT));
    }

    private static SplitPairRule createSplitPairRules(@NonNull String layoutDirection) {
        final Set<SplitPairFilter> pairFilters = getSplitPairFilters();
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.SPLIT_TYPE_EQUAL)
                .setLayoutDirection(parseLayoutDirection(layoutDirection))
                .build();
        // Setting thresholds to ALWAYS_ALLOW values to make it easy for running on all devices.
        return new SplitPairRule.Builder(pairFilters)
                .setDefaultSplitAttributes(splitAttributes)
                .setMinWidthDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMinHeightDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMinSmallestWidthDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .setMaxAspectRatioInLandscape(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .build();
    }

    @NonNull
    private static Set<SplitPairFilter> getSplitPairFilters() {
        final Set<SplitPairFilter> pairFilters = new HashSet<>();
        final SplitPairFilter mainAndSecondaryActivitiesPair = new SplitPairFilter(
                ActivityOptions.ActivityEmbedding.MainActivity.COMPONENT,
                ActivityOptions.ActivityEmbedding.SecondaryActivity.COMPONENT,
                null /* secondaryActivityIntentAction */);
        pairFilters.add(mainAndSecondaryActivitiesPair);
        final SplitPairFilter mainAndTrampolineActivitiesPair = new SplitPairFilter(
                ActivityOptions.ActivityEmbedding.MainActivity.COMPONENT,
                ActivityOptions.ActivityEmbedding.TrampolineActivity.COMPONENT,
                null /* secondaryActivityIntentAction */);
        pairFilters.add(mainAndTrampolineActivitiesPair);
        return pairFilters;
    }

    private static SplitPlaceholderRule createSplitPlaceholderRules(
            @NonNull String layoutDirection) {
        final Set<ActivityFilter> activityFilters = new HashSet<>();
        activityFilters.add(new ActivityFilter(
                ActivityOptions.ActivityEmbedding.PlaceholderPrimaryActivity.COMPONENT,
                null /* intentAction */));
        final Intent intent = new Intent();
        intent.setComponent(
                ActivityOptions.ActivityEmbedding.PlaceholderSecondaryActivity.COMPONENT);
        final SplitAttributes splitAttributes = new SplitAttributes.Builder()
                .setSplitType(SplitAttributes.SplitType.SPLIT_TYPE_EQUAL)
                .setLayoutDirection(parseLayoutDirection(layoutDirection))
                .build();
        final SplitPlaceholderRule rule = new SplitPlaceholderRule.Builder(activityFilters, intent)
                .setDefaultSplitAttributes(splitAttributes)
                .setMinWidthDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMinHeightDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMinSmallestWidthDp(SplitRule.SPLIT_MIN_DIMENSION_ALWAYS_ALLOW)
                .setMaxAspectRatioInPortrait(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .setMaxAspectRatioInLandscape(EmbeddingAspectRatio.ALWAYS_ALLOW)
                .build();
        return rule;
    }

    private static LayoutDirection parseLayoutDirection(@NonNull String layoutDirectionStr) {
        if (layoutDirectionStr.equals(LayoutDirection.LEFT_TO_RIGHT.toString())) {
            return LayoutDirection.LEFT_TO_RIGHT;
        }
        if (layoutDirectionStr.equals(LayoutDirection.BOTTOM_TO_TOP.toString())) {
            return LayoutDirection.BOTTOM_TO_TOP;
        }
        if (layoutDirectionStr.equals(LayoutDirection.RIGHT_TO_LEFT.toString())) {
            return LayoutDirection.RIGHT_TO_LEFT;
        }
        return LayoutDirection.LOCALE;
    }
}
