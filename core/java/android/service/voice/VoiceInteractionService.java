/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import android.annotation.SdkConstant;
import android.app.Instrumentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import com.android.internal.app.IVoiceInteractionManagerService;

public class VoiceInteractionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_VOICE_INTERACTION} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.voice.VoiceInteractionService";

    /**
     * Name under which a VoiceInteractionService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#VoiceInteractionService voice-interaction-service}&gt;</code> tag.
     */
    public static final String SERVICE_META_DATA = "android.voice_interaction";

    IVoiceInteractionService mInterface = new IVoiceInteractionService.Stub() {
    };

    IVoiceInteractionManagerService mSystemService;

    public void startVoiceActivity(Intent intent, Bundle sessionArgs) {
        try {
            mSystemService.startVoiceActivity(intent,
                    intent.resolveType(getContentResolver()),
                    mInterface, sessionArgs);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        return null;
    }
}
