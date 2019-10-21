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

package android.app;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.Immutable;

/**
 * Description of an app-op that was noted for the current process.
 *
 * <p>This is either delivered after a
 * {@link AppOpsManager.AppOpsCollector#onNoted(SyncNotedAppOp) two way binder call} or
 * when the app
 * {@link AppOpsManager.AppOpsCollector#onSelfNoted(SyncNotedAppOp) notes an app-op for
 * itself}.
 */
@Immutable
public final class SyncNotedAppOp {
    private final int mOpCode;
    private final @Nullable String mFeatureId;

    /**
     * @return The op that was noted.
     */
    public @NonNull String getOp() {
        return AppOpsManager.opToPublicName(mOpCode);
    }

    /**
     * @return The {@link android.content.Context#createFeatureContext Feature} in the app
     */
    public @Nullable String getFeatureId() {
        return mFeatureId;
    }

    /**
     * Create a new sync op description
     *
     * @param opCode The op that was noted
     *
     * @hide
     */
    public SyncNotedAppOp(@IntRange(from = 0, to = AppOpsManager._NUM_OP - 1) int opCode,
            @Nullable String featureId) {
        mOpCode = opCode;
        mFeatureId = featureId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SyncNotedAppOp)) {
            return false;
        }

        return mOpCode == ((SyncNotedAppOp) other).mOpCode;
    }

    @Override
    public int hashCode() {
        return mOpCode;
    }
}
