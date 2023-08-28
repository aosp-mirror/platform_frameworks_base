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

package androidx.window.extensions.area;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.app.Activity;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateRequest;
import android.hardware.display.DisplayManager;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.core.util.function.Consumer;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Reference implementation of androidx.window.extensions.area OEM interface for use with
 * WindowManager Jetpack.
 *
 * This component currently supports Rear Display mode with the ability to add and remove
 * status listeners for this mode.
 *
 * The public methods in this class are thread-safe.
 **/
public class WindowAreaComponentImpl implements WindowAreaComponent,
        DeviceStateManager.DeviceStateCallback {

    private static final int INVALID_DISPLAY_ADDRESS = -1;
    private final Object mLock = new Object();

    @NonNull
    private final DeviceStateManager mDeviceStateManager;
    @NonNull
    private final DisplayManager mDisplayManager;
    @NonNull
    private final Executor mExecutor;

    @GuardedBy("mLock")
    private final ArraySet<Consumer<Integer>> mRearDisplayStatusListeners = new ArraySet<>();
    @GuardedBy("mLock")
    private final ArraySet<Consumer<ExtensionWindowAreaStatus>>
            mRearDisplayPresentationStatusListeners = new ArraySet<>();
    private final int mRearDisplayState;
    private final int mConcurrentDisplayState;
    @NonNull
    private final int[] mFoldedDeviceStates;
    private long mRearDisplayAddress = INVALID_DISPLAY_ADDRESS;
    @WindowAreaSessionState
    private int mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_INACTIVE;

    @GuardedBy("mLock")
    private int mCurrentDeviceState = INVALID_DEVICE_STATE;
    @GuardedBy("mLock")
    private int[] mCurrentSupportedDeviceStates;

    @GuardedBy("mLock")
    private DeviceStateRequest mRearDisplayStateRequest;
    @GuardedBy("mLock")
    private RearDisplayPresentationController mRearDisplayPresentationController;

    @Nullable
    @GuardedBy("mLock")
    private DisplayMetrics mRearDisplayMetrics;

    @WindowAreaSessionState
    @GuardedBy("mLock")
    private int mLastReportedRearDisplayPresentationStatus;

    public WindowAreaComponentImpl(@NonNull Context context) {
        mDeviceStateManager = context.getSystemService(DeviceStateManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mExecutor = context.getMainExecutor();

        mCurrentSupportedDeviceStates = mDeviceStateManager.getSupportedStates();
        mFoldedDeviceStates = context.getResources().getIntArray(
                R.array.config_foldedDeviceStates);

        // TODO(b/236022708) Move rear display state to device state config file
        mRearDisplayState = context.getResources().getInteger(
                R.integer.config_deviceStateRearDisplay);

        mConcurrentDisplayState = context.getResources().getInteger(
                R.integer.config_deviceStateConcurrentRearDisplay);

        mDeviceStateManager.registerCallback(mExecutor, this);
        mRearDisplayAddress = getRearDisplayAddress(context);
    }

    /**
     * Adds a listener interested in receiving updates on the RearDisplayStatus
     * of the device. Because this is being called from the OEM provided
     * extensions, the result of the listener will be posted on the executor
     * provided by the developer at the initial call site.
     *
     * Rear display mode moves the calling application to the display on the device that is
     * facing the same direction as the rear cameras. This would be the cover display on a fold-in
     * style device when the device is opened.
     *
     * Depending on the initial state of the device, the {@link Consumer} will receive either
     * {@link WindowAreaComponent#STATUS_AVAILABLE} or
     * {@link WindowAreaComponent#STATUS_UNAVAILABLE} if the feature is supported or not in that
     * state respectively. When the rear display feature is triggered, the status is updated to be
     * {@link WindowAreaComponent#STATUS_ACTIVE}.
     * TODO(b/240727590): Prefix with AREA_
     *
     * @param consumer {@link Consumer} interested in receiving updates to the status of
     * rear display mode.
     */
    @Override
    public void addRearDisplayStatusListener(
            @NonNull Consumer<@WindowAreaStatus Integer> consumer) {
        synchronized (mLock) {
            mRearDisplayStatusListeners.add(consumer);

            // If current device state is still invalid, the initial value has not been provided.
            if (mCurrentDeviceState == INVALID_DEVICE_STATE) {
                return;
            }
            consumer.accept(getCurrentRearDisplayModeStatus());
        }
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     * @param consumer no longer interested in receiving updates to RearDisplayStatus
     */
    @Override
    public void removeRearDisplayStatusListener(
            @NonNull Consumer<@WindowAreaStatus Integer> consumer) {
        synchronized (mLock) {
            mRearDisplayStatusListeners.remove(consumer);
        }
    }

    /**
     * Creates and starts a rear display session and provides updates to the
     * callback provided. Because this is being called from the OEM provided
     * extensions, the result of the listener will be posted on the executor
     * provided by the developer at the initial call site.
     *
     * Rear display mode moves the calling application to the display on the device that is
     * facing the same direction as the rear cameras. This would be the cover display on a fold-in
     * style device when the device is opened.
     *
     * When rear display mode is enabled, a request is made to {@link DeviceStateManager}
     * to override the device state to the state that corresponds to RearDisplay
     * mode. When the {@link DeviceStateRequest} is activated, the provided {@link Consumer} is
     * notified that the session is active by receiving
     * {@link WindowAreaComponent#SESSION_STATE_ACTIVE}.
     *
     * @param activity to provide updates to the client on
     * the status of the Session
     * @param rearDisplaySessionCallback to provide updates to the client on
     * the status of the Session
     */
    @Override
    public void startRearDisplaySession(@NonNull Activity activity,
            @NonNull Consumer<@WindowAreaSessionState Integer> rearDisplaySessionCallback) {
        synchronized (mLock) {
            if (mRearDisplayStateRequest != null) {
                // Rear display session is already active
                throw new IllegalStateException(
                        "Unable to start new rear display session as one is already active");
            }
            mRearDisplayStateRequest = DeviceStateRequest.newBuilder(mRearDisplayState).build();
            mDeviceStateManager.requestState(
                    mRearDisplayStateRequest,
                    mExecutor,
                    new RearDisplayStateRequestCallbackAdapter(rearDisplaySessionCallback)
            );
        }
    }

    /**
     * Ends the current rear display session and provides updates to the
     * callback provided. Because this is being called from the OEM provided
     * extensions, the result of the listener will be posted on the executor
     * provided by the developer at the initial call site.
     */
    @Override
    public void endRearDisplaySession() {
        synchronized (mLock) {
            if (mRearDisplayStateRequest != null || isRearDisplayActive()) {
                mRearDisplayStateRequest = null;
                mDeviceStateManager.cancelStateRequest();
            } else {
                throw new IllegalStateException(
                        "Unable to cancel a rear display session as there is no active session");
            }
        }
    }

    /**
     * Returns the{@link DisplayMetrics} associated with the rear facing display. If the rear facing
     * display was not found in the display list, but we have already computed the
     * {@link DisplayMetrics} for that display, we return the cached value. If no display has been
     * found, then we return an empty {@link DisplayMetrics} value.
     *
     * TODO(b/267563768): Update with guidance from Display team for missing displays.
     *
     * @since {@link WindowExtensions#VENDOR_API_LEVEL_3}
     */
    @Override
    @NonNull
    public DisplayMetrics getRearDisplayMetrics() {
        DisplayMetrics rearDisplayMetrics = null;

        // DISPLAY_CATEGORY_REAR displays are only available when you are in the concurrent
        // display state, so we have to look through all displays to match the address
        final Display[] displays = mDisplayManager.getDisplays(
                DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED);


        for (int i = 0; i < displays.length; i++) {
            DisplayAddress.Physical address =
                    (DisplayAddress.Physical) displays[i].getAddress();
            if (mRearDisplayAddress == address.getPhysicalDisplayId()) {
                rearDisplayMetrics = new DisplayMetrics();
                final Display rearDisplay = displays[i];

                // We must always retrieve the metrics for the rear display regardless of if it is
                // the default display or not.
                rearDisplay.getRealMetrics(rearDisplayMetrics);

                // TODO(b/287170025): This should be something like if (!rearDisplay.isEnabled)
                //  instead. Currently when the rear display is disabled, its state is STATE_OFF.
                if (rearDisplay.getDisplayId() != Display.DEFAULT_DISPLAY) {
                    final Display defaultDisplay = mDisplayManager
                            .getDisplay(Display.DEFAULT_DISPLAY);
                    rotateRearDisplayMetricsIfNeeded(defaultDisplay.getRotation(),
                            rearDisplay.getRotation(), rearDisplayMetrics);
                }
                break;
            }
        }

        synchronized (mLock) {
            // Update the rear display metrics with our latest value if one was received
            if (rearDisplayMetrics != null) {
                mRearDisplayMetrics = rearDisplayMetrics;
            }

            return Objects.requireNonNullElseGet(mRearDisplayMetrics, DisplayMetrics::new);
        }
    }

    /**
     * Adds a listener interested in receiving updates on the RearDisplayPresentationStatus
     * of the device. Because this is being called from the OEM provided
     * extensions, the result of the listener will be posted on the executor
     * provided by the developer at the initial call site.
     *
     * Rear display presentation mode is a feature where an {@link Activity} can present
     * additional content on a device with a second display that is facing the same direction
     * as the rear camera (i.e. the cover display on a fold-in style device). The calling
     * {@link Activity} does not move, whereas in rear display mode it does.
     *
     * This listener receives a {@link Pair} with the first item being the
     * {@link WindowAreaComponent.WindowAreaStatus} that corresponds to the current status of the
     * feature, and the second being the {@link DisplayMetrics} of the display that would be
     * presented to when the feature is active.
     *
     * Depending on the initial state of the device, the {@link Consumer} will receive either
     * {@link WindowAreaComponent#STATUS_AVAILABLE} or
     * {@link WindowAreaComponent#STATUS_UNAVAILABLE} for the status value of the {@link Pair} if
     * the feature is supported or not in that state respectively. Rear display presentation mode is
     * currently not supported when the device is folded. When the rear display presentation feature
     * is triggered, the status is updated to be {@link WindowAreaComponent#STATUS_UNAVAILABLE}.
     * TODO(b/240727590): Prefix with AREA_
     *
     * TODO(b/239833099): Add a STATUS_ACTIVE option to let apps know if a feature is currently
     *  enabled.
     *
     * @param consumer {@link Consumer} interested in receiving updates to the status of
     * rear display presentation mode.
     */
    @Override
    public void addRearDisplayPresentationStatusListener(
            @NonNull Consumer<ExtensionWindowAreaStatus> consumer) {
        synchronized (mLock) {
            mRearDisplayPresentationStatusListeners.add(consumer);

            // If current device state is still invalid, the initial value has not been provided
            if (mCurrentDeviceState == INVALID_DEVICE_STATE) {
                return;
            }
            @WindowAreaStatus int currentStatus = getCurrentRearDisplayPresentationModeStatus();
            DisplayMetrics metrics = currentStatus == STATUS_UNSUPPORTED ? new DisplayMetrics()
                    : getRearDisplayMetrics();
            consumer.accept(
                    new RearDisplayPresentationStatus(currentStatus, metrics));
        }
    }

    /**
     * Removes a listener no longer interested in receiving updates.
     * @param consumer no longer interested in receiving updates to RearDisplayPresentationStatus
     */
    @Override
    public void removeRearDisplayPresentationStatusListener(
            @NonNull Consumer<ExtensionWindowAreaStatus> consumer) {
        synchronized (mLock) {
            mRearDisplayPresentationStatusListeners.remove(consumer);
        }
    }

    /**
     * Creates and starts a rear display presentation session and sends state updates to the
     * consumer provided. This consumer will receive a constant represented by
     * {@link WindowAreaSessionState} to represent the state of the current rear display
     * session. It will be translated to a more friendly interface in the library.
     *
     * Because this is being called from the OEM provided extensions, the library
     * will post the result of the listener on the executor provided by the developer.
     *
     * Rear display presentation mode refers to a feature where an {@link Activity} can present
     * additional content on a device with a second display that is facing the same direction
     * as the rear camera (i.e. the cover display on a fold-in style device). The calling
     * {@link Activity} stays on the user-facing display.
     *
     * @param activity that the OEM implementation will use as a base
     * context and to identify the source display area of the request.
     * The reference to the activity instance must not be stored in the OEM
     * implementation to prevent memory leaks.
     * @param consumer to provide updates to the client on the status of the session
     * @throws UnsupportedOperationException if this method is called when rear display presentation
     * mode is not available. This could be to an incompatible device state or when
     * another process is currently in this mode.
     */
    @Override
    public void startRearDisplayPresentationSession(@NonNull Activity activity,
            @NonNull Consumer<@WindowAreaSessionState Integer> consumer) {
        synchronized (mLock) {
            if (mRearDisplayPresentationController != null) {
                // Rear display presentation session is already active
                throw new IllegalStateException(
                        "Unable to start new rear display presentation session as one is already "
                                + "active");
            }
            if (getCurrentRearDisplayPresentationModeStatus()
                    != WindowAreaComponent.STATUS_AVAILABLE) {
                throw new IllegalStateException(
                        "Unable to start new rear display presentation session as the feature is "
                                + "is not currently available");
            }

            mRearDisplayPresentationController = new RearDisplayPresentationController(activity,
                    stateStatus -> {
                        synchronized (mLock) {
                            if (stateStatus == SESSION_STATE_INACTIVE) {
                                // If the last reported session status was VISIBLE
                                // then the ACTIVE state should be dispatched before INACTIVE
                                // due to not having a good mechanism to know when
                                // the content is no longer visible before it's fully removed
                                if (getLastReportedRearDisplayPresentationStatus()
                                        == SESSION_STATE_CONTENT_VISIBLE) {
                                    consumer.accept(SESSION_STATE_ACTIVE);
                                }
                                mRearDisplayPresentationController = null;
                            }
                            mLastReportedRearDisplayPresentationStatus = stateStatus;
                            consumer.accept(stateStatus);
                        }
                    });
            RearDisplayPresentationRequestCallback deviceStateCallback =
                    new RearDisplayPresentationRequestCallback(activity,
                            mRearDisplayPresentationController);
            DeviceStateRequest concurrentDisplayStateRequest = DeviceStateRequest.newBuilder(
                    mConcurrentDisplayState).build();

            try {
                mDeviceStateManager.requestState(
                        concurrentDisplayStateRequest,
                        mExecutor,
                        deviceStateCallback
                );
            } catch (SecurityException e) {
                // If a SecurityException occurs when invoking DeviceStateManager#requestState
                // (e.g. if the caller is not in the foreground, or if it does not have the required
                // permissions), we should first clean up our local state before re-throwing the
                // SecurityException to the caller. Otherwise, subsequent attempts to
                // startRearDisplayPresentationSession will always fail.
                mRearDisplayPresentationController = null;
                throw e;
            }
        }
    }

    /**
     * Ends the current rear display presentation session and provides updates to the
     * callback provided. When this is ended, the presented content from the calling
     * {@link Activity} will also be removed from the rear facing display.
     * Because this is being called from the OEM provided extensions, the result of the listener
     * will be posted on the executor provided by the developer at the initial call site.
     *
     * Cancelling the {@link DeviceStateRequest} and exiting the rear display presentation state,
     * will remove the presentation window from the cover display as the cover display is no longer
     * enabled.
     */
    @Override
    public void endRearDisplayPresentationSession() {
        synchronized (mLock) {
            if (mRearDisplayPresentationController != null) {
                mDeviceStateManager.cancelStateRequest();
            } else {
                throw new IllegalStateException(
                        "Unable to cancel a rear display presentation session as there is no "
                                + "active session");
            }
        }
    }

    @Nullable
    @Override
    public ExtensionWindowAreaPresentation getRearDisplayPresentation() {
        synchronized (mLock) {
            ExtensionWindowAreaPresentation presentation = null;
            if (mRearDisplayPresentationController != null) {
                presentation = mRearDisplayPresentationController.getWindowAreaPresentation();
            }
            return presentation;
        }
    }

    @Override
    public void onSupportedStatesChanged(int[] supportedStates) {
        synchronized (mLock) {
            mCurrentSupportedDeviceStates = supportedStates;
            updateRearDisplayStatusListeners(getCurrentRearDisplayModeStatus());
            updateRearDisplayPresentationStatusListeners(
                    getCurrentRearDisplayPresentationModeStatus());
        }
    }

    @Override
    public void onStateChanged(int state) {
        synchronized (mLock) {
            mCurrentDeviceState = state;
            updateRearDisplayStatusListeners(getCurrentRearDisplayModeStatus());
            updateRearDisplayPresentationStatusListeners(
                    getCurrentRearDisplayPresentationModeStatus());
        }
    }

    @GuardedBy("mLock")
    private int getCurrentRearDisplayModeStatus() {
        if (mRearDisplayState == INVALID_DEVICE_STATE) {
            return WindowAreaComponent.STATUS_UNSUPPORTED;
        }

        if (!ArrayUtils.contains(mCurrentSupportedDeviceStates, mRearDisplayState)) {
            return WindowAreaComponent.STATUS_UNAVAILABLE;
        }

        if (isRearDisplayActive()) {
            return WindowAreaComponent.STATUS_ACTIVE;
        }

        return WindowAreaComponent.STATUS_AVAILABLE;
    }

    /**
     * Helper method to determine if a rear display session is currently active by checking
     * if the current device state is that which corresponds to {@code mRearDisplayState}.
     *
     * @return {@code true} if the device is in rear display state {@code false} if not
     */
    @GuardedBy("mLock")
    private boolean isRearDisplayActive() {
        return mCurrentDeviceState == mRearDisplayState;
    }

    @GuardedBy("mLock")
    private void updateRearDisplayStatusListeners(@WindowAreaStatus int windowAreaStatus) {
        if (mRearDisplayState == INVALID_DEVICE_STATE) {
            return;
        }
        synchronized (mLock) {
            for (int i = 0; i < mRearDisplayStatusListeners.size(); i++) {
                mRearDisplayStatusListeners.valueAt(i).accept(windowAreaStatus);
            }
        }
    }

    @GuardedBy("mLock")
    private int getCurrentRearDisplayPresentationModeStatus() {
        if (mConcurrentDisplayState == INVALID_DEVICE_STATE) {
            return WindowAreaComponent.STATUS_UNSUPPORTED;
        }

        if (mCurrentDeviceState == mConcurrentDisplayState
                || !ArrayUtils.contains(mCurrentSupportedDeviceStates, mConcurrentDisplayState)
                || isDeviceFolded()) {
            return WindowAreaComponent.STATUS_UNAVAILABLE;
        }
        return WindowAreaComponent.STATUS_AVAILABLE;
    }

    @GuardedBy("mLock")
    private boolean isDeviceFolded() {
        return ArrayUtils.contains(mFoldedDeviceStates, mCurrentDeviceState);
    }

    @GuardedBy("mLock")
    private void updateRearDisplayPresentationStatusListeners(
            @WindowAreaStatus int windowAreaStatus) {
        if (mConcurrentDisplayState == INVALID_DEVICE_STATE) {
            return;
        }
        RearDisplayPresentationStatus consumerValue = new RearDisplayPresentationStatus(
                windowAreaStatus, getRearDisplayMetrics());
        synchronized (mLock) {
            for (int i = 0; i < mRearDisplayPresentationStatusListeners.size(); i++) {
                mRearDisplayPresentationStatusListeners.valueAt(i).accept(consumerValue);
            }
        }
    }

    private long getRearDisplayAddress(Context context) {
        String address = context.getResources().getString(
                R.string.config_rearDisplayPhysicalAddress);
        return address.isEmpty() ? INVALID_DISPLAY_ADDRESS : Long.parseLong(address);
    }

    @GuardedBy("mLock")
    @WindowAreaSessionState
    private int getLastReportedRearDisplayPresentationStatus() {
        return mLastReportedRearDisplayPresentationStatus;
    }

    @VisibleForTesting
    static void rotateRearDisplayMetricsIfNeeded(
            @Surface.Rotation int defaultDisplayRotation,
            @Surface.Rotation int rearDisplayRotation,
            @NonNull DisplayMetrics inOutMetrics) {
        // If the rear display has a non-zero rotation, it means the backing DisplayContent /
        // DisplayRotation is fresh.
        if (rearDisplayRotation != Surface.ROTATION_0) {
            return;
        }

        // If the default display is 0 or 180, the rear display must also be 0 or 180.
        if (defaultDisplayRotation == Surface.ROTATION_0
                || defaultDisplayRotation == Surface.ROTATION_180) {
            return;
        }

        final int heightPixels = inOutMetrics.heightPixels;
        final int widthPixels = inOutMetrics.widthPixels;
        inOutMetrics.widthPixels = heightPixels;
        inOutMetrics.heightPixels = widthPixels;

        final int noncompatHeightPixels = inOutMetrics.noncompatHeightPixels;
        final int noncompatWidthPixels = inOutMetrics.noncompatWidthPixels;
        inOutMetrics.noncompatWidthPixels = noncompatHeightPixels;
        inOutMetrics.noncompatHeightPixels = noncompatWidthPixels;
    }

    /**
     * Callback for the {@link DeviceStateRequest} to be notified of when the request has been
     * activated or cancelled. This callback provides information to the client library
     * on the status of the RearDisplay session through {@code mRearDisplaySessionCallback}
     */
    private class RearDisplayStateRequestCallbackAdapter implements DeviceStateRequest.Callback {

        private final Consumer<Integer> mRearDisplaySessionCallback;

        RearDisplayStateRequestCallbackAdapter(@NonNull Consumer<Integer> callback) {
            mRearDisplaySessionCallback = callback;
        }

        @Override
        public void onRequestActivated(@NonNull DeviceStateRequest request) {
            synchronized (mLock) {
                if (request.equals(mRearDisplayStateRequest)) {
                    mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_ACTIVE;
                    mRearDisplaySessionCallback.accept(mRearDisplaySessionStatus);
                }
            }
        }

        @Override
        public void onRequestCanceled(DeviceStateRequest request) {
            synchronized (mLock) {
                if (request.equals(mRearDisplayStateRequest)) {
                    mRearDisplayStateRequest = null;
                }
                mRearDisplaySessionStatus = WindowAreaComponent.SESSION_STATE_INACTIVE;
                mRearDisplaySessionCallback.accept(mRearDisplaySessionStatus);
            }
        }
    }
}
