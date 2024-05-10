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

package com.android.server.pm;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.content.IntentFilter;

import com.android.internal.annotations.Immutable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Representation of an immutable default cross-profile intent filter.
 */
@Immutable
public final class DefaultCrossProfileIntentFilter {

    @IntDef({
            Direction.TO_PARENT,
            Direction.TO_PROFILE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Direction {
        int TO_PARENT = 0;
        int TO_PROFILE = 1;
    }

    /** The intent filter that's used */
    public final WatchedIntentFilter filter;

    /**
     * The flags related to the forwarding, e.g.
     * {@link android.content.pm.PackageManager#SKIP_CURRENT_PROFILE} or
     * {@link android.content.pm.PackageManager#ONLY_IF_NO_MATCH_FOUND}.
     */
    public final int flags;

    /**
     * The direction of forwarding, can be either {@link Direction#TO_PARENT} or
     * {@link Direction#TO_PROFILE}.
     */
    public final @Direction int direction;

    /**
     * Whether this cross profile intent filter would allow personal data to be shared into
     * the work profile. If this is {@code true}, this intent filter should be only added to
     * the profile if the admin does not enable
     * {@link android.os.UserManager#DISALLOW_SHARE_INTO_MANAGED_PROFILE}.
     */
    public final boolean letsPersonalDataIntoProfile;

    private DefaultCrossProfileIntentFilter(WatchedIntentFilter filter, int flags,
            @Direction int direction, boolean letsPersonalDataIntoProfile) {
        this.filter = requireNonNull(filter);
        this.flags = flags;
        this.direction = direction;
        this.letsPersonalDataIntoProfile = letsPersonalDataIntoProfile;
    }

    static final class Builder {
        private final WatchedIntentFilter mFilter = new WatchedIntentFilter();
        private final int mFlags;
        private final @Direction int mDirection;
        private final boolean mLetsPersonalDataIntoProfile;

        Builder(@Direction int direction, int flags, boolean letsPersonalDataIntoProfile) {
            mDirection = direction;
            mFlags = flags;
            mLetsPersonalDataIntoProfile = letsPersonalDataIntoProfile;
        }

        Builder addAction(String action) {
            mFilter.addAction(action);
            return this;
        }

        Builder addCategory(String category) {
            mFilter.addCategory(category);
            return this;
        }

        Builder addDataType(String type) {
            try {
                mFilter.addDataType(type);
            } catch (IntentFilter.MalformedMimeTypeException e) {
                // ignore
            }
            return this;
        }

        Builder addDataScheme(String scheme) {
            mFilter.addDataScheme(scheme);
            return this;
        }

        DefaultCrossProfileIntentFilter build() {
            return new DefaultCrossProfileIntentFilter(mFilter, mFlags, mDirection,
                    mLetsPersonalDataIntoProfile);
        }
    }
}
