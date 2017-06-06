/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Handler;
import java.net.InetSocketAddress;

/**
 * Commissioning Session.
 *
 * <p>This class enables a device to learn the credential needed to join a network using a technique
 * called "in-band commissioning".
 *
 * @hide
 */
//@SystemApi
public abstract class LowpanCommissioningSession {
    public LowpanCommissioningSession() {}

    /**
     * Callback base class for {@link LowpanCommissioningSession}
     *
     * @hide
     */
    //@SystemApi
    public class Callback {
        public void onReceiveFromCommissioner(@NonNull byte[] packet) {};

        public void onClosed() {};
    }

    /** TODO: doc */
    @NonNull
    public abstract LowpanBeaconInfo getBeaconInfo();

    /** TODO: doc */
    public abstract void sendToCommissioner(@NonNull byte[] packet);

    /** TODO: doc */
    public abstract void setCallback(@Nullable Callback cb, @Nullable Handler handler);

    /** TODO: doc */
    public abstract void close();

    /**
     * This method is largely for Nest Weave, as an alternative to {@link #sendToCommissioner()}
     * and @{link Callback#onReceiveFromCommissioner()}.
     *
     * <p>When used with the Network instance obtained from getNetwork(), the caller can use the
     * given InetSocketAddress to communicate with the commissioner using a UDP (or, under certain
     * circumstances, TCP) socket.
     */
    public abstract @Nullable InetSocketAddress getInetSocketAddress();

    public abstract @Nullable Network getNetwork();
}
