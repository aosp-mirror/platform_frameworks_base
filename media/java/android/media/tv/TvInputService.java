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

package android.media.tv;

import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The TvInputService class represents a TV input or source such as HDMI or built-in tuner which
 * provides pass-through video or broadcast TV programs.
 * <p>
 * Applications will not normally use this service themselves, instead relying on the standard
 * interaction provided by {@link TvView}. Those implementing TV input services should normally do
 * so by deriving from this class and providing their own session implementation based on
 * {@link TvInputService.Session}. All TV input services must require that clients hold the
 * {@link android.Manifest.permission#BIND_TV_INPUT} in order to interact with the service; if this
 * permission is not specified in the manifest, the system will refuse to bind to that TV input
 * service.
 * </p>
 */
public abstract class TvInputService extends Service {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInputService";

    /**
     * This is the interface name that a service implementing a TV input should say that it support
     * -- that is, this is the action it uses for its intent filter. To be supported, the service
     * must also require the {@link android.Manifest.permission#BIND_TV_INPUT} permission so that
     * other applications cannot abuse it.
     */
    public static final String SERVICE_INTERFACE = "android.media.tv.TvInputService";

    /**
     * Name under which a TvInputService component publishes information about itself.
     * This meta-data must reference an XML resource containing an
     * <code>&lt;{@link android.R.styleable#TvInputService tv-input}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.media.tv.input";

    /**
     * Handler instance to handle request from TV Input Manager Service. Should be run in the main
     * looper to be synchronously run with {@code Session.mHandler}.
     */
    private final Handler mServiceHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInputServiceCallback> mCallbacks =
            new RemoteCallbackList<ITvInputServiceCallback>();

    private TvInputManager mTvInputManager;

    @Override
    public final IBinder onBind(Intent intent) {
        return new ITvInputService.Stub() {
            @Override
            public void registerCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                }
            }

            @Override
            public void unregisterCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvInputSessionCallback cb,
                    String inputId) {
                if (channel == null) {
                    Log.w(TAG, "Creating session without input channel");
                }
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                args.arg3 = inputId;
                mServiceHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args).sendToTarget();
            }

            @Override
            public void notifyHardwareAdded(TvInputHardwareInfo hardwareInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_ADD_HARDWARE_TV_INPUT,
                        hardwareInfo).sendToTarget();
            }

            @Override
            public void notifyHardwareRemoved(TvInputHardwareInfo hardwareInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_REMOVE_HARDWARE_TV_INPUT,
                        hardwareInfo).sendToTarget();
            }

            @Override
            public void notifyHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_ADD_HDMI_TV_INPUT,
                        deviceInfo).sendToTarget();
            }

            @Override
            public void notifyHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
                mServiceHandler.obtainMessage(ServiceHandler.DO_REMOVE_HDMI_TV_INPUT,
                        deviceInfo).sendToTarget();
            }
        };
    }

    /**
     * Returns a concrete implementation of {@link Session}.
     * <p>
     * May return {@code null} if this TV input service fails to create a session for some reason.
     * If TV input represents an external device connected to a hardware TV input,
     * {@link HardwareSession} should be returned.
     * </p>
     * @param inputId The ID of the TV input associated with the session.
     */
    public abstract Session onCreateSession(String inputId);

    /**
     * Returns a new {@link TvInputInfo} object if this service is responsible for
     * {@code hardwareInfo}; otherwise, return {@code null}. Override to modify default behavior of
     * ignoring all hardware input.
     *
     * @param hardwareInfo {@link TvInputHardwareInfo} object just added.
     * @hide
     */
    @SystemApi
    public TvInputInfo onHardwareAdded(TvInputHardwareInfo hardwareInfo) {
        return null;
    }

    /**
     * Returns the input ID for {@code deviceId} if it is handled by this service;
     * otherwise, return {@code null}. Override to modify default behavior of ignoring all hardware
     * input.
     *
     * @param hardwareInfo {@link TvInputHardwareInfo} object just removed.
     * @hide
     */
    @SystemApi
    public String onHardwareRemoved(TvInputHardwareInfo hardwareInfo) {
        return null;
    }

    /**
     * Returns a new {@link TvInputInfo} object if this service is responsible for
     * {@code deviceInfo}; otherwise, return {@code null}. Override to modify default behavior of
     * ignoring all HDMI logical input device.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} object just added.
     * @hide
     */
    @SystemApi
    public TvInputInfo onHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
        return null;
    }

    /**
     * Returns the input ID for {@code deviceInfo} if it is handled by this service; otherwise,
     * return {@code null}. Override to modify default behavior of ignoring all HDMI logical input
     * device.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} object just removed.
     * @hide
     */
    @SystemApi
    public String onHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
        return null;
    }

    private boolean isPassthroughInput(String inputId) {
        if (mTvInputManager == null) {
            mTvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        }
        TvInputInfo info = mTvInputManager.getTvInputInfo(inputId);
        if (info != null && info.isPassthroughInput()) {
            return true;
        }
        return false;
    }

    /**
     * Base class for derived classes to implement to provide a TV input session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        private static final int DETACH_OVERLAY_VIEW_TIMEOUT = 5000;
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();
        private final WindowManager mWindowManager;
        final Handler mHandler;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private Context mContext;
        private FrameLayout mOverlayViewContainer;
        private View mOverlayView;
        private OverlayViewCleanUpTask mOverlayViewCleanUpTask;
        private boolean mOverlayViewEnabled;
        private IBinder mWindowToken;
        private Rect mOverlayFrame;

        private Object mLock = new Object();
        // @GuardedBy("mLock")
        private ITvInputSessionCallback mSessionCallback;
        // @GuardedBy("mLock")
        private List<Runnable> mPendingActions = new ArrayList<>();

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(Context context) {
            mContext = context;
            mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            mHandler = new Handler(context.getMainLooper());
        }

        /**
         * Enables or disables the overlay view. By default, the overlay view is disabled. Must be
         * called explicitly after the session is created to enable the overlay view.
         *
         * @param enable {@code true} if you want to enable the overlay view. {@code false}
         *            otherwise.
         */
        public void setOverlayViewEnabled(final boolean enable) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (enable == mOverlayViewEnabled) {
                        return;
                    }
                    mOverlayViewEnabled = enable;
                    if (enable) {
                        if (mWindowToken != null) {
                            createOverlayView(mWindowToken, mOverlayFrame);
                        }
                    } else {
                        removeOverlayView(false);
                    }
                }
            });
        }

        /**
         * Dispatches an event to the application using this session.
         *
         * @param eventType The type of the event.
         * @param eventArgs Optional arguments of the event.
         * @hide
         */
        @SystemApi
        public void notifySessionEvent(final String eventType, final Bundle eventArgs) {
            if (eventType == null) {
                throw new IllegalArgumentException("eventType should not be null.");
            }
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifySessionEvent(" + eventType + ")");
                        if (mSessionCallback != null) {
                            mSessionCallback.onSessionEvent(eventType, eventArgs);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in sending event (event=" + eventType + ")");
                    }
                }
            });
        }

        /**
         * Notifies the channel of the session is retuned by TV input.
         *
         * @param channelUri The URI of a channel.
         */
        public void notifyChannelRetuned(final Uri channelUri) {
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyChannelRetuned");
                        if (mSessionCallback != null) {
                            mSessionCallback.onChannelRetuned(channelUri);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyChannelRetuned");
                    }
                }
            });
        }

        /**
         * Sends the list of all audio/video/subtitle tracks. The is used by the framework to
         * maintain the track information for a given session, which in turn is used by
         * {@link TvView#getTracks} for the application to retrieve metadata for a given track type.
         * The TV input service must call this method as soon as the track information becomes
         * available or is updated. Note that in a case where a part of the information for a
         * certain track is updated, it is not necessary to create a new {@link TvTrackInfo} object
         * with a different track ID.
         *
         * @param tracks A list which includes track information.
         * @throws IllegalArgumentException if {@code tracks} contains redundant tracks.
         */
        public void notifyTracksChanged(final List<TvTrackInfo> tracks) {
            Set<String> trackIdSet = new HashSet<String>();
            for (TvTrackInfo track : tracks) {
                String trackId = track.getId();
                if (trackIdSet.contains(trackId)) {
                    throw new IllegalArgumentException("redundant track ID: " + trackId);
                }
                trackIdSet.add(trackId);
            }
            trackIdSet.clear();

            // TODO: Validate the track list.
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTracksChanged");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTracksChanged(tracks);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTracksChanged");
                    }
                }
            });
        }

        /**
         * Sends the type and ID of a selected track. This is used to inform the application that a
         * specific track is selected. The TV input service must call this method as soon as a track
         * is selected either by default or in response to a call to {@link #onSelectTrack}. The
         * selected track ID for a given type is maintained in the framework until the next call to
         * this method even after the entire track list is updated (but is reset when the session is
         * tuned to a new channel), so care must be taken not to result in an obsolete track ID.
         *
         * @param type The type of the selected track. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the selected track.
         * @see #onSelectTrack
         */
        public void notifyTrackSelected(final int type, final String trackId) {
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTrackSelected");
                        if (mSessionCallback != null) {
                            mSessionCallback.onTrackSelected(type, trackId);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTrackSelected");
                    }
                }
            });
        }

        /**
         * Informs the application that the video is now available for watching. This is primarily
         * used to signal the application to unblock the screen. The TV input service must call this
         * method as soon as the content rendered onto its surface gets ready for viewing.
         *
         * @see #notifyVideoUnavailable
         */
        public void notifyVideoAvailable() {
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoAvailable");
                        if (mSessionCallback != null) {
                            mSessionCallback.onVideoAvailable();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyVideoAvailable");
                    }
                }
            });
        }

        /**
         * Informs the application that the video became unavailable for some reason. This is
         * primarily used to signal the application to block the screen not to show any intermittent
         * video artifacts.
         *
         * @param reason The reason why the video became unavailable:
         *            <ul>
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         *            <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         *            </ul>
         * @see #notifyVideoAvailable
         */
        public void notifyVideoUnavailable(final int reason) {
            if (reason < TvInputManager.VIDEO_UNAVAILABLE_REASON_START
                    || reason > TvInputManager.VIDEO_UNAVAILABLE_REASON_END) {
                throw new IllegalArgumentException("Unknown reason: " + reason);
            }
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoUnavailable");
                        if (mSessionCallback != null) {
                            mSessionCallback.onVideoUnavailable(reason);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyVideoUnavailable");
                    }
                }
            });
        }

        /**
         * Informs the application that the user is allowed to watch the current program content.
         * <p>
         * Each TV input service is required to query the system whether the user is allowed to
         * watch the current program before showing it to the user if the parental controls is
         * enabled (i.e. {@link TvInputManager#isParentalControlsEnabled
         * TvInputManager.isParentalControlsEnabled()} returns {@code true}). Whether the TV input
         * service should block the content or not is determined by invoking
         * {@link TvInputManager#isRatingBlocked TvInputManager.isRatingBlocked(TvContentRating)}
         * with the content rating for the current program. Then the {@link TvInputManager} makes a
         * judgment based on the user blocked ratings stored in the secure settings and returns the
         * result. If the rating in question turns out to be allowed by the user, the TV input
         * service must call this method to notify the application that is permitted to show the
         * content.
         * </p><p>
         * Each TV input service also needs to continuously listen to any changes made to the
         * parental controls settings by registering a broadcast receiver to receive
         * {@link TvInputManager#ACTION_BLOCKED_RATINGS_CHANGED} and
         * {@link TvInputManager#ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED} and immediately
         * reevaluate the current program with the new parental controls settings.
         * </p>
         *
         * @see #notifyContentBlocked
         * @see TvInputManager
         */
        public void notifyContentAllowed() {
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentAllowed");
                        if (mSessionCallback != null) {
                            mSessionCallback.onContentAllowed();
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyContentAllowed");
                    }
                }
            });
        }

        /**
         * Informs the application that the current program content is blocked by parent controls.
         * <p>
         * Each TV input service is required to query the system whether the user is allowed to
         * watch the current program before showing it to the user if the parental controls is
         * enabled (i.e. {@link TvInputManager#isParentalControlsEnabled
         * TvInputManager.isParentalControlsEnabled()} returns {@code true}). Whether the TV input
         * service should block the content or not is determined by invoking
         * {@link TvInputManager#isRatingBlocked TvInputManager.isRatingBlocked(TvContentRating)}
         * with the content rating for the current program. Then the {@link TvInputManager} makes a
         * judgment based on the user blocked ratings stored in the secure settings and returns the
         * result. If the rating in question turns out to be blocked, the TV input service must
         * immediately block the content and call this method with the content rating of the current
         * program to prompt the PIN verification screen.
         * </p><p>
         * Each TV input service also needs to continuously listen to any changes made to the
         * parental controls settings by registering a broadcast receiver to receive
         * {@link TvInputManager#ACTION_BLOCKED_RATINGS_CHANGED} and
         * {@link TvInputManager#ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED} and immediately
         * reevaluate the current program with the new parental controls settings.
         * </p>
         *
         * @param rating The content rating for the current TV program.
         * @see #notifyContentAllowed
         * @see TvInputManager
         */
        public void notifyContentBlocked(final TvContentRating rating) {
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentBlocked");
                        if (mSessionCallback != null) {
                            mSessionCallback.onContentBlocked(rating.flattenToString());
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyContentBlocked");
                    }
                }
            });
        }

        /**
         * Assigns a position of the {@link Surface} passed by {@link #onSetSurface}. The position
         * is relative to an overlay view.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottom Bottom position in pixels, relative to the overlay view.
         * @see #onOverlayViewSizeChanged
         * @hide
         */
        @SystemApi
        public void layoutSurface(final int left, final int top, final int right,
                final int bottom) {
            if (left > right || top > bottom) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            executeOrPostRunnable(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top + ", r="
                                + right + ", b=" + bottom + ",)");
                        if (mSessionCallback != null) {
                            mSessionCallback.onLayoutSurface(left, top, right, bottom);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in layoutSurface");
                    }
                }
            });
        }

        /**
         * Called when the session is released.
         */
        public abstract void onRelease();

        /**
         * Sets the current session as the main session. The main session is a session whose
         * corresponding TV input determines the HDMI-CEC active source device.
         * <p>
         * TV input service that manages HDMI-CEC logical device should implement {@link
         * #onSetMain} to (1) select the corresponding HDMI logical device as the source device
         * when {@code isMain} is {@code true}, and to (2) select the internal device (= TV itself)
         * as the source device when {@code isMain} is {@code false} and the session is still main.
         * Also, if a surface is passed to a non-main session and active source is changed to
         * initiate the surface, the active source should be returned to the main session.
         * </p><p>
         * {@link TvView} guarantees that, when tuning involves a session transition, {@code
         * onSetMain(true)} for new session is called first, {@code onSetMain(false)} for old
         * session is called afterwards. This allows {@code onSetMain(false)} to be no-op when TV
         * input service knows that the next main session corresponds to another HDMI logical
         * device. Practically, this implies that one TV input service should handle all HDMI port
         * and HDMI-CEC logical devices for smooth active source transition.
         * </p>
         *
         * @param isMain If true, session should become main.
         * @see TvView#setMain
         * @hide
         */
        @SystemApi
        public void onSetMain(boolean isMain) {
        }

        /**
         * Sets the {@link Surface} for the current input session on which the TV input renders
         * video.
         *
         * @param surface {@link Surface} an application passes to this TV input session.
         * @return {@code true} if the surface was set, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(Surface surface);

        /**
         * Called after any structural changes (format or size) have been made to the
         * {@link Surface} passed by {@link #onSetSurface}. This method is always called
         * at least once, after {@link #onSetSurface} with non-null {@link Surface} is called.
         *
         * @param format The new PixelFormat of the {@link Surface}.
         * @param width The new width of the {@link Surface}.
         * @param height The new height of the {@link Surface}.
         */
        public void onSurfaceChanged(int format, int width, int height) {
        }

        /**
         * Called when a size of an overlay view is changed by an application. Even when the overlay
         * view is disabled by {@link #setOverlayViewEnabled}, this is called. The size is same as
         * the size of {@link Surface} in general. Once {@link #layoutSurface} is called, the sizes
         * of {@link Surface} and the overlay view can be different.
         *
         * @param width The width of the overlay view.
         * @param height The height of the overlay view.
         * @hide
         */
        @SystemApi
        public void onOverlayViewSizeChanged(int width, int height) {
        }

        /**
         * Sets the relative stream volume of the current TV input session to handle the change of
         * audio focus by setting.
         *
         * @param volume Volume scale from 0.0 to 1.0.
         */
        public abstract void onSetStreamVolume(float volume);

        /**
         * Tunes to a given channel. When the video is available, {@link #notifyVideoAvailable()}
         * should be called. Also, {@link #notifyVideoUnavailable(int)} should be called when the
         * TV input cannot continue playing the given channel.
         *
         * @param channelUri The URI of the channel.
         * @return {@code true} the tuning was successful, {@code false} otherwise.
         */
        public abstract boolean onTune(Uri channelUri);

        /**
         * Calls {@link #onTune(Uri)}. Override this method in order to handle {@code params}.
         *
         * @param channelUri The URI of the channel.
         * @param params The extra parameters from other applications.
         * @return {@code true} the tuning was successful, {@code false} otherwise.
         * @hide
         */
        @SystemApi
        public boolean onTune(Uri channelUri, Bundle params) {
            return onTune(channelUri);
        }

        /**
         * Enables or disables the caption.
         * <p>
         * The locale for the user's preferred captioning language can be obtained by calling
         * {@link CaptioningManager#getLocale CaptioningManager.getLocale()}.
         *
         * @param enabled {@code true} to enable, {@code false} to disable.
         * @see CaptioningManager
         */
        public abstract void onSetCaptionEnabled(boolean enabled);

        /**
         * Requests to unblock the content according to the given rating.
         * <p>
         * The implementation should unblock the content.
         * TV input service has responsibility to decide when/how the unblock expires
         * while it can keep previously unblocked ratings in order not to ask a user
         * to unblock whenever a content rating is changed.
         * Therefore an unblocked rating can be valid for a channel, a program,
         * or certain amount of time depending on the implementation.
         * </p>
         *
         * @param unblockedRating An unblocked content rating
         */
        public void onUnblockContent(TvContentRating unblockedRating) {
        }

        /**
         * Select a given track.
         * <p>
         * If this is done successfully, the implementation should call {@link #notifyTrackSelected}
         * to help applications maintain the selcted track lists.
         * </p>
         *
         * @param trackId The ID of the track to select. {@code null} means to unselect the current
         *            track for a given type.
         * @param type The type of the track to select. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @see #notifyTrackSelected
         */
        public boolean onSelectTrack(int type, String trackId) {
            return false;
        }

        /**
         * Processes a private command sent from the application to the TV input. This can be used
         * to provide domain-specific features that are only known between certain TV inputs and
         * their clients.
         *
         * @param action Name of the command to be performed. This <em>must</em> be a scoped name,
         *            i.e. prefixed with a package name you own, so that different developers will
         *            not create conflicting commands.
         * @param data Any data to include with the command.
         * @hide
         */
        @SystemApi
        public void onAppPrivateCommand(String action, Bundle data) {
        }

        /**
         * Called when an application requests to create an overlay view. Each session
         * implementation can override this method and return its own view.
         *
         * @return a view attached to the overlay window
         */
        public View onCreateOverlayView() {
            return null;
        }

        /**
         * Default implementation of {@link android.view.KeyEvent.Callback#onKeyDown(int, KeyEvent)
         * KeyEvent.Callback.onKeyDown()}: always returns false (doesn't handle the event).
         * <p>
         * Override this to intercept key down events before they are processed by the application.
         * If you return true, the application will not process the event itself. If you return
         * false, the normal application processing will occur as if the TV input had not seen the
         * event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of
         * {@link android.view.KeyEvent.Callback#onKeyLongPress(int, KeyEvent)
         * KeyEvent.Callback.onKeyLongPress()}: always returns false (doesn't handle the event).
         * <p>
         * Override this to intercept key long press events before they are processed by the
         * application. If you return true, the application will not process the event itself. If
         * you return false, the normal application processing will occur as if the TV input had not
         * seen the event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyLongPress(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of
         * {@link android.view.KeyEvent.Callback#onKeyMultiple(int, int, KeyEvent)
         * KeyEvent.Callback.onKeyMultiple()}: always returns false (doesn't handle the event).
         * <p>
         * Override this to intercept special key multiple events before they are processed by the
         * application. If you return true, the application will not itself process the event. If
         * you return false, the normal application processing will occur as if the TV input had not
         * seen the event at all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param count The number of times the action was made.
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
            return false;
        }

        /**
         * Default implementation of {@link android.view.KeyEvent.Callback#onKeyUp(int, KeyEvent)
         * KeyEvent.Callback.onKeyUp()}: always returns false (doesn't handle the event).
         * <p>
         * Override this to intercept key up events before they are processed by the application. If
         * you return true, the application will not itself process the event. If you return false,
         * the normal application processing will occur as if the TV input had not seen the event at
         * all.
         *
         * @param keyCode The value in event.getKeyCode().
         * @param event Description of the key event.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         */
        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return false;
        }

        /**
         * Implement this method to handle touch screen motion events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTouchEvent
         */
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle trackball events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onTrackballEvent
         */
        public boolean onTrackballEvent(MotionEvent event) {
            return false;
        }

        /**
         * Implement this method to handle generic motion events on the current input session.
         *
         * @param event The motion event being received.
         * @return If you handled the event, return {@code true}. If you want to allow the event to
         *         be handled by the next receiver, return {@code false}.
         * @see View#onGenericMotionEvent
         */
        public boolean onGenericMotionEvent(MotionEvent event) {
            return false;
        }

        /**
         * This method is called when the application would like to stop using the current input
         * session.
         */
        void release() {
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            synchronized(mLock) {
                mSessionCallback = null;
                mPendingActions.clear();
            }
            // Removes the overlay view lastly so that any hanging on the main thread can be handled
            // in {@link #scheduleOverlayViewCleanup}.
            removeOverlayView(true);
        }

        /**
         * Calls {@link #onSetMain}.
         */
        void setMain(boolean isMain) {
            onSetMain(isMain);
        }

        /**
         * Calls {@link #onSetSurface}.
         */
        void setSurface(Surface surface) {
            onSetSurface(surface);
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSurfaceChanged}.
         */
        void dispatchSurfaceChanged(int format, int width, int height) {
            if (DEBUG) {
                Log.d(TAG, "dispatchSurfaceChanged(format=" + format + ", width=" + width
                        + ", height=" + height + ")");
            }
            onSurfaceChanged(format, width, height);
        }

        /**
         * Calls {@link #onSetStreamVolume}.
         */
        void setStreamVolume(float volume) {
            onSetStreamVolume(volume);
        }

        /**
         * Calls {@link #onTune}.
         */
        void tune(Uri channelUri, Bundle params) {
            onTune(channelUri, params);
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onSetCaptionEnabled}.
         */
        void setCaptionEnabled(boolean enabled) {
            onSetCaptionEnabled(enabled);
        }

        /**
         * Calls {@link #onSelectTrack}.
         */
        void selectTrack(int type, String trackId) {
            onSelectTrack(type, trackId);
        }

        /**
         * Calls {@link #onUnblockContent}.
         */
        void unblockContent(String unblockedRating) {
            onUnblockContent(TvContentRating.unflattenFromString(unblockedRating));
            // TODO: Handle failure.
        }

        /**
         * Calls {@link #onAppPrivateCommand}.
         */
        void appPrivateCommand(String action, Bundle data) {
            onAppPrivateCommand(action, data);
        }

        /**
         * Creates an overlay view. This calls {@link #onCreateOverlayView} to get a view to attach
         * to the overlay window.
         *
         * @param windowToken A window token of an application.
         * @param frame A position of the overlay view.
         */
        void createOverlayView(IBinder windowToken, Rect frame) {
            if (mOverlayViewContainer != null) {
                removeOverlayView(false);
            }
            if (DEBUG) Log.d(TAG, "create overlay view(" + frame + ")");
            mWindowToken = windowToken;
            mOverlayFrame = frame;
            onOverlayViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            if (!mOverlayViewEnabled) {
                return;
            }
            mOverlayView = onCreateOverlayView();
            if (mOverlayView == null) {
                return;
            }
            if (mOverlayViewCleanUpTask != null) {
                mOverlayViewCleanUpTask.cancel(true);
                mOverlayViewCleanUpTask = null;
            }
            // Creates a container view to check hanging on the overlay view detaching.
            // Adding/removing the overlay view to/from the container make the view attach/detach
            // logic run on the main thread.
            mOverlayViewContainer = new FrameLayout(mContext);
            mOverlayViewContainer.addView(mOverlayView);
            // TvView's window type is TYPE_APPLICATION_MEDIA and we want to create
            // an overlay window above the media window but below the application window.
            int type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
            // We make the overlay view non-focusable and non-touchable so that
            // the application that owns the window token can decide whether to consume or
            // dispatch the input events.
            int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            mWindowParams = new WindowManager.LayoutParams(
                    frame.right - frame.left, frame.bottom - frame.top,
                    frame.left, frame.top, type, flag, PixelFormat.TRANSPARENT);
            mWindowParams.privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
            mWindowParams.gravity = Gravity.START | Gravity.TOP;
            mWindowParams.token = windowToken;
            mWindowManager.addView(mOverlayViewContainer, mWindowParams);
        }

        /**
         * Relayouts the current overlay view.
         *
         * @param frame A new position of the overlay view.
         */
        void relayoutOverlayView(Rect frame) {
            if (DEBUG) Log.d(TAG, "relayoutOverlayView(" + frame + ")");
            if (mOverlayFrame == null || mOverlayFrame.width() != frame.width()
                    || mOverlayFrame.height() != frame.height()) {
                // Note: relayoutOverlayView is called whenever TvView's layout is changed
                // regardless of setOverlayViewEnabled.
                onOverlayViewSizeChanged(frame.right - frame.left, frame.bottom - frame.top);
            }
            mOverlayFrame = frame;
            if (!mOverlayViewEnabled || mOverlayViewContainer == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mOverlayViewContainer, mWindowParams);
        }

        /**
         * Removes the current overlay view.
         */
        void removeOverlayView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeOverlayView(" + mOverlayViewContainer + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mOverlayFrame = null;
            }
            if (mOverlayViewContainer != null) {
                // Removes the overlay view from the view hierarchy in advance so that it can be
                // cleaned up in the {@link OverlayViewCleanUpTask} if the remove process is
                // hanging.
                mOverlayViewContainer.removeView(mOverlayView);
                mOverlayView = null;
                mWindowManager.removeView(mOverlayViewContainer);
                mOverlayViewContainer = null;
                mWindowParams = null;
            }
        }

        /**
         * Schedules a task which checks whether the overlay view is detached and kills the process
         * if it is not. Note that this method is expected to be called in a non-main thread.
         */
        void scheduleOverlayViewCleanup() {
            View overlayViewParent = mOverlayViewContainer;
            if (overlayViewParent != null) {
                mOverlayViewCleanUpTask = new OverlayViewCleanUpTask();
                mOverlayViewCleanUpTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                        overlayViewParent);
            }
        }

        /**
         * Takes care of dispatching incoming input events and tells whether the event was handled.
         */
        int dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
            if (DEBUG) Log.d(TAG, "dispatchInputEvent(" + event + ")");
            boolean isNavigationKey = false;
            if (event instanceof KeyEvent) {
                KeyEvent keyEvent = (KeyEvent) event;
                isNavigationKey = isNavigationKey(keyEvent.getKeyCode());
                if (keyEvent.dispatch(this, mDispatcherState, this)) {
                    return TvInputManager.Session.DISPATCH_HANDLED;
                }
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return TvInputManager.Session.DISPATCH_HANDLED;
                    }
                }
            }
            if (mOverlayViewContainer == null || !mOverlayViewContainer.isAttachedToWindow()) {
                return TvInputManager.Session.DISPATCH_NOT_HANDLED;
            }
            if (!mOverlayViewContainer.hasWindowFocus()) {
                mOverlayViewContainer.getViewRootImpl().windowFocusChanged(true, true);
            }
            if (isNavigationKey && mOverlayViewContainer.hasFocusable()) {
                // If mOverlayView has focusable views, navigation key events should be always
                // handled. If not, it can make the application UI navigation messed up.
                // For example, in the case that the left-most view is focused, a left key event
                // will not be handled in ViewRootImpl. Then, the left key event will be handled in
                // the application during the UI navigation of the TV input.
                mOverlayViewContainer.getViewRootImpl().dispatchInputEvent(event);
                return TvInputManager.Session.DISPATCH_HANDLED;
            } else {
                mOverlayViewContainer.getViewRootImpl().dispatchInputEvent(event, receiver);
                return TvInputManager.Session.DISPATCH_IN_PROGRESS;
            }
        }

        private void initialize(ITvInputSessionCallback callback) {
            synchronized(mLock) {
                mSessionCallback = callback;
                for (Runnable runnable : mPendingActions) {
                    runnable.run();
                }
                mPendingActions.clear();
            }
        }

        private final void executeOrPostRunnable(Runnable action) {
            synchronized(mLock) {
                if (mSessionCallback == null) {
                    // The session is not initialized yet.
                    mPendingActions.add(action);
                } else {
                    if (mHandler.getLooper().isCurrentThread()) {
                        action.run();
                    } else {
                        // Posts the runnable if this is not called from the main thread
                        mHandler.post(action);
                    }
                }
            }
        }

        private final class OverlayViewCleanUpTask extends AsyncTask<View, Void, Void> {
            @Override
            protected Void doInBackground(View... views) {
                View overlayViewParent = views[0];
                try {
                    Thread.sleep(DETACH_OVERLAY_VIEW_TIMEOUT);
                } catch (InterruptedException e) {
                    return null;
                }
                if (isCancelled()) {
                    return null;
                }
                if (overlayViewParent.isAttachedToWindow()) {
                    Log.e(TAG, "Time out on releasing overlay view. Killing "
                            + overlayViewParent.getContext().getPackageName());
                    Process.killProcess(Process.myPid());
                }
                return null;
            }
        }
    }

    /**
     * Base class for a TV input session which represents an external device connected to a
     * hardware TV input.
     * <p>
     * This class is for an input which provides channels for the external set-top box to the
     * application. Once a TV input returns an implementation of this class on
     * {@link #onCreateSession(String)}, the framework will create a separate session for
     * a hardware TV Input (e.g. HDMI 1) and forward the application's surface to the session so
     * that the user can see the screen of the hardware TV Input when she tunes to a channel from
     * this TV input. The implementation of this class is expected to change the channel of the
     * external set-top box via a proprietary protocol when {@link HardwareSession#onTune(Uri)} is
     * requested by the application.
     * </p><p>
     * Note that this class is not for inputs for internal hardware like built-in tuner and HDMI 1.
     * </p>
     * @see #onCreateSession(String)
     */
    public abstract static class HardwareSession extends Session {

        /**
         * Creates a new HardwareSession.
         *
         * @param context The context of the application
         */
        public HardwareSession(Context context) {
            super(context);
        }

        private TvInputManager.Session mHardwareSession;
        private ITvInputSession mProxySession;
        private ITvInputSessionCallback mProxySessionCallback;
        private Handler mServiceHandler;

        /**
         * Returns the hardware TV input ID the external device is connected to.
         * <p>
         * TV input is expected to provide {@link android.R.attr#setupActivity} so that
         * the application can launch it before using this TV input. The setup activity may let
         * the user select the hardware TV input to which the external device is connected. The ID
         * of the selected one should be stored in the TV input so that it can be returned here.
         * </p>
         */
        public abstract String getHardwareInputId();

        private final TvInputManager.SessionCallback mHardwareSessionCallback =
                new TvInputManager.SessionCallback() {
            @Override
            public void onSessionCreated(TvInputManager.Session session) {
                mHardwareSession = session;
                SomeArgs args = SomeArgs.obtain();
                if (session != null) {
                    args.arg1 = HardwareSession.this;
                    args.arg2 = mProxySession;
                    args.arg3 = mProxySessionCallback;
                    args.arg4 = session.getToken();
                } else {
                    args.arg1 = null;
                    args.arg2 = null;
                    args.arg3 = mProxySessionCallback;
                    args.arg4 = null;
                    onRelease();
                }
                mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED, args)
                        .sendToTarget();
                session.tune(TvContract.buildChannelUriForPassthroughInput(getHardwareInputId()));
            }

            @Override
            public void onVideoAvailable(final TvInputManager.Session session) {
                if (mHardwareSession == session) {
                    onHardwareVideoAvailable();
                }
            }

            @Override
            public void onVideoUnavailable(final TvInputManager.Session session,
                    final int reason) {
                if (mHardwareSession == session) {
                    onHardwareVideoUnavailable(reason);
                }
            }
        };

        /**
         * This method will not be called in {@link HardwareSession}. Framework will
         * forward the application's surface to the hardware TV input.
         */
        @Override
        public final boolean onSetSurface(Surface surface) {
            Log.e(TAG, "onSetSurface() should not be called in HardwareProxySession.");
            return false;
        }

        /**
         * Called when the underlying hardware TV input session calls
         * {@link TvInputService.Session#notifyVideoAvailable()}.
         */
        public void onHardwareVideoAvailable() { }

        /**
         * Called when the underlying hardware TV input session calls
         * {@link TvInputService.Session#notifyVideoUnavailable(int)}.
         *
         * @param reason The reason that the hardware TV input stopped the playback:
         * <ul>
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * </ul>
         */
        public void onHardwareVideoUnavailable(int reason) { }
    }

    /** @hide */
    public static boolean isNavigationKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
                return true;
        }
        return false;
    }

    @SuppressLint("HandlerLeak")
    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_NOTIFY_SESSION_CREATED = 2;
        private static final int DO_ADD_HARDWARE_TV_INPUT = 3;
        private static final int DO_REMOVE_HARDWARE_TV_INPUT = 4;
        private static final int DO_ADD_HDMI_TV_INPUT = 5;
        private static final int DO_REMOVE_HDMI_TV_INPUT = 6;

        private void broadcastAddHardwareTvInput(int deviceId, TvInputInfo inputInfo) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).addHardwareTvInput(deviceId, inputInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while broadcasting.", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        private void broadcastAddHdmiTvInput(int id, TvInputInfo inputInfo) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).addHdmiTvInput(id, inputInfo);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while broadcasting.", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        private void broadcastRemoveTvInput(String inputId) {
            int n = mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    mCallbacks.getBroadcastItem(i).removeTvInput(inputId);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error while broadcasting.", e);
                }
            }
            mCallbacks.finishBroadcast();
        }

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg2;
                    String inputId = (String) args.arg3;
                    args.recycle();
                    Session sessionImpl = onCreateSession(inputId);
                    if (sessionImpl == null) {
                        try {
                            // Failed to create a session.
                            cb.onSessionCreated(null, null);
                        } catch (RemoteException e) {
                            Log.e(TAG, "error in onSessionCreated");
                        }
                        return;
                    }
                    ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                            sessionImpl, channel);
                    if (sessionImpl instanceof HardwareSession) {
                        HardwareSession proxySession =
                                ((HardwareSession) sessionImpl);
                        String harewareInputId = proxySession.getHardwareInputId();
                        if (TextUtils.isEmpty(harewareInputId) ||
                                !isPassthroughInput(harewareInputId)) {
                            if (TextUtils.isEmpty(harewareInputId)) {
                                Log.w(TAG, "Hardware input id is not setup yet.");
                            } else {
                                Log.w(TAG, "Invalid hardware input id : " + harewareInputId);
                            }
                            sessionImpl.onRelease();
                            try {
                                cb.onSessionCreated(null, null);
                            } catch (RemoteException e) {
                                Log.e(TAG, "error in onSessionCreated");
                            }
                            return;
                        }
                        proxySession.mProxySession = stub;
                        proxySession.mProxySessionCallback = cb;
                        proxySession.mServiceHandler = mServiceHandler;
                        TvInputManager manager = (TvInputManager) getSystemService(
                                Context.TV_INPUT_SERVICE);
                        manager.createSession(harewareInputId,
                                proxySession.mHardwareSessionCallback, mServiceHandler);
                    } else {
                        SomeArgs someArgs = SomeArgs.obtain();
                        someArgs.arg1 = sessionImpl;
                        someArgs.arg2 = stub;
                        someArgs.arg3 = cb;
                        someArgs.arg4 = null;
                        mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                                someArgs).sendToTarget();
                    }
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Session sessionImpl = (Session) args.arg1;
                    ITvInputSession stub = (ITvInputSession) args.arg2;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg3;
                    IBinder hardwareSessionToken = (IBinder) args.arg4;
                    try {
                        cb.onSessionCreated(stub, hardwareSessionToken);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated");
                    }
                    if (sessionImpl != null) {
                        sessionImpl.initialize(cb);
                    }
                    args.recycle();
                    return;
                }
                case DO_ADD_HARDWARE_TV_INPUT: {
                    TvInputHardwareInfo hardwareInfo = (TvInputHardwareInfo) msg.obj;
                    TvInputInfo inputInfo = onHardwareAdded(hardwareInfo);
                    if (inputInfo != null) {
                        broadcastAddHardwareTvInput(hardwareInfo.getDeviceId(), inputInfo);
                    }
                    return;
                }
                case DO_REMOVE_HARDWARE_TV_INPUT: {
                    TvInputHardwareInfo hardwareInfo = (TvInputHardwareInfo) msg.obj;
                    String inputId = onHardwareRemoved(hardwareInfo);
                    if (inputId != null) {
                        broadcastRemoveTvInput(inputId);
                    }
                    return;
                }
                case DO_ADD_HDMI_TV_INPUT: {
                    HdmiDeviceInfo deviceInfo = (HdmiDeviceInfo) msg.obj;
                    TvInputInfo inputInfo = onHdmiDeviceAdded(deviceInfo);
                    if (inputInfo != null) {
                        broadcastAddHdmiTvInput(deviceInfo.getId(), inputInfo);
                    }
                    return;
                }
                case DO_REMOVE_HDMI_TV_INPUT: {
                    HdmiDeviceInfo deviceInfo = (HdmiDeviceInfo) msg.obj;
                    String inputId = onHdmiDeviceRemoved(deviceInfo);
                    if (inputId != null) {
                        broadcastRemoveTvInput(inputId);
                    }
                    return;
                }
                default: {
                    Log.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }
    }
}
