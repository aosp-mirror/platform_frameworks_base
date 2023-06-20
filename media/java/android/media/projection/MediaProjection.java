/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.Objects;

/**
 * A token granting applications the ability to capture screen contents and/or
 * record system audio. The exact capabilities granted depend on the type of
 * MediaProjection.
 *
 * <p>A screen capture session can be started through {@link
 * MediaProjectionManager#createScreenCaptureIntent}. This grants the ability to
 * capture screen contents, but not system audio.
 */
public final class MediaProjection {
    private static final String TAG = "MediaProjection";

    /**
     * Requires an app registers a {@link Callback} before invoking
     * {@link #createVirtualDisplay(String, int, int, int, int, Surface, VirtualDisplay.Callback,
     * Handler) createVirtualDisplay}.
     *
     * <p>Enabled after version 33 (Android T), so applies to target SDK of 34+ (Android U+).
     *
     * @hide
     */
    @VisibleForTesting
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final long MEDIA_PROJECTION_REQUIRES_CALLBACK = 269849258L; // buganizer id

    private final IMediaProjection mImpl;
    private final Context mContext;
    private final DisplayManager mDisplayManager;
    @NonNull
    private final Map<Callback, CallbackRecord> mCallbacks = new ArrayMap<>();

    /** @hide */
    public MediaProjection(Context context, IMediaProjection impl) {
        this(context, impl, context.getSystemService(DisplayManager.class));
    }

    /** @hide */
    @VisibleForTesting
    public MediaProjection(Context context, IMediaProjection impl, DisplayManager displayManager) {
        mContext = context;
        mImpl = impl;
        try {
            mImpl.start(new MediaProjectionCallback());
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to start media projection", e);
        }
        mDisplayManager = displayManager;
    }

    /**
     * Register a listener to receive notifications about when the {@link MediaProjection} or
     * captured content changes state.
     *
     * <p>The callback must be registered before invoking
     * {@link #createVirtualDisplay(String, int, int, int, int, Surface, VirtualDisplay.Callback,
     * Handler)} to ensure that any notifications on the callback are not missed. The client must
     * implement {@link Callback#onStop()} and clean up any resources it is holding, e.g. the
     * {@link VirtualDisplay} and {@link Surface}.
     *
     * @param callback The callback to call.
     * @param handler  The handler on which the callback should be invoked, or
     *                 null if the callback should be invoked on the calling thread's looper.
     * @throws NullPointerException If the given callback is null.
     * @see #unregisterCallback
     */
    public void registerCallback(@NonNull Callback callback, @Nullable Handler handler) {
        final Callback c = Objects.requireNonNull(callback);
        if (handler == null) {
            handler = new Handler();
        }
        mCallbacks.put(c, new CallbackRecord(c, handler));
    }

    /**
     * Unregister a {@link MediaProjection} listener.
     *
     * @param callback The callback to unregister.
     * @throws NullPointerException If the given callback is null.
     * @see #registerCallback
     */
    public void unregisterCallback(@NonNull Callback callback) {
        final Callback c = Objects.requireNonNull(callback);
        mCallbacks.remove(c);
    }

