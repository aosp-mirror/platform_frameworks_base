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
package com.android.settingslib.testutils.shadow;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RoutingSessionInfo;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.util.List;

@Implements(MediaRouter2Manager.class)
public class ShadowRouter2Manager {

    private List<MediaRoute2Info> mAvailableRoutes;
    private List<MediaRoute2Info> mAllRoutes;
    private List<MediaRoute2Info> mDeselectableRoutes;
    private List<RoutingSessionInfo> mRemoteSessions;
    private List<RoutingSessionInfo> mRoutingSessions;
    private RoutingSessionInfo mSystemRoutingSession;

    @Implementation
    protected List<MediaRoute2Info> getAvailableRoutes(String packageName) {
        return mAvailableRoutes;
    }

    public void setAvailableRoutes(List<MediaRoute2Info> infos) {
        mAvailableRoutes = infos;
    }

    @Implementation
    protected List<MediaRoute2Info> getAllRoutes() {
        return mAllRoutes;
    }

    public void setAllRoutes(List<MediaRoute2Info> infos) {
        mAllRoutes = infos;
    }

    @Implementation
    protected List<RoutingSessionInfo> getRemoteSessions() {
        return mRemoteSessions;
    }

    public void setRemoteSessions(List<RoutingSessionInfo> infos) {
        mRemoteSessions = infos;
    }

    @Implementation
    protected List<RoutingSessionInfo> getRoutingSessions(String packageName) {
        return mRoutingSessions;
    }

    public void setRoutingSessions(List<RoutingSessionInfo> infos) {
        mRoutingSessions = infos;
    }

    @Implementation
    public RoutingSessionInfo getSystemRoutingSession(@Nullable String packageName) {
        return mSystemRoutingSession;
    }

    public void setSystemRoutingSession(RoutingSessionInfo sessionInfo) {
        mSystemRoutingSession = sessionInfo;
    }

    @Implementation
    public List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo sessionInfo) {
        return mDeselectableRoutes;
    }

    public void setDeselectableRoutes(List<MediaRoute2Info> routes) {
        mDeselectableRoutes = routes;
    }

    public static ShadowRouter2Manager getShadow() {
        return (ShadowRouter2Manager) Shadow.extract(
                MediaRouter2Manager.getInstance(RuntimeEnvironment.application));
    }

    @Implementation
    protected List<MediaRoute2Info> getTransferableRoutes(String packageName) {
        return mAvailableRoutes;
    }

    public void setTransferableRoutes(List<MediaRoute2Info> infos) {
        mAvailableRoutes = infos;
    }
}
