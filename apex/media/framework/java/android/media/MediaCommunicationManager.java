/*
 * Copyright 2020 The Android Open Source Project
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
package android.media;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;

import com.android.modules.utils.build.SdkLevel;

/**
 * Provides support for interacting with {@link android.media.MediaSession2 MediaSession2s}
 * that applications have published to express their ongoing media playback state.
 */
// TODO: Add notifySession2Created() and sendMessage().
@SystemService(Context.MEDIA_COMMUNICATION_SERVICE)
public class MediaCommunicationManager {
    private static final String TAG = "MediaCommunicationManager";

    /**
     * The manager version used from beginning.
     */
    private static final int VERSION_1 = 1;

    /**
     * Current manager version.
     */
    private static final int CURRENT_VERSION = VERSION_1;

    private final Context mContext;
    private final IMediaCommunicationService mService;

    /**
     * @hide
     */
    public MediaCommunicationManager(@NonNull Context context) {
        if (!SdkLevel.isAtLeastS()) {
            throw new UnsupportedOperationException("Android version must be S or greater.");
        }
        mContext = context;
        mService = IMediaCommunicationService.Stub.asInterface(
                MediaFrameworkInitializer.getMediaServiceManager()
                        .getMediaCommunicationServiceRegisterer()
                        .get());
    }

    /**
     * Gets the version of this {@link MediaCommunicationManager}.
     */
    public @IntRange(from = 1) int getVersion() {
        return CURRENT_VERSION;
    }
}
