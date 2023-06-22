/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media;

import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;

import javax.inject.Inject;

/**
 * Testable wrapper around {@link ResumeMediaBrowser} constructor
 */
public class ResumeMediaBrowserFactory {
    private final Context mContext;
    private final MediaBrowserFactory mBrowserFactory;

    @Inject
    public ResumeMediaBrowserFactory(Context context, MediaBrowserFactory browserFactory) {
        mContext = context;
        mBrowserFactory = browserFactory;
    }

    /**
     * Creates a new ResumeMediaBrowser.
     *
     * @param callback will be called on connection or error, and addTrack when media item found
     * @param componentName component to browse
     * @param userId ID of the current user
     * @return
     */
    public ResumeMediaBrowser create(ResumeMediaBrowser.Callback callback,
            ComponentName componentName, @UserIdInt int userId) {
        return new ResumeMediaBrowser(mContext, callback, componentName, mBrowserFactory,
                userId);
    }
}
