/*
 * Copyright 2022 The Android Open Source Project
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

package android.media.tv.tuner.frontend;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.media.tv.tuner.frontend.FrontendStatus.FrontendStatusType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A class contains the Frontend Status Readiness of a given type.
 *
 * @hide
 */
@SystemApi
public final class FrontendStatusReadiness {
    /** @hide */
    @IntDef({FRONTEND_STATUS_READINESS_UNDEFINED, FRONTEND_STATUS_READINESS_UNAVAILABLE,
            FRONTEND_STATUS_READINESS_UNSTABLE, FRONTEND_STATUS_READINESS_STABLE,
            FRONTEND_STATUS_READINESS_UNSUPPORTED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Readiness {}

    /**
     * The FrontendStatus readiness status for the given FrontendStatusType is undefined.
     */
    public static final int FRONTEND_STATUS_READINESS_UNDEFINED =
            android.hardware.tv.tuner.FrontendStatusReadiness.UNDEFINED;

    /**
     * The FrontendStatus for the given FrontendStatusType is currently unavailable.
     */
    public static final int FRONTEND_STATUS_READINESS_UNAVAILABLE =
            android.hardware.tv.tuner.FrontendStatusReadiness.UNAVAILABLE;

    /**
     * The FrontendStatus for the given FrontendStatusType is ready to read, but it’s unstable.
     */
    public static final int FRONTEND_STATUS_READINESS_UNSTABLE =
            android.hardware.tv.tuner.FrontendStatusReadiness.UNSTABLE;

    /**
     * The FrontendStatus for the given FrontendStatusType is ready to read, and it’s stable.
     */
    public static final int FRONTEND_STATUS_READINESS_STABLE =
            android.hardware.tv.tuner.FrontendStatusReadiness.STABLE;

    /**
     * The FrontendStatus for the given FrontendStatusType is not supported.
     */
    public static final int FRONTEND_STATUS_READINESS_UNSUPPORTED =
            android.hardware.tv.tuner.FrontendStatusReadiness.UNSUPPORTED;

    @FrontendStatusType private int mFrontendStatusType;
    @Readiness private int mStatusReadiness;

    private FrontendStatusReadiness(int type, int readiness) {
        mFrontendStatusType = type;
        mStatusReadiness = readiness;
    }

    /**
     * Gets the frontend status type.
     */
    @FrontendStatusType
    public int getStatusType() {
        return mFrontendStatusType;
    }
    /**
     * Gets the frontend status readiness.
     */
    @Readiness
    public int getStatusReadiness() {
        return mStatusReadiness;
    }
}
