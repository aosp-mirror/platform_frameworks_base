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
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

@RunWith(RobolectricTestRunner.class)
public class ProviderTileTest {

    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private Context mContext;
    private ProviderInfo mProviderInfo;
    private Bundle mMetaData;
    private Tile mTile;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
        mProviderInfo = new ProviderInfo();
        mProviderInfo.applicationInfo = new ApplicationInfo();
        mProviderInfo.packageName = mContext.getPackageName();
        mProviderInfo.name = "abc";
        mProviderInfo.authority = "authority";
        mMetaData = new Bundle();
        mMetaData.putString(META_DATA_PREFERENCE_KEYHINT, "key");
        mMetaData.putString(META_DATA_PREFERENCE_TITLE, "title");
        mMetaData.putInt(META_DATA_PREFERENCE_ICON, com.android.internal.R.drawable.ic_plus);
        mTile = new ProviderTile(mProviderInfo, "category", mMetaData);
    }

    @Test
    public void isPrimaryProfileOnly_profilePrimary_shouldReturnTrue() {
        mMetaData.putString(META_DATA_KEY_PROFILE, PROFILE_PRIMARY);
        assertThat(mTile.isPrimaryProfileOnly()).isTrue();
    }

    @Test
    public void isPrimaryProfileOnly_profileAll_shouldReturnFalse() {
        mMetaData.putString(META_DATA_KEY_PROFILE, PROFILE_ALL);
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void isPrimaryProfileOnly_noExplicitValue_shouldReturnFalse() {
        assertThat(mTile.isPrimaryProfileOnly()).isFalse();
    }

    @Test
    public void getIcon_noContextOrMetadata_shouldThrowNullPointerException() {
        thrown.expect(NullPointerException.class);

        final Tile tile = new ProviderTile(mProviderInfo, "category", null);
    }

    @Test
    public void getIcon_hasIconMetadata_returnIcon() {
        mMetaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(mTile.getIcon(RuntimeEnvironment.application).getResId())
                .isEqualTo(android.R.drawable.ic_info);
    }

    @Test
    public void isIconTintable_hasMetadata_shouldReturnIconTintableMetadata() {
        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);

        mMetaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, false);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();

        mMetaData.putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, true);
        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isTrue();
    }

    @Test
    public void isIconTintable_noIcon_shouldReturnFalse() {
        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void isIconTintable_noTintableMetadata_shouldReturnFalse() {
        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);
        mMetaData.putInt(META_DATA_PREFERENCE_ICON, android.R.drawable.ic_info);

        assertThat(tile.isIconTintable(RuntimeEnvironment.application)).isFalse();
    }

    @Test
    public void getPriority_noMetadata_return0() {
        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_badMetadata_return0() {
        mMetaData.putString(META_DATA_KEY_ORDER, "1");

        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);

        assertThat(tile.getOrder()).isEqualTo(0);
    }

    @Test
    public void getPriority_validMetadata_returnMetadataValue() {
        mMetaData.putInt(META_DATA_KEY_ORDER, 1);

        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);

        assertThat(tile.getOrder()).isEqualTo(1);
    }

    @Test
    @Config(shadows = ShadowTileUtils.class)
    public void getTitle_shouldEnsureMetadataNotStale() {
        final ResolveInfo info = new ResolveInfo();
        info.providerInfo = mProviderInfo;
        final ShadowPackageManager spm = Shadow.extract(mContext.getPackageManager());
        spm.addResolveInfoForIntent(
                new Intent().setClassName(mProviderInfo.packageName, mProviderInfo.name), info);
        ShadowTileUtils.setMetaData(mMetaData);

        final Tile tile = new ProviderTile(mProviderInfo, "category", mMetaData);
        final long staleTimeStamp = -10000;
        tile.mLastUpdateTime = staleTimeStamp;

        tile.getTitle(RuntimeEnvironment.application);

        assertThat(tile.mLastUpdateTime).isNotEqualTo(staleTimeStamp);
    }

    @Implements(TileUtils.class)
    private static class ShadowTileUtils {

        private static Bundle sMetaData;

        @Implementation
        protected static Bundle getSwitchDataFromProvider(Context context, String authority,
                String key) {
            return sMetaData;
        }

        private static void setMetaData(Bundle metaData) {
            sMetaData = metaData;
        }
    }
}
