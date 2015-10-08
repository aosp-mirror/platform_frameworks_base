/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * RadioTuner interface provides methods to control a radio tuner on the device: selecting and
 * configuring the active band, muting/unmuting, scanning and tuning, etc...
 *
 * Obtain a RadioTuner interface by calling {@link RadioManager#openTuner(int,
 * RadioManager.BandConfig, boolean, RadioTuner.Callback, Handler)}.
 * @hide
 */
@SystemApi
public abstract class RadioTuner {

    /** Scanning direction UP for {@link #step(int, boolean)}, {@link #scan(int, boolean)} */
    public static final int DIRECTION_UP      = 0;

    /** Scanning directions DOWN for {@link #step(int, boolean)}, {@link #scan(int, boolean)} */
    public static final int DIRECTION_DOWN    = 1;

    /**
     * Close the tuner interface. The {@link Callback} callback will not be called
     * anymore and associated resources will be released.
     * Must be called when the tuner is not needed to make hardware resources available to others.
     * */
    public abstract void close();

    /**
     * Set the active band configuration for this module.
     * Must be a valid configuration obtained via buildConfig() from a valid BandDescriptor listed
     * in the ModuleProperties of the module with the specified ID.
     * @param config The desired band configuration (FmBandConfig or AmBandConfig).
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int setConfiguration(RadioManager.BandConfig config);

    /**
     * Get current configuration.
     * @param config a BandConfig array of lengh 1 where the configuration is returned.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int getConfiguration(RadioManager.BandConfig[] config);


    /**
     * Set mute state. When muted, the radio tuner audio source is not available for playback on
     * any audio device. when unmuted, the radio tuner audio source is output as a media source
     * and renderd over the audio device selected for media use case.
     * The radio tuner audio source is muted by default when the tuner is first attached.
     * Only effective if the tuner is attached with audio enabled.
     *
     * @param mute the requested mute state.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int setMute(boolean mute);

    /**
     * Get mute state.
     *
     * @return {@code true} if the radio tuner audio source is muted or a problem occured
     * retrieving the mute state, {@code false} otherwise.
     */
    public abstract boolean getMute();

    /**
     * Step up or down by one channel spacing.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when step completes or
     * onError() when cancelled or timeout.
     * @param direction {@link #DIRECTION_UP} or {@link #DIRECTION_DOWN}.
     * @param skipSubChannel indicates to skip sub channels when the configuration currently
     * selected supports sub channel (e.g HD Radio). N/A otherwise.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int step(int direction, boolean skipSubChannel);

    /**
     * Scan up or down to next valid station.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when scan completes or
     * onError() when cancelled or timeout.
     * @param direction {@link #DIRECTION_UP} or {@link #DIRECTION_DOWN}.
     * @param skipSubChannel indicates to skip sub channels when the configuration currently
     * selected supports sub channel (e.g HD Radio). N/A otherwise.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int scan(int direction, boolean skipSubChannel);

    /**
     * Tune to a specific frequency.
     * The operation is asynchronous and {@link Callback}
     * onProgramInfoChanged() will be called when tune completes or
     * onError() when cancelled or timeout.
     * @param channel the specific channel or frequency to tune to.
     * @param subChannel the specific sub-channel to tune to. N/A if the selected configuration
     * does not support cub channels.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int tune(int channel, int subChannel);

    /**
     * Cancel a pending scan or tune operation.
     * If an operation is pending, {@link Callback} onError() will be called with
     * {@link #ERROR_CANCELLED}.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int cancel();

    /**
     * Get current station information.
     * @param info a ProgramInfo array of lengh 1 where the information is returned.
     * @return
     * <ul>
     *  <li>{@link RadioManager#STATUS_OK} in case of success, </li>
     *  <li>{@link RadioManager#STATUS_ERROR} in case of unspecified error, </li>
     *  <li>{@link RadioManager#STATUS_NO_INIT} if the native service cannot be reached, </li>
     *  <li>{@link RadioManager#STATUS_BAD_VALUE} if parameters are invalid, </li>
     *  <li>{@link RadioManager#STATUS_INVALID_OPERATION} if the call is out of sequence, </li>
     *  <li>{@link RadioManager#STATUS_DEAD_OBJECT} if the binder transaction to the native
     *  service fails, </li>
     * </ul>
     */
    public abstract int getProgramInformation(RadioManager.ProgramInfo[] info);

