/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.credentials;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.credentials.ui.UserSelectionDialogResult;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Base class of a request session, that listens to UI events. This class must be extended
 * every time a new response type is expected from the providers.
 */
abstract class RequestSession implements CredentialManagerUi.CredentialManagerUiCallback,
        ProviderSession.ProviderInternalCallback {
    @NonNull protected final IBinder mRequestId;
    @NonNull protected final Context mContext;
    @NonNull protected final CredentialManagerUi mCredentialManagerUi;
    @NonNull protected final String mRequestType;
    @NonNull protected final Handler mHandler;
    @NonNull protected boolean mIsFirstUiTurn = true;
    @UserIdInt protected final int mUserId;

    protected RequestSession(@NonNull Context context,
            @UserIdInt int userId, @NonNull String requestType) {
        mContext = context;
        mUserId = userId;
        mRequestType = requestType;
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        mRequestId = new Binder();
        mCredentialManagerUi = new CredentialManagerUi(mContext,
                mUserId, this);
    }

    /** Returns the unique identifier of this request session. */
    public IBinder getRequestId() {
        return mRequestId;
    }

    @Override // from CredentialManagerUiCallback
    public abstract void onUiSelection(UserSelectionDialogResult selection);

    @Override // from CredentialManagerUiCallback
    public abstract void onUiCancelation();

    @Override // from ProviderInternalCallback
    public abstract void onProviderStatusChanged(ProviderSession.Status status, ComponentName componentName);
}
