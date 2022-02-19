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

package com.android.server.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.UserHandle;
import android.service.games.GameService;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import com.android.server.SystemService;
import com.android.server.app.GameServiceConfiguration.GameServiceComponentConfiguration;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

final class GameServiceProviderSelectorImpl implements GameServiceProviderSelector {
    private static final String TAG = "GameServiceProviderSelector";
    private static final String GAME_SERVICE_NODE_NAME = "game-service";
    private static final boolean DEBUG = false;

    private final Resources mResources;
    private final PackageManager mPackageManager;

    GameServiceProviderSelectorImpl(@NonNull Resources resources,
            @NonNull PackageManager packageManager) {
        mResources = resources;
        mPackageManager = packageManager;
    }

    @Override
    @Nullable
    public GameServiceConfiguration get(@Nullable SystemService.TargetUser user,
            @Nullable String packageNameOverride) {
        if (user == null) {
            return null;
        }

        boolean isUserSupported = user.isFull() && !user.isManagedProfile();
        if (!isUserSupported) {
            Slog.i(TAG, "Game Service not supported for user: " + user.getUserIdentifier());
            return null;
        }

        int resolveInfoQueryFlags;
        String gameServicePackage;
        if (!TextUtils.isEmpty(packageNameOverride)) {
            resolveInfoQueryFlags = 0;
            gameServicePackage = packageNameOverride;
        } else {
            resolveInfoQueryFlags = PackageManager.MATCH_SYSTEM_ONLY;
            gameServicePackage = mResources.getString(
                    com.android.internal.R.string.config_systemGameService);
        }

        if (TextUtils.isEmpty(gameServicePackage)) {
            Slog.w(TAG, "No game service package defined");
            return null;
        }

        int userId = user.getUserIdentifier();
        List<ResolveInfo> gameServiceResolveInfos =
                mPackageManager.queryIntentServicesAsUser(
                        new Intent(GameService.ACTION_GAME_SERVICE).setPackage(gameServicePackage),
                        PackageManager.GET_META_DATA | resolveInfoQueryFlags,
                        userId);
        if (DEBUG) {
            Slog.i(TAG, "Querying package: " + gameServicePackage + " and user id: " + userId);
            Slog.i(TAG, "Found resolve infos: " + gameServiceResolveInfos);
        }

        if (gameServiceResolveInfos == null || gameServiceResolveInfos.isEmpty()) {
            Slog.w(TAG, "No available game service found for user id: " + userId);
            return new GameServiceConfiguration(gameServicePackage, null);
        }

        GameServiceConfiguration selectedProvider = null;
        for (ResolveInfo resolveInfo : gameServiceResolveInfos) {
            if (resolveInfo.serviceInfo == null) {
                continue;
            }
            ServiceInfo gameServiceServiceInfo = resolveInfo.serviceInfo;

            ComponentName gameSessionServiceComponentName =
                    determineGameSessionServiceFromGameService(gameServiceServiceInfo);
            if (gameSessionServiceComponentName == null) {
                continue;
            }

            selectedProvider =
                    new GameServiceConfiguration(
                            gameServicePackage,
                            new GameServiceComponentConfiguration(
                                    new UserHandle(userId),
                                    gameServiceServiceInfo.getComponentName(),
                                    gameSessionServiceComponentName));
            break;
        }

        if (selectedProvider == null) {
            Slog.w(TAG, "No valid game service found for user id: " + userId);
            return new GameServiceConfiguration(gameServicePackage, null);
        }

        return selectedProvider;
    }

    @Nullable
    private ComponentName determineGameSessionServiceFromGameService(
            @NonNull ServiceInfo gameServiceServiceInfo) {
        String gameSessionService;
        try (XmlResourceParser parser = gameServiceServiceInfo.loadXmlMetaData(mPackageManager,
                GameService.SERVICE_META_DATA)) {
            if (parser == null) {
                Slog.w(TAG, "No " + GameService.SERVICE_META_DATA + " meta-data found for "
                        + gameServiceServiceInfo.getComponentName());
                return null;
            }

            Resources resources = mPackageManager.getResourcesForApplication(
                    gameServiceServiceInfo.packageName);

            AttributeSet attributeSet = Xml.asAttributeSet(parser);
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // Do nothing
            }

            boolean isStartingTagGameService = GAME_SERVICE_NODE_NAME.equals(parser.getName());
            if (!isStartingTagGameService) {
                Slog.w(TAG, "Meta-data does not start with " + GAME_SERVICE_NODE_NAME + " tag");
                return null;
            }

            TypedArray array = resources.obtainAttributes(attributeSet,
                    com.android.internal.R.styleable.GameService);
            gameSessionService = array.getString(
                    com.android.internal.R.styleable.GameService_gameSessionService);
            array.recycle();
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException ex) {
            Slog.w("Error while parsing meta-data for " + gameServiceServiceInfo.getComponentName(),
                    ex);
            return null;
        }

        if (TextUtils.isEmpty(gameSessionService)) {
            Slog.w(TAG, "No gameSessionService specified");
            return null;
        }
        ComponentName componentName =
                new ComponentName(gameServiceServiceInfo.packageName, gameSessionService);

        try {
            mPackageManager.getServiceInfo(componentName, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException ex) {
            Slog.w(TAG, "GameSessionService does not exist: " + componentName);
            return null;
        }

        return componentName;
    }
}
