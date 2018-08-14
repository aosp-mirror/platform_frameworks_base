/*
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

package com.android.printspooler.model;

import static android.content.Context.BIND_AUTO_CREATE;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

public class PrintSpoolerProvider implements ServiceConnection {
    private final Context mContext;
    private final Runnable mCallback;

    private PrintSpoolerService mSpooler;

    public PrintSpoolerProvider(Context context, Runnable callback) {
        mContext = context;
        mCallback = callback;
        Intent intent = new Intent(mContext, PrintSpoolerService.class);
        mContext.bindService(intent, this, BIND_AUTO_CREATE);
    }

    public PrintSpoolerService getSpooler() {
        return mSpooler;
    }

    public void destroy() {
        if (mSpooler != null) {
            mContext.unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mSpooler = ((PrintSpoolerService.PrintSpooler) service).getService();
        if (mSpooler != null) {
            mCallback.run();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        /* do nothing - we are in the same process */
    }
}
