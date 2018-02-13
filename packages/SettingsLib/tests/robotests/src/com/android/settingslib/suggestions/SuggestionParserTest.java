/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.suggestions;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.RuntimeEnvironment.application;
import static org.robolectric.shadow.api.Shadow.extract;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.android.settingslib.SettingsLibRobolectricTestRunner;
import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtilsTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class SuggestionParserTest {

    private ShadowPackageManager mPackageManager;
    private SuggestionParser mSuggestionParser;
    private SuggestionCategory mMultipleCategory;
    private SuggestionCategory mExclusiveCategory;
    private SuggestionCategory mExpiredExclusiveCategory;
    private List<Tile> mSuggestionsBeforeDismiss;
    private List<Tile> mSuggestionsAfterDismiss;
    private SharedPreferences mPrefs;
    private Tile mSuggestion;

    @Before
    public void setUp() {
        mPackageManager = extract(application.getPackageManager());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(application);
        mSuggestion = new Tile();
        mSuggestion.intent = new Intent("action");
        mSuggestion.intent.setComponent(new ComponentName("pkg", "cls"));
        mSuggestion.metaData = new Bundle();
        mMultipleCategory = new SuggestionCategory();
        mMultipleCategory.category = "category1";
        mMultipleCategory.multiple = true;
        mExclusiveCategory = new SuggestionCategory();
        mExclusiveCategory.category = "category2";
        mExclusiveCategory.exclusive = true;
        mExpiredExclusiveCategory = new SuggestionCategory();
        mExpiredExclusiveCategory.category = "category3";
        mExpiredExclusiveCategory.exclusive = true;
        mExpiredExclusiveCategory.exclusiveExpireDaysInMillis = 0;

        mSuggestionParser = new SuggestionParser(application, mPrefs,
                Arrays.asList(mMultipleCategory, mExclusiveCategory, mExpiredExclusiveCategory),
                "0");

        ResolveInfo info1 = TileUtilsTest.newInfo(true, null);
        info1.activityInfo.packageName = "pkg";
        ResolveInfo infoDupe1 = TileUtilsTest.newInfo(true, null);
        infoDupe1.activityInfo.packageName = "pkg";

        ResolveInfo info2 = TileUtilsTest.newInfo(true, null);
        info2.activityInfo.packageName = "pkg2";
        ResolveInfo info3 = TileUtilsTest.newInfo(true, null);
        info3.activityInfo.packageName = "pkg3";
        ResolveInfo info4 = TileUtilsTest.newInfo(true, null);
        info4.activityInfo.packageName = "pkg4";

        Intent intent1 = new Intent(Intent.ACTION_MAIN).addCategory("category1");
        Intent intent2 = new Intent(Intent.ACTION_MAIN).addCategory("category2");
        Intent intent3 = new Intent(Intent.ACTION_MAIN).addCategory("category3");

        mPackageManager.addResolveInfoForIntent(intent1, info1);
        mPackageManager.addResolveInfoForIntent(intent1, info2);
        mPackageManager.addResolveInfoForIntent(intent1, infoDupe1);
        mPackageManager.addResolveInfoForIntent(intent2, info3);
        mPackageManager.addResolveInfoForIntent(intent3, info4);
    }

    @Test
    public void dismissSuggestion_shouldDismiss() {
        assertThat(mSuggestionParser.dismissSuggestion(mSuggestion)).isTrue();
    }

    @Test
    public void testGetSuggestions_withoutSmartSuggestions_shouldDismiss() {
        readAndDismissSuggestion(false);
        mSuggestionParser.readSuggestions(mMultipleCategory, mSuggestionsAfterDismiss, false);
        assertThat(mSuggestionsBeforeDismiss).hasSize(2);
        assertThat(mSuggestionsAfterDismiss).hasSize(1);
        assertThat(mSuggestionsBeforeDismiss.get(1)).isEqualTo(mSuggestionsAfterDismiss.get(0));
    }

    @Test
    public void testGetSuggestions_withSmartSuggestions_shouldDismiss() {
        readAndDismissSuggestion(true);
        assertThat(mSuggestionsBeforeDismiss).hasSize(2);
        assertThat(mSuggestionsAfterDismiss).hasSize(1);
    }

    @Test
    public void testGetSuggestion_exclusiveNotAvailable_onlyRegularCategoryAndNoDupe() {
        mPackageManager.removeResolveInfosForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory("category2"),
                "pkg3");
        mPackageManager.removeResolveInfosForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory("category3"),
                "pkg4");

        // If exclusive item is not available, the other categories should be shown
        final SuggestionList sl =
                mSuggestionParser.getSuggestions(false /* isSmartSuggestionEnabled */);
        final List<Tile> suggestions = sl.getSuggestions();
        assertThat(suggestions).hasSize(2);

        assertThat(suggestions.get(0).intent.getComponent().getPackageName()).isEqualTo("pkg");
        assertThat(suggestions.get(1).intent.getComponent().getPackageName()).isEqualTo("pkg2");
    }

    @Test
    public void testGetSuggestion_exclusiveExpiredAvailable_shouldLoadWithRegularCategory() {
        // First remove permanent exclusive
        mPackageManager.removeResolveInfosForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory("category2"),
                "pkg3");
        // Set the other exclusive to be expired.
        mPrefs.edit()
                .putLong(mExpiredExclusiveCategory.category + "_setup_time",
                        System.currentTimeMillis() - 1000)
                .commit();

        // If exclusive is expired, they should be shown together with the other categories
        final SuggestionList sl =
                mSuggestionParser.getSuggestions(true /* isSmartSuggestionEnabled */);
        final List<Tile> suggestions = sl.getSuggestions();

        assertThat(suggestions).hasSize(3);
    }

    @Test
    public void testGetSuggestions_exclusive() {
        final SuggestionList sl =
                mSuggestionParser.getSuggestions(false /* isSmartSuggestionEnabled */);
        final List<Tile> suggestions = sl.getSuggestions();

        assertThat(suggestions).hasSize(1);
    }

    @Test
    public void isSuggestionDismissed_dismissedSuggestion_shouldReturnTrue() {
        final Tile suggestion = new Tile();
        suggestion.metaData = new Bundle();
        suggestion.metaData.putString(SuggestionParser.META_DATA_DISMISS_CONTROL, "1,2,3");
        suggestion.intent = new Intent().setComponent(new ComponentName("pkg", "cls"));

        // Dismiss suggestion when smart suggestion is not enabled.
        mSuggestionParser.dismissSuggestion(suggestion);

        assertThat(mSuggestionParser.isDismissed(suggestion, true /* isSmartSuggestionEnabled */))
                .isTrue();
    }

    private void readAndDismissSuggestion(boolean isSmartSuggestionEnabled) {
        mSuggestionsBeforeDismiss = new ArrayList<>();
        mSuggestionsAfterDismiss = new ArrayList<>();
        mSuggestionParser.readSuggestions(
                mMultipleCategory, mSuggestionsBeforeDismiss, isSmartSuggestionEnabled);

        final Tile suggestion = mSuggestionsBeforeDismiss.get(0);
        if (mSuggestionParser.dismissSuggestion(suggestion)) {
            mPackageManager.removeResolveInfosForIntent(
                    new Intent(Intent.ACTION_MAIN).addCategory(mMultipleCategory.category),
                    suggestion.intent.getComponent().getPackageName());
        }
        mSuggestionParser.readSuggestions(
                mMultipleCategory, mSuggestionsAfterDismiss, isSmartSuggestionEnabled);
    }
}
