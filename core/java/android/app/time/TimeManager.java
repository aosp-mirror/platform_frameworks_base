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
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timezonedetector.ITimeZoneDetectorService;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
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
@SystemService(Context.TIME_MANAGER_SERVICE)
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
     * <p>The ability to modify configuration settings can be subject to restrictions. For
     * example, they may be determined by device hardware, general policy (i.e. only the primary
     * user can set them), or by a managed device policy. Use {@link
     * #getTimeCapabilitiesAndConfig()} to obtain information at runtime about the user's
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

    /**
     * Returns a snapshot of the device's current system clock time state. See also {@link
     * #confirmTime(UnixEpochTime)} for how this information can be used.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    @NonNull
    public TimeState getTimeState() {
        if (DEBUG) {
            Log.d(TAG, "getTimeState called");
        }
        try {
            return mITimeDetectorService.getTimeState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Confirms the device's current time during device setup, raising the system's confidence in
     * the time if needed. Unlike {@link #setManualTime(UnixEpochTime)}, which can only be used when
     * automatic time detection is currently disabled, this method can be used regardless of the
     * automatic time detection setting, but only to confirm the current time (which may have been
     * set via automatic means). Use {@link #getTimeState()} to obtain the time state to confirm.
     *
     * <p>Returns {@code false} if the confirmation is invalid, i.e. if the time being
     * confirmed is no longer the time the device is currently set to. Confirming a time
     * in which the system already has high confidence will return {@code true}.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean confirmTime(@NonNull UnixEpochTime unixEpochTime) {
        if (DEBUG) {
            Log.d(TAG, "confirmTime called: " + unixEpochTime);
        }
        try {
            return mITimeDetectorService.confirmTime(unixEpochTime);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempts to set the device's time, expected to be determined from the user's manually entered
     * information.
     *
     * <p>Returns {@code false} if the time is invalid, or the device configuration / user
     * capabilities prevents the time being accepted, e.g. if the device is currently set to
     * "automatic time detection". This method returns {@code true} if the time was accepted even
     * if it is the same as the current device time.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean setManualTime(@NonNull UnixEpochTime unixEpochTime) {
        if (DEBUG) {
            Log.d(TAG, "setTime called: " + unixEpochTime);
        }
        try {
            ManualTimeSuggestion manualTimeSuggestion = new ManualTimeSuggestion(unixEpochTime);
            manualTimeSuggestion.addDebugInfo("TimeManager.setTime()");
            manualTimeSuggestion.addDebugInfo("UID: " + android.os.Process.myUid());
            manualTimeSuggestion.addDebugInfo("UserHandle: " + android.os.Process.myUserHandle());
            manualTimeSuggestion.addDebugInfo("Process: " + android.os.Process.myProcessName());
            return mITimeDetectorService.setManualTime(manualTimeSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a snapshot of the device's current time zone state. See also {@link
     * #confirmTimeZone(String)} and {@link #setManualTimeZone(String)} for how this information may
     * be used.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    @NonNull
    public TimeZoneState getTimeZoneState() {
        if (DEBUG) {
            Log.d(TAG, "getTimeZoneState called");
        }
        try {
            return mITimeZoneDetectorService.getTimeZoneState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Confirms the device's current time zone ID, raising the system's confidence in the time zone
     * if needed. Unlike {@link #setManualTimeZone(String)}, which can only be used when automatic
     * time zone detection is currently disabled, this method can be used regardless of the
     * automatic time zone detection setting, but only to confirm the current value (which may have
     * been set via automatic means).
     *
     * <p>Returns {@code false} if the confirmation is invalid, i.e. if the time zone ID being
     * confirmed is no longer the time zone ID the device is currently set to. Confirming a time
     * zone ID in which the system already has high confidence returns {@code true}.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean confirmTimeZone(@NonNull String timeZoneId) {
        if (DEBUG) {
            Log.d(TAG, "confirmTimeZone called: " + timeZoneId);
        }
        try {
            return mITimeZoneDetectorService.confirmTimeZone(timeZoneId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Attempts to set the device's time zone, expected to be determined from a user's manually
     * entered information.
     *
     * <p>Returns {@code false} if the time zone is invalid, or the device configuration / user
     * capabilities prevents the time zone being accepted, e.g. if the device is currently set to
     * "automatic time zone detection". {@code true} is returned if the time zone is accepted. A
     * time zone that is accepted and matches the current device time zone returns {@code true}.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION)
    public boolean setManualTimeZone(@NonNull String timeZoneId) {
        if (DEBUG) {
            Log.d(TAG, "setManualTimeZone called: " + timeZoneId);
        }
        try {
            ManualTimeZoneSuggestion manualTimeZoneSuggestion =
                    new ManualTimeZoneSuggestion(timeZoneId);
            manualTimeZoneSuggestion.addDebugInfo("TimeManager.setManualTimeZone()");
            manualTimeZoneSuggestion.addDebugInfo("UID: " + android.os.Process.myUid());
            manualTimeZoneSuggestion.addDebugInfo("Process: " + android.os.Process.myProcessName());
            return mITimeZoneDetectorService.setManualTimeZone(manualTimeZoneSuggestion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
