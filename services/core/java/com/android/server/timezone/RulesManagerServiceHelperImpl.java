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

package com.android.server.timezone;

import com.android.internal.util.DumpUtils;

import android.app.timezone.RulesManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * A single class that implements multiple helper interfaces for use by {@link RulesManagerService}.
 */
final class RulesManagerServiceHelperImpl
        implements PermissionHelper, Executor, RulesManagerIntentHelper {

    private final Context mContext;

    RulesManagerServiceHelperImpl(Context context) {
        mContext = context;
    }

    @Override
    public void enforceCallerHasPermission(String requiredPermission) {
        mContext.enforceCallingPermission(requiredPermission, null /* message */);
    }

    @Override
    public boolean checkDumpPermission(String tag, PrintWriter pw) {
        return DumpUtils.checkDumpPermission(mContext, tag, pw);
    }

    @Override
    public void execute(Runnable runnable) {
        AsyncTask.execute(runnable);
    }

    @Override
    public void sendTimeZoneOperationStaged() {
        sendOperationIntent(true /* staged */);
    }

    @Override
    public void sendTimeZoneOperationUnstaged() {
        sendOperationIntent(false /* staged */);
    }

    private void sendOperationIntent(boolean staged) {
        Intent intent = new Intent(RulesManager.ACTION_RULES_UPDATE_OPERATION);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putExtra(RulesManager.EXTRA_OPERATION_STAGED, staged);
        mContext.sendBroadcastAsUser(intent, UserHandle.SYSTEM);
    }

}
