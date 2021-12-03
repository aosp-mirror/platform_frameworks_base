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

package com.android.systemui.communal;

import android.app.communal.CommunalManager;
import android.content.Context;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import java.lang.ref.WeakReference;

import javax.inject.Inject;

/**
 * The {@link CommunalManagerUpdater} is responsible for forwarding state from SystemUI to
 * the {@link CommunalManager} system service.
 */
@SysUISingleton
public class CommunalManagerUpdater extends CoreStartable {
    private static final String TAG = "CommunalManagerUpdater";

    private final CommunalManager mCommunalManager;
    private final CommunalSourceMonitor mMonitor;

    private final CommunalSourceMonitor.Callback mSourceCallback =
            new CommunalSourceMonitor.Callback() {
                @Override
                public void onSourceAvailable(WeakReference<CommunalSource> source) {
                    try {
                        mCommunalManager.setCommunalViewShowing(
                                source != null && source.get() != null);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Error updating communal manager service state", e);
                    }
                }
            };

    @Inject
    public CommunalManagerUpdater(Context context, CommunalSourceMonitor monitor) {
        super(context);
        mCommunalManager = context.getSystemService(CommunalManager.class);
        mMonitor = monitor;
    }

    @Override
    public void start() {
        if (mCommunalManager != null) {
            mMonitor.addCallback(mSourceCallback);
        }
    }
}
