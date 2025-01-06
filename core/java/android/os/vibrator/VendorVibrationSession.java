/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os.vibrator;

import static android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.CombinedVibration;
import android.os.RemoteException;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A vendor session that temporarily gains control over the system vibrators.
 *
 * <p>Vibration effects can be played by the vibrator in a vendor session via {@link #vibrate}. The
 * effects will be forwarded to the vibrator hardware immediately. Any concurrency support is
 * defined and controlled by the vibrator hardware implementation.
 *
 * <p>The session should be ended by {@link #close()}, which will wait until the last vibration ends
 * and the vibrator is released. The end of the session will be notified to the {@link Callback}
 * provided when the session was created.
 *
 * <p>Any ongoing session can be immediately interrupted by the vendor app via {@link #cancel()},
 * including after {@link #close()} was called and the session is tearing down. A session can also
 * be canceled by the vibrator service when it needs to regain control of the system vibrators.
 *
 * @see Vibrator#startVendorSession
 * @hide
 */
@FlaggedApi(FLAG_VENDOR_VIBRATION_EFFECTS)
@SystemApi
public final class VendorVibrationSession implements AutoCloseable {
    private static final String TAG = "VendorVibrationSession";

    /**
     * The session ended successfully.
     */
    public static final int STATUS_SUCCESS = IVibrationSession.STATUS_SUCCESS;

    /**
     * The session was ignored.
     *
     * <p>This might be caused by user settings, vibration policies or the device state that
     * prevents the app from performing vibrations for the requested
     * {@link android.os.VibrationAttributes}.
     */
    public static final int STATUS_IGNORED = IVibrationSession.STATUS_IGNORED;

    /**
     * The session is not supported.
     *
     * <p>The support for vendor vibration sessions can be checked via
     * {@link Vibrator#areVendorSessionsSupported()}.
     */
    public static final int STATUS_UNSUPPORTED = IVibrationSession.STATUS_UNSUPPORTED;

    /**
     * The session was canceled.
     *
     * <p>This might be triggered by the app after a session starts via {@link #cancel()}, or it
     * can be triggered by the platform before or after the session has started.
     */
    public static final int STATUS_CANCELED = IVibrationSession.STATUS_CANCELED;

    /**
     * The session status is unknown.
     */
    public static final int STATUS_UNKNOWN = IVibrationSession.STATUS_UNKNOWN;

    /**
     * The session failed with unknown error.
     *
     * <p>This can be caused by a failure to start a vibration session or after it has started, to
     * indicate it has ended unexpectedly because of a system failure.
     */
    public static final int STATUS_UNKNOWN_ERROR = IVibrationSession.STATUS_UNKNOWN_ERROR;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_SUCCESS,
            STATUS_IGNORED,
            STATUS_UNSUPPORTED,
            STATUS_CANCELED,
            STATUS_UNKNOWN,
            STATUS_UNKNOWN_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status{}

    private final IVibrationSession mSession;

    /** @hide */
    public VendorVibrationSession(@NonNull IVibrationSession session) {
        Objects.requireNonNull(session);
        mSession = session;
    }

    /**
     * Vibrate with a given effect.
     *
     * <p>The vibration will be sent to the vibrator hardware immediately, without waiting for any
     * previous vibration completion. The vendor should control the concurrency behavior at the
     * hardware level (e.g. queueing, mixing, interrupting).
     *
     * <p>If the provided effect is played by the vibrator service with controlled timings (e.g.
     * effects created via {@link VibrationEffect#createWaveform}), then triggering a new vibration
     * will cause the ongoing playback to be interrupted in favor of the new vibration. If the
     * effect is broken down into multiple consecutive commands (e.g. large primitive compositions)
     * then the hardware commands will be triggered in succession without waiting for the completion
     * callback.
     *
     * <p>The vendor app is responsible for timing the session requests and the vibrator hardware
     * implementation is free to handle concurrency with different policies.
     *
     * @param effect The {@link VibrationEffect} describing the vibration to be performed.
     * @param reason The description for the vibration reason, for debugging purposes.
     */
    @RequiresPermission(android.Manifest.permission.VIBRATE)
    public void vibrate(@NonNull VibrationEffect effect, @Nullable String reason) {
        try {
            mSession.vibrate(CombinedVibration.createParallel(effect), reason);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to vibrate in a vendor vibration session.", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancel ongoing session.
     *
     * <p>This will stop the vibration immediately and return the vibrator control to the
     * platform. This can also be triggered after {@link #close()} to immediately release the
     * vibrator.
     *
     * <p>This will trigger {@link VendorVibrationSession.Callback#onFinished} directly with
     * {@link #STATUS_CANCELED}.
     */
    public void cancel() {
        try {
            mSession.cancelSession();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to cancel vendor vibration session.", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * End ongoing session gracefully.
     *
     * <p>This might continue the vibration while it's ramping down and wrapping up the session
     * in the vibrator hardware. No more vibration commands can be sent through this session
     * after this method is called.
     *
     * <p>This will trigger {@link VendorVibrationSession.Callback#onFinishing()}.
     */
    @Override
    public void close() {
        try {
            mSession.finishSession();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to finish vendor vibration session.", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Callbacks for {@link VendorVibrationSession} events.
     *
     * @see Vibrator#startVendorSession
     * @see VendorVibrationSession
     */
    public interface Callback {

        /**
         * New session was successfully started.
         *
         * <p>The vendor app can interact with the vibrator using the
         * {@link VendorVibrationSession} provided.
         */
        void onStarted(@NonNull VendorVibrationSession session);

        /**
         * The session is ending and finishing any pending vibrations.
         *
         * <p>This is only invoked after {@link #onStarted(VendorVibrationSession)}. It will be
         * triggered by both {@link VendorVibrationSession#cancel()} and
         * {@link VendorVibrationSession#close()}. This might also be triggered if the platform
         * cancels the ongoing session.
         *
         * <p>Session vibrations might be still ongoing in the vibrator hardware but the app can
         * no longer send commands through the session. A finishing session can still be immediately
         * stopped via calls to {@link VendorVibrationSession.Callback#cancel()}.
         */
        void onFinishing();

        /**
         * The session is finished.
         *
         * <p>The vibrator has finished any vibration and returned to the platform's control. This
         * might be triggered by the vendor app or by the vibrator service.
         *
         * <p>If this is triggered before {@link #onStarted} then the session was finished before
         * starting, either because it was cancelled or failed to start. If the session has already
         * started then this will be triggered after {@link #onFinishing()} to indicate all session
         * vibrations are complete and the vibrator is no longer under the session's control.
         *
         * @param status The session status.
         */
        void onFinished(@VendorVibrationSession.Status int status);
    }
}
