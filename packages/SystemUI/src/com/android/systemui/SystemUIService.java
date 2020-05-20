/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.os.BinderInternal;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpHandler;
import com.android.systemui.dump.SystemUIAuxiliaryDumpService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

public class SystemUIService extends Service {

    private final Handler mMainHandler;
    private final DumpHandler mDumpHandler;

    @Inject
    public SystemUIService(
            @Main Handler mainHandler,
            DumpHandler dumpHandler) {
        super();
        mMainHandler = mainHandler;
        mDumpHandler = dumpHandler;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();

        // For debugging RescueParty
        if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean("debug.crash_sysui", false)) {
            throw new RuntimeException();
        }

        if (Build.IS_DEBUGGABLE) {
            // b/71353150 - looking for leaked binder proxies
            BinderInternal.nSetBinderProxyCountEnabled(true);
            BinderInternal.nSetBinderProxyCountWatermarks(1000,900);
            BinderInternal.setBinderProxyCountCallback(
                    new BinderInternal.BinderProxyLimitListener() {
                        @Override
                        public void onLimitReached(int uid) {
                            Slog.w(SystemUIApplication.TAG,
                                    "uid " + uid + " sent too many Binder proxies to uid "
                                    + Process.myUid());
                        }
                    }, mMainHandler);
        }

        // Bind the dump service so we can dump extra info during a bug report
        startServiceAsUser(
                new Intent(getApplicationContext(), SystemUIAuxiliaryDumpService.class),
                UserHandle.SYSTEM);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // If no args are passed, assume we're being dumped as part of a bug report (sadly, we have
        // no better way to guess whether this is taking place). Set the appropriate dump priority
        // (CRITICAL) to reflect that this is taking place.
        String[] massagedArgs = args;
        if (args.length == 0) {
            massagedArgs = new String[] {
                    DumpHandler.PRIORITY_ARG,
                    DumpHandler.PRIORITY_ARG_CRITICAL};
        }

        mDumpHandler.dump(fd, pw, massagedArgs);
    }
}
