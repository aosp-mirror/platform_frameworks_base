/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.Context;
import android.os.Binder;
import android.provider.Settings;
import android.view.accessibility.CaptioningManager;

/**
 * Implementation class for CaptioningManager's interface that need system server identity.
 */
public class CaptioningManagerImpl implements CaptioningManager.SystemAudioCaptioningAccessing {
    private static final boolean SYSTEM_AUDIO_CAPTIONING_UI_DEFAULT_ENABLED = false;

    private final Context mContext;

    public CaptioningManagerImpl(Context context) {
        mContext = context;
    }

    /**
     * Sets the system audio caption enabled state.
     *
     * @param isEnabled The system audio captioning enabled state.
     * @param userId The user Id.
     */
    @Override
    public void setSystemAudioCaptioningEnabled(boolean isEnabled, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ODI_CAPTIONS_ENABLED, isEnabled ? 1 : 0, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Gets the system audio caption UI enabled state.
     *
     * @param userId The user Id.
     * @return the system audio caption UI enabled state.
     */
    @Override
    public boolean isSystemAudioCaptioningUiEnabled(int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED,
                    SYSTEM_AUDIO_CAPTIONING_UI_DEFAULT_ENABLED ? 1 : 0, userId) == 1;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Sets the system audio caption UI enabled state.
     *
     * @param isEnabled The system audio captioning UI enabled state.
     * @param userId The user Id.
     */
    @Override
    public void setSystemAudioCaptioningUiEnabled(boolean isEnabled, int userId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    Settings.Secure.ODI_CAPTIONS_VOLUME_UI_ENABLED, isEnabled ? 1 : 0, userId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
