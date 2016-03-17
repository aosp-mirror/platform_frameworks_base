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

/**
 * A representation of a NAN publish session. Created when
 * {@link WifiNanManager#publish(PublishConfig, WifiNanSessionCallback)} is
 * executed. The object can be used to stop and re-start (re-configure) the
 * publish session.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanPublishSession extends WifiNanSession {
    /**
     * {@hide}
     */
    public WifiNanPublishSession(WifiNanManager manager, int sessionId,
            WifiNanSessionCallback callback) {
        super(manager, sessionId, callback);
    }

    /**
     * Re-configure the publish session. Note that the
     * {@link WifiNanSessionCallback} is not replaced - the same listener used
     * at creation is still used.
     *
     * @param publishConfig The configuration ({@link PublishConfig}) of the
     *            publish session.
     */
    public void updatePublish(PublishConfig publishConfig) {
        if (mTerminated) {
            mCallback.onSessionConfigFail(WifiNanSessionCallback.FAIL_REASON_SESSION_TERMINATED);
            return;
        } else {
            mManager.updatePublish(mSessionId, publishConfig);
        }
    }
}
