/*
 * Copyright (c) 2016 The Android Open Source Project
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

import com.android.ims.internal.IImsExternalCallStateListener;

/**
 * Provides the ImsMultiEndpoint interface
 *
 * {@hide}
 */
interface IImsMultiEndpoint {
    /**
     * Sets the listener.
     */
    void setListener(in IImsExternalCallStateListener listener);


    /**
     * Query api to get the latest Dialog Event Package information
     * Should be invoked only after setListener is done
     */
    void requestImsExternalCallStateInfo();
}
