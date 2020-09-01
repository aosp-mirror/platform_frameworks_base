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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Binder;
import android.os.UserHandle;

import java.util.Objects;

/**
 * Provides an adapter to access functionality reserved to components running in system_server
 * Functionality such as sending privileged broadcasts is to be accessed through the default
 * adapter, whereas tests can inject a no-op adapter.
 */
public class SystemServerAdapter {

    protected final Context mContext;

    protected SystemServerAdapter(@Nullable Context context) {
        mContext = context;
    }
    /**
     * Create a wrapper around privileged functionality.
     * @return the adapter
     */
    static final @NonNull SystemServerAdapter getDefaultAdapter(Context context) {
        Objects.requireNonNull(context);
        return new SystemServerAdapter(context);
    }

    /**
     * @return true if this is supposed to be run in system_server, false otherwise (e.g. for a
     *     unit test)
     */
    public boolean isPrivileged() {
        return true;
    }

    /**
     * Broadcast ACTION_MICROPHONE_MUTE_CHANGED
     */
    public void sendMicrophoneMuteChangedIntent() {
        mContext.sendBroadcastAsUser(
                new Intent(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                UserHandle.ALL);
    }

    /**
     * Broadcast ACTION_AUDIO_BECOMING_NOISY
     */
    public void sendDeviceBecomingNoisyIntent() {
        if (mContext == null) {
            return;
        }
        final Intent intent = new Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
