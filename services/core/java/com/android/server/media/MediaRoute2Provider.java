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
import android.media.MediaRoute2ProviderInfo;
import android.media.RoutingSessionInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

abstract class MediaRoute2Provider {
    final ComponentName mComponentName;
    final String mUniqueId;

    Callback mCallback;
    private volatile MediaRoute2ProviderInfo mProviderInfo;
    private volatile List<RoutingSessionInfo> mSessionInfos = Collections.emptyList();

    MediaRoute2Provider(@NonNull ComponentName componentName) {
        mComponentName = Objects.requireNonNull(componentName, "Component name must not be null.");
        mUniqueId = componentName.flattenToShortString();
    }

    public void setCallback(MediaRoute2ProviderProxy.Callback callback) {
        mCallback = callback;
    }

    public abstract void requestCreateSession(String packageName, String routeId,
            String routeType, long requestId);
    public abstract void releaseSession(String sessionId);

    public abstract void selectRoute(String sessionId, String routeId);
    public abstract void deselectRoute(String sessionId, String routeId);
    public abstract void transferToRoute(String sessionId, String routeId);

    public abstract void sendControlRequest(String routeId, Intent request);
    public abstract void requestSetVolume(String routeId, int volume);
    public abstract void requestUpdateVolume(String routeId, int delta);

    @NonNull
    public String getUniqueId() {
        return mUniqueId;
    }

    @Nullable
    public MediaRoute2ProviderInfo getProviderInfo() {
        return mProviderInfo;
    }

    @NonNull
    public List<RoutingSessionInfo> getSessionInfos() {
        return mSessionInfos;
    }

    void setProviderState(MediaRoute2ProviderInfo providerInfo,
            List<RoutingSessionInfo> sessionInfos) {
        if (providerInfo == null) {
            mProviderInfo = null;
        } else {
            mProviderInfo = new MediaRoute2ProviderInfo.Builder(providerInfo)
                    .setUniqueId(mUniqueId)
                    .build();
        }
        List<RoutingSessionInfo> sessionInfoWithProviderId = new ArrayList<RoutingSessionInfo>();
        for (RoutingSessionInfo sessionInfo : sessionInfos) {
            sessionInfoWithProviderId.add(
                    new RoutingSessionInfo.Builder(sessionInfo)
                            .setProviderId(mUniqueId)
                            .build());
        }
        mSessionInfos = sessionInfoWithProviderId;
    }

    void notifyProviderState() {
        if (mCallback != null) {
            mCallback.onProviderStateChanged(this);
        }
    }

    void setAndNotifyProviderState(MediaRoute2ProviderInfo providerInfo,
            List<RoutingSessionInfo> sessionInfos) {
        setProviderState(providerInfo, sessionInfos);
        notifyProviderState();
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public interface Callback {
        void onProviderStateChanged(@Nullable MediaRoute2Provider provider);
        void onSessionCreated(@NonNull MediaRoute2Provider provider,
                @Nullable RoutingSessionInfo sessionInfo, long requestId);
        // TODO: Remove this when MediaRouter2ServiceImpl notifies clients of session changes.
        void onSessionInfoChanged(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
        // TODO: Call this when service actually notifies of session release.
        void onSessionReleased(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
    }
}
