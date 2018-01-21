/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.media;

import android.content.Context;
import android.media.IMediaSession2;
import android.media.MediaController2;
import android.media.SessionToken;
import android.media.MediaSessionService2;

/**
 * Records a {@link MediaSessionService2}.
 * <p>
 * Owner of this object should handle synchronization.
 */
class MediaSessionService2Record extends MediaSession2Record {
    private static final boolean DEBUG = true; // TODO(jaewan): Modify
    private static final String TAG = "SessionService2Record";

    private final int mType;
    private final String mServiceName;
    private final SessionToken mToken;

    public MediaSessionService2Record(Context context,
            SessionDestroyedListener sessionDestroyedListener, int type,
            String packageName, String serviceName, String id) {
        super(context, sessionDestroyedListener);
        mType = type;
        mServiceName = serviceName;
        mToken = new SessionToken(mType, packageName, id, mServiceName, null);
    }

    /**
     * Overriden to change behavior of
     * {@link #createSessionToken(int, String, String, IMediaSession2)}}.
     */
    @Override
    MediaController2 onCreateMediaController(
            String packageName, String id, IMediaSession2 sessionBinder) {
        SessionToken token = new SessionToken(mType, packageName, id, mServiceName, sessionBinder);
        return createMediaController(token);
    }

    /**
     * @return token with no session binder information.
     */
    @Override
    public SessionToken getToken() {
        return mToken;
    }
}
