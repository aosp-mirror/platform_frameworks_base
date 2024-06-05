/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_ORDER;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SEARCHABLE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SWITCH_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
public class ActivityTileTest {

    private Context mContext;
    private ActivityInfo mActivityInfo;
    private Tile mTile;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mActivityInfo = new ActivityInfo();
        mActivityInfo.applicationInfo = new ApplicationInfo();
        mActivityInfo.packageName = mContext.getPackageName();
        mActivityInfo.name = "abc";
        mActivityInfo.icon = com.android.internal.R.drawable.ic_plus;
        mActivityInfo.metaData = new Bundle();
        mTile = new ActivityTile(mActivityInfo, "category");
    }

    @Test
    public void isPrimaryProfileOnly_profilePrimary_shouldReturnTrue() {
        mActivityInfo.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_PRIMARY);
        assertThat(mTile.isPrimaryProfileOnly()).isTrue();
    }

    @Test
    public void isPrimaryProfileOnly_profileAll_shouldReturnFalse() {
        mActivityInfo.metaData.putString(META_DATA_KEY_PROFILE, PROFILE_ALL);
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_noExplicitValue_shouldReturnFalse() {
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_nullMetadata_shouldReturnFalse() {
        mActivityInfo.metaData = null;
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void getIcon_noContextOrMetadata_returnNull() {
        mActivityInfo.metaData = null;
        final Tile tile = new ActivityTile(mActivityInfo, "category");
        assertThat(tile.getIcon(null)).isNull();
        assertThat(tile.getIcon(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void getIcon_providedByUri_returnNull() {
        mActivityInfo.metaData.putString(META_DATA_PREFERENCE_ICON_URI, "content://foobar/icon");

        assertThat(mTile.getIcon(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void getIcon_hasIconMetadata_returnIcon() {
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(mTile.getIcon(RuntimeEnvironment.application).getResId())
                .isEqualTo(android.R.drawable.ic_info);
    }

    @Test
    public void getIcon_transparentColorInMetadata_returnNull() {
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, android.R.color.transparent);

        assertThat(mTile.getIcon(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void isIconTintable_hasMetadata_shouldReturnIconTintableMetadata() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        mActivityInfo.metaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, false);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();

        mActivityInfo.metaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, true);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isTrue();
    }

    @Test
    public void isIconTintable_noIcon_shouldReturnFalse() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void isIconTintable_noTintableMetadata_shouldReturnFalse() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");
        mActivityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void getPriority_noMetadata_return0() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_badMetadata_return0() {
        mActivityInfo.metaData.putString(META_DATA_KEY_ORDER, "1");

        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_validMetadata_returnMetadataValue() {
        mActivityInfo.metaData.putInt(META_DATA_KEY_ORDER, 1);

        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getOrder()).isEqualTo(1);
    }

    @Test
    public void getTitle_shouldEnsureMetadataNotStale() {
        final ResolveInfo info = new ResolveInfo();
        info.activityInfo = mActivityInfo;
        final ShadowPackageManager spm = Shadow.extract(mContext.getPackageManager());
        spm.addResolveInfoForIntent(
                new Intent().setClassName(mActivityInfo.packageName, mActivityInfo.name), info);

        final Tile tile = new ActivityTile(mActivityInfo, "category");
        final long staleTimeStamp = -10000;
        tile.mLastUpdateTime = staleTimeStamp;

        tile.getTitle(RuntimeEnvironment.application);

        assertThat(tile.mLastUpdateTime).isNotEqualTo(staleTimeStamp);
    }

    @Test
    public void getTitle_noActivity_returnNull() {
        final ResolveInfo info = new ResolveInfo();
        info.activityInfo = mActivityInfo;
        final ShadowPackageManager spm = Shadow.extract(mContext.getPackageManager());
        spm.removePackage(mActivityInfo.packageName);

        final Tile tile = new ActivityTile(mActivityInfo, "category");
        tile.mComponentInfo = null;

        assertThat(tile.getTitle(RuntimeEnvironment.application)).isNull();
    }

    @Test
    public void hasPendingIntent_empty_returnsFalse() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.hasPendingIntent()).isFalse();
    }

    @Test
    public void hasPendingIntent_notEmpty_returnsTrue() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");
        tile.pendingIntentMap.put(
                UserHandle.CURRENT, PendingIntent.getActivity(mContext, 0, new Intent(), 0));

        assertThat(tile.hasPendingIntent()).isTrue();
    }

    @Test
    public void hasGroupKey_empty_returnsFalse() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.hasGroupKey()).isFalse();
    }

    @Test
    public void hasGroupKey_notEmpty_returnsTrue() {
        mActivityInfo.metaData.putString(META_DATA_PREFERENCE_GROUP_KEY, "test_key");
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.hasGroupKey()).isTrue();
    }

    @Test
    public void getGroupKey_empty_returnsNull() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getGroupKey()).isNull();
    }

    @Test
    public void getGroupKey_notEmpty_returnsValue() {
        mActivityInfo.metaData.putString(META_DATA_PREFERENCE_GROUP_KEY, "test_key");
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getGroupKey()).isEqualTo("test_key");
    }

    @Test
    public void getType_withoutSwitch_returnsAction() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getType()).isEqualTo(Tile.Type.ACTION);
    }

    @Test
    public void getType_withSwitch_returnsSwitchWithAction() {
        mActivityInfo.metaData.putString(META_DATA_PREFERENCE_SWITCH_URI, "test://testabc/");
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.getType()).isEqualTo(Tile.Type.SWITCH_WITH_ACTION);
    }

    @Test
    public void isSearchable_nullMetadata_isTrue() {
        mActivityInfo.metaData = null;

        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.isSearchable()).isTrue();
    }

    @Test
    public void isSearchable_notSet_isTrue() {
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.isSearchable()).isTrue();
    }

    @Test
    public void isSearchable_isSet_false() {
        mActivityInfo.metaData.putBoolean(META_DATA_PREFERENCE_SEARCHABLE, false);
        final Tile tile = new ActivityTile(mActivityInfo, "category");

        assertThat(tile.isSearchable()).isFalse();
    }
}
