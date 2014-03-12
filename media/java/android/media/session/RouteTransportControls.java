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

import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

/**
 * A standard media control interface for Routes. Routes can support multiple
 * interfaces for MediaSessions to interact with. TODO rewrite for routes
 */
public final class RouteTransportControls {
    private static final String TAG = "RouteTransportControls";
    public static final String NAME = "android.media.session.RouteTransportControls";

    private static final String KEY_VALUE1 = "value1";

    private static final String METHOD_FAST_FORWARD = "fastForward";
    private static final String METHOD_GET_CURRENT_POSITION = "getCurrentPosition";
    private static final String METHOD_GET_CAPABILITIES = "getCapabilities";

    private static final String EVENT_PLAYSTATE_CHANGE = "playstateChange";
    private static final String EVENT_METADATA_CHANGE = "metadataChange";

    private final MediaController mController;
    private final RouteInterface mIface;

    private RouteTransportControls(RouteInterface iface, MediaController controller) {
        mIface = iface;
        mController = controller;
    }

    public static RouteTransportControls from(MediaController controller) {
//        MediaInterface iface = controller.getInterface(NAME);
//        if (iface != null) {
//            return new RouteTransportControls(iface, controller);
//        }
        return null;
    }

    /**
     * Send a play command to the route. TODO rename resume() and use messaging
     * protocol, not KeyEvent
     */
    public void play() {
        // TODO
    }

    /**
     * Send a pause command to the session.
     */
    public void pause() {
        // TODO
    }

    /**
     * Set the rate at which to fastforward. Valid values are in the range [0,1]
     * with actual rates depending on the implementation.
     *
     * @param rate
     */
    public void fastForward(float rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("Rate must be between 0 and 1 inclusive");
        }
        Bundle b = new Bundle();
        b.putFloat(KEY_VALUE1, rate);
        mIface.sendCommand(METHOD_FAST_FORWARD, b, null);
    }

    public void getCurrentPosition(ResultReceiver cb) {
        mIface.sendCommand(METHOD_GET_CURRENT_POSITION, null, cb);
    }

    public void getCapabilities(ResultReceiver cb) {
        mIface.sendCommand(METHOD_GET_CAPABILITIES, null, cb);
    }

    public void addListener(Listener listener) {
        mIface.addListener(listener.mListener);
    }

    public void addListener(Listener listener, Handler handler) {
        mIface.addListener(listener.mListener, handler);
    }

    public void removeListener(Listener listener) {
        mIface.removeListener(listener.mListener);
    }

    public static abstract class Stub extends RouteInterface.Stub {
        private final MediaSession mSession;

        public Stub(MediaSession session) {
            mSession = session;
        }

        @Override
        public String getName() {
            return NAME;
        }

        @Override
        public void onCommand(String method, Bundle extras, ResultReceiver cb) {
            if (TextUtils.isEmpty(method)) {
                return;
            }
            Bundle result;
            if (METHOD_FAST_FORWARD.equals(method)) {
                fastForward(extras.getFloat(KEY_VALUE1, -1));
            } else if (METHOD_GET_CURRENT_POSITION.equals(method)) {
                if (cb != null) {
                    result = new Bundle();
                    result.putLong(KEY_VALUE1, getCurrentPosition());
                    cb.send(0, result);
                }
            } else if (METHOD_GET_CAPABILITIES.equals(method)) {
                if (cb != null) {
                    result = new Bundle();
                    result.putLong(KEY_VALUE1, getCapabilities());
                    cb.send(0, result);
                }
            }
        }

        /**
         * Override to handle fast forwarding. Valid values are [0,1] inclusive.
         * The interpretation of the rate is up to the implementation. If no
         * rate was included with the command a rate of -1 will be used by
         * default.
         *
         * @param rate The rate at which to fast forward as a multiplier
         */
        public void fastForward(float rate) {
            Log.w(TAG, "fastForward is not supported.");
        }

        /**
         * Override to handle getting the current position of playback in
         * millis.
         *
         * @return The current position in millis or -1
         */
        public long getCurrentPosition() {
            Log.w(TAG, "getCurrentPosition is not supported");
            return -1;
        }

        /**
         * Override to handle getting the set of capabilities currently
         * available.
         *
         * @return A bit mask of the supported capabilities
         */
        public long getCapabilities() {
            Log.w(TAG, "getCapabilities is not supported");
            return 0;
        }

        /**
         * Publish the current playback state to the system and any controllers.
         * Valid values are defined in {@link RemoteControlClient}. TODO move
         * play states somewhere else.
         *
         * @param state
         */
        public final void updatePlaybackState(int state) {
            Bundle extras = new Bundle();
            extras.putInt(KEY_VALUE1, state);
            sendEvent(mSession, EVENT_PLAYSTATE_CHANGE, extras);
        }
    }

    /**
     * Register this event listener using TODO to receive
     * TransportControlInterface events from a session.
     *
     * @see RouteInterface.EventListener
     */
    public static abstract class Listener {

        private RouteInterface.EventListener mListener = new RouteInterface.EventListener() {
            @Override
            public final void onEvent(String event, Bundle args) {
                if (EVENT_PLAYSTATE_CHANGE.equals(event)) {
                    onPlaybackStateChange(args.getInt(KEY_VALUE1));
                } else if (EVENT_METADATA_CHANGE.equals(event)) {
                    onMetadataUpdate(args);
                }
            }
        };

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
        public void onMetadataUpdate(Bundle metadata) {
        }
    }

}
