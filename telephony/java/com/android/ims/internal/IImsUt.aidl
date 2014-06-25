/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims.internal;

import android.os.Bundle;

import com.android.ims.internal.IImsUtListener;

/**
 * Provides the Ut interface interworking to get/set the supplementary service configuration.
 *
 * {@hide}
 */
interface IImsUt {
    /**
     * Closes the object. This object is not usable after being closed.
     */
    void close();

    /**
     * Retrieves the configuration of the call barring.
     */
    int queryCallBarring(int cbType);

    /**
     * Retrieves the configuration of the call forward.
     */
    int queryCallForward(int condition, String number);

    /**
     * Retrieves the configuration of the call waiting.
     */
    int queryCallWaiting();

    /**
     * Updates or retrieves the supplementary service configuration.
     */
    int transact(in Bundle ssInfo);

    /**
     * Updates the configuration of the call barring.
     */
    int updateCallBarring(int cbType, boolean enable);

    /**
     * Updates the configuration of the call forward.
     */
    int updateCallForward(int action, int condition, String number, int timeSeconds);

    /**
     * Updates the configuration of the call waiting.
     */
    int updateCallWaiting(boolean enable);

    /**
     * Sets the listener.
     */
    void setListener(in IImsUtListener listener);
}
