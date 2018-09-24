/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.appbinding;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.Handler;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * System server that keeps a binding to an app to keep it always running.
 */
public class AppBindingService extends Binder {
    public static final String TAG = "AppBindingService";

    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    private final Injector mInjector;
    private final Context mContext;
    private final Handler mHandler;
    private final IPackageManager mIPackageManager;

    static class Injector {
        public IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }
    }

    /**
     * System service interacts with this service via this class.
     */
    public static final class Lifecycle extends SystemService {
        final AppBindingService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new AppBindingService(new Injector(), context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.APP_BINDING_SERVICE, mService);
        }
    }

    private AppBindingService(Injector injector, Context context) {
        mInjector = injector;
        mContext = context;
        mIPackageManager = injector.getIPackageManager();
        mHandler = BackgroundThread.getHandler();
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
    }
}
