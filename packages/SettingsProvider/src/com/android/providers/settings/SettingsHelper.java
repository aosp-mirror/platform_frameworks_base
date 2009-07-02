/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.providers.settings;

import android.backup.BackupDataInput;
import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.os.IHardwareService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.content.IContentService;
import android.util.Log;

public class SettingsHelper {
    private static final String TAG = "SettingsHelper";

    private Context mContext;
    private AudioManager mAudioManager;
    private IContentService mContentService;
    private static final String SYNC_AUTO = "auto_sync";
    private static final String SYNC_MAIL = "gmail-ls_sync";
    private static final String SYNC_CALENDAR = "calendar_sync";
    private static final String SYNC_CONTACTS = "contacts_sync";

    public SettingsHelper(Context context) {
        mContext = context;
        mAudioManager = (AudioManager) context
                .getSystemService(Context.AUDIO_SERVICE);
        mContentService = ContentResolver.getContentService();
    }

    public void restoreValue(String name, String value) {
        if (Settings.System.SCREEN_BRIGHTNESS.equals(name)) {
            setBrightness(Integer.parseInt(value));
        } else if (Settings.System.SOUND_EFFECTS_ENABLED.equals(name)) {
            if (Integer.parseInt(value) == 1) {
                mAudioManager.loadSoundEffects();
            } else {
                mAudioManager.unloadSoundEffects();
            }
        }
    }

    private void setBrightness(int brightness) {
        try {
            IHardwareService hardware = IHardwareService.Stub
                    .asInterface(ServiceManager.getService("hardware"));
            if (hardware != null) {
                hardware.setBacklights(brightness);
            }
        } catch (RemoteException doe) {

        }
    }

    static final String[] PROVIDERS = { "gmail-ls", "calendar", "contacts" };
    
    byte[] getSyncProviders() {
        byte[] sync = new byte[1 + PROVIDERS.length];
        try {
            sync[0] = (byte) (mContentService.getListenForNetworkTickles() ? 1 : 0);
            for (int i = 0; i < PROVIDERS.length; i++) {
                sync[i + 1] = (byte) 
                        (mContentService.getSyncProviderAutomatically(PROVIDERS[i]) ? 1 : 0);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Unable to backup sync providers");
            return sync;
        }
        return sync;
    }
    
    void setSyncProviders(BackupDataInput backup) {
        byte[] sync = new byte[backup.getDataSize()];

        try {
            backup.readEntityData(sync, 0, sync.length);
            mContentService.setListenForNetworkTickles(sync[0] == 1);
            for (int i = 0; i < PROVIDERS.length; i++) {
                mContentService.setSyncProviderAutomatically(PROVIDERS[i], sync[i + 1] > 0);
            }
        } catch (RemoteException re) {
            Log.w(TAG, "Unable to restore sync providers");
        } catch (java.io.IOException ioe) {
            Log.w(TAG, "Unable to read sync settings");
        }
    }
}
