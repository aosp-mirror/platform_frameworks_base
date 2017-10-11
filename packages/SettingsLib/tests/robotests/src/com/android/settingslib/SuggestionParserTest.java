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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.android.settingslib.drawer.Tile;
import com.android.settingslib.drawer.TileUtilsTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(SettingLibRobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class SuggestionParserTest {

    @Mock
    private PackageManager mPackageManager;
    private Context mContext;
    private SuggestionParser mSuggestionParser;
    private SuggestionParser.SuggestionCategory mSuggestioCategory;
    private List<Tile> mSuggestionsBeforeDismiss;
    private List<Tile> mSuggestionsAfterDismiss;
    private SharedPreferences mPrefs;
    private Tile mSuggestion;
    private List<ResolveInfo> mInfo;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        mSuggestion = new Tile();
        mSuggestion.intent = new Intent("action");
        mSuggestion.intent.setComponent(new ComponentName("pkg", "cls"));
        mSuggestion.metaData = new Bundle();
        mSuggestionParser = new SuggestionParser(
            mContext, mPrefs, R.xml.suggestion_ordering, "0,0");
        mSuggestioCategory = new SuggestionParser.SuggestionCategory();
        mSuggestioCategory.category = "category1";
        mSuggestioCategory.multiple = true;
        mInfo = new ArrayList<>();
        ResolveInfo info1 = TileUtilsTest.newInfo(true, "category1");
        info1.activityInfo.packageName = "pkg";
        ResolveInfo info2 = TileUtilsTest.newInfo(true, "category1");
        info2.activityInfo.packageName = "pkg2";
        mInfo.add(info1);
        mInfo.add(info2);
        when(mPackageManager.queryIntentActivitiesAsUser(
            any(Intent.class), anyInt(), anyInt())).thenReturn(mInfo);
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
        mSuggestionParser.readSuggestions(mSuggestioCategory, mSuggestionsAfterDismiss, false);
        assertThat(mSuggestionsBeforeDismiss.size()).isEqualTo(2);
        assertThat(mSuggestionsAfterDismiss.size()).isEqualTo(1);
        assertThat(mSuggestionsBeforeDismiss.get(1)).isEqualTo(mSuggestionsAfterDismiss.get(0));
    }

    @Test
    public void testGetSuggestions_withSmartSuggestions() {
        readAndDismissSuggestion(true);
        assertThat(mSuggestionsBeforeDismiss.size()).isEqualTo(2);
        assertThat(mSuggestionsAfterDismiss.size()).isEqualTo(2);
        assertThat(mSuggestionsBeforeDismiss).isEqualTo(mSuggestionsAfterDismiss);
    }

    private void readAndDismissSuggestion(boolean isSmartSuggestionEnabled) {
        mSuggestionsBeforeDismiss = new ArrayList<Tile>();
        mSuggestionsAfterDismiss = new ArrayList<Tile>();
        mSuggestionParser.readSuggestions(
            mSuggestioCategory, mSuggestionsBeforeDismiss, isSmartSuggestionEnabled);
        if (mSuggestionParser.dismissSuggestion(
            mSuggestionsBeforeDismiss.get(0), isSmartSuggestionEnabled)) {
            mInfo.remove(0);
        }
        mSuggestionParser.readSuggestions(
            mSuggestioCategory, mSuggestionsAfterDismiss, isSmartSuggestionEnabled);
    }
}
