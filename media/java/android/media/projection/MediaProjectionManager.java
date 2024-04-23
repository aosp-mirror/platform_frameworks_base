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
import android.annotation.SuppressLint;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.ActivityOptions.LaunchCookie;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.Overridable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
 * Manages the retrieval of certain types of {@link MediaProjection} tokens.
 *
 * <p><ol>An example flow of starting a media projection will be:
 *     <li>Declare a foreground service with the type {@code mediaProjection} in
 *     the {@code AndroidManifest.xml}.
 *     </li>
 *     <li>Create an intent by calling {@link MediaProjectionManager#createScreenCaptureIntent()}
 *         and pass this intent to {@link Activity#startActivityForResult(Intent, int)}.
 *     </li>
 *     <li>On getting {@link Activity#onActivityResult(int, int, Intent)},
 *         start the foreground service with the type
 *         {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}.
 *     </li>
 *     <li>Retrieve the media projection token by calling
 *         {@link MediaProjectionManager#getMediaProjection(int, Intent)} with the result code and
 *         intent from the {@link Activity#onActivityResult(int, int, Intent)} above.
 *     </li>
 *     <li>Start the screen capture session for media projection by calling
 *         {@link MediaProjection#createVirtualDisplay(String, int, int, int, int, Surface,
 *         android.hardware.display.VirtualDisplay.Callback, Handler)}.
 *     </li>
 * </ol>
 */
@SystemService(Context.MEDIA_PROJECTION_SERVICE)
public final class MediaProjectionManager {
    private static final String TAG = "MediaProjectionManager";

    /**
     * This change id ensures that users are presented with a choice of capturing a single app
     * or the entire screen when initiating a MediaProjection session, overriding the usage of
     * MediaProjectionConfig#createConfigForDefaultDisplay.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    public static final long OVERRIDE_DISABLE_MEDIA_PROJECTION_SINGLE_APP_OPTION = 316897322L;

    /**
     * Intent extra to customize the permission dialog based on the host app's preferences.
     * @hide
     */
    public static final String EXTRA_MEDIA_PROJECTION_CONFIG =
            "android.media.projection.extra.EXTRA_MEDIA_PROJECTION_CONFIG";
    /** @hide */
    public static final String EXTRA_APP_TOKEN = "android.media.projection.extra.EXTRA_APP_TOKEN";
    /** @hide */
    public static final String EXTRA_MEDIA_PROJECTION =
            "android.media.projection.extra.EXTRA_MEDIA_PROJECTION";
    /** @hide */
    public static final String EXTRA_LAUNCH_COOKIE =
            "android.media.projection.extra.EXTRA_LAUNCH_COOKIE";

    /** @hide */
    public static final int TYPE_SCREEN_CAPTURE = 0;
    /** @hide */
    public static final int TYPE_MIRRORING = 1;
    /** @hide */
    public static final int TYPE_PRESENTATION = 2;

    private Context mContext;
    private Map<Callback, CallbackDelegate> mCallbacks;
    private IMediaProjectionManager mService;

    /** @hide */
    public MediaProjectionManager(Context context) {
        mContext = context;
        IBinder b = ServiceManager.getService(Context.MEDIA_PROJECTION_SERVICE);
        mService = IMediaProjectionManager.Stub.asInterface(b);
        mCallbacks = new ArrayMap<>();
    }

    /**
     * Returns an {@link Intent} that <b>must</b> be passed to
     * {@link Activity#startActivityForResult(Intent, int)} (or similar) in order to start screen
     * capture. The activity will prompt the user whether to allow screen capture.  The result of
     * this activity (received by overriding {@link Activity#onActivityResult(int, int, Intent)
     * onActivityResult(int, int, Intent)}) should be passed to
     * {@link #getMediaProjection(int, Intent)}.
     * <p>
     * Identical to calling {@link #createScreenCaptureIntent(MediaProjectionConfig)} with
     * a {@link MediaProjectionConfig#createConfigForUserChoice()}.
     * </p>
     * <p>
     * Should be used instead of {@link #createScreenCaptureIntent(MediaProjectionConfig)} when the
     * calling app does not want to customize the activity shown to the user.
     * </p>
     */
    @NonNull
    public Intent createScreenCaptureIntent() {
        Intent i = new Intent();
        final ComponentName mediaProjectionPermissionDialogComponent =
                ComponentName.unflattenFromString(mContext.getResources().getString(
                        com.android.internal.R.string
                        .config_mediaProjectionPermissionDialogComponent));
        i.setComponent(mediaProjectionPermissionDialogComponent);
        return i;
    }

    /**
     * Returns an {@link Intent} that <b>must</b> be passed to
     * {@link Activity#startActivityForResult(Intent, int)} (or similar) in order to start screen
     * capture. Customizes the activity and resulting {@link MediaProjection} session based up
     * the provided {@code config}. The activity will prompt the user whether to allow screen
     * capture. The result of this activity (received by overriding
     * {@link Activity#onActivityResult(int, int, Intent) onActivityResult(int, int, Intent)})
     * should be passed to {@link #getMediaProjection(int, Intent)}.
     *
     * <p>
     * If {@link MediaProjectionConfig} was created from:
     * <ul>
     *     <li>
     *         {@link MediaProjectionConfig#createConfigForDefaultDisplay()}, then creates an
     *         {@link Intent} for capturing the default display. The activity limits the user's
     *         choice to just the display specified.
     *     </li>
     *     <li>
     *         {@link MediaProjectionConfig#createConfigForUserChoice()}, then creates an
     *         {@link Intent} for deferring which region to capture to the user. This gives the
     *         user the same behaviour as calling {@link #createScreenCaptureIntent()}. The
     *         activity gives the user the choice between
     *         {@link android.view.Display#DEFAULT_DISPLAY}, or a different region.
     *     </li>
     * </ul>
     * </p>
     * <p>
     * Should be used instead of {@link #createScreenCaptureIntent()} when the calling app wants to
     * customize the activity shown to the user.
     * </p>
     *
     * @param config Customization for the {@link MediaProjection} that this {@link Intent} requests
     *               the user's consent for.
     * @return An {@link Intent} requesting the user's consent, specialized based upon the given
     * configuration.
     */
    @NonNull
    public Intent createScreenCaptureIntent(@NonNull MediaProjectionConfig config) {
        Intent i = createScreenCaptureIntent();
        i.putExtra(EXTRA_MEDIA_PROJECTION_CONFIG, config);
        return i;
    }

    /**
     * Returns an intent similar to {@link #createScreenCaptureIntent()} that will enable screen
     * recording of the task with the specified launch cookie. This method should only be used for
     * testing.
     *
     * @param launchCookie the launch cookie corresponding to the task to record.
     * @hide
     */
    @SuppressLint("UnflaggedApi")
    @TestApi
    @NonNull
    public Intent createScreenCaptureIntent(@NonNull LaunchCookie launchCookie) {
        Intent i = createScreenCaptureIntent();
        i.putExtra(EXTRA_LAUNCH_COOKIE, launchCookie);
        return i;
    }

    /**
     * Retrieves the {@link MediaProjection} obtained from a successful screen
     * capture request. The result code and data from the request are provided by overriding
     * {@link Activity#onActivityResult(int, int, Intent) onActivityResult(int, int, Intent)},
     * which is called after starting an activity using {@link #createScreenCaptureIntent()}.
     * <p>
     * Starting from Android {@link android.os.Build.VERSION_CODES#R R}, if your application
     * requests the {@link android.Manifest.permission#SYSTEM_ALERT_WINDOW SYSTEM_ALERT_WINDOW}
     * permission, and the user has not explicitly denied it, the permission will be automatically
     * granted until the projection is stopped. The permission allows your app to display user
     * controls on top of the screen being captured.
     * </p>
     * <p>
     * An app targeting SDK version {@link android.os.Build.VERSION_CODES#Q Q} or later must
     * invoke {@code getMediaProjection} and maintain the capture session
     * ({@link MediaProjection#createVirtualDisplay(String, int, int, int, int, Surface,
     * android.hardware.display.VirtualDisplay.Callback, Handler)
     * MediaProjection#createVirtualDisplay}) while running a foreground service. The app must set
     * the {@link android.R.attr#foregroundServiceType foregroundServiceType} attribute to
     * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     * FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION} in the
     * <a href="/guide/topics/manifest/service-element"><code>&lt;service&gt;</code></a> element of
     * the app's manifest file.
     * </p>
     * <p>
     * For an app targeting SDK version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE U} or
     * later, the user must have granted the app with the permission to start a projection,
     * before the app starts a foreground service with the type
     * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}.
     * Additionally, the app must have started the foreground service with that type before calling
     * this API here, or else it'll receive a {@link SecurityException} from this API call, unless
     * it's a privileged app. Apps can request the permission via the
     * {@link #createScreenCaptureIntent()} and {@link Activity#startActivityForResult(Intent, int)}
     * (or similar APIs).
     * </p>
     *
     * @param resultCode The result code from {@link Activity#onActivityResult(int, int, Intent)
     *                   onActivityResult(int, int, Intent)}.
     * @param resultData The result data from {@link Activity#onActivityResult(int, int, Intent)
     *                   onActivityResult(int, int, Intent)}.
     * @return The media projection obtained from a successful screen capture request, or null if
     * the result of the screen capture request is not {@link Activity#RESULT_OK RESULT_OK}.
     * @throws IllegalStateException On
     *                               pre-{@link android.os.Build.VERSION_CODES#Q Q} devices if a
     *                               previously obtained {@code MediaProjection} from the same
     *                               {@code resultData} has not yet been stopped.
     * @throws SecurityException     On {@link android.os.Build.VERSION_CODES#Q Q}+ devices if not
     *                               invoked from a foreground service with type
     *                {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
     *                               FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}, unless caller is a
     *                               privileged app.
     * @see <a href="/guide/components/foreground-services">
     * Foreground services developer guide</a>
     * @see <a href="/guide/topics/large-screens/media-projection">
     * Media projection developer guide</a>
     */
    public MediaProjection getMediaProjection(int resultCode, @NonNull Intent resultData) {
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            return null;
        }
        IBinder projection = resultData.getIBinderExtra(EXTRA_MEDIA_PROJECTION);
        if (projection == null) {
            return null;
        }
        // Don't do anything here if app is re-using the token; we check how often
        // IMediaProjection#start is invoked. Fail to the app when they start recording.
        return new MediaProjection(mContext, IMediaProjection.Stub.asInterface(projection));
    }

    /**
     * Get the {@link MediaProjectionInfo} for the active {@link MediaProjection}.
     * @hide
     */
    public MediaProjectionInfo getActiveProjectionInfo() {
        try {
            return mService.getActiveProjectionInfo();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get the active projection info", e);
        }
        return null;
    }

    /**
     * Stop the current projection if there is one.
     * @hide
     */
    public void stopActiveProjection() {
        try {
            Log.d(TAG, "Content Recording: stopping active projection");
            mService.stopActiveProjection();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to stop the currently active media projection", e);
        }
    }

    /**
     * Add a callback to monitor all of the {@link MediaProjection}s activity.
     * Not for use by regular applications, must have the MANAGE_MEDIA_PROJECTION permission.
     * @hide
     */
    public void addCallback(@NonNull Callback callback, @Nullable Handler handler) {
        if (callback == null) {
            Log.w(TAG, "Content Recording: cannot add null callback");
            throw new IllegalArgumentException("callback must not be null");
        }
        CallbackDelegate delegate = new CallbackDelegate(callback, handler);
        mCallbacks.put(callback, delegate);
        try {
            mService.addCallback(delegate);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to add callbacks to MediaProjection service", e);
        }
    }

    /**
     * Remove a MediaProjection monitoring callback.
     * @hide
     */
    public void removeCallback(@NonNull Callback callback) {
        if (callback == null) {
            Log.w(TAG, "ContentRecording: cannot remove null callback");
            throw new IllegalArgumentException("callback must not be null");
        }
        CallbackDelegate delegate = mCallbacks.remove(callback);
        try {
            if (delegate != null) {
                mService.removeCallback(delegate);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to add callbacks to MediaProjection service", e);
        }
    }

    /** @hide */
    public static abstract class Callback {
        public abstract void onStart(MediaProjectionInfo info);

        public abstract void onStop(MediaProjectionInfo info);

        /**
         * Called when the {@link ContentRecordingSession} was set for the current media
         * projection.
         *
         * @param info    always present and contains information about the media projection host.
         * @param session the recording session for the current media projection. Can be
         *                {@code null} when the recording will stop.
         */
        public void onRecordingSessionSet(
                @NonNull MediaProjectionInfo info,
                @Nullable ContentRecordingSession session
        ) {
        }
    }

    /** @hide */
    private final static class CallbackDelegate extends IMediaProjectionWatcherCallback.Stub {
        private Callback mCallback;
        private Handler mHandler;

        public CallbackDelegate(Callback callback, Handler handler) {
            mCallback = callback;
            if (handler == null) {
                handler = new Handler();
            }
            mHandler = handler;
        }

        @Override
        public void onStart(final MediaProjectionInfo info) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onStart(info);
                }
            });
        }

        @Override
        public void onStop(final MediaProjectionInfo info) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCallback.onStop(info);
                }
            });
        }

        @Override
        public void onRecordingSessionSet(
                @NonNull final MediaProjectionInfo info,
                @Nullable final ContentRecordingSession session
        ) {
            mHandler.post(() -> mCallback.onRecordingSessionSet(info, session));
        }
    }
}
