/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.supervision;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.supervision.ISupervisionManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Service for handling system supervision.
 */
public class SupervisionService extends ISupervisionManager.Stub {
    private static final String LOG_TAG = "SupervisionService";

    private final Context mContext;

    public SupervisionService(Context context) {
        mContext = context.createAttributionContext("SupervisionService");
    }

    @Override
    public boolean isSupervisionEnabled() {
        return false;
    }

    @Override
    public void onShellCommand(
            @Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        new SupervisionServiceShellCommand(this)
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter fout, @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, fout)) return;

        fout.println("Supervision enabled: " + isSupervisionEnabled());
    }

    public static class Lifecycle extends SystemService {
        private final SupervisionService mSupervisionService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mSupervisionService = new SupervisionService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.SUPERVISION_SERVICE, mSupervisionService);
        }
    }
}
