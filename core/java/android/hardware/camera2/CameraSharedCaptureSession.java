/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.camera2;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.view.Surface;

import com.android.internal.camera.flags.Flags;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * A shared capture session for a {@link CameraDevice}, when a camera device is opened in shared
 * mode possibly by multiple clients at the same time.
 *
 * <p>An active shared capture session is a special type of capture session used exclusively
 * for shared camera access by multiple applications, provided the camera device supports this
 * mode. To determine if a camera device supports shared mode, use the
 * {@link android.hardware.camera2.CameraManager#isCameraDeviceSharingSupported} API.
 * If supported, multiple clients can open the camera by calling
 * {@link android.hardware.camera2.CameraManager#openSharedCamera} and create a shared capture
 * session by calling {@link CameraDevice#createCaptureSession(SessionConfiguration)} and using
 * session type as {@link android.hardware.camera2.params.SessionConfiguration#SESSION_SHARED}</p>
 *
 * <p>When an application has opened a camera device in shared mode, it can only create a shared
 * capture session using session type as
 * {@link android.hardware.camera2.params.SessionConfiguration#SESSION_SHARED}. Any other session
 * type value will trigger {@link IllegalArgumentException}. Once the configuration is complete and
 * the session is ready to actually capture data, the provided
 * {@link CameraCaptureSession.StateCallback}'s
 * {@link CameraCaptureSession.StateCallback#onConfigured} callback will be called and will
 * receive a CameraCaptureSession (castable to {@link CameraSharedCaptureSession}).</p>
 *
 * <p>Shared capture sessions uses a predefined configuration detailed in
 * {@link CameraCharacteristics#SHARED_SESSION_CONFIGURATION}. Using different configuration values
 * when creating session will result in an {@link IllegalArgumentException}.</p>
 *
 * <p>When camera is opened in shared mode, the highest priority client among all the clients will
 * be the primary client while the others would be secondary clients. Clients will know if they are
 * primary or secondary by the device state callback
 * {@link CameraDevice.StateCallback#onOpenedInSharedMode}. Once the camera has been opened in
 * shared mode, their access priorities of being a primary or secondary client can change if
 * another higher priority client opens the camera later. Once the camera has been opened,
 * any change in primary client status will be shared by the device state callback
 * {@link CameraDevice.StateCallback#onClientSharedAccessPriorityChanged}.</p>
 *
 * <p>The priority of client access is determined by considering two factors: its current process
 * state and its "out of memory" score. Clients operating in the background are assigned a lower
 * priority. In contrast, clients running in the foreground, along with system-level clients, are
 * given a higher priority.</p>
 *
 * <p>Primary clients can create capture requests, modify any capture parameters and send them to
 * the capture session for a one-shot capture or as a repeating request using the following apis:
 * </p>
 *
 * <ul>
 *
 * <li>{@link CameraSharedCaptureSession#capture}</li>
 *
 * <li>{@link CameraSharedCaptureSession#captureSingleRequest}</li>
 *
 * <li>{@link CameraSharedCaptureSession#setRepeatingRequest}</li>
 *
 * <li>{@link CameraSharedCaptureSession#setSingleRepeatingRequest}</li>
 *
 * <li>{@link CameraSharedCaptureSession#stopRepeating}</li>
 *
 * </ul>
 *
 * <p>Secondary clients cannot create a capture request and modify any capture parameters. However,
 * they can start the camera streaming to desired surface targets using
 * {@link CameraSharedCaptureSession#startStreaming}, which will apply default parameters. Once the
 * streaming has successfully started, then they can stop the streaming using
 * {@link CameraSharedCaptureSession#stopStreaming}.</p>
 *
 * <p>The following APIs are not supported in shared capture sessions by either the primary or
 * secondary client.</p>
 *
 * <ul>
 *
 * <li>{@link CameraSharedCaptureSession#captureBurst}</li>
 *
 * <li>{@link CameraSharedCaptureSession#captureBurstRequests}</li>
 *
 * <li>{@link CameraSharedCaptureSession#setRepeatingBurst}</li>
 *
 * <li>{@link CameraSharedCaptureSession#setRepeatingBurstRequests}</li>
 *
 * <li>{@link CameraSharedCaptureSession#switchToOffline}</li>
 *
 * <li>{@link CameraSharedCaptureSession#updateOutputConfiguration}</li>
 *
 * <li>{@link CameraSharedCaptureSession#finalizeOutputConfigurations}</li>
 *
 * <li>{@link CameraSharedCaptureSession#prepare}</li>
 *
 * </ul>
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
@SystemApi
public abstract class CameraSharedCaptureSession extends CameraCaptureSession {

    /**
     * Request start of the streaming of camera images by this shared capture session.
     *
     * <p>With this method, the camera device will continually capture images
     * using the settings provided by primary client if there is ongoing repeating request
     * by the primary client or default settings if no ongoing streaming request in progress.</p>
     *
     * <p> startStreaming has lower priority than the capture requests submitted
     * through {@link #capture} by primary client, so if {@link #capture} is called when a
     * streaming is active, the capture request will be processed before any further
     * streaming requests are processed.</p>
     *
     * <p>To stop the streaming, call {@link #stopStreaming}</p>
     *
     * <p>Calling this method will replace any earlier streaming set up by this method.</p>
     *
     * @param surfaces List of target surfaces to use for streaming.
     * @param executor The executor which will be used for invoking the listener.
     * @param listener The callback object to notify the status and progress of the image capture.
     *
     * @return int A unique capture sequence ID used by
     *             {@link CaptureCallback#onCaptureSequenceCompleted}.
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     * @throws IllegalArgumentException If the request references no surfaces or references surfaces
     *                                  that are not currently configured as outputs; or
     *                                  the executor is null, or the listener is null.
     * @see #stopStreaming
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
    @SystemApi
    public abstract int startStreaming(@NonNull List<Surface> surfaces,
            @NonNull @CallbackExecutor Executor executor, @NonNull CaptureCallback listener)
            throws CameraAccessException;

    /**
     * <p>Cancel any ongoing streaming started by {@link #startStreaming}</p>
     *
     * @throws CameraAccessException if the camera device is no longer connected or has
     *                               encountered a fatal error
     * @throws IllegalStateException if this session is no longer active, either because the session
     *                               was explicitly closed, a new session has been created
     *                               or the camera device has been closed.
     *
     * @see #startStreaming
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_CAMERA_MULTI_CLIENT)
    @SystemApi
    public abstract void stopStreaming() throws CameraAccessException;
}
