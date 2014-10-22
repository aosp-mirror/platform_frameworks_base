/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.content.res;

import android.os.IBinder;

/** @hide */
public final class ResourcesKey {
    final String mResDir;
    final float mScale;
    private final int mHash;
    private final IBinder mToken;

    public final int mDisplayId;
    public final Configuration mOverrideConfiguration = new Configuration();

    public ResourcesKey(String resDir, int displayId, Configuration overrideConfiguration,
            float scale, IBinder token) {
        mResDir = resDir;
        mDisplayId = displayId;
        if (overrideConfiguration != null) {
            mOverrideConfiguration.setTo(overrideConfiguration);
        }
        mScale = scale;
        mToken = token;

        int hash = 17;
        hash = 31 * hash + (mResDir == null ? 0 : mResDir.hashCode());
        hash = 31 * hash + mDisplayId;
        hash = 31 * hash + (mOverrideConfiguration != null
                ? mOverrideConfiguration.hashCode() : 0);
        hash = 31 * hash + Float.floatToIntBits(mScale);
        mHash = hash;
    }

    public boolean hasOverrideConfiguration() {
        return !Configuration.EMPTY.equals(mOverrideConfiguration);
    }

    @Override
    public int hashCode() {
        return mHash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ResourcesKey)) {
            return false;
        }
        ResourcesKey peer = (ResourcesKey) obj;
        if (mResDir != peer.mResDir) {
            if (mResDir == null || peer.mResDir == null) {
                return false;
            } else if (!mResDir.equals(peer.mResDir)) {
                return false;
            }
        }
        if (mDisplayId != peer.mDisplayId) {
            return false;
        }
        if (mOverrideConfiguration != peer.mOverrideConfiguration) {
            if (mOverrideConfiguration == null || peer.mOverrideConfiguration == null) {
                return false;
            }
            if (!mOverrideConfiguration.equals(peer.mOverrideConfiguration)) {
                return false;
            }
        }
        if (mScale != peer.mScale) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return Integer.toHexString(mHash);
    }
}
