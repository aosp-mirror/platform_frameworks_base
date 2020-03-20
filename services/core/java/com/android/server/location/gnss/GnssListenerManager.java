/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.gnss;

import android.annotation.Nullable;
import android.location.LocationManagerInternal;
import android.location.util.identity.CallerIdentity;
import android.location.util.listeners.AbstractListenerManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.location.AppForegroundHelper;
import com.android.server.location.AppOpsHelper;
import com.android.server.location.SettingsHelper;
import com.android.server.location.UserInfoHelper;
import com.android.server.location.util.listeners.IdentifiedRegistration;

import java.util.Objects;

/**
 * Manager for all GNSS related listeners. This class handles deactivating listeners that do not
 * belong to the current user, that do not have the appropriate permissions, or that are not
 * currently in the foreground. It will also disable listeners if the GNSS provider is disabled.
 * Listeners must be registered with the associated IBinder as the key, if the IBinder dies, the
 * registration will automatically be removed.
 *
 * @param <TRequest>       request type
 * @param <TListener>      listener type
 * @param <TMergedRequest> merged request type
 */
public abstract class GnssListenerManager<TRequest, TListener extends IInterface, TMergedRequest>
        extends AbstractListenerManager<IBinder, TRequest, TListener, GnssListenerManager<TRequest,
        TListener, TMergedRequest>.GnssRegistration, TMergedRequest> {

    /**
     * Registration object for GNSS listeners.
     */
    protected class GnssRegistration extends
            IdentifiedRegistration<TRequest, TListener> implements Binder.DeathRecipient {

        @Nullable private volatile IBinder mKey;

        @GuardedBy("this")
        private boolean mAppOpsAllowed;

        @GuardedBy("this")
        private boolean mForeground;

        @GuardedBy("this")
        private boolean mActive;

        protected GnssRegistration(@Nullable TRequest request, CallerIdentity callerIdentity,
                TListener listener) {
            super(request, callerIdentity, listener);
            mKey = listener.asBinder();
        }

        /**
         * Returns true if this registration is currently in the foreground.
         */
        public synchronized boolean isForeground() {
            return mForeground;
        }

        synchronized boolean isActive() {
            return mActive;
        }

        @Override
        protected final boolean onRegister() {
            try {
                Objects.requireNonNull(mKey).linkToDeath(this, 0);
            } catch (RemoteException e) {
                mKey = null;
                return false;
            }

            mAppOpsAllowed = mAppOpsHelper.checkLocationAccess(getIdentity());
            mForeground = mAppForegroundHelper.isAppForeground(getIdentity().uid);
            onActiveChanged();

            return true;
        }

        @Override
        protected void onUnregister() {
            IBinder key = mKey;
            if (key != null) {
                mKey = null;
                key.unlinkToDeath(this, 0);
            }
        }

        boolean onUserChanged(int userId) {
            return getIdentity().userId == userId;
        }

        boolean onLocationEnabledChanged(int userId) {
            if (userId == getIdentity().userId) {
                return onActiveChanged();
            }

            return false;
        }

        boolean onBackgroundThrottlePackageWhitelistChanged() {
            return onActiveChanged();
        }

        boolean onAppOpsChanged(String packageName) {
            if (getIdentity().packageName.equals(packageName)) {
                boolean appOpsAllowed = mAppOpsHelper.checkLocationAccess(getIdentity());
                synchronized (this) {
                    if (appOpsAllowed != mAppOpsAllowed) {
                        mAppOpsAllowed = appOpsAllowed;
                        return onActiveChanged();
                    }
                }
            }

            return false;
        }

        boolean onForegroundChanged(int uid, boolean foreground) {
            if (getIdentity().uid == uid) {
                synchronized (this) {
                    if (foreground != mForeground) {
                        mForeground = foreground;
                        return onActiveChanged();
                    }
                }
            }

            return false;
        }

        private boolean onActiveChanged() {
            synchronized (this) {
                // TODO: we should be checking if the gps provider is enabled, not location
                boolean active = mAppOpsAllowed
                        && (mForeground || isBackgroundRestrictionExempt(getIdentity()))
                        && mUserInfoHelper.isCurrentUserId(getIdentity().userId)
                        && mSettingsHelper.isLocationEnabled(getIdentity().userId);
                if (active != mActive) {
                    mActive = active;
                    return true;
                }
            }

            return false;
        }

        private boolean isBackgroundRestrictionExempt(CallerIdentity identity) {
            if (identity.uid == Process.SYSTEM_UID) {
                return true;
            }

            if (mSettingsHelper.getBackgroundThrottlePackageWhitelist().contains(
                    identity.packageName)) {
                return true;
            }

            return mLocationManagerInternal.isProvider(null, identity);
        }

        @Override
        public void binderDied() {
            IBinder key = mKey;
            if (key != null) {
                removeListener(key, this);
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(getIdentity());

            ArraySet<String> flags = new ArraySet<>(2);
            if (!mForeground) {
                flags.add("bg");
            }
            if (!mAppOpsAllowed) {
                flags.add("na");
            }
            if (!flags.isEmpty()) {
                builder.append(" ").append(flags);
            }

            if (getRequest() != null) {
                builder.append(" ").append(getRequest());
            }
            return builder.toString();
        }
    }

    protected final UserInfoHelper mUserInfoHelper;
    protected final SettingsHelper mSettingsHelper;
    protected final AppOpsHelper mAppOpsHelper;
    protected final AppForegroundHelper mAppForegroundHelper;
    protected final LocationManagerInternal mLocationManagerInternal;

    private final UserInfoHelper.UserListener mUserChangedListener = this::onUserChanged;
    private final SettingsHelper.UserSettingChangedListener mLocationEnabledChangedListener =
            this::onLocationEnabledChanged;
    private final SettingsHelper.GlobalSettingChangedListener
            mBackgroundThrottlePackageWhitelistChangedListener =
            this::onBackgroundThrottlePackageWhitelistChanged;
    private final AppOpsHelper.LocationAppOpListener mAppOpsChangedListener = this::onAppOpsChanged;
    private final AppForegroundHelper.AppForegroundListener mAppForegroundChangedListener =
            this::onAppForegroundChanged;

    protected GnssListenerManager(UserInfoHelper userInfoHelper, SettingsHelper settingsHelper,
            AppOpsHelper appOpsHelper, AppForegroundHelper appForegroundHelper) {
        mUserInfoHelper = userInfoHelper;
        mSettingsHelper = settingsHelper;
        mAppOpsHelper = appOpsHelper;
        mAppForegroundHelper = appForegroundHelper;
        mLocationManagerInternal = Objects.requireNonNull(
                LocalServices.getService(LocationManagerInternal.class));
    }

    /**
     * Adds a listener with the given identity.
     */
    protected void addListener(CallerIdentity identity, TListener listener) {
        addListener(null, identity, listener);
    }

    /**
     * Adds a listener with the given identity and request.
     */
    protected void addListener(TRequest request, CallerIdentity identity, TListener listener) {
        addRegistration(listener.asBinder(), new GnssRegistration(request, identity, listener));
    }

    /**
     * Removes the given listener.
     */
    public void removeListener(TListener listener) {
        removeRegistration(listener.asBinder());
    }

    void removeListener(IBinder key, GnssRegistration registration) {
        removeRegistration(key, registration);
    }

    @Override
    protected boolean isActive(GnssRegistration registration) {
        // we don't have an easy listener for provider enabled status changes available, so we
        // check it every time, which should be pretty cheap
        return registration.isActive();
    }

    @Override
    protected void onRegister() {
        mUserInfoHelper.addListener(mUserChangedListener);
        mSettingsHelper.addOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.addOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mAppOpsHelper.addListener(mAppOpsChangedListener);
        mAppForegroundHelper.addListener(mAppForegroundChangedListener);
    }

    @Override
    protected void onUnregister() {
        mUserInfoHelper.removeListener(mUserChangedListener);
        mSettingsHelper.removeOnLocationEnabledChangedListener(mLocationEnabledChangedListener);
        mSettingsHelper.removeOnBackgroundThrottlePackageWhitelistChangedListener(
                mBackgroundThrottlePackageWhitelistChangedListener);
        mAppOpsHelper.removeListener(mAppOpsChangedListener);
        mAppForegroundHelper.removeListener(mAppForegroundChangedListener);
    }

    private void onUserChanged(int userId, int change) {
        if (change == UserInfoHelper.UserListener.USER_SWITCHED) {
            updateRegistrations(registration -> registration.onUserChanged(userId));
        }
    }

    private void onLocationEnabledChanged(int userId) {
        updateRegistrations(registration -> registration.onLocationEnabledChanged(userId));
    }

    private void onBackgroundThrottlePackageWhitelistChanged() {
        updateRegistrations(GnssRegistration::onBackgroundThrottlePackageWhitelistChanged);
    }

    private void onAppOpsChanged(String packageName) {
        updateRegistrations(registration -> registration.onAppOpsChanged(packageName));
    }

    private void onAppForegroundChanged(int uid, boolean foreground) {
        updateRegistrations(registration -> registration.onForegroundChanged(uid, foreground));
    }

    /**
     * May be overridden by subclasses to provide extra debug information.
     */
    protected boolean isServiceSupported() {
        return true;
    }

    @Override
    protected String serviceStateToString() {
        if (!isServiceSupported()) {
            return "unsupported";
        }

        return super.serviceStateToString();
    }
}
