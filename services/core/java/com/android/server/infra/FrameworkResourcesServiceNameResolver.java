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

import android.annotation.ArrayRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    @NonNull
    private final Context mContext;
    @NonNull
    private final Object mLock = new Object();
    @StringRes
    private final int mStringResourceId;
    @ArrayRes
    private final int mArrayResourceId;
    private final boolean mIsMultiple;
    /**
     * Map of temporary service name list set by {@link #setTemporaryServices(int, String[], int)},
     * keyed by {@code userId}.
     *
     * <p>Typically used by Shell command and/or CTS tests to configure temporary services if
     * mIsMultiple is true.
     */
    @GuardedBy("mLock")
    private final SparseArray<String[]> mTemporaryServiceNamesList = new SparseArray<>();
    /**
     * Map of default services that have been disabled by
     * {@link #setDefaultServiceEnabled(int, boolean)},keyed by {@code userId}.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     */
    @GuardedBy("mLock")
    private final SparseBooleanArray mDefaultServicesDisabled = new SparseBooleanArray();
    @Nullable
    private NameResolverListener mOnSetCallback;
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
        mStringResourceId = resourceId;
        mArrayResourceId = -1;
        mIsMultiple = false;
    }

    public FrameworkResourcesServiceNameResolver(@NonNull Context context,
            @ArrayRes int resourceId, boolean isMultiple) {
        if (!isMultiple) {
            throw new UnsupportedOperationException("Please use "
                    + "FrameworkResourcesServiceNameResolver(context, @StringRes int) constructor "
                    + "if single service mode is requested.");
        }
        mContext = context;
        mStringResourceId = -1;
        mArrayResourceId = resourceId;
        mIsMultiple = true;
    }

    @Override
    public void setOnTemporaryServiceNameChangedCallback(@NonNull NameResolverListener callback) {
        synchronized (mLock) {
            this.mOnSetCallback = callback;
        }
    }

    @Override
    public String getServiceName(@UserIdInt int userId) {
        String[] serviceNames = getServiceNameList(userId);
        return (serviceNames == null || serviceNames.length == 0) ? null : serviceNames[0];
    }

    @Override
    public String getDefaultServiceName(@UserIdInt int userId) {
        String[] serviceNames = getDefaultServiceNameList(userId);
        return (serviceNames == null || serviceNames.length == 0) ? null : serviceNames[0];
    }

    /**
     * Gets the default list of the service names for the given user.
     *
     * <p>Typically implemented by services which want to provide multiple backends.
     */
    @Override
    public String[] getServiceNameList(int userId) {
        synchronized (mLock) {
            String[] temporaryNames = mTemporaryServiceNamesList.get(userId);
            if (temporaryNames != null) {
                // Always log it, as it should only be used on CTS or during development
                Slog.w(TAG, "getServiceName(): using temporary name "
                        + Arrays.toString(temporaryNames) + " for user " + userId);
                return temporaryNames;
            }
            final boolean disabled = mDefaultServicesDisabled.get(userId);
            if (disabled) {
                // Always log it, as it should only be used on CTS or during development
                Slog.w(TAG, "getServiceName(): temporary name not set and default disabled for "
                        + "user " + userId);
                return null;
            }
            return getDefaultServiceNameList(userId);

        }
    }

    /**
     * Gets the default list of the service names for the given user.
     *
     * <p>Typically implemented by services which want to provide multiple backends.
     */
    @Override
    public String[] getDefaultServiceNameList(int userId) {
        synchronized (mLock) {
            if (mIsMultiple) {
                String[] serviceNameList = mContext.getResources().getStringArray(mArrayResourceId);
                // Filter out unimplemented services
                // Initialize the validated array as null because we do not know the final size.
                List<String> validatedServiceNameList = new ArrayList<>();
                try {
                    for (int i = 0; i < serviceNameList.length; i++) {
                        if (TextUtils.isEmpty(serviceNameList[i])) {
                            continue;
                        }
                        ComponentName serviceComponent = ComponentName.unflattenFromString(
                                serviceNameList[i]);
                        ServiceInfo serviceInfo = AppGlobals.getPackageManager().getServiceInfo(
                                serviceComponent,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
                        if (serviceInfo != null) {
                            validatedServiceNameList.add(serviceNameList[i]);
                        }
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Could not validate provided services.", e);
                }
                String[] validatedServiceNameArray = new String[validatedServiceNameList.size()];
                return validatedServiceNameList.toArray(validatedServiceNameArray);
            } else {
                final String name = mContext.getString(mStringResourceId);
                return TextUtils.isEmpty(name) ? new String[0] : new String[]{name};
            }
        }
    }

    @Override
    public boolean isConfiguredInMultipleMode() {
        return mIsMultiple;
    }

    @Override
    public boolean isTemporary(@UserIdInt int userId) {
        synchronized (mLock) {
            return mTemporaryServiceNamesList.get(userId) != null;
        }
    }

    @Override
    public void setTemporaryService(@UserIdInt int userId, @NonNull String componentName,
            int durationMs) {
        setTemporaryServices(userId, new String[]{componentName}, durationMs);
    }

    @Override
    public void setTemporaryServices(int userId, @NonNull String[] componentNames, int durationMs) {
        synchronized (mLock) {
            mTemporaryServiceNamesList.put(userId, componentNames);

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
            for (int i = 0; i < componentNames.length; i++) {
                notifyTemporaryServiceNameChangedLocked(userId, componentNames[i],
                        /* isTemporary= */ true);
            }
        }
    }

    @Override
    public void resetTemporaryService(@UserIdInt int userId) {
        synchronized (mLock) {
            Slog.i(TAG, "resetting temporary service for user " + userId + " from "
                    + Arrays.toString(mTemporaryServiceNamesList.get(userId)));
            mTemporaryServiceNamesList.remove(userId);
            if (mTemporaryHandler != null) {
                mTemporaryHandler.removeMessages(MSG_RESET_TEMPORARY_SERVICE);
                mTemporaryHandler = null;
            }
            notifyTemporaryServiceNameChangedLocked(userId, /* newTemporaryName= */ null,
                    /* isTemporary= */ false);
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
        synchronized (mLock) {
            return "FrameworkResourcesServiceNamer[temps=" + mTemporaryServiceNamesList + "]";
        }
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw) {
        synchronized (mLock) {
            pw.print("FrameworkResourcesServiceNamer: resId=");
            pw.print(mStringResourceId);
            pw.print(", numberTemps=");
            pw.print(mTemporaryServiceNamesList.size());
            pw.print(", enabledDefaults=");
            pw.print(mDefaultServicesDisabled.size());
        }
    }

    // TODO(b/117779333): support proto
    @Override
    public void dumpShort(@NonNull PrintWriter pw, @UserIdInt int userId) {
        synchronized (mLock) {
            final String[] temporaryNames = mTemporaryServiceNamesList.get(userId);
            if (temporaryNames != null) {
                pw.print("tmpName=");
                pw.print(Arrays.toString(temporaryNames));
                final long ttl = mTemporaryServiceExpiration - SystemClock.elapsedRealtime();
                pw.print(" (expires in ");
                TimeUtils.formatDuration(ttl, pw);
                pw.print("), ");
            }
            pw.print("defaultName=");
            pw.print(getDefaultServiceName(userId));
            final boolean disabled = mDefaultServicesDisabled.get(userId);
            pw.println(disabled ? " (disabled)" : " (enabled)");
        }
    }

    private void notifyTemporaryServiceNameChangedLocked(@UserIdInt int userId,
            @Nullable String newTemporaryName, boolean isTemporary) {
        if (mOnSetCallback != null) {
            mOnSetCallback.onNameResolved(userId, newTemporaryName, isTemporary);
        }
    }
}
