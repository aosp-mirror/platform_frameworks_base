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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.Log;
import android.view.ContentRecordingSession;
import android.view.Surface;

import java.util.Map;

/**
 * A token granting applications the ability to capture screen contents and/or
 * record system audio. The exact capabilities granted depend on the type of
 * MediaProjection.
 *
 * <p>
 * A screen capture session can be started through {@link
 * MediaProjectionManager#createScreenCaptureIntent}. This grants the ability to
 * capture screen contents, but not system audio.
 * </p>
 */
public final class MediaProjection {
    private static final String TAG = "MediaProjection";

    private final IMediaProjection mImpl;
    private final Context mContext;
    private final Map<Callback, CallbackRecord> mCallbacks;
    @Nullable private IMediaProjectionManager mProjectionService = null;

    /** @hide */
    public MediaProjection(Context context, IMediaProjection impl) {
        mCallbacks = new ArrayMap<Callback, CallbackRecord>();
        mContext = context;
        mImpl = impl;
        try {
            mImpl.start(new MediaProjectionCallback());
        } catch (RemoteException e) {
            throw new RuntimeException("Failed to start media projection", e);
        }
    }

    /** Register a listener to receive notifications about when the {@link
     * MediaProjection} changes state.
     *
     * @param callback The callback to call.
     * @param handler The handler on which the callback should be invoked, or
     * null if the callback should be invoked on the calling thread's looper.
     *
     * @see #unregisterCallback
     */
    public void registerCallback(Callback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback should not be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        mCallbacks.put(callback, new CallbackRecord(callback, handler));
    }

    /** Unregister a MediaProjection listener.
     *
     * @param callback The callback to unregister.
     *
     * @see #registerCallback
     */
    public void unregisterCallback(Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback should not be null");
        }
        mCallbacks.remove(callback);
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
        VirtualDisplay virtualDisplay = createVirtualDisplay(builder, callback, handler);
        return virtualDisplay;
    }

    /**
     * Creates a {@link android.hardware.display.VirtualDisplay} to capture the
     * contents of the screen.
     *
     * @param name The name of the virtual display, must be non-empty.
     * @param width The width of the virtual display in pixels. Must be
     * greater than 0.
     * @param height The height of the virtual display in pixels. Must be
     * greater than 0.
     * @param dpi The density of the virtual display in dpi. Must be greater
     * than 0.
     * @param surface The surface to which the content of the virtual display
     * should be rendered, or null if there is none initially.
     * @param flags A combination of virtual display flags. See {@link DisplayManager} for the full
     * list of flags.
     * @param callback Callback to call when the virtual display's state
     * changes, or null if none.
     * @param handler The {@link android.os.Handler} on which the callback should be
     * invoked, or null if the callback should be invoked on the calling
     * thread's main {@link android.os.Looper}.
     *
     * @see android.hardware.display.VirtualDisplay
     */
    public VirtualDisplay createVirtualDisplay(@NonNull String name,
            int width, int height, int dpi, int flags, @Nullable Surface surface,
            @Nullable VirtualDisplay.Callback callback, @Nullable Handler handler) {
        final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width,
                height, dpi).setFlags(flags);
        if (surface != null) {
            builder.setSurface(surface);
        }
        VirtualDisplay virtualDisplay = createVirtualDisplay(builder, callback, handler);
        return virtualDisplay;
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
        try {
            final IBinder launchCookie = mImpl.getLaunchCookie();
            Context windowContext = null;
            ContentRecordingSession session;
            if (launchCookie == null) {
                windowContext = mContext.createWindowContext(mContext.getDisplayNoVerify(),
                        TYPE_APPLICATION, null /* options */);
                session = ContentRecordingSession.createDisplaySession(
                        windowContext.getWindowContextToken());
            } else {
                session = ContentRecordingSession.createTaskSession(launchCookie);
            }
            virtualDisplayConfig.setWindowManagerMirroring(true);
            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            final VirtualDisplay virtualDisplay = dm.createVirtualDisplay(this,
                    virtualDisplayConfig.build(), callback, handler, windowContext);
            if (virtualDisplay == null) {
                // Since WM handling a new display and DM creating a new VirtualDisplay is async,
                // WM may have tried to start task recording and encountered an error that required
                // stopping recording entirely. The VirtualDisplay would then be null when the
                // MediaProjection is no longer active.
                return null;
            }
            session.setDisplayId(virtualDisplay.getDisplay().getDisplayId());
            // Successfully set up, so save the current session details.
            getProjectionService().setContentRecordingSession(session, mImpl);
            return virtualDisplay;
        } catch (RemoteException e) {
            // Can not capture if WMS is not accessible, so bail out.
            throw e.rethrowFromSystemServer();
        }
    }

    private IMediaProjectionManager getProjectionService() {
        if (mProjectionService == null) {
            mProjectionService = IMediaProjectionManager.Stub.asInterface(
                    ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE));
        }
        return mProjectionService;
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
    public static abstract class Callback {
        /**
         * Called when the MediaProjection session is no longer valid.
         * <p>
         * Once a MediaProjection has been stopped, it's up to the application to release any
         * resources it may be holding (e.g. {@link android.hardware.display.VirtualDisplay}s).
         * </p>
         */
        public void onStop() { }
    }

    private final class MediaProjectionCallback extends IMediaProjectionCallback.Stub {
        @Override
        public void onStop() {
            for (CallbackRecord cbr : mCallbacks.values()) {
                cbr.onStop();
            }
        }
    }

    private final static class CallbackRecord {
        private final Callback mCallback;
        private final Handler mHandler;

        public CallbackRecord(Callback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }

        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onStop();
                }
            });
        }
    }
}
