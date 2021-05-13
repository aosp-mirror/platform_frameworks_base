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

package com.android.server.connectivity;

import android.annotation.NonNull;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A ref that autodestructs at the first usage of it.
 * @param <T> The type of the held object
 * @hide
 */
public class AutodestructReference<T> {
    private final AtomicReference<T> mHeld;
    public AutodestructReference(@NonNull T obj) {
        if (null == obj) throw new NullPointerException("Autodestruct reference to null");
        mHeld = new AtomicReference<>(obj);
    }

    /** Get the ref and destruct it. NPE if already destructed. */
    @NonNull
    public T getAndDestroy() {
        final T obj = mHeld.getAndSet(null);
        if (null == obj) throw new NullPointerException("Already autodestructed");
        return obj;
    }
}
