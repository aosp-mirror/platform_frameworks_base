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

package com.android.systemui.communal.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.android.systemui.communal.CommunalSourceMonitor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.communal.ICommunalHost;
import com.android.systemui.shared.communal.ICommunalSource;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * CommunalService services requests to {@link ICommunalHost}, allowing clients to declare
 * themselves as the source of communal surfaces.
 */
public class CommunalService extends Service {
    final Executor mMainExecutor;
    final CommunalSourceMonitor mMonitor;
    private final CommunalSourceImpl.Factory mSourceFactory;

    private ICommunalHost.Stub mBinder = new ICommunalHost.Stub() {
        @Override
        public void setSource(ICommunalSource source) {
            mMonitor.setSource(
                    source != null ? mSourceFactory.create(source) : null);
        }
    };

    @Inject
    CommunalService(@Main Executor mainExecutor, CommunalSourceImpl.Factory sourceFactory,
            CommunalSourceMonitor monitor) {
        mMainExecutor = mainExecutor;
        mSourceFactory = sourceFactory;
        mMonitor = monitor;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // The service does not expect requests outside ICommunalHost.
        return mBinder;
    }
}
