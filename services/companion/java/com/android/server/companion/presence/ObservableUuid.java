/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.presence;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.ParcelUuid;

public class ObservableUuid {
    private final int mUserId;
    private final String mPackageName;

    private final ParcelUuid mUuid;

    private final long mTimeApprovedMs;

    public ObservableUuid(@UserIdInt int userId, @NonNull ParcelUuid uuid,
            @NonNull String packageName, Long timeApprovedMs) {
        mUserId = userId;
        mUuid = uuid;
        mPackageName = packageName;
        mTimeApprovedMs = timeApprovedMs;
    }

    public int getUserId() {
        return mUserId;
    }

    public ParcelUuid getUuid() {
        return mUuid;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public long getTimeApprovedMs() {
        return mTimeApprovedMs;
    }
}