    /**
     * @hide
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int dpi, boolean isSecure, @Nullable Surface surface,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        if (isSecure) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        }
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, dpi).setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        return createVirtualDisplay(builder, callback, handler);
    }

    /**
     * Creates a {@link android.hardware.display.VirtualDisplay} to capture the
     * contents of the screen.
     *
     * <p>To correctly clean up resources associated with a capture, the application must register a
     * {@link Callback} before invocation. The app must override {@link Callback#onStop()} to clean
     * up (by invoking{@link VirtualDisplay#release()}, {@link Surface#release()} and related
     * resources).
     *
     * @param name     The name of the virtual display, must be non-empty.
     * @param width    The width of the virtual display in pixels. Must be greater than 0.
     * @param height   The height of the virtual display in pixels. Must be greater than 0.
     * @param dpi      The density of the virtual display in dpi. Must be greater than 0.
     * @param surface  The surface to which the content of the virtual display should be rendered,
     *                 or null if there is none initially.
     * @param flags    A combination of virtual display flags. See {@link DisplayManager} for the
     *                 full list of flags. Note that
     *                 {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PRESENTATION}
     *                 is always enabled. The following flags may be overridden, depending on how
     *                 the component with {android.Manifest.permission.MANAGE_MEDIA_PROJECTION}
     *                 handles the user's consent:
     *                 {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY},
     *                 {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR},
     *                 {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PUBLIC}.
     * @param callback Callback invoked when the virtual display's state changes, or null.
     * @param handler  The {@link android.os.Handler} on which the callback should be invoked, or
     *                 null if the callback should be invoked on the calling thread's main
     *                 {@link android.os.Looper}.
     * @throws IllegalStateException In the following scenarios, if the target SDK is {@link
     *                               android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE U} and up:
     *                               <ol>
     *                                 <li>If no {@link Callback} is registered.</li>
     *                                 <li>If {@link MediaProjectionManager#getMediaProjection}
     *                                 was invoked more than once to get this
     *                                 {@code MediaProjection} instance.
     *                                 <li>If this instance has already taken a recording through
     *                                 {@code #createVirtualDisplay}.
     *                               </ol>
     *                               However, if the target SDK is less than
     *                               {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE U}, no
     *                               exception is thrown. In case 1, recording begins even without
     *                               the callback. In case 2 & 3, recording doesn't begin
     *                               until the user re-grants consent in the dialog.
     * @throws SecurityException If attempting to create a new virtual display associated with this
     *                           MediaProjection instance after it has been stopped by invoking
     *                           {@link #stop()}.
     *
     * @see VirtualDisplay
     * @see VirtualDisplay.Callback
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int dpi, int flags, @Nullable Surface surface,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        if (shouldMediaProjectionRequireCallback()) {
            if (mCallbacks.isEmpty()) {
                throw new IllegalStateException(
                        "Must register a callback before starting capture, to manage resources in"
                                + " response to MediaProjection states.");
            }
        }
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, dpi).setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        return createVirtualDisplay(builder, callback, handler);
    }

    /**
     * Creates a {@link android.hardware.display.VirtualDisplay} to capture the
     * contents of the screen.
     *
     * @param virtualDisplayConfig The arguments for the virtual display configuration. See
     * {@link VirtualDisplayConfig} for using it.
     * @param callback Callback to call when the virtual display's state changes, or null if none.
     * @param handler The {@link android.os.Handler} on which the callback should be invoked, or
     *                null if the callback should be invoked on the calling thread's main
     *                {@link android.os.Looper}.
     *
     * @see android.hardware.display.VirtualDisplay
     * @hide
     */
    @Nullable
    public VirtualDisplay createVirtualDisplay(
            @NonNull VirtualDisplayConfig.Builder virtualDisplayConfig,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        // Pass in the current session details, so they are guaranteed to only be set in
        // WindowManagerService AFTER a VirtualDisplay is constructed (assuming there are no
        // errors during set-up).
        // Do not introduce a separate aidl call here to prevent a race
        // condition between setting up the VirtualDisplay and checking token validity.
        virtualDisplayConfig.setWindowManagerMirroringEnabled(true);
        // Do not declare a display id to mirror; default to the default display.
        // DisplayManagerService will ask MediaProjectionManagerService to check if the app
        // is re-using consent. Always return the projection instance to keep this call
        // non-blocking; no content is sent to the app until the user re-grants consent.
        final VirtualDisplay virtualDisplay = mDisplayManager.createVirtualDisplay(this,
                virtualDisplayConfig.build(), callback, handler);
        if (virtualDisplay == null) {
            // Since WindowManager handling a new display and DisplayManager creating a new
            // VirtualDisplay is async, WindowManager may have tried to start task recording
            // and encountered an error that required stopping recording entirely. The
            // VirtualDisplay would then be null and the MediaProjection is no longer active.
            Slog.w(TAG, "Failed to create virtual display.");
            return null;
        }
        return virtualDisplay;
    }

    /**
     * Returns {@code true} when MediaProjection requires the app registers a callback before
     * beginning to capture via
     * {@link #createVirtualDisplay(String, int, int, int, int, Surface, VirtualDisplay.Callback,
     * Handler)}.
     */
    private boolean shouldMediaProjectionRequireCallback() {
        return CompatChanges.isChangeEnabled(MEDIA_PROJECTION_REQUIRES_CALLBACK);
    }

