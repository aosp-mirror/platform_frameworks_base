/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.telephony.util;

import android.os.IInterface;
import android.os.RemoteCallbackList;

import java.util.function.Consumer;

/**
 * Extension of RemoteCallbackList
 * @param <E> defines the type of registered callbacks
 */
public class RemoteCallbackListExt<E extends IInterface> extends RemoteCallbackList<E> {
    /**
     * Performs {@code action} on each callback, calling
     * {@link RemoteCallbackListExt#beginBroadcast()}
     * /{@link RemoteCallbackListExt#finishBroadcast()} before/after looping
     * @param action to be performed on each callback
     *
     */
    public void broadcastAction(Consumer<E> action) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                action.accept(getBroadcastItem(i));
            }
        } finally {
            finishBroadcast();
        }
    }
}
