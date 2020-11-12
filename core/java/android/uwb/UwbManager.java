/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * This class provides a way to perform Ultra Wideband (UWB) operations such as querying the
 * device's capabilities and determining the distance and angle between the local device and a
 * remote device.
 *
 * <p>To get a {@link UwbManager}, call the <code>Context.getSystemService(UwbManager.class)</code>.
 *
 * @hide
 */
@SystemService(Context.UWB_SERVICE)
public final class UwbManager {
    private IUwbAdapter mUwbAdapter;
    private static final String SERVICE_NAME = "uwb";

    private final AdapterStateListener mAdapterStateListener;
    private final RangingManager mRangingManager;

    /**
     * Interface for receiving UWB adapter state changes
     */
    public interface AdapterStateCallback {
        /**
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                STATE_CHANGED_REASON_SESSION_STARTED,
                STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED,
                STATE_CHANGED_REASON_SYSTEM_POLICY,
                STATE_CHANGED_REASON_SYSTEM_BOOT,
                STATE_CHANGED_REASON_ERROR_UNKNOWN})
        @interface StateChangedReason {}

        /**
         * Indicates that the state change was due to opening of first UWB session
         */
        int STATE_CHANGED_REASON_SESSION_STARTED = 0;

        /**
         * Indicates that the state change was due to closure of all UWB sessions
         */
        int STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED = 1;

        /**
         * Indicates that the state change was due to changes in system policy
         */
        int STATE_CHANGED_REASON_SYSTEM_POLICY = 2;

        /**
         * Indicates that the current state is due to a system boot
         */
        int STATE_CHANGED_REASON_SYSTEM_BOOT = 3;

        /**
         * Indicates that the state change was due to some unknown error
         */
        int STATE_CHANGED_REASON_ERROR_UNKNOWN = 4;

        /**
         * Invoked when underlying UWB adapter's state is changed
         * <p>Invoked with the adapter's current state after registering an
         * {@link AdapterStateCallback} using
         * {@link UwbManager#registerAdapterStateCallback(Executor, AdapterStateCallback)}.
         *
         * <p>Possible values for the state to change are
         * {@link #STATE_CHANGED_REASON_SESSION_STARTED},
         * {@link #STATE_CHANGED_REASON_ALL_SESSIONS_CLOSED},
         * {@link #STATE_CHANGED_REASON_SYSTEM_POLICY},
         * {@link #STATE_CHANGED_REASON_SYSTEM_BOOT},
         * {@link #STATE_CHANGED_REASON_ERROR_UNKNOWN}.
         *
         * @param isEnabled true when UWB adapter is enabled, false when it is disabled
         * @param reason the reason for the state change
         */
        void onStateChanged(boolean isEnabled, @StateChangedReason int reason);
    }

    /**
     * Use <code>Context.getSystemService(UwbManager.class)</code> to get an instance.
     *
     * @param adapter an instance of an {@link android.uwb.IUwbAdapter}
     */
    private UwbManager(IUwbAdapter adapter) {
        mUwbAdapter = adapter;
        mAdapterStateListener = new AdapterStateListener(adapter);
        mRangingManager = new RangingManager(adapter);
    }

    /**
     * @hide
     */
    public static UwbManager getInstance() {
        IBinder b = ServiceManager.getService(SERVICE_NAME);
        if (b == null) {
            return null;
        }

        IUwbAdapter adapter = IUwbAdapter.Stub.asInterface(b);
        if (adapter == null) {
            return null;
        }

        return new UwbManager(adapter);
    }

    /**
     * Register an {@link AdapterStateCallback} to listen for UWB adapter state changes
     * <p>The provided callback will be invoked by the given {@link Executor}.
     *
     * <p>When first registering a callback, the callbacks's
     * {@link AdapterStateCallback#onStateChanged(boolean, int)} is immediately invoked to indicate
     * the current state of the underlying UWB adapter with the most recent
     * {@link AdapterStateCallback.StateChangedReason} that caused the change.
     *
     * @param executor an {@link Executor} to execute given callback
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void registerAdapterStateCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull AdapterStateCallback callback) {
        mAdapterStateListener.register(executor, callback);
    }

    /**
     * Unregister the specified {@link AdapterStateCallback}
     * <p>The same {@link AdapterStateCallback} object used when calling
     * {@link #registerAdapterStateCallback(Executor, AdapterStateCallback)} must be used.
     *
     * <p>Callbacks are automatically unregistered when application process goes away
     *
     * @param callback user implementation of the {@link AdapterStateCallback}
     */
    public void unregisterAdapterStateCallback(@NonNull AdapterStateCallback callback) {
        mAdapterStateListener.unregister(callback);
    }

