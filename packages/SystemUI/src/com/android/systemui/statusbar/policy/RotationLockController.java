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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.os.UserHandle;

import com.android.internal.view.RotationPolicy;

import java.util.concurrent.CopyOnWriteArrayList;

public final class RotationLockController {
    private final Context mContext;
    private final CopyOnWriteArrayList<RotationLockControllerCallback> mCallbacks =
            new CopyOnWriteArrayList<RotationLockControllerCallback>();

    private final RotationPolicy.RotationPolicyListener mRotationPolicyListener =
            new RotationPolicy.RotationPolicyListener() {
        @Override
        public void onChange() {
            notifyChanged();
        }
    };

    public interface RotationLockControllerCallback {
        public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible);
    }

    public RotationLockController(Context context) {
        mContext = context;
        notifyChanged();
        if (RotationPolicy.isRotationLockToggleSupported(mContext)) {
            RotationPolicy.registerRotationPolicyListener(mContext,
                    mRotationPolicyListener, UserHandle.USER_ALL);
        }
    }

    public void addRotationLockControllerCallback(RotationLockControllerCallback callback) {
        mCallbacks.add(callback);
    }

    public boolean isRotationLocked() {
        if (RotationPolicy.isRotationLockToggleSupported(mContext)) {
            return RotationPolicy.isRotationLocked(mContext);
        }
        return false;
    }

    public void setRotationLocked(boolean locked) {
        if (RotationPolicy.isRotationLockToggleSupported(mContext)) {
            RotationPolicy.setRotationLock(mContext, locked);
        }
    }

    public boolean isRotationLockAffordanceVisible() {
        if (RotationPolicy.isRotationLockToggleSupported(mContext)) {
            return RotationPolicy.isRotationLockToggleVisible(mContext);
        }
        return false;
    }

    public void release() {
        if (RotationPolicy.isRotationLockToggleSupported(mContext)) {
            RotationPolicy.unregisterRotationPolicyListener(mContext,
                    mRotationPolicyListener);
        }
    }

    private void notifyChanged() {
        for (RotationLockControllerCallback callback : mCallbacks) {
            callback.onRotationLockStateChanged(RotationPolicy.isRotationLocked(mContext),
                    RotationPolicy.isRotationLockToggleVisible(mContext));
        }
    }
}
