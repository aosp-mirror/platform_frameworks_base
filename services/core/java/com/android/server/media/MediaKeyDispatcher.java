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

package com.android.server.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.session.ISessionManager;
import android.media.session.MediaSession;
import android.os.Binder;
import android.view.KeyEvent;

/**
 * Provides a way to customize behavior for media key events.
 *
 * Note: When instantiating this class, {@link MediaSessionService} will only use the constructor
 * without any parameters.
 */
public abstract class MediaKeyDispatcher {
    public MediaKeyDispatcher() {
        // Constructor used for reflection
    }

    /**
     * Implement this to customize the logic for which MediaSession should consume which key event.
     *
     * @param keyEvent a non-null KeyEvent whose key code is one of the supported media buttons.
     * @param uid the uid value retrieved by calling {@link Binder#getCallingUid()} from
     *         {@link ISessionManager#dispatchMediaKeyEvent(String, boolean, KeyEvent, boolean)}
     * @param asSystemService {@code true} if the event came from the system service via hardware
     *         devices. {@code false} if the event came from the app process through key injection.
     * @return a {@link MediaSession.Token} instance that should consume the given key event.
     */
    @Nullable
    MediaSession.Token getSessionForKeyEvent(@NonNull KeyEvent keyEvent, int uid,
            boolean asSystemService) {
        return null;
    }
}
