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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;

import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Privileges granted to a Process that allows it to execute starts from the background.
 * @hide
 */
public class BackgroundStartPrivileges {
    /** No privileges. */
    public static final BackgroundStartPrivileges NONE = new BackgroundStartPrivileges(
            false, false, null);
    /** Allow activity starts (and implies allowing foreground service starts).  */
    public static final BackgroundStartPrivileges ALLOW_BAL = new BackgroundStartPrivileges(
            true, true, null);
    /** Allow foreground service starts. */
    public static final BackgroundStartPrivileges ALLOW_FGS = new BackgroundStartPrivileges(
            false, true, null);

    private final boolean mAllowsBackgroundActivityStarts;
    private final boolean mAllowsBackgroundForegroundServiceStarts;
    private final IBinder mOriginatingToken;

    private BackgroundStartPrivileges(boolean allowsBackgroundActivityStarts,
            boolean allowsBackgroundForegroundServiceStarts, @Nullable IBinder originatingToken) {
        Preconditions.checkArgument(
                !allowsBackgroundActivityStarts || allowsBackgroundForegroundServiceStarts,
                "backgroundActivityStarts implies bgFgServiceStarts");
        mAllowsBackgroundActivityStarts = allowsBackgroundActivityStarts;
        mAllowsBackgroundForegroundServiceStarts = allowsBackgroundForegroundServiceStarts;
        mOriginatingToken = originatingToken;
    }

    /**
     * Return a token that allows background activity starts and attributes it to a specific
     * originatingToken.
     */
    public static BackgroundStartPrivileges allowBackgroundActivityStarts(
            @Nullable IBinder originatingToken) {
        if (originatingToken == null) {
            // try to avoid creating new instances
            return ALLOW_BAL;
        }
        return new BackgroundStartPrivileges(true, true, originatingToken);
    }

    /**
     * Merge this {@link BackgroundStartPrivileges} with another {@link BackgroundStartPrivileges}.
     *
     * The resulting object will grant the union of the privileges of the merged objects.
     * The originating tokens is retained only if both {@link BackgroundStartPrivileges} are the
     * same.
     *
     * If one of the merged objects is {@link #NONE} then the other object is returned and the
     * originating token is NOT cleared.
     */
    public @NonNull BackgroundStartPrivileges merge(@Nullable BackgroundStartPrivileges other) {
        // shortcuts in case
        if (other == NONE || other == null) {
            return this;
        }
        if (this == NONE) {
            return other;
        }

        boolean allowsBackgroundActivityStarts =
                this.allowsBackgroundActivityStarts() || other.allowsBackgroundActivityStarts();
        boolean allowsBackgroundFgsStarts =
                this.allowsBackgroundFgsStarts() || other.allowsBackgroundFgsStarts();
        if (this.mOriginatingToken == other.mOriginatingToken) {
            // can reuse this?
            if (this.mAllowsBackgroundActivityStarts == allowsBackgroundActivityStarts
                    && this.mAllowsBackgroundForegroundServiceStarts == allowsBackgroundFgsStarts) {
                return this;
            }
            // can reuse other?
            if (other.mAllowsBackgroundActivityStarts == allowsBackgroundActivityStarts
                   && other.mAllowsBackgroundForegroundServiceStarts == allowsBackgroundFgsStarts) {
                return other;
            }
            // need to create a new instance (this should never happen)
            return new BackgroundStartPrivileges(allowsBackgroundActivityStarts,
                    allowsBackgroundFgsStarts, this.mOriginatingToken);
        } else {
            // no originating token -> can use standard instance
            if (allowsBackgroundActivityStarts) {
                return ALLOW_BAL;
            } else if (allowsBackgroundFgsStarts) {
                return ALLOW_FGS;
            } else {
                return NONE;
            }
        }
    }

    /**
     * Merge a collection of {@link BackgroundStartPrivileges} into a single token.
     *
     * The resulting object will grant the union of the privileges of the merged objects.
     * The originating tokens is retained only if all {@link BackgroundStartPrivileges} are the
     * same.
     *
     * If the list contains {@link #NONE}s these are ignored.
     */
    public static @NonNull BackgroundStartPrivileges merge(
            @Nullable List<BackgroundStartPrivileges> list) {
        if (list == null || list.isEmpty()) {
            return NONE;
        }
        BackgroundStartPrivileges current = list.get(0);
        for (int i = list.size(); i-- > 1; ) {
            current = current.merge(list.get(i));
        }
        return current;
    }

    /**
     * @return {@code true} if this grants the permission to start background activities from the
     * background.
     */
    public boolean allowsBackgroundActivityStarts() {
        return mAllowsBackgroundActivityStarts;
    }

    /**
     * @return {@code true} this grants the permission to start foreground services from the
     * background. */
    public boolean allowsBackgroundFgsStarts() {
        return mAllowsBackgroundForegroundServiceStarts;
    }

    /** @return true if this grants any privileges. */
    public boolean allowsAny() {
        return mAllowsBackgroundActivityStarts || mAllowsBackgroundForegroundServiceStarts;
    }

    /** Return true if this grants no privileges. */
    public boolean allowsNothing() {
        return !allowsAny();
    }

    /**
     * Gets the originating token.
     *
     * The originating token is optional information that allows to trace back the origin of this
     * object. Besides debugging, this is used to e.g. identify privileges created by the
     * notification service.
     */
    public @Nullable IBinder getOriginatingToken() {
        return mOriginatingToken;
    }

    @Override
    public String toString() {
        return "BackgroundStartPrivileges["
                + "allowsBackgroundActivityStarts=" + mAllowsBackgroundActivityStarts
                + ", allowsBackgroundForegroundServiceStarts="
                + mAllowsBackgroundForegroundServiceStarts
                + ", originatingToken=" + mOriginatingToken
                + ']';
    }
}
