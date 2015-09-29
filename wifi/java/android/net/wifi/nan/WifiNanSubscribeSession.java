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
 * A representation of a NAN subscribe session. Created when
 * {@link WifiNanManager#subscribe(SubscribeData, SubscribeSettings, WifiNanSessionListener, int)}
 * is executed. The object can be used to stop and re-start (re-configure) the
 * subscribe session.
 *
 * @hide PROPOSED_NAN_API
 */
public class WifiNanSubscribeSession extends WifiNanSession {
    /**
     * {@hide}
     */
    public WifiNanSubscribeSession(WifiNanManager manager, int sessionId) {
        super(manager, sessionId);
    }

    /**
     * Restart/re-configure the subscribe session. Note that the
     * {@link WifiNanSessionListener} is not replaced - the same listener used at
     * creation is still used.
     *
     * @param subscribeData The data ({@link SubscribeData}) to subscribe.
     * @param subscribeSettings The settings ({@link SubscribeSettings}) of the
     *            subscribe session.
     */
    public void subscribe(SubscribeData subscribeData, SubscribeSettings subscribeSettings) {
        mManager.subscribe(mSessionId, subscribeData, subscribeSettings);
    }
}
