/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.util.service;

/**
 * The {@link Observer} interface specifies an entity which listeners
 * can be informed of changes to the source, which will require updating. Note that this deals
 * with changes to the source itself, not content which will be updated through the interface.
 */
public interface Observer {
    /**
     * Callback for receiving updates from the {@link Observer}.
     */
    interface Callback {
        /**
         * Invoked when the source has changed.
         */
        void onSourceChanged();
    }

    /**
     * Adds a callback to receive future updates from the {@link Observer}.
     */
    void addCallback(Callback callback);

    /**
     * Removes a callback from receiving further updates.
     * @param callback
     */
    void removeCallback(Callback callback);
}
