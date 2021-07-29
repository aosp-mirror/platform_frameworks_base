/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import android.os.UserHandle;
import android.util.Slog;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.TAG;

abstract class HandlerParams {
    /** User handle for the user requesting the information or installation. */
    private final UserHandle mUser;
    String mTraceMethod;
    int mTraceCookie;

    HandlerParams(UserHandle user) {
        mUser = user;
    }

    UserHandle getUser() {
        return mUser;
    }

    HandlerParams setTraceMethod(String traceMethod) {
        mTraceMethod = traceMethod;
        return this;
    }

    HandlerParams setTraceCookie(int traceCookie) {
        mTraceCookie = traceCookie;
        return this;
    }

    final void startCopy() {
        if (DEBUG_INSTALL) Slog.i(TAG, "startCopy " + mUser + ": " + this);
        handleStartCopy();
        handleReturnCode();
    }

    abstract void handleStartCopy();
    abstract void handleReturnCode();
}
