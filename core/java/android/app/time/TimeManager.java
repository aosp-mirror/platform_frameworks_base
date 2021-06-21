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

package android.app.time;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.timedetector.ITimeDetectorService;
import android.app.timezonedetector.ITimeZoneDetectorService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.Executor;

/**
 * The interface through which system components can interact with time and time zone services.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.TIME_MANAGER)
public final class TimeManager {
    private static final String TAG = "time.TimeManager";
    private static final boolean DEBUG = false;

    private final Object mLock = new Object();
    private final ITimeZoneDetectorService mITimeZoneDetectorService;
    private final ITimeDetectorService mITimeDetectorService;

    @GuardedBy("mLock")
    private ITimeZoneDetectorListener mTimeZoneDetectorReceiver;

    /**
     * The registered listeners. The key is the actual listener that was registered, the value is a
     * wrapper that ensures the listener is executed on the correct Executor.
     */
    @GuardedBy("mLock")
    private ArrayMap<TimeZoneDetectorListener, TimeZoneDetectorListener> mTimeZoneDetectorListeners;

    /** @hide */
    public TimeManager() throws ServiceNotFoundException {
        // TimeManager is an API over one or possibly more services. At least until there's an
        // internal refactoring.
        mITimeZoneDetectorService = ITimeZoneDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_ZONE_DETECTOR_SERVICE));
        mITimeDetectorService = ITimeDetectorService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TIME_DETECTOR_SERVICE));
    }

    /**
     * Returns the calling user's time zone capabilities and configuration.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    @NonNull
    public TimeZoneCapabilitiesAndConfig getTimeZoneCapabilitiesAndConfig() {
        if (DEBUG) {
            Log.d(TAG, "getTimeZoneCapabilitiesAndConfig called");
        }
        try {
            return mITimeZoneDetectorService.getCapabilitiesAndConfig();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the calling user's time capabilities and configuration.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    @NonNull
    public TimeCapabilitiesAndConfig getTimeCapabilitiesAndConfig() {
        if (DEBUG) {
            Log.d(TAG, "getTimeCapabilitiesAndConfig called");
        }
        try {
            return mITimeDetectorService.getCapabilitiesAndConfig();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Modifies the time detection configuration.
     *
     * @return {@code true} if all the configuration settings specified have been set to the
     * new values, {@code false} if none have
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean updateTimeConfiguration(@NonNull TimeConfiguration configuration) {
        if (DEBUG) {
            Log.d(TAG, "updateTimeConfiguration called: " + configuration);
        }
        try {
            return mITimeDetectorService.updateConfiguration(configuration);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Modifies the time zone detection configuration.
     *
     * <p>Configuration settings vary in scope: some may be global (affect all users), others may be
     * specific to the current user.
     *
     * <p>The ability to modify configuration settings can be subject to restrictions. For
     * example, they may be determined by device hardware, general policy (i.e. only the primary
     * user can set them), or by a managed device policy. Use {@link
     * #getTimeZoneCapabilitiesAndConfig()} to obtain information at runtime about the user's
     * capabilities.
     *
     * <p>Attempts to modify configuration settings with capabilities that are {@link
     * Capabilities#CAPABILITY_NOT_SUPPORTED} or {@link
     * Capabilities#CAPABILITY_NOT_ALLOWED} will have no effect and a {@code false}
     * will be returned. Modifying configuration settings with capabilities that are {@link
     * Capabilities#CAPABILITY_NOT_APPLICABLE} or {@link
     * Capabilities#CAPABILITY_POSSESSED} will succeed. See {@link
     * TimeZoneCapabilities} for further details.
     *
     * <p>If the supplied configuration only has some values set, then only the specified settings
     * will be updated (where the user's capabilities allow) and other settings will be left
     * unchanged.
     *
     * @return {@code true} if all the configuration settings specified have been set to the
     *   new values, {@code false} if none have
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean updateTimeZoneConfiguration(@NonNull TimeZoneConfiguration configuration) {
        if (DEBUG) {
            Log.d(TAG, "updateTimeZoneConfiguration called: " + configuration);
        }
        try {
            return mITimeZoneDetectorService.updateConfiguration(configuration);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * An interface that can be used to listen for changes to the time zone detector behavior.
     */
    @FunctionalInterface
    public interface TimeZoneDetectorListener {
        /**
         * Called when something about the time zone detector behavior on the device has changed.
         * For example, this could be because the current user has switched, one of the global or
         * user's settings been changed, or something that could affect a user's capabilities with
         * respect to the time zone detector has changed. Because different users can have different
         * configuration and capabilities, this method may be called when nothing has changed for
         * the receiving user.
         */
        void onChange();
    }

    /**
     * Registers a listener that will be informed when something about the time zone detector
     * behavior changes.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public void addTimeZoneDetectorListener(@NonNull Executor executor,
            @NonNull TimeZoneDetectorListener listener) {

        if (DEBUG) {
            Log.d(TAG, "addTimeZoneDetectorListener called: " + listener);
        }
        synchronized (mLock) {
            if (mTimeZoneDetectorListeners == null) {
                mTimeZoneDetectorListeners = new ArrayMap<>();
            } else if (mTimeZoneDetectorListeners.containsKey(listener)) {
                return;
            }

            if (mTimeZoneDetectorReceiver == null) {
                ITimeZoneDetectorListener iListener = new ITimeZoneDetectorListener.Stub() {
                    @Override
                    public void onChange() {
                        notifyTimeZoneDetectorListeners();
                    }
                };
                mTimeZoneDetectorReceiver = iListener;
                try {
                    mITimeZoneDetectorService.addListener(mTimeZoneDetectorReceiver);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mTimeZoneDetectorListeners.put(listener, () -> executor.execute(listener::onChange));
        }
    }

    private void notifyTimeZoneDetectorListeners() {
        ArrayMap<TimeZoneDetectorListener, TimeZoneDetectorListener> timeZoneDetectorListeners;
        synchronized (mLock) {
            if (mTimeZoneDetectorListeners == null || mTimeZoneDetectorListeners.isEmpty()) {
                return;
            }
            timeZoneDetectorListeners = new ArrayMap<>(mTimeZoneDetectorListeners);
        }
        int size = timeZoneDetectorListeners.size();
        for (int i = 0; i < size; i++) {
            timeZoneDetectorListeners.valueAt(i).onChange();
        }
    }

    /**
     * Removes a listener previously passed to
     * {@link #addTimeZoneDetectorListener(Executor, TimeZoneDetectorListener)}
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public void removeTimeZoneDetectorListener(@NonNull TimeZoneDetectorListener listener) {
        if (DEBUG) {
            Log.d(TAG, "removeConfigurationListener called: " + listener);
        }

        synchronized (mLock) {
            if (mTimeZoneDetectorListeners == null || mTimeZoneDetectorListeners.isEmpty()) {
                return;
            }
            mTimeZoneDetectorListeners.remove(listener);

            // If the last local listener has been removed, remove and discard the
            // mTimeZoneDetectorReceiver.
            if (mTimeZoneDetectorListeners.isEmpty()) {
                try {
                    mITimeZoneDetectorService.removeListener(mTimeZoneDetectorReceiver);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                } finally {
                    mTimeZoneDetectorReceiver = null;
                }
            }
        }
    }

    /**
     * Suggests the current time from an external time source. For example, a form factor-specific
     * HAL. This time <em>may</em> be used to set the device system clock, depending on the device
     * configuration and user settings. This method call is processed asynchronously.
     * See {@link ExternalTimeSuggestion} for more details.
     */
    @RequiresPermission(android.Manifest.permission.SUGGEST_EXTERNAL_TIME)
    public void suggestExternalTime(@NonNull ExternalTimeSuggestion timeSuggestion) {
        if (DEBUG) {
            Log.d(TAG, "suggestExternalTime called: " + timeSuggestion);
        }
        try {
            mITimeDetectorService.suggestExternalTime(timeSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
