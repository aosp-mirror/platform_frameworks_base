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

package android.app.slice;

import android.annotation.SystemService;
import android.app.slice.ISliceListener.Stub;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

/**
 * @hide
 */
@SystemService(Context.SLICE_SERVICE)
public class SliceManager {

    private final ISliceManager mService;
    private final Context mContext;

    public SliceManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = ISliceManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.SLICE_SERVICE));
    }

    /**
     */
    public void addSliceListener(Uri uri, SliceListener listener, SliceSpec[] specs) {
        try {
            mService.addSliceListener(uri, mContext.getPackageName(), listener.mStub, specs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public void removeSliceListener(Uri uri, SliceListener listener) {
        try {
            mService.removeSliceListener(uri, mContext.getPackageName(), listener.mStub);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public void pinSlice(Uri uri, SliceSpec[] specs) {
        try {
            mService.pinSlice(mContext.getPackageName(), uri, specs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public void unpinSlice(Uri uri) {
        try {
            mService.unpinSlice(mContext.getPackageName(), uri);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public boolean hasSliceAccess() {
        try {
            return mService.hasSliceAccess(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public SliceSpec[] getPinnedSpecs(Uri uri) {
        try {
            return mService.getPinnedSpecs(uri, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     */
    public abstract static class SliceListener {
        private final Handler mHandler;

        /**
         */
        public SliceListener() {
            this(Handler.getMain());
        }

        /**
         */
        public SliceListener(Handler h) {
            mHandler = h;
        }

        /**
         */
        public abstract void onSliceUpdated(Slice s);

        private final ISliceListener.Stub mStub = new Stub() {
            @Override
            public void onSliceUpdated(Slice s) throws RemoteException {
                mHandler.post(() -> SliceListener.this.onSliceUpdated(s));
            }
        };
    }
}
