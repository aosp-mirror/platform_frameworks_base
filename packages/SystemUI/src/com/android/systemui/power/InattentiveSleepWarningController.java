/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.power;

import android.content.Context;

import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Receives messages sent from {@link com.android.server.power.InattentiveSleepWarningController}
 * and shows the appropriate inattentive sleep UI (e.g. {@link InattentiveSleepWarningView}).
 */
@Singleton
public class InattentiveSleepWarningController extends SystemUI implements CommandQueue.Callbacks {
    private final CommandQueue mCommandQueue;
    private InattentiveSleepWarningView mOverlayView;

    @Inject
    public InattentiveSleepWarningController(Context context, CommandQueue commandQueue) {
        super(context);
        mCommandQueue = commandQueue;
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @Override
    public void showInattentiveSleepWarning() {
        if (mOverlayView == null) {
            mOverlayView = new InattentiveSleepWarningView(mContext);
        }

        mOverlayView.show();
    }

    @Override
    public void dismissInattentiveSleepWarning(boolean animated) {
        if (mOverlayView != null) {
            mOverlayView.dismiss(animated);
        }
    }
}
