/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.os.UserHandle;
import android.util.Pair;

import com.android.internal.app.AbstractMultiProfilePagerAdapter.CrossProfileIntentsChecker;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.MyUserIdProvider;
import com.android.internal.app.AbstractMultiProfilePagerAdapter.QuietModeManager;
import com.android.internal.app.chooser.TargetInfo;
import com.android.internal.logging.MetricsLogger;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Singleton providing overrides to be applied by any {@code IChooserWrapper} used in testing.
 * We cannot directly mock the activity created since instrumentation creates it, so instead we use
 * this singleton to modify behavior.
 */
public class ChooserActivityOverrideData {
    private static ChooserActivityOverrideData sInstance = null;

    public static ChooserActivityOverrideData getInstance() {
        if (sInstance == null) {
            sInstance = new ChooserActivityOverrideData();
        }
        return sInstance;
    }

    @SuppressWarnings("Since15")
    public Function<PackageManager, PackageManager> createPackageManager;
    public Function<TargetInfo, Boolean> onSafelyStartCallback;
    public Function<ChooserListAdapter, Void> onQueryDirectShareTargets;
    public BiFunction<
            IChooserWrapper, ChooserListAdapter, Pair<Integer, ChooserActivity.ServiceResultInfo[]>>
            directShareTargets;
    public ResolverListController resolverListController;
    public ResolverListController workResolverListController;
    public Boolean isVoiceInteraction;
    public boolean isImageType;
    public Cursor resolverCursor;
    public boolean resolverForceException;
    public Bitmap previewThumbnail;
    public MetricsLogger metricsLogger;
    public ChooserActivityLogger chooserActivityLogger;
    public int alternateProfileSetting;
    public Resources resources;
    public UserHandle workProfileUserHandle;
    public boolean hasCrossProfileIntents;
    public boolean isQuietModeEnabled;
    public boolean isWorkProfileUserRunning;
    public boolean isWorkProfileUserUnlocked;
    public Integer myUserId;
    public QuietModeManager mQuietModeManager;
    public MyUserIdProvider mMyUserIdProvider;
    public CrossProfileIntentsChecker mCrossProfileIntentsChecker;
    public PackageManager packageManager;

    public void reset() {
        onSafelyStartCallback = null;
        onQueryDirectShareTargets = null;
        directShareTargets = null;
        isVoiceInteraction = null;
        createPackageManager = null;
        previewThumbnail = null;
        isImageType = false;
        resolverCursor = null;
        resolverForceException = false;
        resolverListController = mock(ResolverListController.class);
        workResolverListController = mock(ResolverListController.class);
        metricsLogger = mock(MetricsLogger.class);
        chooserActivityLogger = new ChooserActivityLoggerFake();
        alternateProfileSetting = 0;
        resources = null;
        workProfileUserHandle = null;
        hasCrossProfileIntents = true;
        isQuietModeEnabled = false;
        isWorkProfileUserRunning = true;
        isWorkProfileUserUnlocked = true;
        myUserId = null;
        packageManager = null;
        mQuietModeManager = new QuietModeManager() {
            @Override
            public boolean isQuietModeEnabled(UserHandle workProfileUserHandle) {
                return isQuietModeEnabled;
            }

            @Override
            public void requestQuietModeEnabled(boolean enabled,
                    UserHandle workProfileUserHandle) {
                isQuietModeEnabled = enabled;
            }

            @Override
            public void markWorkProfileEnabledBroadcastReceived() {
            }

            @Override
            public boolean isWaitingToEnableWorkProfile() {
                return false;
            }
        };

        mMyUserIdProvider = new MyUserIdProvider() {
            @Override
            public int getMyUserId() {
                return myUserId != null ? myUserId : UserHandle.myUserId();
            }
        };

        mCrossProfileIntentsChecker = mock(CrossProfileIntentsChecker.class);
        when(mCrossProfileIntentsChecker.hasCrossProfileIntents(any(), anyInt(), anyInt()))
                .thenAnswer(invocation -> hasCrossProfileIntents);
    }

    private ChooserActivityOverrideData() {}
}
