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

    /** Handler message to {@link #resetTemporaryServiceLocked()} */
    private static final int MSG_RESET_TEMPORARY_SERVICE = 0;

    private final @NonNull Context mContext;
    private final @NonNull @UserIdInt int mUserId;
    private final @NonNull Object mLock;
    private final @StringRes int mResourceId;
    private @Nullable Runnable mOnSetCallback;

    /**
     * Temporary service name set by {@link #setTemporaryServiceLocked(String, int)}.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     */
    @GuardedBy("mLock")
    @Nullable
    private String mTemporaryServiceName;

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

    public FrameworkResourcesServiceNameResolver(@NonNull Context context, @UserIdInt int userId,
            @NonNull Object lock, @StringRes int resourceId) {
        mLock = lock;
        mContext = context;
        mUserId = userId;
        mResourceId = resourceId;
    }

    @Override
    public void setOnTemporaryServiceNameChangedCallback(@NonNull Runnable callback) {
        this.mOnSetCallback = callback;
    }

    @Override
    public String getDefaultServiceName() {
        final String name = mContext.getString(mResourceId);
        return TextUtils.isEmpty(name) ? null : name;
    }

    @Override
    public String getServiceNameLocked() {
        if (mTemporaryServiceName != null) {
            // Always log it, as it should only be used on CTS or during development
            Slog.w(TAG, "getComponentName(): using temporary name " + mTemporaryServiceName);
            return mTemporaryServiceName;
        } else {
            return getDefaultServiceName();
        }
    }

    @Override
    public boolean isTemporaryLocked() {
        return mTemporaryServiceName != null;
    }

    @Override
    public void setTemporaryServiceLocked(@NonNull String componentName, int durationMs) {
        mTemporaryServiceName = componentName;

        if (mTemporaryHandler == null) {
            mTemporaryHandler = new Handler(Looper.getMainLooper(), null, true) {
                @Override
                public void handleMessage(Message msg) {
                    if (msg.what == MSG_RESET_TEMPORARY_SERVICE) {
                        synchronized (mLock) {
                            resetTemporaryServiceLocked();
                        }
                    } else {
                        Slog.wtf(TAG, "invalid handler msg: " + msg);
                    }
                }
            };
        } else {
            removeResetTemporaryServiceMessageLocked();
        }
        mTemporaryServiceExpiration = SystemClock.elapsedRealtime() + durationMs;
        mTemporaryHandler.sendEmptyMessageDelayed(MSG_RESET_TEMPORARY_SERVICE, durationMs);
        onServiceNameChanged();
    }

    @Override
    public void resetTemporaryServiceLocked() {
        Slog.i(TAG, "resetting temporary service from " + mTemporaryServiceName);
        mTemporaryServiceName = null;
        if (mTemporaryHandler != null) {
            removeResetTemporaryServiceMessageLocked();
            mTemporaryHandler = null;
        }
        onServiceNameChanged();
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShortLocked(@NonNull PrintWriter pw) {
        pw.print("FrameworkResourcesServiceNamer: resId="); pw.print(mResourceId);
        if (mTemporaryServiceName != null) {
            pw.print(", tmpName="); pw.print(mTemporaryServiceName);
            final long ttl = mTemporaryServiceExpiration - SystemClock.elapsedRealtime();
            pw.print(" (expires in "); TimeUtils.formatDuration(ttl, pw); pw.print(")");
            pw.print(", defaultName="); pw.println(getDefaultServiceName());
        } else {
            pw.print(", serviceName="); pw.println(getDefaultServiceName());
        }
    }

    private void onServiceNameChanged() {
        if (mOnSetCallback != null) {
            mOnSetCallback.run();
        }
    }

    private void removeResetTemporaryServiceMessageLocked() {
        // NOTE: caller should already have checked it
        mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_SERVICE);
    }
}
