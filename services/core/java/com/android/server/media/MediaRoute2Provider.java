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
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

abstract class MediaRoute2Provider {
    final ComponentName mComponentName;
    final String mUniqueId;
    final Object mLock = new Object();

    Callback mCallback;
    boolean mIsSystemRouteProvider;
    private volatile MediaRoute2ProviderInfo mProviderInfo;

    @GuardedBy("mLock")
    final List<RoutingSessionInfo> mSessionInfos = new ArrayList<>();

    MediaRoute2Provider(@NonNull ComponentName componentName) {
        mComponentName = Objects.requireNonNull(componentName, "Component name must not be null.");
        mUniqueId = componentName.flattenToShortString();
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public abstract void requestCreateSession(
            long requestId,
            String packageName,
            String routeId,
            @Nullable Bundle sessionHints,
            @RoutingSessionInfo.TransferReason int transferReason,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName);

    public abstract void releaseSession(long requestId, String sessionId);

    public abstract void updateDiscoveryPreference(
            Set<String> activelyScanningPackages, RouteDiscoveryPreference discoveryPreference);

    public abstract void selectRoute(long requestId, String sessionId, String routeId);
    public abstract void deselectRoute(long requestId, String sessionId, String routeId);

    public abstract void transferToRoute(
            long requestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName,
            String sessionId,
            String routeId,
            @RoutingSessionInfo.TransferReason int transferReason);

    public abstract void setRouteVolume(long requestId, String routeId, int volume);
    public abstract void setSessionVolume(long requestId, String sessionId, int volume);
    public abstract void prepareReleaseSession(@NonNull String sessionId);

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
        synchronized (mLock) {
            return new ArrayList<>(mSessionInfos);
        }
    }

    void setProviderState(MediaRoute2ProviderInfo providerInfo) {
        if (providerInfo == null) {
            mProviderInfo = null;
        } else {
            mProviderInfo = new MediaRoute2ProviderInfo.Builder(providerInfo)
                    .setUniqueId(mComponentName.getPackageName(), mUniqueId)
                    .setSystemRouteProvider(mIsSystemRouteProvider)
                    .build();
        }
    }

    void notifyProviderState() {
        if (mCallback != null) {
            mCallback.onProviderStateChanged(this);
        }
    }

    void setAndNotifyProviderState(MediaRoute2ProviderInfo providerInfo) {
        setProviderState(providerInfo);
        notifyProviderState();
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + getDebugString());
        prefix += "  ";

        if (mProviderInfo == null) {
            pw.println(prefix + "<provider info not received, yet>");
        } else if (mProviderInfo.getRoutes().isEmpty()) {
            pw.println(prefix + "<provider info has no routes>");
        } else {
            for (MediaRoute2Info route : mProviderInfo.getRoutes()) {
                pw.printf("%s%s | %s\n", prefix, route.getId(), route.getName());
            }
        }

        pw.println(prefix + "Active routing sessions:");
        synchronized (mLock) {
            if (mSessionInfos.isEmpty()) {
                pw.println(prefix + "  <no active routing sessions>");
            } else {
                for (RoutingSessionInfo routingSessionInfo : mSessionInfos) {
                    routingSessionInfo.dump(pw, prefix + "  ");
                }
            }
        }
    }

    @Override
    public String toString() {
        return getDebugString();
    }

    /** Returns a human-readable string describing the instance, for debugging purposes. */
    protected abstract String getDebugString();

    public interface Callback {
        void onProviderStateChanged(@Nullable MediaRoute2Provider provider);
        void onSessionCreated(@NonNull MediaRoute2Provider provider,
                long requestId, @Nullable RoutingSessionInfo sessionInfo);
        void onSessionUpdated(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
        void onSessionReleased(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo);
        void onRequestFailed(@NonNull MediaRoute2Provider provider, long requestId, int reason);
    }

    /**
     * Holds session creation or transfer initiation information for a transfer in flight.
     *
     * <p>The initiator app is typically also the {@link RoutingSessionInfo#getClientPackageName()
     * client app}, with the exception of the {@link MediaRouter2#getSystemController() system
     * routing session} which is exceptional in that it's shared among all apps.
     *
     * <p>For the system routing session, the initiator app is the one that programmatically
     * triggered the transfer (for example, via {@link MediaRouter2#transferTo}), or the target app
     * of the proxy router that did the transfer.
     *
     * @see MediaRouter2.RoutingController#wasTransferInitiatedBySelf()
     * @see RoutingSessionInfo#getTransferInitiatorPackageName()
     * @see RoutingSessionInfo#getTransferInitiatorUserHandle()
     */
    protected static class SessionCreationOrTransferRequest {

        /**
         * The id of the request, or {@link
         * android.media.MediaRoute2ProviderService#REQUEST_ID_NONE} if unknown.
         */
        public final long mRequestId;

        /** The {@link MediaRoute2Info#getId() id} of the target route. */
        @NonNull public final String mTargetRouteId;

        @RoutingSessionInfo.TransferReason public final int mTransferReason;

        /** The {@link android.os.UserHandle} on which the initiator app is running. */
        @NonNull public final UserHandle mTransferInitiatorUserHandle;

        @NonNull public final String mTransferInitiatorPackageName;

        SessionCreationOrTransferRequest(
                long requestId,
                @NonNull String routeId,
                @RoutingSessionInfo.TransferReason int transferReason,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName) {
            mRequestId = requestId;
            mTargetRouteId = routeId;
            mTransferReason = transferReason;
            mTransferInitiatorUserHandle = transferInitiatorUserHandle;
            mTransferInitiatorPackageName = transferInitiatorPackageName;
        }

        public boolean isTargetRoute(@Nullable MediaRoute2Info route2Info) {
            return route2Info != null && mTargetRouteId.equals(route2Info.getId());
        }

        public boolean isTargetRouteIdInList(@NonNull List<String> routesList) {
            return routesList.stream().anyMatch(mTargetRouteId::equals);
        }
    }
}
