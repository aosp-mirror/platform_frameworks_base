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

package android.tv;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.tv.TvInputManager.Session;
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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.SomeArgs;

/**
 * A base class for implementing television input service.
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
    public static final String SERVICE_INTERFACE = "android.tv.TvInputService";

    private String mId;
    private final Handler mHandler = new ServiceHandler();
    private final RemoteCallbackList<ITvInputServiceCallback> mCallbacks =
            new RemoteCallbackList<ITvInputServiceCallback>();
    private boolean mAvailable;

    @Override
    public void onCreate() {
        super.onCreate();
        mId = TvInputInfo.generateInputIdForComponenetName(
                new ComponentName(getPackageName(), getClass().getName()));
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new ITvInputService.Stub() {
            @Override
            public void registerCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.register(cb);
                    // The first time a callback is registered, the service needs to report its
                    // availability status so that the system can know its initial value.
                    try {
                        cb.onAvailabilityChanged(mId, mAvailable);
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onAvailabilityChanged", e);
                    }
                }
            }

            @Override
            public void unregisterCallback(ITvInputServiceCallback cb) {
                if (cb != null) {
                    mCallbacks.unregister(cb);
                }
            }

            @Override
            public void createSession(InputChannel channel, ITvInputSessionCallback cb) {
                if (channel == null) {
                    Log.w(TAG, "Creating session without input channel");
                }
                if (cb == null) {
                    return;
                }
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = channel;
                args.arg2 = cb;
                mHandler.obtainMessage(ServiceHandler.DO_CREATE_SESSION, args).sendToTarget();
            }
        };
    }

    /**
     * Convenience method to notify an availability change of this TV input service.
     *
     * @param available {@code true} if the input service is available to show TV programs.
     */
    public final void setAvailable(boolean available) {
        if (available != mAvailable) {
            mAvailable = available;
            mHandler.obtainMessage(ServiceHandler.DO_BROADCAST_AVAILABILITY_CHANGE, available)
                    .sendToTarget();
        }
    }

    /**
     * Get the number of callbacks that are registered.
     *
     * @hide
     */
    @VisibleForTesting
    public final int getRegisteredCallbackCount() {
        return mCallbacks.getRegisteredCallbackCount();
    }

    /**
     * Returns a concrete implementation of {@link TvInputSessionImpl}.
     * <p>
     * May return {@code null} if this TV input service fails to create a session for some reason.
     * </p>
     */
    public abstract TvInputSessionImpl onCreateSession();

    /**
     * Base class for derived classes to implement to provide {@link TvInputManager.Session}.
     */
    public abstract class TvInputSessionImpl implements KeyEvent.Callback {
        private final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();
        private final WindowManager mWindowManager;
        private WindowManager.LayoutParams mWindowParams;
        private Surface mSurface;
        private View mOverlayView;
        private boolean mOverlayViewEnabled;
        private IBinder mWindowToken;
        private Rect mOverlayFrame;

        public TvInputSessionImpl() {
            mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
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
         * Called when the session is released.
         */
        public abstract void onRelease();

        /**
         * Sets the {@link Surface} for the current input session on which the TV input renders
         * video.
         *
         * @param surface {@link Surface} an application passes to this TV input session.
         * @return {@code true} if the surface was set, {@code false} otherwise.
         */
        public abstract boolean onSetSurface(Surface surface);

        /**
         * Sets the relative volume of the current TV input session to handle the change of audio
         * focus by setting.
         *
         * @param volume Volume scale from 0.0 to 1.0.
         */
        public abstract void onSetVolume(float volume);

        /**
         * Tunes to a given channel.
         *
         * @param channelUri The URI of the channel.
         * @return {@code true} the tuning was successful, {@code false} otherwise.
         */
        public abstract boolean onTune(Uri channelUri);

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
            removeOverlayView(true);
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
         * Calls {@link #onSetVolume}.
         */
        void setVolume(float volume) {
            onSetVolume(volume);
        }

        /**
         * Calls {@link #onTune}.
         */
        void tune(Uri channelUri) {
            onTune(channelUri);
            // TODO: Handle failure.
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
            if (DEBUG) {
                Log.d(TAG, "create overlay view(" + frame + ")");
            }
            mWindowToken = windowToken;
            mOverlayFrame = frame;
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
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
            if (DEBUG) {
                Log.d(TAG, "relayout overlay view(" + frame + ")");
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
            if (DEBUG) {
                Log.d(TAG, "remove overlay view(" + mOverlayView + ")");
            }
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
            if (event instanceof KeyEvent) {
                if (((KeyEvent) event).dispatch(this, mDispatcherState, this)) {
                    return Session.DISPATCH_HANDLED;
                }
            } else if (event instanceof MotionEvent) {
                MotionEvent motionEvent = (MotionEvent) event;
                final int source = motionEvent.getSource();
                if (motionEvent.isTouchEvent()) {
                    if (onTouchEvent(motionEvent)) {
                        return Session.DISPATCH_HANDLED;
                    }
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    if (onTrackballEvent(motionEvent)) {
                        return Session.DISPATCH_HANDLED;
                    }
                } else {
                    if (onGenericMotionEvent(motionEvent)) {
                        return Session.DISPATCH_HANDLED;
                    }
                }
            }
            if (mOverlayView == null) {
                return Session.DISPATCH_NOT_HANDLED;
            }
            if (!mOverlayView.hasWindowFocus()) {
                mOverlayView.getViewRootImpl().windowFocusChanged(true, true);
            }
            mOverlayView.getViewRootImpl().dispatchInputEvent(event, receiver);
            return Session.DISPATCH_IN_PROGRESS;
        }
    }

    private final class ServiceHandler extends Handler {
        private static final int DO_CREATE_SESSION = 1;
        private static final int DO_BROADCAST_AVAILABILITY_CHANGE = 2;

        @Override
        public final void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_CREATE_SESSION: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputChannel channel = (InputChannel) args.arg1;
                    ITvInputSessionCallback cb = (ITvInputSessionCallback) args.arg2;
                    try {
                        TvInputSessionImpl sessionImpl = onCreateSession();
                        if (sessionImpl == null) {
                            // Failed to create a session.
                            cb.onSessionCreated(null);
                        } else {
                            ITvInputSession stub = new ITvInputSessionWrapper(TvInputService.this,
                                    sessionImpl, channel);
                            cb.onSessionCreated(stub);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "error in onSessionCreated");
                    }
                    args.recycle();
                    return;
                }
                case DO_BROADCAST_AVAILABILITY_CHANGE: {
                    boolean isAvailable = (Boolean) msg.obj;
                    int n = mCallbacks.beginBroadcast();
                    try {
                        for (int i = 0; i < n; i++) {
                            mCallbacks.getBroadcastItem(i).onAvailabilityChanged(mId, isAvailable);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Unexpected exception", e);
                    } finally {
                        mCallbacks.finishBroadcast();
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
