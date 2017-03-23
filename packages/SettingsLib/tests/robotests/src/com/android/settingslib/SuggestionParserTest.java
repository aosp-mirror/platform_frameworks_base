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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtilsTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.builder.DefaultPackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class SuggestionParserTest {

    private Context mContext;
    private SuggestionParser mSuggestionParser;
    private SuggestionParser.SuggestionCategory mMultipleCategory;
    private SuggestionParser.SuggestionCategory mExclusiveCategory;
    private List<Tile> mSuggestionsBeforeDismiss;
    private List<Tile> mSuggestionsAfterDismiss;
    private SharedPreferences mPrefs;
    private Tile mSuggestion;

    @Before
    public void setUp() {
        RuntimeEnvironment.setRobolectricPackageManager(
                new TestPackageManager(RuntimeEnvironment.getAppResourceLoader()));
        mContext = RuntimeEnvironment.application;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mSuggestion = new Tile();
        mSuggestion.intent = new Intent("action");
        mSuggestion.intent.setComponent(new ComponentName("pkg", "cls"));
        mSuggestion.metaData = new Bundle();
        mMultipleCategory = new SuggestionParser.SuggestionCategory();
        mMultipleCategory.category = "category1";
        mMultipleCategory.multiple = true;
        mExclusiveCategory = new SuggestionParser.SuggestionCategory();
        mExclusiveCategory.category = "category2";
        mExclusiveCategory.exclusive = true;
        mSuggestionParser = new SuggestionParser(
                mContext, mPrefs, Arrays.asList(mMultipleCategory, mExclusiveCategory), "0,0");

        ResolveInfo info1 = TileUtilsTest.newInfo(true, "category1");
        info1.activityInfo.packageName = "pkg";
        ResolveInfo info2 = TileUtilsTest.newInfo(true, "category1");
        info2.activityInfo.packageName = "pkg2";
        ResolveInfo info3 = TileUtilsTest.newInfo(true, "category2");
        info3.activityInfo.packageName = "pkg3";

        Intent intent1 = new Intent(Intent.ACTION_MAIN).addCategory("category1");
        Intent intent2 = new Intent(Intent.ACTION_MAIN).addCategory("category2");
        RuntimeEnvironment.getRobolectricPackageManager().addResolveInfoForIntent(intent1, info1);
        RuntimeEnvironment.getRobolectricPackageManager().addResolveInfoForIntent(intent1, info2);
        RuntimeEnvironment.getRobolectricPackageManager().addResolveInfoForIntent(intent2, info3);
    }

    @Test
    public void testDismissSuggestion_withoutSmartSuggestion() {
        assertThat(mSuggestionParser.dismissSuggestion(mSuggestion, false)).isTrue();
    }

    @Test
    public void testDismissSuggestion_withSmartSuggestion() {
        assertThat(mSuggestionParser.dismissSuggestion(mSuggestion, true)).isFalse();
    }

    @Test
    public void testGetSuggestions_withoutSmartSuggestions() {
        readAndDismissSuggestion(false);
        mSuggestionParser.readSuggestions(mMultipleCategory, mSuggestionsAfterDismiss, false);
        assertThat(mSuggestionsBeforeDismiss).hasSize(2);
        assertThat(mSuggestionsAfterDismiss).hasSize(1);
        assertThat(mSuggestionsBeforeDismiss.get(1)).isEqualTo(mSuggestionsAfterDismiss.get(0));
    }

    @Test
    public void testGetSuggestions_withSmartSuggestions() {
        readAndDismissSuggestion(true);
        assertThat(mSuggestionsBeforeDismiss).hasSize(2);
        assertThat(mSuggestionsAfterDismiss).hasSize(2);
        assertThat(mSuggestionsBeforeDismiss).isEqualTo(mSuggestionsAfterDismiss);
    }

    @Test
    public void testGetSuggestion_exclusiveNotAvailable() {
        RuntimeEnvironment.getRobolectricPackageManager().removeResolveInfosForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory("category2"),
                "pkg3");

        // If exclusive item is not available, the other categories should be shown
        final List<Tile> suggestions = mSuggestionParser.getSuggestions();
        assertThat(suggestions).hasSize(2);
        assertThat(suggestions.get(0).category).isEqualTo("category1");
        assertThat(suggestions.get(1).category).isEqualTo("category1");
    }

    @Test
    public void testGetSuggestions_exclusive() {
        final List<Tile> suggestions = mSuggestionParser.getSuggestions();
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).category).isEqualTo("category2");
    }

    private void readAndDismissSuggestion(boolean isSmartSuggestionEnabled) {
        mSuggestionsBeforeDismiss = new ArrayList<>();
        mSuggestionsAfterDismiss = new ArrayList<>();
        mSuggestionParser.readSuggestions(
                mMultipleCategory, mSuggestionsBeforeDismiss, isSmartSuggestionEnabled);
        final Tile suggestion = mSuggestionsBeforeDismiss.get(0);
        if (mSuggestionParser.dismissSuggestion(suggestion, isSmartSuggestionEnabled)) {
            RuntimeEnvironment.getRobolectricPackageManager().removeResolveInfosForIntent(
                    new Intent(Intent.ACTION_MAIN).addCategory(suggestion.category),
                    suggestion.intent.getComponent().getPackageName());
        }
        mSuggestionParser.readSuggestions(
                mMultipleCategory, mSuggestionsAfterDismiss, isSmartSuggestionEnabled);
    }

    private static class TestPackageManager extends DefaultPackageManager {

        TestPackageManager(ResourceLoader appResourceLoader) {
            super(appResourceLoader);
        }

        @Override
        public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) {
            return super.queryIntentActivities(intent, flags);
        }
    }
}
