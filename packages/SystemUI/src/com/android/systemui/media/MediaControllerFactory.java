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

import android.annotation.NonNull;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;

import javax.inject.Inject;

/**
 * Testable wrapper around {@link MediaController} constructor.
 */
public class MediaControllerFactory {

    private final Context mContext;

    @Inject
    public MediaControllerFactory(Context context) {
        mContext = context;
    }

    /**
     * Creates a new MediaController from a session's token.
     *
     * @param token The token for the session. This value must never be null.
     */
    public MediaController create(@NonNull MediaSession.Token token) {
        return new MediaController(mContext, token);
    }
}
