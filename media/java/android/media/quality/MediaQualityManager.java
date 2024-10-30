/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.quality;

import android.annotation.FlaggedApi;
import android.annotation.SystemService;
import android.content.Context;
import android.media.tv.flags.Flags;

/**
 * Expose TV setting APIs for the application to use
 * @hide
 */
@FlaggedApi(Flags.FLAG_MEDIA_QUALITY_FW)
@SystemService(Context.MEDIA_QUALITY_SERVICE)
public class MediaQualityManager {
    // TODO: unhide the APIs for api review
    private static final String TAG = "MediaQualityManager";

    private final IMediaQualityManager mService;
    private final Context mContext;

    /**
     * @hide
     */
    public MediaQualityManager(Context context, IMediaQualityManager service) {
        mContext = context;
        mService = service;
    }
}
