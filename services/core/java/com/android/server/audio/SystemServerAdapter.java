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
import android.os.UserHandle;

/**
 * Provides an adapter to access functionality reserved to components running in system_server
 * Functionality such as sending privileged broadcasts is to be accessed through the default
 * adapter, whereas tests can inject a no-op adapter.
 */
public class SystemServerAdapter {

    protected final Context mContext;

    private SystemServerAdapter(@Nullable Context context) {
        mContext = context;
    }
    /**
     * Create a wrapper around privileged functionality.
     * @return the adapter
     */
    static final @NonNull SystemServerAdapter getDefaultAdapter(Context context) {
        return new SystemServerAdapter(context);
    }

    /**
     * Create an adapter that does nothing.
     * Use for running non-privileged tests, such as unit tests
     * @return a no-op adapter
     */
    static final @NonNull SystemServerAdapter getNoOpAdapter() {
        return new NoOpSystemServerAdapter();
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

    //--------------------------------------------------------------------
    protected static class NoOpSystemServerAdapter extends SystemServerAdapter {

        NoOpSystemServerAdapter() {
            super(null);
        }

        @Override
        public boolean isPrivileged() {
            return false;
        }

        @Override
        public void sendMicrophoneMuteChangedIntent() {
            // no-op
        }
    }
}
