/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.testing.shadows;

import android.os.Binder;
import android.os.UserHandle;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/**
 * Extends {@link org.robolectric.shadows.ShadowBinder} with {@link Binder#clearCallingIdentity()}
 * and {@link Binder#restoreCallingIdentity(long)}. Uses a hardcoded default {@link #LOCAL_UID} to
 * mimic the local process uid.
 */
@Implements(Binder.class)
public class ShadowBinder extends org.robolectric.shadows.ShadowBinder {
    public static final Integer LOCAL_UID = 1000;
    private static Integer originalCallingUid;
    private static UserHandle sCallingUserHandle;

    @Implementation
    protected static long clearCallingIdentity() {
        originalCallingUid = getCallingUid();
        setCallingUid(LOCAL_UID);
        return 1L;
    }

    @Implementation
    protected static void restoreCallingIdentity(long token) {
        setCallingUid(originalCallingUid);
    }

    public static void setCallingUserHandle(UserHandle userHandle) {
        sCallingUserHandle = userHandle;
    }

    /**
     * Shadows {@link Binder#getCallingUserHandle()}. If {@link ShadowBinder#sCallingUserHandle}
     * is set, return that; otherwise mimic the default implementation.
     */
    @Implementation
    public static UserHandle getCallingUserHandle() {
        if (sCallingUserHandle != null) {
            return sCallingUserHandle;
        } else {
            return UserHandle.of(UserHandle.getUserId(getCallingUid()));
        }
    }

    /**
     * Clean up and reset state that was created for testing.
     */
    @Resetter
    public static void reset() {
        sCallingUserHandle = null;
        org.robolectric.shadows.ShadowBinder.reset();
    }
}
