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

package android.bluetooth.le;

/**
 * Callback of Bluetooth LE advertising, which is used to deliver advertising operation status.
 */
public abstract class AdvertiseCallback {

    /**
     * The operation is success.
     *
     * @hide
     */
    public static final int SUCCESS = 0;
    /**
     * Fails to start advertising as the advertisement data contains services that are not added to
     * the local bluetooth GATT server.
     */
    public static final int ADVERTISE_FAILED_SERVICE_UNKNOWN = 1;
    /**
     * Fails to start advertising as system runs out of quota for advertisers.
     */
    public static final int ADVERTISE_FAILED_TOO_MANY_ADVERTISERS = 2;

    /**
     * Fails to start advertising as the advertising is already started.
     */
    public static final int ADVERTISE_FAILED_ALREADY_STARTED = 3;
    /**
     * Fails to stop advertising as the advertising is not started.
     */
    public static final int ADVERTISE_FAILED_NOT_STARTED = 4;

    /**
     * Operation fails due to bluetooth controller failure.
     */
    public static final int ADVERTISE_FAILED_CONTROLLER_FAILURE = 5;

    /**
     * Callback when advertising operation succeeds.
     *
     * @param settingsInEffect The actual settings used for advertising, which may be different from
     *            what the app asks.
     */
    public abstract void onSuccess(AdvertiseSettings settingsInEffect);

    /**
     * Callback when advertising operation fails.
     *
     * @param errorCode Error code for failures.
     */
    public abstract void onFailure(int errorCode);
}