    /**
     * Get a {@link PersistableBundle} with the supported UWB protocols and parameters.
     * <p>The {@link PersistableBundle} should be parsed using a support library
     *
     * <p>Android reserves the '^android.*' namespace</p>
     *
     * @return {@link PersistableBundle} of the device's supported UWB protocols and parameters
     */
    @NonNull
    public PersistableBundle getSpecificationInfo() {
        try {
            return mUwbAdapter.getSpecificationInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if ranging is supported, regardless of ranging method
     *
     * @return true if ranging is supported
     */
    public boolean isRangingSupported() {
        try {
            return mUwbAdapter.isRangingSupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL,
            ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL})
    public @interface AngleOfArrivalSupportType {}

    /**
     * Indicate absence of support for angle of arrival measurement
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE = 1;

    /**
     * Indicate support for planar angle of arrival measurement, due to antenna
     * limitation. Typically requires at least two antennas.
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D = 2;

    /**
     * Indicate support for three dimensional angle of arrival measurement.
     * Typically requires at least three antennas. However, due to antenna
     * arrangement, a platform may only support hemi-spherical azimuth angles
     * ranging from -pi/2 to pi/2
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL = 3;

    /**
     * Indicate support for three dimensional angle of arrival measurement.
     * Typically requires at least three antennas. This mode supports full
     * azimuth angles ranging from -pi to pi.
     */
    public static final int ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL = 4;

    /**
     * Gets the {@link AngleOfArrivalSupportType} supported on this platform
     * <p>Possible return values are
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL},
     * {@link #ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL}.
     *
     * @return angle of arrival type supported
     */
    @AngleOfArrivalSupportType
    public int getAngleOfArrivalSupport() {
        try {
            switch (mUwbAdapter.getAngleOfArrivalSupport()) {
                case AngleOfArrivalSupport.TWO_DIMENSIONAL:
                    return ANGLE_OF_ARRIVAL_SUPPORT_TYPE_2D;

                case AngleOfArrivalSupport.THREE_DIMENSIONAL_HEMISPHERICAL:
                    return ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_HEMISPHERICAL;

                case AngleOfArrivalSupport.THREE_DIMENSIONAL_SPHERICAL:
                    return ANGLE_OF_ARRIVAL_SUPPORT_TYPE_3D_SPHERICAL;

                case AngleOfArrivalSupport.NONE:
                default:
                    return ANGLE_OF_ARRIVAL_SUPPORT_TYPE_NONE;
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a {@link List} of supported channel numbers based on the device's current location
     * <p>The returned values are ordered by the system's desired ordered of use, with the first
     * entry being the most preferred.
     *
     * <p>Channel numbers are defined based on the IEEE 802.15.4z standard for UWB.
     *
     * @return {@link List} of supported channel numbers ordered by preference
     */
    @NonNull
    public List<Integer> getSupportedChannelNumbers() {
        List<Integer> channels = new ArrayList<>();
        try {
            for (int channel : mUwbAdapter.getSupportedChannels()) {
                channels.add(channel);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return channels;
    }

    /**
     * Get a {@link List} of supported preamble code indices
     * <p> Preamble code indices are defined based on the IEEE 802.15.4z standard for UWB.
     *
     * @return {@link List} of supported preamble code indices
     */
    @NonNull
    public Set<Integer> getSupportedPreambleCodeIndices() {
        Set<Integer> preambles = new HashSet<>();
        try {
            for (int preamble : mUwbAdapter.getSupportedPreambleCodes()) {
                preambles.add(preamble);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return preambles;
    }

    /**
     * Get the timestamp resolution for events in nanoseconds
     * <p>This value defines the maximum error of all timestamps for events reported to
     * {@link RangingSession.Callback}.
     *
     * @return the timestamp resolution in nanoseconds
     */
    @SuppressLint("MethodNameUnits")
    public long elapsedRealtimeResolutionNanos() {
        try {
            return mUwbAdapter.getTimestampResolutionNanos();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the number of simultaneous sessions allowed in the system
     *
     * @return the maximum allowed number of simultaneously open {@link RangingSession} instances.
     */
    public int getMaxSimultaneousSessions() {
        try {
            return mUwbAdapter.getMaxSimultaneousSessions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the maximum number of remote devices in a {@link RangingSession} when the local device
     * is the initiator.
     *
     * @return the maximum number of remote devices per {@link RangingSession}
     */
    public int getMaxRemoteDevicesPerInitiatorSession() {
        try {
            return mUwbAdapter.getMaxRemoteDevicesPerInitiatorSession();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the maximum number of remote devices in a {@link RangingSession} when the local device
     * is a responder.
     *
     * @return the maximum number of remote devices per {@link RangingSession}
     */
    public int getMaxRemoteDevicesPerResponderSession() {
        try {
            return mUwbAdapter.getMaxRemoteDevicesPerResponderSession();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open a {@link RangingSession} with the given parameters
     * <p>This function is asynchronous and will return before ranging begins. The
     * {@link RangingSession.Callback#onOpenSuccess(RangingSession, PersistableBundle)} function is
     * called with a {@link RangingSession} object used to control ranging when the session is
     * successfully opened.
     *
     * <p>If a session cannot be opened, then {@link RangingSession.Callback#onClosed(int)} will be
     * invoked with the appropriate {@link RangingSession.Callback.CloseReason}.
     *
     * <p>An open {@link RangingSession} will be automatically closed if client application process
     * dies.
     *
     * <p>A UWB support library must be used in order to construct the {@code parameter}
     * {@link PersistableBundle}.
     *
     * @param parameters the parameters that define the ranging session
     * @param executor {@link Executor} to run callbacks
     * @param callbacks {@link RangingSession.Callback} to associate with the
     *                  {@link RangingSession} that is being opened.
     *
     * @return an {@link AutoCloseable} that is able to be used to close or cancel the opening of a
     *         {@link RangingSession} that has been requested through {@link #openRangingSession}
     *         but has not yet been made available by
     *         {@link RangingSession.Callback#onOpenSuccess}.
     */
    @NonNull
    public AutoCloseable openRangingSession(@NonNull PersistableBundle parameters,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RangingSession.Callback callbacks) {
        return mRangingManager.openSession(parameters, executor, callbacks);
    }
}
