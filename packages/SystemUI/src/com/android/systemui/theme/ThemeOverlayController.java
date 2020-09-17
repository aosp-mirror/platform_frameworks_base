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
package com.android.systemui.theme;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.om.OverlayManager;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;

import com.google.android.collect.Sets;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls the application of theme overlays across the system for all users.
 * This service is responsible for:
 * - Observing changes to Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES and applying the
 * corresponding overlays across the system
 * - Observing user switches, applying the overlays for the current user to user 0 (for systemui)
 * - Observing work profile changes and applying overlays from the primary user to their
 * associated work profiles
 */
@Singleton
public class ThemeOverlayController extends SystemUI {
    private static final String TAG = "ThemeOverlayController";
    private static final boolean DEBUG = false;

    private ThemeOverlayManager mThemeManager;
    private UserManager mUserManager;
    private BroadcastDispatcher mBroadcastDispatcher;
    private final Handler mBgHandler;

    @Inject
    public ThemeOverlayController(Context context, BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler) {
        super(context);
        mBroadcastDispatcher = broadcastDispatcher;
        mBgHandler = bgHandler;
    }

    @Override
    public void start() {
        if (DEBUG) Log.d(TAG, "Start");
        mUserManager = mContext.getSystemService(UserManager.class);
        mThemeManager = new ThemeOverlayManager(
                mContext.getSystemService(OverlayManager.class),
                AsyncTask.THREAD_POOL_EXECUTOR,
                mContext.getString(R.string.launcher_overlayable_package),
                mContext.getString(R.string.themepicker_overlayable_package));
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        mBroadcastDispatcher.registerReceiverWithHandler(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (DEBUG) Log.d(TAG, "Updating overlays for user switch / profile added.");
                updateThemeOverlays();
            }
        }, filter, mBgHandler, UserHandle.ALL);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
                false,
                new ContentObserver(mBgHandler) {

                    @Override
                    public void onChange(boolean selfChange, Collection<Uri> uris, int flags,
                            int userId) {
                        if (DEBUG) Log.d(TAG, "Overlay changed for user: " + userId);
                        if (ActivityManager.getCurrentUser() == userId) {
                            updateThemeOverlays();
                        }
                    }
                },
                UserHandle.USER_ALL);
    }

    private void updateThemeOverlays() {
        final int currentUser = ActivityManager.getCurrentUser();
        final String overlayPackageJson = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                currentUser);
        if (DEBUG) Log.d(TAG, "updateThemeOverlays: " + overlayPackageJson);
        final Map<String, String> categoryToPackage = new ArrayMap<>();
        if (!TextUtils.isEmpty(overlayPackageJson)) {
            try {
                JSONObject object = new JSONObject(overlayPackageJson);
                for (String category : ThemeOverlayManager.THEME_CATEGORIES) {
                    if (object.has(category)) {
                        categoryToPackage.put(category, object.getString(category));
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, "Failed to parse THEME_CUSTOMIZATION_OVERLAY_PACKAGES.", e);
            }
        }
        Set<UserHandle> userHandles = Sets.newHashSet(UserHandle.of(currentUser));
        for (UserInfo userInfo : mUserManager.getEnabledProfiles(currentUser)) {
            if (userInfo.isManagedProfile()) {
                userHandles.add(userInfo.getUserHandle());
            }
        }
        mThemeManager.applyCurrentUserOverlays(categoryToPackage, userHandles);
    }
}
