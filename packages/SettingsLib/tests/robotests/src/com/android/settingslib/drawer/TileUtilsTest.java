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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.IA_SETTINGS_ACTION;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_PENDING_INTENT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.RuntimeEnvironment.application;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = TileUtilsTest.ShadowTileUtils.class)
public class TileUtilsTest {

    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Resources mResources;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private Context mUserContext;
    @Mock
    private ContentResolver mUserContentResolver;

    private static final String URI_GET_SUMMARY = "content://authority/text/summary";
    private static final String URI_GET_ICON = "content://authority/icon/my_icon";

    @Before
    public void setUp() throws NameNotFoundException {
        mContext = spy(application);
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getResourcesForApplication(anyString())).thenReturn(mResources);
        when(mPackageManager.getResourcesForApplication((String) isNull())).thenReturn(mResources);
        when(mPackageManager.getApplicationInfo(eq("abc"), anyInt()))
                .thenReturn(application.getApplicationInfo());
        mContentResolver = spy(application.getContentResolver());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getPackageName()).thenReturn("com.android.settings");
        when(mUserContext.getContentResolver()).thenReturn(mUserContentResolver);
        ShadowTileUtils.sCallRealEntryDataFromProvider = false;
    }

    @Test
    public void getTilesForIntent_shouldParseCategory() {
        final String testCategory = "category1";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(true, testCategory));
        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(2);
        assertThat(outTiles.get(0).getCategory()).isEqualTo(testCategory);
        assertThat(outTiles.get(1).getCategory()).isEqualTo(testCategory);
    }

    @Test
    public void getTilesForIntent_shouldParseKeyHintForSystemApp() {
        String keyHint = "key";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, keyHint);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).hasSize(2);
        assertThat(outTiles.get(0).getKey(mContext)).isEqualTo(keyHint);
        assertThat(outTiles.get(1).getKey(mContext)).isEqualTo(keyHint);
    }

    @Test
    public void getTilesForIntent_shouldSkipNonSystemApp() {
        final String testCategory = "category1";
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        info.add(newInfo(false, testCategory));

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).isEmpty();
    }

    @Test
    public void getCategories_withPackageName() {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        Map<Pair<String, String>, Tile> cache = new ArrayMap<>();
        Global.putInt(mContext.getContentResolver(), Global.DEVICE_PROVISIONED, 1);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        List<UserHandle> userHandleList = new ArrayList<>();

        userHandleList.add(new UserHandle(ActivityManager.getCurrentUser()));
        when(mUserManager.getUserProfiles()).thenReturn(userHandleList);

        TileUtils.getCategories(mContext, cache);
        verify(mPackageManager, atLeastOnce()).queryIntentActivitiesAsUser(
                intentCaptor.capture(), anyInt(), anyInt());
        verify(mPackageManager, atLeastOnce()).queryIntentContentProvidersAsUser(
                intentCaptor.capture(), anyInt(), anyInt());

        assertThat(intentCaptor.getAllValues().get(0).getPackage())
                .isEqualTo(TileUtils.SETTING_PKG);
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleAsString() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, "my title", 0, PROFILE_ALL);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(2);
        assertThat(outTiles.get(0).getTitle(mContext)).isEqualTo("my title");
        assertThat(outTiles.get(1).getTitle(mContext)).isEqualTo("my title");
    }

    @Test
    public void getTilesForIntent_shouldReadMetadataTitleFromResource() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        when(mResources.getString(eq(123)))
                .thenReturn("my localized title");

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);
        assertThat(outTiles).hasSize(2);
        assertThat(outTiles.get(0).getTitle(mContext)).isEqualTo("my localized title");
        assertThat(outTiles.get(1).getTitle(mContext)).isEqualTo("my localized title");
    }

    @Test
    public void getTilesForIntent_shouldNotTintIconIfInSettingsPackage() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        resolveInfo.activityInfo.packageName = "com.android.settings";
        resolveInfo.activityInfo.applicationInfo.packageName = "com.android.settings";
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.get(0).isIconTintable(mContext)).isFalse();
        assertThat(outTiles.get(1).isIconTintable(mContext)).isFalse();
    }

    @Test
    public void getTilesForIntent_tileAlreadyInCache_shouldUpdateMetaData() {
        final Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        final List<Tile> outTiles = new ArrayList<>();
        final List<ResolveInfo> info = new ArrayList<>();
        final ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        resolveInfo.activityInfo.packageName = "com.android.settings";
        resolveInfo.activityInfo.applicationInfo.packageName = "com.android.settings";
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(1);
        final Bundle oldMetadata = outTiles.get(0).getMetaData();

        resolveInfo.activityInfo.metaData = new Bundle(oldMetadata);
        resolveInfo.activityInfo.metaData.putInt(META_DATA_PREFERENCE_ICON,
                com.android.internal.R.drawable.ic_phone);
        outTiles.clear();
        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(1);
        final Bundle newMetaData = outTiles.get(0).getMetaData();
        assertThat(newMetaData).isNotSameInstanceAs(oldMetadata);
    }

    @Test
    public void getTilesForIntent_shouldMarkIconTintableIfMetadataSet() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        resolveInfo.activityInfo.metaData
                .putBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE, true);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles.get(0).isIconTintable(mContext)).isTrue();
        assertThat(outTiles.get(1).isIconTintable(mContext)).isTrue();
    }

    @Test
    public void getTilesForIntent_shouldProcessUriContentForSystemApp() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION, addedCache,
                null /* defaultCategory */, outTiles, false /* usePriority */);

        assertThat(outTiles).hasSize(2);
    }

    @Test
    public void loadTilesForAction_isPrimaryProfileOnly_shouldSkipNonPrimaryUserTiles() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_PRIMARY);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);
        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
                anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, new UserHandle(10), IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).isEmpty();
    }

    @Test
    public void loadTilesForAction_multipleUserProfiles_updatesUserHandle() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);
        TileUtils.loadTilesForAction(mContext, new UserHandle(10), IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).hasSize(1);
        assertThat(outTiles.get(0).userHandle)
                .containsExactly(UserHandle.CURRENT, new UserHandle(10));
    }

    @Test
    public void loadTilesForAction_forUserProvider_getEntryDataFromProvider_inContextOfGivenUser() {
        ShadowTileUtils.sCallRealEntryDataFromProvider = true;
        UserHandle userHandle = new UserHandle(10);

        doReturn(mUserContext).when(mContext).createContextAsUser(eq(userHandle), anyInt());

        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentContentProvidersAsUser(any(Intent.class), anyInt(),
            anyInt())).thenReturn(info);

        TileUtils.loadTilesForAction(mContext, userHandle, IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        verify(mUserContentResolver, atLeastOnce())
            .acquireUnstableProvider(any(Uri.class));
    }

    @Test
    public void loadTilesForAction_withPendingIntent_updatesPendingIntentMap() {
        Map<Pair<String, String>, Tile> addedCache = new ArrayMap<>();
        List<Tile> outTiles = new ArrayList<>();
        List<ResolveInfo> info = new ArrayList<>();
        ResolveInfo resolveInfo = newInfo(true, null /* category */, null, URI_GET_ICON,
                URI_GET_SUMMARY, null, 123, PROFILE_ALL);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(), 0);
        resolveInfo.activityInfo.metaData
                .putParcelable(META_DATA_PREFERENCE_PENDING_INTENT, pendingIntent);
        info.add(resolveInfo);

        when(mPackageManager.queryIntentActivitiesAsUser(any(Intent.class), anyInt(), anyInt()))
                .thenReturn(info);

        TileUtils.loadTilesForAction(mContext, UserHandle.CURRENT, IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);
        TileUtils.loadTilesForAction(mContext, new UserHandle(10), IA_SETTINGS_ACTION,
                addedCache, null /* defaultCategory */, outTiles, false /* requiresSettings */);

        assertThat(outTiles).hasSize(1);
        assertThat(outTiles.get(0).pendingIntentMap).containsExactly(
                UserHandle.CURRENT, pendingIntent, new UserHandle(10), pendingIntent);
    }

    public static ResolveInfo newInfo(boolean systemApp, String category) {
        return newInfo(systemApp, category, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint) {
        return newInfo(systemApp, category, keyHint, null, null);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri) {
        return newInfo(systemApp, category, keyHint, iconUri, summaryUri, null, 0, PROFILE_ALL);
    }

    private static ResolveInfo newInfo(boolean systemApp, String category, String keyHint,
            String iconUri, String summaryUri, String title, int titleResId, String profile) {

        final Bundle metaData = newMetaData(category, keyHint, iconUri, summaryUri, title,
                titleResId, profile);
        final ResolveInfo info = new ResolveInfo();
        info.system = systemApp;

        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "abc";
        info.activityInfo.name = "123";
        info.activityInfo.metaData = metaData;
        info.activityInfo.applicationInfo = new ApplicationInfo();

        info.providerInfo = new ProviderInfo();
        info.providerInfo.packageName = "abc";
        info.providerInfo.name = "456";
        info.providerInfo.authority = "auth";
        info.providerInfo.metaData = metaData;
        ShadowTileUtils.setMetaData(metaData);
        info.providerInfo.applicationInfo = new ApplicationInfo();

        if (systemApp) {
            info.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
            info.providerInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        return info;
    }

    private static Bundle newMetaData(String category, String keyHint, String iconUri,
            String summaryUri, String title, int titleResId, String profile) {
        final Bundle metaData = new Bundle();
        metaData.putString("com.android.settings.category", category);
        metaData.putInt(META_DATA_PREFERENCE_ICON, 314159);
        metaData.putString(META_DATA_PREFERENCE_SUMMARY, "static-summary");
        if (keyHint != null) {
            metaData.putString(META_DATA_PREFERENCE_KEYHINT, keyHint);
        }
        if (iconUri != null) {
            metaData.putString(META_DATA_PREFERENCE_ICON_URI, iconUri);
        }
        if (summaryUri != null) {
            metaData.putString(META_DATA_PREFERENCE_SUMMARY_URI, summaryUri);
        }
        if (titleResId != 0) {
            metaData.putInt(TileUtils.META_DATA_PREFERENCE_TITLE, titleResId);
        } else if (title != null) {
            metaData.putString(TileUtils.META_DATA_PREFERENCE_TITLE, title);
        }
        if (profile != null) {
            metaData.putString(META_DATA_KEY_PROFILE, profile);
        }
        return metaData;
    }

    @Implements(TileUtils.class)
    static class ShadowTileUtils {

        private static Bundle sMetaData;

        private static boolean sCallRealEntryDataFromProvider;

        @Implementation
        protected static List<Bundle> getEntryDataFromProvider(Context context, String authority) {
            if (sCallRealEntryDataFromProvider) {
                return Shadow.directlyOn(
                    TileUtils.class,
                    "getEntryDataFromProvider",
                    ReflectionHelpers.ClassParameter.from(Context.class, context),
                    ReflectionHelpers.ClassParameter.from(String.class, authority));
            }
            return Arrays.asList(sMetaData);
        }

        private static void setMetaData(Bundle metaData) {
            sMetaData = metaData;
        }
    }
}
