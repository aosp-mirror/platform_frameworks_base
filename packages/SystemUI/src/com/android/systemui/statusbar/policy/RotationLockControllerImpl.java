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

/** Platform implementation of the rotation lock controller. **/
public final class RotationLockControllerImpl implements RotationLockController {
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

    public RotationLockControllerImpl(Context context) {
        mContext = context;
        setListening(true);
    }

    public void addCallback(RotationLockControllerCallback callback) {
        mCallbacks.add(callback);
        notifyChanged(callback);
    }

    public void removeCallback(RotationLockControllerCallback callback) {
        mCallbacks.remove(callback);
    }

    public int getRotationLockOrientation() {
        return RotationPolicy.getRotationLockOrientation(mContext);
    }

    public boolean isRotationLocked() {
        return RotationPolicy.isRotationLocked(mContext);
    }

    public void setRotationLocked(boolean locked) {
        RotationPolicy.setRotationLock(mContext, locked);
    }

    public boolean isRotationLockAffordanceVisible() {
        return RotationPolicy.isRotationLockToggleVisible(mContext);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            RotationPolicy.registerRotationPolicyListener(mContext, mRotationPolicyListener,
                    UserHandle.USER_ALL);
        } else {
            RotationPolicy.unregisterRotationPolicyListener(mContext, mRotationPolicyListener);
        }
    }

    private void notifyChanged() {
        for (RotationLockControllerCallback callback : mCallbacks) {
            notifyChanged(callback);
        }
    }

    private void notifyChanged(RotationLockControllerCallback callback) {
        callback.onRotationLockStateChanged(RotationPolicy.isRotationLocked(mContext),
                RotationPolicy.isRotationLockToggleVisible(mContext));
    }
}
