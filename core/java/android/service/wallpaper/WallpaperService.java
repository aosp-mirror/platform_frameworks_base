/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.service.wallpaper;

import com.android.internal.os.HandlerCaller;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

/**
 * A wallpaper service is responsible for showing a live wallpaper behind
 * applications that would like to sit on top of it.
 */
public abstract class WallpaperService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.service.wallpaper.WallpaperService";

    private static final String LOG_TAG = "WallpaperService";

    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IWallpaperServiceWrapper(this);
    }

    /**
     * Implements the internal {@link IWallpaperService} interface to convert
     * incoming calls to it back to calls on an {@link WallpaperService}.
     */
    class IWallpaperServiceWrapper extends IWallpaperService.Stub
            implements HandlerCaller.Callback {

        private static final int DO_ON_INTERRUPT = 10;
        
        private final HandlerCaller mCaller;

        private WallpaperService mTarget;

        public IWallpaperServiceWrapper(WallpaperService context) {
            mTarget = context;
            mCaller = new HandlerCaller(context, this);
        }

        public void onInterrupt() {
            Message message = mCaller.obtainMessage(DO_ON_INTERRUPT);
            mCaller.sendMessage(message);
        }

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ON_INTERRUPT :
                    //mTarget.onInterrupt();
                    return;
                default :
                    Log.w(LOG_TAG, "Unknown message type " + message.what);
            }
        }
    }
}
