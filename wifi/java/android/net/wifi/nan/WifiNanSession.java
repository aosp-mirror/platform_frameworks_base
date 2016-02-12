/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.nan;

import android.util.Log;

/**
 * A representation of a single publish or subscribe NAN session. This object
 * will not be created directly - only its child classes are available:
 * {@link WifiNanPublishSession} and {@link WifiNanSubscribeSession}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSession {
    private static final String TAG = "WifiNanSession";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    /**
     * {@hide}
     */
    protected WifiNanManager mManager;

    /**
     * {@hide}
     */
    protected int mSessionId;

    /**
     * {@hide}
     */
    private boolean mDestroyed;

    /**
     * {@hide}
     */
    public WifiNanSession(WifiNanManager manager, int sessionId) {
        if (VDBG) Log.v(TAG, "New client created: manager=" + manager + ", sessionId=" + sessionId);

        mManager = manager;
        mSessionId = sessionId;
        mDestroyed = false;
    }

    /**
     * Terminate the current publish or subscribe session - i.e. stop
     * transmitting packet on-air (for an active session) or listening for
     * matches (for a passive session). Note that the session may still receive
     * incoming messages and may be re-configured/re-started at a later time.
     */
    public void stop() {
        mManager.stopSession(mSessionId);
    }

    /**
     * Destroy the current publish or subscribe session. Performs a
     * {@link WifiNanSession#stop()} function but in addition destroys the session -
     * it will not be able to receive any messages or to be restarted at a later
     * time.
     */
    public void destroy() {
        mManager.destroySession(mSessionId);
        mDestroyed = true;
    }

    /**
     * {@hide}
     */
    @Override
    protected void finalize() throws Throwable {
        if (!mDestroyed) {
            Log.w(TAG, "WifiNanSession mSessionId=" + mSessionId
                            + " was not explicitly destroyed. The session may use resources until "
                            + "destroyed so step should be done explicitly");
        }
        destroy();
    }

    /**
     * Sends a message to the specified destination. Message transmission is
     * part of the current discovery session - i.e. executed subsequent to a
     * publish/subscribe
     * {@link WifiNanSessionListener#onMatch(int, byte[], int, byte[], int)}
     * event.
     *
     * @param peerId The peer's ID for the message. Must be a result of an
     *            {@link WifiNanSessionListener#onMatch(int, byte[], int, byte[], int)}
     *            event.
     * @param message The message to be transmitted.
     * @param messageLength The number of bytes from the {@code message} to be
     *            transmitted.
     * @param messageId An arbitrary integer used by the caller to identify the
     *            message. The same integer ID will be returned in the callbacks
     *            indicated message send success or failure.
     */
    public void sendMessage(int peerId, byte[] message, int messageLength, int messageId) {
        mManager.sendMessage(mSessionId, peerId, message, messageLength, messageId);
    }
}
