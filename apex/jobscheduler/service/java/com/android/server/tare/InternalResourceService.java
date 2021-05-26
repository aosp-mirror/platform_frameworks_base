/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tare;

import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;

/**
 * Responsible for handling app's ARC count based on events, ensuring ARCs are credited when
 * appropriate, and reclaiming ARCs at the right times. The IRS deals with the high level details
 * while the {@link Teller} deals with the nitty-gritty details.
 *
 * Note on locking: Any function with the suffix 'Locked' needs to lock on {@link #mLock}.
 *
 * @hide
 */
public class InternalResourceService extends SystemService {
    public static final String TAG = "TARE-IRS";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /** Global local for all resource economy state. */
    private final Object mLock = new Object();

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public InternalResourceService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {

    }

    @NonNull
    Object getLock() {
        return mLock;
    }
}