    /**
     * Get current antenna connection state for current configuration.
     * Only valid if a configuration has been applied.
     * @return {@code true} if the antenna is connected, {@code false} otherwise.
     */
    public abstract boolean isAntennaConnected();

    /**
     * Indicates if this client actually controls the tuner.
     * Control is always granted after
     * {@link RadioManager#openTuner(int,
     * RadioManager.BandConfig, boolean, Callback, Handler)}
     * returns a non null tuner interface.
     * Control is lost when another client opens an interface on the same tuner.
     * When this happens, {@link Callback#onControlChanged(boolean)} is received.
     * The client can either wait for control to be returned (which is indicated by the same
     * callback) or close and reopen the tuner interface.
     * @return {@code true} if this interface controls the tuner,
     * {@code false} otherwise or if a problem occured retrieving the state.
     */
    public abstract boolean hasControl();

    /** Indicates a failure of radio IC or driver.
     * The application must close and re open the tuner */
    public static final int ERROR_HARDWARE_FAILURE = 0;
    /** Indicates a failure of the radio service.
     * The application must close and re open the tuner */
    public static final  int ERROR_SERVER_DIED = 1;
    /** A pending seek or tune operation was cancelled */
    public static final  int ERROR_CANCELLED = 2;
    /** A pending seek or tune operation timed out */
    public static final  int ERROR_SCAN_TIMEOUT = 3;
    /** The requested configuration could not be applied */
    public static final  int ERROR_CONFIG = 4;

    /**
     * Callback provided by the client application when opening a {@link RadioTuner}
     * to receive asynchronous operation results, updates and error notifications.
     */
    public static abstract class Callback {
        /**
         * onError() is called when an error occured while performing an asynchronous
         * operation of when the hardware or system service experiences a problem.
         * status is one of {@link #ERROR_HARDWARE_FAILURE}, {@link #ERROR_SERVER_DIED},
         * {@link #ERROR_CANCELLED}, {@link #ERROR_SCAN_TIMEOUT},
         * {@link #ERROR_CONFIG}
         */
        public void onError(int status) {}
        /**
         * onConfigurationChanged() is called upon successful completion of
         * {@link RadioManager#openTuner(int, RadioManager.BandConfig, boolean, Callback, Handler)}
         * or {@link RadioTuner#setConfiguration(RadioManager.BandConfig)}
         */
        public void onConfigurationChanged(RadioManager.BandConfig config) {}
        /**
         * onProgramInfoChanged() is called upon successful completion of
         * {@link RadioTuner#step(int, boolean)}, {@link RadioTuner#scan(int, boolean)},
         * {@link RadioTuner#tune(int, int)} or when a switching to alternate frequency occurs.
         * Note that if metadata only are updated,  {@link #onMetadataChanged(RadioMetadata)} will
         * be called.
         */
        public void onProgramInfoChanged(RadioManager.ProgramInfo info) {}
        /**
         * onMetadataChanged() is called when new meta data are received on current program.
         * Meta data are also received in {@link RadioManager.ProgramInfo} when
         *  {@link #onProgramInfoChanged(RadioManager.ProgramInfo)} is called.
         */
        public void onMetadataChanged(RadioMetadata metadata) {}
        /**
         * onTrafficAnnouncement() is called when a traffic announcement starts and stops.
         */
        public void onTrafficAnnouncement(boolean active) {}
        /**
         * onEmergencyAnnouncement() is called when an emergency annoucement starts and stops.
         */
        public void onEmergencyAnnouncement(boolean active) {}
        /**
         * onAntennaState() is called when the antenna is connected or disconnected.
         */
        public void onAntennaState(boolean connected) {}
        /**
         * onControlChanged() is called when the client loses or gains control of the radio tuner.
         * The control is always granted after a successful call to
         * {@link RadioManager#openTuner(int, RadioManager.BandConfig, boolean, Callback, Handler)}.
         * If another client opens the same tuner, onControlChanged() will be called with
         * control set to {@code false} to indicate loss of control.
         * At this point, RadioTuner APIs other than getters will return
         * {@link RadioManager#STATUS_INVALID_OPERATION}.
         * When the other client releases the tuner, onControlChanged() will be called
         * with control set to {@code true}.
         */
        public void onControlChanged(boolean control) {}
    }

}

