/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.telecom;

import android.os.Handler;


/**
 * This class is used to associate a generic callback of type T with a handler to which commands and
 * status updates will be delivered to.
 *
 * @hide
 */
class CallbackRecord<T> {
    private final T mCallback;
    private final Handler mHandler;

    public CallbackRecord(T callback, Handler handler) {
        mCallback = callback;
        mHandler = handler;
    }

    public T getCallback() {
        return mCallback;
    }

    public Handler getHandler() {
        return mHandler;
    }
}
