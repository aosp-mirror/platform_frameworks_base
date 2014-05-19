/* Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

import android.media.MediaMetadata;
import android.media.session.ISessionController;
import android.media.session.RouteOptions;
import android.media.session.RouteCommand;
import android.media.session.RouteInfo;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Interface to a MediaSession in the system.
 * @hide
 */
interface ISession {
    void sendEvent(String event, in Bundle data);
    ISessionController getController();
    void setFlags(int flags);
    void setActive(boolean active);
    void destroy();

    // These commands are for setting up and communicating with routes
    // Returns true if the route was set for this session
    boolean setRoute(in RouteInfo route);
    void setRouteOptions(in List<RouteOptions> options);
    void connectToRoute(in RouteInfo route, in RouteOptions options);
    void disconnectFromRoute(in RouteInfo route);
    void sendRouteCommand(in RouteCommand event, in ResultReceiver cb);

    // These commands are for the TransportPerformer
    void setMetadata(in MediaMetadata metadata);
    void setPlaybackState(in PlaybackState state);
    void setRatingType(int type);
}