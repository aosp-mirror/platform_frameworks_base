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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

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
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
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
     * Get the number of callbacks that are registered.
     * @hide
     */
    @VisibleForTesting
    public final int getRegisteredCallbackCount() {
        return mCallbacks.getRegisteredCallbackCount();
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

    /**
     * Base class for derived classes to implement to provide a TV input session.
     */
    public abstract static class Session implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();
        private final WindowManager mWindowManager;
        final Handler mHandler;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private View mOverlayView;
        private boolean mOverlayViewEnabled;
        private IBinder mWindowToken;
        private Rect mOverlayFrame;
        private ITvInputSessionCallback mSessionCallback;

        /**
         * Creates a new Session.
         *
         * @param context The context of the application
         */
        public Session(Context context) {
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
        public void notifySessionEvent(final String eventType, final Bundle eventArgs) {
            if (eventType == null) {
                throw new IllegalArgumentException("eventType should not be null.");
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifySessionEvent(" + eventType + ")");
                        mSessionCallback.onSessionEvent(eventType, eventArgs);
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
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyChannelRetuned");
                        mSessionCallback.onChannelRetuned(channelUri);
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyChannelRetuned");
                    }
                }
            });
        }

        /**
         * Sends the change on the track information. This is expected to be called whenever a track
         * is added/removed and the metadata of a track is modified.
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
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTracksChanged");
                        mSessionCallback.onTracksChanged(tracks);
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTracksChanged");
                    }
                }
            });
        }

        /**
         * Sends the ID of the selected track for a given track type. This is expected to be called
         * whenever there is a change on track selection.
         *
         * @param type The type of the selected track. The type can be
         *            {@link TvTrackInfo#TYPE_AUDIO}, {@link TvTrackInfo#TYPE_VIDEO} or
         *            {@link TvTrackInfo#TYPE_SUBTITLE}.
         * @param trackId The ID of the selected track.
         * @see #onSelectTrack
         */
        public void notifyTrackSelected(final int type, final String trackId) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyTrackSelected");
                        mSessionCallback.onTrackSelected(type, trackId);
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyTrackSelected");
                    }
                }
            });
        }

        /**
         * Informs the application that video is available and the playback of the TV stream has
         * been started.
         */
        public void notifyVideoAvailable() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoAvailable");
                        mSessionCallback.onVideoAvailable();
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyVideoAvailable");
                    }
                }
            });
        }

        /**
         * Informs the application that video is not available, so the TV input cannot continue
         * playing the TV stream.
         *
         * @param reason The reason that the TV input stopped the playback:
         * <ul>
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_UNKNOWN}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_TUNING}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL}
         * <li>{@link TvInputManager#VIDEO_UNAVAILABLE_REASON_BUFFERING}
         * </ul>
         */
        public void notifyVideoUnavailable(final int reason) {
            if (reason < TvInputManager.VIDEO_UNAVAILABLE_REASON_START
                    || reason > TvInputManager.VIDEO_UNAVAILABLE_REASON_END) {
                throw new IllegalArgumentException("Unknown reason: " + reason);
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyVideoUnavailable");
                        mSessionCallback.onVideoUnavailable(reason);
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
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentAllowed");
                        mSessionCallback.onContentAllowed();
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
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "notifyContentBlocked");
                        mSessionCallback.onContentBlocked(rating.flattenToString());
                    } catch (RemoteException e) {
                        Log.w(TAG, "error in notifyContentBlocked");
                    }
                }
            });
        }

        /**
         * Assigns a position of the {@link Surface} passed by {@link #onSetSurface}. The position
         * is relative to an overlay view. {@see #onOverlayViewSizeChanged}.
         *
         * @param left Left position in pixels, relative to the overlay view.
         * @param top Top position in pixels, relative to the overlay view.
         * @param right Right position in pixels, relative to the overlay view.
         * @param bottm Bottom position in pixels, relative to the overlay view.
         * @hide
         */
        @SystemApi
        public void layoutSurface(final int left, final int top, final int right, final int bottm) {
            if (left > right || top > bottm) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) Log.d(TAG, "layoutSurface (l=" + left + ", t=" + top + ", r="
                                + right + ", b=" + bottm + ",)");
                        mSessionCallback.onLayoutSurface(left, top, right, bottm);
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
         * Set the current session as the "main" session. See {@link TvView#setMainTvView} for the
         * meaning of "main".
         * <p>
         * This is primarily for HDMI-CEC active source management. TV input service that manages
         * HDMI-CEC logical device should make sure not only to select the corresponding HDMI
         * logical device as source device on {@code onSetMainSession(true)}, but also to select
         * internal device on {@code onSetMainSession(false)}. Also, if surface is set to non-main
         * session, it needs to select internal device after temporarily selecting corresponding
         * HDMI logical device for set up.
         * </p><p>
         * It is guaranteed that {@code onSetMainSession(true)} for new session is called first,
         * and {@code onSetMainSession(false)} for old session is called afterwards. This allows
         * {@code onSetMainSession(false)} to be no-op when TV input service knows that the next
         * main session corresponds to another HDMI logical device. Practically, this implies that
         * one TV input service should handle all HDMI port and HDMI-CEC logical devices for smooth
         * active source transition.
         * </p>
         *
         * @param isMainSession If true, session is main.
         * @hide
         */
        @SystemApi
        public void onSetMainSession(boolean isMainSession) {
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
            removeOverlayView(true);
            onRelease();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
        }

        /**
         * Calls {@link #onSetMainSession}.
         */
        void setMainSession(boolean isMainSession) {
            onSetMainSession(isMainSession);
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
            if (mOverlayView != null) {
                mWindowManager.removeView(mOverlayView);
                mOverlayView = null;
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
            mWindowManager.addView(mOverlayView, mWindowParams);
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
            if (!mOverlayViewEnabled || mOverlayView == null) {
                return;
            }
            mWindowParams.x = frame.left;
            mWindowParams.y = frame.top;
            mWindowParams.width = frame.right - frame.left;
            mWindowParams.height = frame.bottom - frame.top;
            mWindowManager.updateViewLayout(mOverlayView, mWindowParams);
        }

        /**
         * Removes the current overlay view.
         */
        void removeOverlayView(boolean clearWindowToken) {
            if (DEBUG) Log.d(TAG, "removeOverlayView(" + mOverlayView + ")");
            if (clearWindowToken) {
                mWindowToken = null;
                mOverlayFrame = null;
            }
            if (mOverlayView != null) {
                mWindowManager.removeView(mOverlayView);
                mOverlayView = null;
                mWindowParams = null;
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
            if (mOverlayView == null || !mOverlayView.isAttachedToWindow()) {
                return TvInputManager.Session.DISPATCH_NOT_HANDLED;
            }
            if (!mOverlayView.hasWindowFocus()) {
                mOverlayView.getViewRootImpl().windowFocusChanged(true, true);
            }
            if (isNavigationKey && mOverlayView.hasFocusable()) {
                // If mOverlayView has focusable views, navigation key events should be always
                // handled. If not, it can make the application UI navigation messed up.
                // For example, in the case that the left-most view is focused, a left key event
                // will not be handled in ViewRootImpl. Then, the left key event will be handled in
                // the application during the UI navigation of the TV input.
                mOverlayView.getViewRootImpl().dispatchInputEvent(event);
                return TvInputManager.Session.DISPATCH_HANDLED;
            } else {
                mOverlayView.getViewRootImpl().dispatchInputEvent(event, receiver);
                return TvInputManager.Session.DISPATCH_IN_PROGRESS;
            }
        }

        private void setSessionCallback(ITvInputSessionCallback callback) {
            mSessionCallback = callback;
        }
    }

    /**
     * Base class for a TV input session which represents an external device connected to a
     * hardware TV input. Once TV input returns an implementation of this class on
     * {@link #onCreateSession(String)}, the framework will create a hardware session and forward
     * the application's surface to the hardware TV input.
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
                    args.arg1 = mProxySession;
                    args.arg2 = mProxySessionCallback;
                    args.arg3 = session.getToken();
                } else {
                    args.arg1 = null;
                    args.arg2 = mProxySessionCallback;
                    args.arg3 = null;
                    onRelease();
                }
                mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED, args)
                        .sendToTarget();
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
                    sessionImpl.setSessionCallback(cb);
                    ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                            sessionImpl, channel);
                    if (sessionImpl instanceof HardwareSession) {
                        HardwareSession proxySession =
                                ((HardwareSession) sessionImpl);
                        String harewareInputId = proxySession.getHardwareInputId();
                        if (TextUtils.isEmpty(harewareInputId)) {
                            sessionImpl.onRelease();
                            Log.w(TAG, "Hardware input id is not setup yet.");
                            try {
                                cb.onSessionCreated(null, null);
                            } catch (RemoteException e) {
                                Log.e(TAG, "error in onSessionCreated");
                            }
                            return;
                        }
                        // TODO: check if the given ID is really hardware TV input.
                        proxySession.mProxySession = stub;
                        proxySession.mProxySessionCallback = cb;
                        proxySession.mServiceHandler = mServiceHandler;
                        TvInputManager manager = (TvInputManager) getSystemService(
                                Context.TV_INPUT_SERVICE);
                        manager.createSession(harewareInputId,
                                proxySession.mHardwareSessionCallback, mServiceHandler);
                    } else {
                        SomeArgs someArgs = SomeArgs.obtain();
                        someArgs.arg1 = stub;
                        someArgs.arg2 = cb;
                        someArgs.arg3 = null;
                        mServiceHandler.obtainMessage(ServiceHandler.DO_NOTIFY_SESSION_CREATED,
                                someArgs).sendToTarget();
                    }
                    return;
                }
                case DO_NOTIFY_SESSION_CREATED: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    ITvInputSession stub = (ITvInputSession) args.arg1;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg2;
                    IBinder hardwareSessionToken = (IBinder) args.arg3;
                    try {
                        cb.onSessionCreated(stub, hardwareSessionToken);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated");
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
