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
package android.media.routeprovider;

import android.media.session.RoutePlaybackControls;
import android.media.session.RouteInterface;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

/**
 * Standard wrapper for using playback controls over a {@link RouteInterfaceHandler}.
 * This is the provider half of the interface. Sessions should use
 * {@link RoutePlaybackControls} to interact with this interface.
 * @hide
 */
public final class RoutePlaybackControlsHandler {
    private static final String TAG = "RoutePlaybackControls";

    private final RouteInterfaceHandler mIface;

    private RoutePlaybackControlsHandler(RouteInterfaceHandler iface) {
        mIface = iface;
    }

    /**
     * Add this interface to the specified route and return a handle for
     * communicating on the interface.
     *
     * @param connection The connection to register this interface on.
     * @return A handle for communicating on this interface.
     */
    public static RoutePlaybackControlsHandler addTo(RouteConnection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection may not be null");
        }
        RouteInterfaceHandler iface = connection
                .addRouteInterface(RoutePlaybackControls.NAME);

        return new RoutePlaybackControlsHandler(iface);
    }

    /**
     * Add a {@link Listener} to this interface. The listener will receive
     * commands on the caller's thread.
     *
     * @param listener The listener to send commands to.
     */
    public void addListener(Listener listener) {
        addListener(listener, null);
    }

    /**
     * Add a {@link Listener} to this interface. The listener will receive
     * updates on the handler's thread. If no handler is specified the caller's
     * thread will be used instead.
     *
     * @param listener The listener to send commands to.
     * @param handler The handler whose thread calls should be posted on. May be
     *            null.
     */
    public void addListener(Listener listener, Handler handler) {
        mIface.addListener(listener, handler);
    }

    /**
     * Remove a {@link Listener} from this interface.
     *
     * @param listener The Listener to remove.
     */
    public void removeListener(Listener listener) {
        mIface.removeListener(listener);
    }

    /**
     * Publish the current playback state to the system and any controllers.
     * Valid values are defined in {@link PlaybackState}. TODO create
     * RoutePlaybackState.
     *
     * @param state
     */
    public void sendPlaybackChangeEvent(int state) {
        Bundle extras = new Bundle();
        extras.putInt(RoutePlaybackControls.KEY_VALUE1, state);
        mIface.sendEvent(RoutePlaybackControls.EVENT_PLAYSTATE_CHANGE, extras);
    }

    /**
     * Command handler for the RoutePlaybackControls interface. You can add a
     * Listener to the interface using {@link #addListener}.
     */
    public static abstract class Listener extends RouteInterfaceHandler.CommandListener {

        @Override
        public final boolean onCommand(RouteInterfaceHandler iface, String method, Bundle extras,
                ResultReceiver cb) {
            if (RoutePlaybackControls.CMD_FAST_FORWARD.equals(method)) {
                boolean success = fastForward();
                // TODO specify type of error
                RouteInterfaceHandler.sendResult(cb, success
                        ? RouteInterface.RESULT_SUCCESS
                        : RouteInterface.RESULT_ERROR, null);
                return true;
            } else if (RoutePlaybackControls.CMD_GET_CURRENT_POSITION.equals(method)) {
                Bundle result = new Bundle();
                result.putLong(RoutePlaybackControls.KEY_VALUE1, getCurrentPosition());
                RouteInterfaceHandler.sendResult(cb, RouteInterface.RESULT_SUCCESS,
                        result);
                return true;
            } else if (RoutePlaybackControls.CMD_GET_CAPABILITIES.equals(method)) {
                Bundle result = new Bundle();
                result.putLong(RoutePlaybackControls.KEY_VALUE1, getCapabilities());
                RouteInterfaceHandler.sendResult(cb, RouteInterface.RESULT_SUCCESS,
                        result);
                return true;
            } else if (RoutePlaybackControls.CMD_PLAY_NOW.equals(method)) {
                playNow(extras.getString(RoutePlaybackControls.KEY_VALUE1, null), cb);
                return true;
            } else if (RoutePlaybackControls.CMD_RESUME.equals(method)) {
                boolean success = resume();
                RouteInterfaceHandler.sendResult(cb, success
                        ? RouteInterface.RESULT_SUCCESS
                        : RouteInterface.RESULT_ERROR, null);
                return true;
            } else if (RoutePlaybackControls.CMD_PAUSE.equals(method)) {
                boolean success = pause();
                RouteInterfaceHandler.sendResult(cb, success
                        ? RouteInterface.RESULT_SUCCESS
                        : RouteInterface.RESULT_ERROR, null);
                return true;
            } else {
                // The command wasn't recognized
            }
            return false;
        }

        /**
         * Override to handle fast forwarding.
         *
         * @return true if the request succeeded, false otherwise
         */
        public boolean fastForward() {
            Log.w(TAG, "fastForward is not supported.");
            return false;
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
         * Override to handle play now requests.
         *
         * @param content The uri of the item to play.
         * @param cb The callback to send the result to.
         */
        public void playNow(String content, ResultReceiver cb) {
            Log.w(TAG, "playNow is not supported");
            if (cb != null) {
                // We do this directly since we don't have a reference to the
                // iface
                cb.send(RouteInterface.RESULT_COMMAND_NOT_SUPPORTED, null);
            }
        }

        /**
         * Override to handle resume requests. Return true if the call was
         * handled, even if it was a no-op.
         *
         * @return true if the call was handled.
         */
        public boolean resume() {
            Log.w(TAG, "resume is not supported");
            return false;
        }

        /**
         * Override to handle pause requests. Return true if the call was
         * handled, even if it was a no-op.
         *
         * @return true if the call was handled.
         */
        public boolean pause() {
            Log.w(TAG, "pause is not supported");
            return false;
        }
    }
}
