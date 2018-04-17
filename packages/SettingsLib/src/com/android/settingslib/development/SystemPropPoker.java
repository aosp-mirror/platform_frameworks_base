/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.development;

import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import android.util.Log;

public class SystemPropPoker  {
    private static final String TAG = "SystemPropPoker";

    private static final SystemPropPoker sInstance = new SystemPropPoker();

    private boolean mBlockPokes = false;

    private SystemPropPoker() {}

    @NonNull
    public static SystemPropPoker getInstance() {
        return sInstance;
    }

    public void blockPokes() {
        mBlockPokes = true;
    }

    public void unblockPokes() {
        mBlockPokes = false;
    }

    public void poke() {
        if (!mBlockPokes) {
            createPokerTask().execute();
        }
    }

    @VisibleForTesting
    PokerTask createPokerTask() {
        return new PokerTask();
    }

    public static class PokerTask extends AsyncTask<Void, Void, Void> {

        @VisibleForTesting
        String[] listServices() {
            return ServiceManager.listServices();
        }

        @VisibleForTesting
        IBinder checkService(String service) {
            return ServiceManager.checkService(service);
        }

        @Override
        protected Void doInBackground(Void... params) {
            String[] services = listServices();
            if (services == null) {
                Log.e(TAG, "There are no services, how odd");
                return null;
            }
            for (String service : services) {
                IBinder obj = checkService(service);
                if (obj != null) {
                    Parcel data = Parcel.obtain();
                    try {
                        obj.transact(IBinder.SYSPROPS_TRANSACTION, data, null, 0);
                    } catch (RemoteException e) {
                        // Ignore
                    } catch (Exception e) {
                        Log.i(TAG, "Someone wrote a bad service '" + service
                                + "' that doesn't like to be poked", e);
                    }
                    data.recycle();
                }
            }
            return null;
        }
    }
}
