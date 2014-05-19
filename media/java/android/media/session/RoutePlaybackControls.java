/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;

/**
 * A standard media control interface for Routes that support queueing and
 * transport controls. Routes may support multiple interfaces for MediaSessions
 * to interact with.
 * @hide
 */
public final class RoutePlaybackControls {
    private static final String TAG = "RoutePlaybackControls";
    public static final String NAME = "android.media.session.RoutePlaybackControls";

    /** @hide */
    public static final String KEY_VALUE1 = "value1";

    /** @hide */
    public static final String CMD_FAST_FORWARD = "fastForward";
    /** @hide */
    public static final String CMD_GET_CURRENT_POSITION = "getCurrentPosition";
    /** @hide */
    public static final String CMD_GET_CAPABILITIES = "getCapabilities";
    /** @hide */
    public static final String CMD_PLAY_NOW = "playNow";
    /** @hide */
    public static final String CMD_RESUME = "resume";
    /** @hide */
    public static final String CMD_PAUSE = "pause";

    /** @hide */
    public static final String EVENT_PLAYSTATE_CHANGE = "playstateChange";
    /** @hide */
    public static final String EVENT_METADATA_CHANGE = "metadataChange";

    private final RouteInterface mIface;

    private RoutePlaybackControls(RouteInterface iface) {
        mIface = iface;
    }

    /**
     * Get a new MediaRoutePlaybackControls instance for sending commands using
     * this interface. If the provided route doesn't support this interface null
     * will be returned.
     *
     * @param route The route to send commands to.
     * @return A MediaRoutePlaybackControls instance or null if not supported.
     */
    public static RoutePlaybackControls from(Route route) {
        RouteInterface iface = route.getInterface(NAME);
        if (iface != null) {
            return new RoutePlaybackControls(iface);
        }
        return null;
    }

    /**
     * Send a resume command to the route.
     */
    public void resume() {
        mIface.sendCommand(CMD_RESUME, null, null);
    }

    /**
     * Send a pause command to the route.
     */
    public void pause() {
        mIface.sendCommand(CMD_PAUSE, null, null);
    }

    /**
     * Send a fast forward command.
     */
    public void fastForward() {
        Bundle b = new Bundle();
        mIface.sendCommand(CMD_FAST_FORWARD, b, null);
    }

    /**
     * Retrieves the current playback position.
     *
     * @param cb The callback to receive the result on.
     */
    public void getCurrentPosition(ResultReceiver cb) {
        mIface.sendCommand(CMD_GET_CURRENT_POSITION, null, cb);
    }

    public void getCapabilities(ResultReceiver cb) {
        mIface.sendCommand(CMD_GET_CAPABILITIES, null, cb);
    }

    public void addListener(Listener listener) {
        mIface.addListener(listener);
    }

    public void addListener(Listener listener, Handler handler) {
        mIface.addListener(listener, handler);
    }

    public void removeListener(Listener listener) {
        mIface.removeListener(listener);
    }

    public void playNow(String content) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_VALUE1, content);
        mIface.sendCommand(CMD_PLAY_NOW, bundle, null);
    }

    /**
     * Register this event listener using {@link #addListener} to receive
     * RoutePlaybackControl events from a session.
     */
    public static abstract class Listener extends RouteInterface.EventListener {
        @Override
        public final void onEvent(String event, Bundle args) {
            if (EVENT_PLAYSTATE_CHANGE.equals(event)) {
                onPlaybackStateChange(args.getInt(KEY_VALUE1, 0));
            } else if (EVENT_METADATA_CHANGE.equals(event)) {
                onMetadataUpdate((MediaMetadata) args.getParcelable(KEY_VALUE1));
            }
        }

        /**
         * Override to handle updates to the playback state. Valid values are in
         * {@link TransportPerformer}. TODO put playstate values somewhere more
         * generic.
         *
         * @param state
         */
        public void onPlaybackStateChange(int state) {
        }

        /**
         * Override to handle metadata changes for this session's media. The
         * default supported fields are those in {@link MediaMetadata}.
         *
         * @param metadata
         */
        public void onMetadataUpdate(MediaMetadata metadata) {
        }
    }

}
