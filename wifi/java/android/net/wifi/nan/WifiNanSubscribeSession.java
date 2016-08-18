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

import android.annotation.NonNull;
import android.util.Log;

/**
 * A class representing a NAN subscribe session. Created when
 * {@link WifiNanManager#subscribe(SubscribeConfig, WifiNanSessionCallback)} is called and a
 * discovery session is created and returned in
 * {@link WifiNanSessionCallback#onSubscribeStarted(WifiNanSubscribeSession)}. See baseline
 * functionality of all discovery sessions in {@link WifiNanSession}. This object allows updating
 * an existing/running subscribe discovery session using {@link #updateSubscribe(SubscribeConfig)}.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSubscribeSession extends WifiNanSession {
    private static final String TAG = "WifiNanSubscribeSession";

    /**
     * {@hide}
     */
    public WifiNanSubscribeSession(WifiNanManager manager, int sessionId) {
        super(manager, sessionId);
    }

    /**
     * Re-configure the currently active subscribe session. The
     * {@link WifiNanSessionCallback} is not replaced - the same listener used
     * at creation is still used. The results of the configuration are returned using
     * {@link WifiNanSessionCallback}:
     * <ul>
     *     <li>{@link WifiNanSessionCallback#onSessionConfigSuccess()}: configuration update
     *     succeeded.
     *     <li>{@link WifiNanSessionCallback#onSessionConfigFail(int)}: configuration update
     *     failed. The subscribe discovery session is still running using its previous
     *     configuration (i.e. update failure does not terminate the session).
     * </ul>
     *
     * @param subscribeConfig The new discovery subscribe session configuration
     *                        ({@link SubscribeConfig}).
     */
    public void updateSubscribe(@NonNull SubscribeConfig subscribeConfig) {
        if (mTerminated) {
            Log.w(TAG, "updateSubscribe: called on terminated session");
            return;
        } else {
            WifiNanManager mgr = mMgr.get();
            if (mgr == null) {
                Log.w(TAG, "updateSubscribe: called post GC on WifiNanManager");
                return;
            }

            mgr.updateSubscribe(mSessionId, subscribeConfig);
        }
    }
}
