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
 * limitations under the License.
 */
package com.android.server.infra;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Gets the service name using a framework resources, temporarily changing the service if necessary
 * (typically during CTS tests or service development).
 *
 * @hide
 */
public final class FrameworkResourcesServiceNameResolver implements ServiceNameResolver {

    private static final String TAG = FrameworkResourcesServiceNameResolver.class.getSimpleName();

    /** Handler message to {@link #resetTemporaryService(int)} */
    private static final int MSG_RESET_TEMPORARY_SERVICE = 0;

    private final @NonNull Context mContext;
    private final @NonNull Object mLock = new Object();
    private final @StringRes int mResourceId;
    private @Nullable NameResolverListener mOnSetCallback;

    /**
     * Map of temporary service name set by {@link #setTemporaryService(int, String, int)},
     * keyed by {@code userId}.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     */
    @GuardedBy("mLock")
    private final SparseArray<String> mTemporaryServiceNames = new SparseArray<>();

    /**
     * Map of default services that have been disabled by
     * {@link #setDefaultServiceEnabled(int, boolean)},keyed by {@code userId}.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mDefaultServicesDisabled = new SparseBooleanArray();

    /**
     * When the temporary service will expire (and reset back to the default).
     */
    @GuardedBy("mLock")
    private long mTemporaryServiceExpiration;

    /**
     * Handler used to reset the temporary service name.
     */
    @GuardedBy("mLock")
    private Handler mTemporaryHandler;

    public FrameworkResourcesServiceNameResolver(@NonNull Context context,
            @StringRes int resourceId) {
        mContext = context;
        mResourceId = resourceId;
    }

    @Override
    public void setOnTemporaryServiceNameChangedCallback(@NonNull NameResolverListener callback) {
        synchronized (mLock) {
            this.mOnSetCallback = callback;
        }
    }

    @Override
    public String getDefaultServiceName(@UserIdInt int userId) {
        synchronized (mLock) {
            final String name = mContext.getString(mResourceId);
            return TextUtils.isEmpty(name) ? null : name;
        }
    }

    @Override
    public String getServiceName(@UserIdInt int userId) {
        synchronized (mLock) {
            final String temporaryName = mTemporaryServiceNames.get(userId);
            if (temporaryName != null) {
                // Always log it, as it should only be used on CTS or during development
                Slog.w(TAG, "getServiceName(): using temporary name " + temporaryName
                        + " for user " + userId);
                return temporaryName;
            }
            final boolean disabled = mDefaultServicesDisabled.get(userId);
            if (disabled) {
                // Always log it, as it should only be used on CTS or during development
                Slog.w(TAG, "getServiceName(): temporary name not set and default disabled for "
                        + "user " + userId);
                return null;
            }
            return getDefaultServiceName(userId);
        }
    }

    @Override
    public boolean isTemporary(@UserIdInt int userId) {
        synchronized (mLock) {
            return mTemporaryServiceNames.get(userId) != null;
        }
    }

    @Override
    public void setTemporaryService(@UserIdInt int userId, @NonNull String componentName,
            int durationMs) {
        synchronized (mLock) {
            mTemporaryServiceNames.put(userId, componentName);

            if (mTemporaryHandler == null) {
                mTemporaryHandler = new Handler(Looper.getMainLooper(), null, true) {
                    @Override
                    public void handleMessage(Message msg) {
                        if (msg.what == MSG_RESET_TEMPORARY_SERVICE) {
                            synchronized (mLock) {
                                resetTemporaryService(userId);
                            }
                        } else {
                            Slog.wtf(TAG, "invalid handler msg: " + msg);
                        }
                    }
                };
            } else {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_SERVICE);
            }
            mTemporaryServiceExpiration = SystemClock.elapsedRealtime() + durationMs;
            mTemporaryHandler.sendEmptyMessageDelayed(MSG_RESET_TEMPORARY_SERVICE, durationMs);
            notifyTemporaryServiceNameChangedLocked(userId, componentName);
        }
    }

    @Override
    public void resetTemporaryService(@UserIdInt int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "resetting temporary service for user " + userId + " from "
                    + mTemporaryServiceNames.get(userId));
            mTemporaryServiceNames.remove(userId);
            if (mTemporaryHandler != null) {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_SERVICE);
                mTemporaryHandler = null;
            }
            notifyTemporaryServiceNameChangedLocked(userId, /* newTemporaryName= */ null);
        }
    }

    @Override
    public boolean setDefaultServiceEnabled(int userId, boolean enabled) {
        synchronized (mLock) {
            final boolean currentlyEnabled = isDefaultServiceEnabledLocked(userId);
            if (currentlyEnabled == enabled) {
                Slog.i(TAG, "setDefaultServiceEnabled(" + userId + "): already " + enabled);
                return false;
            }
            if (enabled) {
                Slog.i(TAG, "disabling default service for user " + userId);
                mDefaultServicesDisabled.removeAt(userId);
            } else {
                Slog.i(TAG, "enabling default service for user " + userId);
                mDefaultServicesDisabled.put(userId, true);
            }
        }
        return true;
    }

    @Override
    public boolean isDefaultServiceEnabled(int userId) {
        synchronized (mLock) {
            return isDefaultServiceEnabledLocked(userId);
        }
    }

    private boolean isDefaultServiceEnabledLocked(int userId) {
        return !mDefaultServicesDisabled.get(userId);
    }

    @Override
    public String toString() {
        return "FrameworkResourcesServiceNamer[temps=" + mTemporaryServiceNames + "]";
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw) {
        synchronized (mLock) {
            pw.print("FrameworkResourcesServiceNamer: resId="); pw.print(mResourceId);
            pw.print(", numberTemps="); pw.print(mTemporaryServiceNames.size());
            pw.print(", enabledDefaults="); pw.print(mDefaultServicesDisabled.size());
        }
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw, @UserIdInt int userId) {
        synchronized (mLock) {
            final String temporaryName = mTemporaryServiceNames.get(userId);
            if (temporaryName != null) {
                pw.print("tmpName="); pw.print(temporaryName);
                final long ttl = mTemporaryServiceExpiration - SystemClock.elapsedRealtime();
                pw.print(" (expires in "); TimeUtils.formatDuration(ttl, pw); pw.print("), ");
            }
            pw.print("defaultName="); pw.print(getDefaultServiceName(userId));
            final boolean disabled = mDefaultServicesDisabled.get(userId);
            pw.println(disabled ? " (disabled)" : " (enabled)");
        }
    }

    private void notifyTemporaryServiceNameChangedLocked(@UserIdInt int userId,
            @Nullable String newTemporaryName) {
        if (mOnSetCallback != null) {
            mOnSetCallback.onNameResolved(userId, newTemporaryName);
        }
    }
}
