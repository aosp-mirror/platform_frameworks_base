/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteSessionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

abstract class MediaRoute2Provider {
    final ComponentName mComponentName;
    final String mUniqueId;

    Callback mCallback;
    private volatile MediaRoute2ProviderInfo mProviderInfo;
    private volatile List<RouteSessionInfo> mSessionInfos = Collections.emptyList();

    MediaRoute2Provider(@NonNull ComponentName componentName) {
        mComponentName = Objects.requireNonNull(componentName, "Component name must not be null.");
        mUniqueId = componentName.flattenToShortString();
    }

    public void setCallback(MediaRoute2ProviderProxy.Callback callback) {
        mCallback = callback;
    }

    public abstract void requestCreateSession(String packageName, String routeId,
            String controlCategory, long requestId);
    public abstract void releaseSession(int sessionId);

    public abstract void selectRoute(int sessionId, MediaRoute2Info route);
    public abstract void deselectRoute(int sessionId, MediaRoute2Info route);
    public abstract void transferRoute(int sessionId, MediaRoute2Info route);

    public abstract void sendControlRequest(MediaRoute2Info route, Intent request);
    public abstract void requestSetVolume(MediaRoute2Info route, int volume);
    public abstract void requestUpdateVolume(MediaRoute2Info route, int delta);

    @NonNull
    public String getUniqueId() {
        return mUniqueId;
    }

    @Nullable
    public MediaRoute2ProviderInfo getProviderInfo() {
        return mProviderInfo;
    }

    @NonNull
    public List<RouteSessionInfo> getSessionInfos() {
        return mSessionInfos;
    }

    void setAndNotifyProviderState(MediaRoute2ProviderInfo providerInfo,
            List<RouteSessionInfo> sessionInfos) {
        if (providerInfo == null) {
            mProviderInfo = null;
        } else {
            mProviderInfo = new MediaRoute2ProviderInfo.Builder(providerInfo)
                    .setUniqueId(mUniqueId)
                    .build();
        }
        List<RouteSessionInfo> sessionInfoWithProviderId = new ArrayList<RouteSessionInfo>();
        for (RouteSessionInfo sessionInfo : sessionInfos) {
            sessionInfoWithProviderId.add(
                    new RouteSessionInfo.Builder(sessionInfo)
                            .setProviderId(mUniqueId)
                            .build());
        }
        mSessionInfos = sessionInfoWithProviderId;

        if (mCallback != null) {
            mCallback.onProviderStateChanged(this);
        }
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public interface Callback {
        void onProviderStateChanged(@Nullable MediaRoute2Provider provider);
        void onSessionCreated(@NonNull MediaRoute2Provider provider,
                @Nullable RouteSessionInfo sessionInfo, long requestId);
    }
}