    /**
     * Stops projection.
     */
    public void stop() {
        try {
            mImpl.stop();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to stop projection", e);
        }
    }

    /**
     * Get the underlying IMediaProjection.
     * @hide
     */
    public IMediaProjection getProjection() {
        return mImpl;
    }

    /**
     * Callbacks for the projection session.
     */
    public abstract static class Callback {
        /**
         * Called when the MediaProjection session is no longer valid.
         *
         * <p>Once a MediaProjection has been stopped, it's up to the application to release any
         * resources it may be holding (e.g. releasing the {@link VirtualDisplay} and
         * {@link Surface}).
         */
        public void onStop() { }

        /**
         * Invoked immediately after capture begins or when the size of the captured region changes,
         * providing the accurate sizing for the streamed capture.
         *
         * <p>The given width and height, in pixels, corresponds to the same width and height that
         * would be returned from {@link android.view.WindowMetrics#getBounds()} of the captured
         * region.
         *
         * <p>If the recorded content has a different aspect ratio from either the
         * {@link VirtualDisplay} or output {@link Surface}, the captured stream has letterboxing
         * (black bars) around the recorded content. The application can avoid the letterboxing
         * around the recorded content by updating the size of both the {@link VirtualDisplay} and
         * output {@link Surface}:
         *
         * <pre>
         * &#x40;Override
         * public String onCapturedContentResize(int width, int height) {
         *     // VirtualDisplay instance from MediaProjection#createVirtualDisplay
         *     virtualDisplay.resize(width, height, dpi);
         *
         *     // Create a new Surface with the updated size (depending on the application's use
         *     // case, this may be through different APIs - see Surface documentation for
         *     // options).
         *     int texName; // the OpenGL texture object name
         *     SurfaceTexture surfaceTexture = new SurfaceTexture(texName);
         *     surfaceTexture.setDefaultBufferSize(width, height);
         *     Surface surface = new Surface(surfaceTexture);
         *
         *     // Ensure the VirtualDisplay has the updated Surface to send the capture to.
         *     virtualDisplay.setSurface(surface);
         * }</pre>
         */
        public void onCapturedContentResize(int width, int height) { }

        /**
         * Invoked immediately after capture begins or when the visibility of the captured region
         * changes, providing the current visibility of the captured region.
         *
         * <p>Applications can take advantage of this callback by showing or hiding the captured
         * content from the output {@link Surface}, based on if the captured region is currently
         * visible to the user.
         *
         * <p>For example, if the user elected to capture a single app (from the activity shown from
         * {@link MediaProjectionManager#createScreenCaptureIntent()}), the following scenarios
         * trigger the callback:
         * <ul>
         *     <li>
         *         The captured region is visible ({@code isVisible} with value {@code true}),
         *         because the captured app is at least partially visible. This may happen if the
         *         user moves the covering app to show at least some portion of the captured app
         *         (e.g. the user has multiple apps visible in a multi-window mode such as split
         *         screen).
         *     </li>
         *     <li>
         *         The captured region is invisible ({@code isVisible} with value {@code false}) if
         *         it is entirely hidden. This may happen if another app entirely covers the
         *         captured app, or the user navigates away from the captured app.
         *     </li>
         * </ul>
         */
        public void onCapturedContentVisibilityChanged(boolean isVisible) { }
    }

    private final class MediaProjectionCallback extends IMediaProjectionCallback.Stub {
        @Override
        public void onStop() {
            Slog.v(TAG, "Dispatch stop to " + mCallbacks.size() + " callbacks.");
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onStop();
            }
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onCapturedContentResize(width, height);
            }
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onCapturedContentVisibilityChanged(isVisible);
            }
        }
    }

    private static final class CallbackRecord extends Callback {
        private final Callback mCallback;
        private final Handler mHandler;

        public CallbackRecord(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }


        @Override
        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onStop();
                }
            });
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            mHandler.post(() -> mCallback.onCapturedContentResize(width, height));
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            mHandler.post(() -> mCallback.onCapturedContentVisibilityChanged(isVisible));
        }
    }
}
