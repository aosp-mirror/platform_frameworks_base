/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.media;

import android.content.Context;
import android.media.session.MediaSessionProviderService;
import android.os.PowerManager;
import android.util.Log;

/**
 * System implementation of MediaSessionProviderService
 */
public class AmlMediaSessionProviderService extends MediaSessionProviderService {
    private static final String TAG = "AmlMediaSessionProviderS";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private Context mContext;

    public AmlMediaSessionProviderService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }
}
