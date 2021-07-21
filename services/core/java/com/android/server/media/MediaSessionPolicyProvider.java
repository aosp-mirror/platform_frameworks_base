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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.media.session.MediaSession;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Abstract class for customizing how {@link MediaSessionService} handles sessions.
 *
 * Note: When instantiating this class, {@link MediaSessionService} will only use the constructor
 * without any parameters.
 */
// TODO: Move this class to apex/media/
public abstract class MediaSessionPolicyProvider {
    @IntDef(value = {
            SESSION_POLICY_IGNORE_BUTTON_RECEIVER,
            SESSION_POLICY_IGNORE_BUTTON_SESSION
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SessionPolicy {}

    /**
     * Policy to ignore media button receiver, to not revive the media app when its media session is
     * released or the app is dead.
     *
     * @see MediaSession#setMediaButtonReceiver
     */
    static final int SESSION_POLICY_IGNORE_BUTTON_RECEIVER = 1 << 0;

    /**
     * Policy to ignore sessions that should not respond to media key events via
     * {@link MediaSessionService}. A typical use case is to explicitly
     * ignore sessions that should not respond to media key events even if their playback state has
     * changed most recently.
     */
    static final int SESSION_POLICY_IGNORE_BUTTON_SESSION = 1 << 1;

    public MediaSessionPolicyProvider(Context context) {
        // Constructor used for reflection
    }

    /**
     * Use this to statically set policies for sessions when they are created.
     * Use android.media.session.MediaSessionManager#setSessionPolicies(MediaSession.Token, int)
     * to dynamically change policies at runtime.
     *
     * @param uid
     * @param packageName
     * @return list of policies
     */
    @SessionPolicy int getSessionPoliciesForApplication(int uid, @NonNull String packageName) {
        return 0;
    }
}
