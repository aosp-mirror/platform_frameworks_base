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

package com.android.server.communal;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.app.communal.ICommunalManager;
import android.content.Context;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System service for handling Communal Mode state.
 */
public final class CommunalManagerService extends SystemService {
    private final Context mContext;
    private final AtomicBoolean mCommunalViewIsShowing = new AtomicBoolean(false);
    private final BinderService mBinderService;

    public CommunalManagerService(Context context) {
        super(context);
        mContext = context;
        mBinderService = new BinderService();
    }

    @VisibleForTesting
    BinderService getBinderServiceInstance() {
        return mBinderService;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMMUNAL_SERVICE, mBinderService);
    }

    private final class BinderService extends ICommunalManager.Stub {
        /**
         * Sets whether or not we are in communal mode.
         */
        @RequiresPermission(Manifest.permission.WRITE_COMMUNAL_STATE)
        @Override
        public void setCommunalViewShowing(boolean isShowing) {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_COMMUNAL_STATE,
                    Manifest.permission.WRITE_COMMUNAL_STATE
                            + "permission required to modify communal state.");
            if (mCommunalViewIsShowing.get() == isShowing) {
                return;
            }
            mCommunalViewIsShowing.set(isShowing);
        }
    }
}
