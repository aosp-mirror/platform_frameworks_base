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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

/**
 * An active voice interaction session, initiated by a {@link VoiceInteractionService}.
 */
public abstract class VoiceInteractionSessionService extends Service {

    static final int MSG_NEW_SESSION = 1;

    IVoiceInteractionManagerService mSystemService;
    VoiceInteractionSession mSession;

    IVoiceInteractionSessionService mInterface = new IVoiceInteractionSessionService.Stub() {
        public void newSession(IBinder token, Bundle args) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOO(MSG_NEW_SESSION,
                    token, args));

        }
    };

    HandlerCaller mHandlerCaller;
    final HandlerCaller.Callback mHandlerCallerCallback = new HandlerCaller.Callback() {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args = (SomeArgs)msg.obj;
            switch (msg.what) {
                case MSG_NEW_SESSION:
                    doNewSession((IBinder)args.arg1, (Bundle)args.arg2);
                    break;
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mSystemService = IVoiceInteractionManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VOICE_INTERACTION_MANAGER_SERVICE));
        mHandlerCaller = new HandlerCaller(this, Looper.myLooper(),
                mHandlerCallerCallback, true);
    }

    public abstract VoiceInteractionSession onNewSession(Bundle args);

    @Override
    public IBinder onBind(Intent intent) {
        return mInterface.asBinder();
    }

    void doNewSession(IBinder token, Bundle args) {
        if (mSession != null) {
            mSession.doDestroy();
            mSession = null;
        }
        mSession = onNewSession(args);
        try {
            mSystemService.deliverNewSession(token, mSession.mSession, mSession.mInteractor);
            mSession.doCreate(mSystemService, token, args);
        } catch (RemoteException e) {
        }
    }
}
