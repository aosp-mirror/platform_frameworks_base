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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.face.ISession;
import android.hardware.biometrics.face.V1_0.IBiometricsFace;

import com.android.server.biometrics.sensors.face.hidl.HidlToAidlSessionAdapter;

import java.util.function.Supplier;

/**
 * A holder for an AIDL {@link ISession} with additional metadata about the current user
 * and the backend.
 */
public class AidlSession {

    private final int mHalInterfaceVersion;
    @NonNull
    private final ISession mSession;
    private final int mUserId;
    @NonNull private final AidlResponseHandler mAidlResponseHandler;

    public AidlSession(int halInterfaceVersion, @NonNull ISession session, int userId,
            AidlResponseHandler aidlResponseHandler) {
        mHalInterfaceVersion = halInterfaceVersion;
        mSession = session;
        mUserId = userId;
        mAidlResponseHandler = aidlResponseHandler;
    }

    public AidlSession(Context context, Supplier<IBiometricsFace> session, int userId,
            AidlResponseHandler aidlResponseHandler) {
        mSession = new HidlToAidlSessionAdapter(context, session, userId, aidlResponseHandler);
        mHalInterfaceVersion = 0;
        mUserId = userId;
        mAidlResponseHandler = aidlResponseHandler;
    }

    @NonNull public ISession getSession() {
        return mSession;
    }

    public int getUserId() {
        return mUserId;
    }

    /** The HAL callback, which should only be used in tests {@See BiometricTestSessionImpl}. */
    public AidlResponseHandler getHalSessionCallback() {
        return mAidlResponseHandler;
    }

    /**
     * If this backend implements the *WithContext methods for enroll, authenticate, and
     * detectInteraction. These variants should always be called if they are available.
     */
    public boolean hasContextMethods() {
        return mHalInterfaceVersion >= 2;
    }

    /**
     * If this backend implements enroll methods with an {@link android.view.Surface}.
     */
    public boolean supportsFaceEnrollOptions() {
        return mHalInterfaceVersion >= 4;
    }
}
