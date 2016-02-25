/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shortcut;

import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import com.android.internal.policy.IShortcutService;

/**
 * This class takes functions from IShortcutService that come in binder pool threads and
 * post them onto shortcut handlers.
 */
public class ShortcutKeyServiceProxy extends IShortcutService.Stub {
    private static final int MSG_SHORTCUT_RECEIVED = 1;

    private final Object mLock = new Object();
    private Callbacks mCallbacks;
    private final Handler mHandler = new H();

    public interface Callbacks {
        void onShortcutKeyPressed(long shortcutCode);
    }

    public ShortcutKeyServiceProxy(Callbacks callbacks) { mCallbacks = callbacks; }

    @Override
    public void notifyShortcutKeyPressed(long shortcutCode) throws RemoteException {
        synchronized (mLock) {
            mHandler.obtainMessage(MSG_SHORTCUT_RECEIVED, shortcutCode).sendToTarget();
        }
    }

    private final class H extends Handler {
        public void handleMessage(Message msg) {
            final int what = msg.what;
            switch (what) {
                case MSG_SHORTCUT_RECEIVED:
                    mCallbacks.onShortcutKeyPressed((Long)msg.obj);
                    break;
            }
        }
    }
}
