/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.service.wallpaper;

import static android.app.WallpaperManager.COMMAND_FREEZE;
import static android.app.WallpaperManager.COMMAND_UNFREEZE;
import static android.graphics.Matrix.MSCALE_X;
import static android.graphics.Matrix.MSCALE_Y;
import static android.graphics.Matrix.MSKEW_X;
import static android.graphics.Matrix.MSKEW_Y;
import static android.view.SurfaceControl.METADATA_WINDOW_TYPE;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.BLASTBufferQueue;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowSession;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.InsetsVisibilities;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowLayout;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.ClientWindowFrames;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.HandlerCaller;
import com.android.internal.view.BaseIWindow;
import com.android.internal.view.BaseSurfaceHolder;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A wallpaper service is responsible for showing a live wallpaper behind
 * applications that would like to sit on top of it.  This service object
 * itself does very little -- its only purpose is to generate instances of
 * {@link Engine} as needed.  Implementing a wallpaper thus
 * involves subclassing from this, subclassing an Engine implementation,
 * and implementing {@link #onCreateEngine()} to return a new instance of
 * your engine.
 */
public abstract class WallpaperService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_WALLPAPER} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.wallpaper.WallpaperService";

    /**
     * Name under which a WallpaperService component publishes information
     * about itself.  This meta-data must reference an XML resource containing
     * a <code>&lt;{@link android.R.styleable#Wallpaper wallpaper}&gt;</code>
     * tag.
     */
    public static final String SERVICE_META_DATA = "android.service.wallpaper";

    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;
    static final float MIN_PAGE_ALLOWED_MARGIN = .05f;
    private static final int MIN_BITMAP_SCREENSHOT_WIDTH = 64;
    private static final long DEFAULT_UPDATE_SCREENSHOT_DURATION = 60 * 1000; //Once per minute
    private static final @NonNull RectF LOCAL_COLOR_BOUNDS =
            new RectF(0, 0, 1, 1);

    private static final int DO_ATTACH = 10;
    private static final int DO_DETACH = 20;
    private static final int DO_SET_DESIRED_SIZE = 30;
    private static final int DO_SET_DISPLAY_PADDING = 40;
    private static final int DO_IN_AMBIENT_MODE = 50;

    private static final int MSG_UPDATE_SURFACE = 10000;
    private static final int MSG_VISIBILITY_CHANGED = 10010;
    private static final int MSG_WALLPAPER_OFFSETS = 10020;
    private static final int MSG_WALLPAPER_COMMAND = 10025;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static final int MSG_WINDOW_RESIZED = 10030;
    private static final int MSG_WINDOW_MOVED = 10035;
    private static final int MSG_TOUCH_EVENT = 10040;
    private static final int MSG_REQUEST_WALLPAPER_COLORS = 10050;
    private static final int MSG_ZOOM = 10100;
    private static final int MSG_SCALE_PREVIEW = 10110;
    private static final int MSG_REPORT_SHOWN = 10150;
    private static final int MSG_UPDATE_DIMMING = 10200;
    private static final List<Float> PROHIBITED_STEPS = Arrays.asList(0f, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY);

    private static final int NOTIFY_COLORS_RATE_LIMIT_MS = 1000;

    private static final boolean ENABLE_WALLPAPER_DIMMING =
            SystemProperties.getBoolean("persist.debug.enable_wallpaper_dimming", true);

    private static final long DIMMING_ANIMATION_DURATION_MS = 300L;

    private final ArrayList<Engine> mActiveEngines
            = new ArrayList<Engine>();

    static final class WallpaperCommand {
        String action;
        int x;
        int y;
        int z;
        Bundle extras;
        boolean sync;
    }

    /**
     * The actual implementation of a wallpaper.  A wallpaper service may
     * have multiple instances running (for example as a real wallpaper
     * and as a preview), each of which is represented by its own Engine
     * instance.  You must implement {@link WallpaperService#onCreateEngine()}
     * to return your concrete Engine implementation.
     */
    public class Engine {
        IWallpaperEngineWrapper mIWallpaperEngine;
        final ArraySet<RectF> mLocalColorAreas = new ArraySet<>(4);
        final ArraySet<RectF> mLocalColorsToAdd = new ArraySet<>(4);

        // 2D matrix [x][y] to represent a page of a portion of a window
        EngineWindowPage[] mWindowPages = new EngineWindowPage[0];
        Bitmap mLastScreenshot;
        int mLastWindowPage = -1;
        private boolean mResetWindowPages;

        // Copies from mIWallpaperEngine.
        HandlerCaller mCaller;
        IWallpaperConnection mConnection;
        IBinder mWindowToken;

        boolean mInitializing = true;
        boolean mVisible;
        boolean mReportedVisible;
        boolean mDestroyed;
        // Set to true after receiving WallpaperManager#COMMAND_FREEZE. It's reset back to false
        // after receiving WallpaperManager#COMMAND_UNFREEZE. COMMAND_FREEZE is fully applied once
        // mScreenshotSurfaceControl isn't null. When this happens, then Engine is notified through
        // doVisibilityChanged that main wallpaper surface is no longer visible and the wallpaper
        // host receives onVisibilityChanged(false) callback.
        private boolean mFrozenRequested = false;

        // Current window state.
        boolean mCreated;
        boolean mSurfaceCreated;
        boolean mIsCreating;
        boolean mDrawingAllowed;
        boolean mOffsetsChanged;
        boolean mFixedSizeAllowed;
        boolean mShouldDim;
        // Whether the wallpaper should be dimmed by default (when no additional dimming is applied)
        // based on its color hints
        boolean mShouldDimByDefault;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        int mCurWidth;
        int mCurHeight;
        float mZoom = 0f;
        int mWindowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int mWindowPrivateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS
                | WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST;
        int mCurWindowFlags = mWindowFlags;
        int mCurWindowPrivateFlags = mWindowPrivateFlags;
        Rect mPreviewSurfacePosition;
        final ClientWindowFrames mWinFrames = new ClientWindowFrames();
        final Rect mDispatchedContentInsets = new Rect();
        final Rect mDispatchedStableInsets = new Rect();
        DisplayCutout mDispatchedDisplayCutout = DisplayCutout.NO_CUTOUT;
        final InsetsState mInsetsState = new InsetsState();
        final InsetsVisibilities mRequestedVisibilities = new InsetsVisibilities();
        final InsetsSourceControl[] mTempControls = new InsetsSourceControl[0];
        final MergedConfiguration mMergedConfiguration = new MergedConfiguration();
        final Bundle mSyncSeqIdBundle = new Bundle();
        private final Point mSurfaceSize = new Point();
        private final Point mLastSurfaceSize = new Point();
        private final Matrix mTmpMatrix = new Matrix();
        private final float[] mTmpValues = new float[9];

        final WindowManager.LayoutParams mLayout
                = new WindowManager.LayoutParams();
        IWindowSession mSession;

        final Object mLock = new Object();
        boolean mOffsetMessageEnqueued;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        float mPendingXOffset;
        float mPendingYOffset;
        float mPendingXOffsetStep;
        float mPendingYOffsetStep;
        boolean mPendingSync;
        MotionEvent mPendingMove;
        boolean mIsInAmbientMode;

        // Needed for throttling onComputeColors.
        private long mLastColorInvalidation;
        private final Runnable mNotifyColorsChanged = this::notifyColorsChanged;
        private final Supplier<Long> mClockFunction;
        private final Handler mHandler;

        private Display mDisplay;
        private Context mDisplayContext;
        private int mDisplayState;
        private @Surface.Rotation int mDisplayInstallOrientation;
        private float mWallpaperDimAmount = 0.05f;
        private float mPreviousWallpaperDimAmount = mWallpaperDimAmount;
        private float mDefaultDimAmount = mWallpaperDimAmount;

        SurfaceControl mSurfaceControl = new SurfaceControl();
        SurfaceControl mBbqSurfaceControl;
        BLASTBufferQueue mBlastBufferQueue;
        private SurfaceControl mScreenshotSurfaceControl;
        private Point mScreenshotSize = new Point();

        final BaseSurfaceHolder mSurfaceHolder = new BaseSurfaceHolder() {
            {
                mRequestedFormat = PixelFormat.RGBX_8888;
            }

            @Override
            public boolean onAllowLockCanvas() {
                return mDrawingAllowed;
            }

            @Override
            public void onRelayoutContainer() {
                Message msg = mCaller.obtainMessage(MSG_UPDATE_SURFACE);
                mCaller.sendMessage(msg);
            }

            @Override
            public void onUpdateSurface() {
                Message msg = mCaller.obtainMessage(MSG_UPDATE_SURFACE);
                mCaller.sendMessage(msg);
            }

            public boolean isCreating() {
                return mIsCreating;
            }

            @Override
            public void setFixedSize(int width, int height) {
                if (!mFixedSizeAllowed) {
                    // Regular apps can't do this.  It can only work for
                    // certain designs of window animations, so you can't
                    // rely on it.
                    throw new UnsupportedOperationException(
                            "Wallpapers currently only support sizing from layout");
                }
                super.setFixedSize(width, height);
            }

            public void setKeepScreenOn(boolean screenOn) {
                throw new UnsupportedOperationException(
                        "Wallpapers do not support keep screen on");
            }

            private void prepareToDraw() {
                if (mDisplayState == Display.STATE_DOZE
                        || mDisplayState == Display.STATE_DOZE_SUSPEND) {
                    try {
                        mSession.pokeDrawLock(mWindow);
                    } catch (RemoteException e) {
                        // System server died, can be ignored.
                    }
                }
            }

            @Override
            public Canvas lockCanvas() {
                prepareToDraw();
                return super.lockCanvas();
            }

            @Override
            public Canvas lockCanvas(Rect dirty) {
                prepareToDraw();
                return super.lockCanvas(dirty);
            }

            @Override
            public Canvas lockHardwareCanvas() {
                prepareToDraw();
                return super.lockHardwareCanvas();
            }
        };

        final class WallpaperInputEventReceiver extends InputEventReceiver {
            public WallpaperInputEventReceiver(InputChannel inputChannel, Looper looper) {
                super(inputChannel, looper);
            }

            @Override
            public void onInputEvent(InputEvent event) {
                boolean handled = false;
                try {
                    if (event instanceof MotionEvent
                            && (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                        MotionEvent dup = MotionEvent.obtainNoHistory((MotionEvent)event);
                        dispatchPointer(dup);
                        handled = true;
                    }
                } finally {
                    finishInputEvent(event, handled);
                }
            }
        }
        WallpaperInputEventReceiver mInputEventReceiver;

        final BaseIWindow mWindow = new BaseIWindow() {
            @Override
            public void resized(ClientWindowFrames frames, boolean reportDraw,
                    MergedConfiguration mergedConfiguration, InsetsState insetsState,
                    boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId,
                    int syncSeqId, int resizeMode) {
                Message msg = mCaller.obtainMessageIO(MSG_WINDOW_RESIZED,
                        reportDraw ? 1 : 0,
                        mergedConfiguration);
                mCaller.sendMessage(msg);
            }

            @Override
            public void moved(int newX, int newY) {
                Message msg = mCaller.obtainMessageII(MSG_WINDOW_MOVED, newX, newY);
                mCaller.sendMessage(msg);
            }

            @Override
            public void dispatchAppVisibility(boolean visible) {
                // We don't do this in preview mode; we'll let the preview
                // activity tell us when to run.
                if (!mIWallpaperEngine.mIsPreview) {
                    Message msg = mCaller.obtainMessageI(MSG_VISIBILITY_CHANGED,
                            visible ? 1 : 0);
                    mCaller.sendMessage(msg);
                }
            }

            @Override
            public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                    float zoom, boolean sync) {
                synchronized (mLock) {
                    if (DEBUG) Log.v(TAG, "Dispatch wallpaper offsets: " + x + ", " + y);
                    mPendingXOffset = x;
                    mPendingYOffset = y;
                    mPendingXOffsetStep = xStep;
                    mPendingYOffsetStep = yStep;
                    if (sync) {
                        mPendingSync = true;
                    }
                    if (!mOffsetMessageEnqueued) {
                        mOffsetMessageEnqueued = true;
                        Message msg = mCaller.obtainMessage(MSG_WALLPAPER_OFFSETS);
                        mCaller.sendMessage(msg);
                    }
                    Message msg = mCaller.obtainMessageI(MSG_ZOOM, Float.floatToIntBits(zoom));
                    mCaller.sendMessage(msg);
                }
            }

            @Override
            public void dispatchWallpaperCommand(String action, int x, int y,
                    int z, Bundle extras, boolean sync) {
                synchronized (mLock) {
                    if (DEBUG) Log.v(TAG, "Dispatch wallpaper command: " + x + ", " + y);
                    WallpaperCommand cmd = new WallpaperCommand();
                    cmd.action = action;
                    cmd.x = x;
                    cmd.y = y;
                    cmd.z = z;
                    cmd.extras = extras;
                    cmd.sync = sync;
                    Message msg = mCaller.obtainMessage(MSG_WALLPAPER_COMMAND);
                    msg.obj = cmd;
                    mCaller.sendMessage(msg);
                }
            }
        };

        /**
         * Default constructor
         */
        public Engine() {
            this(SystemClock::elapsedRealtime, Handler.getMain());
        }

        /**
         * Constructor used for test purposes.
         *
         * @param clockFunction Supplies current times in millis.
         * @param handler Used for posting/deferring asynchronous calls.
         * @hide
         */
        @VisibleForTesting
        public Engine(Supplier<Long> clockFunction, Handler handler) {
            mClockFunction = clockFunction;
            mHandler = handler;
        }

        /**
         * Provides access to the surface in which this wallpaper is drawn.
         */
        public SurfaceHolder getSurfaceHolder() {
            return mSurfaceHolder;
        }

        /**
         * Convenience for {@link WallpaperManager#getDesiredMinimumWidth()
         * WallpaperManager.getDesiredMinimumWidth()}, returning the width
         * that the system would like this wallpaper to run in.
         */
        public int getDesiredMinimumWidth() {
            return mIWallpaperEngine.mReqWidth;
        }

        /**
         * Convenience for {@link WallpaperManager#getDesiredMinimumHeight()
         * WallpaperManager.getDesiredMinimumHeight()}, returning the height
         * that the system would like this wallpaper to run in.
         */
        public int getDesiredMinimumHeight() {
            return mIWallpaperEngine.mReqHeight;
        }

        /**
         * Return whether the wallpaper is currently visible to the user,
         * this is the last value supplied to
         * {@link #onVisibilityChanged(boolean)}.
         */
        public boolean isVisible() {
            return mReportedVisible;
        }

        /**
         * Return whether the wallpaper is capable of extracting local colors in a rectangle area,
         * Must implement without calling super:
         * {@link #addLocalColorsAreas(List)}
         * {@link #removeLocalColorsAreas(List)}
         * When local colors change, call {@link #notifyLocalColorsChanged(List, List)}
         * See {@link com.android.systemui.wallpapers.ImageWallpaper} for an example
         * @hide
         */
        public boolean supportsLocalColorExtraction() {
            return false;
        }

        /**
         * Returns true if this engine is running in preview mode -- that is,
         * it is being shown to the user before they select it as the actual
         * wallpaper.
         */
        public boolean isPreview() {
            return mIWallpaperEngine.mIsPreview;
        }

        /**
         * Returns true if this engine is running in ambient mode -- that is,
         * it is being shown in low power mode, on always on display.
         * @hide
         */
        @SystemApi
        public boolean isInAmbientMode() {
            return mIsInAmbientMode;
        }

        /**
         * This will be called when the wallpaper is first started. If true is returned, the system
         * will zoom in the wallpaper by default and zoom it out as the user interacts,
         * to create depth. Otherwise, zoom will have to be handled manually
         * in {@link #onZoomChanged(float)}.
         *
         * @hide
         */
        public boolean shouldZoomOutWallpaper() {
            return false;
        }

        /**
         * This will be called in the end of {@link #updateSurface(boolean, boolean, boolean)}.
         * If true is returned, the engine will not report shown until rendering finished is
         * reported. Otherwise, the engine will report shown immediately right after redraw phase
         * in {@link #updateSurface(boolean, boolean, boolean)}.
         *
         * @hide
         */
        public boolean shouldWaitForEngineShown() {
            return false;
        }

        /**
         * Reports the rendering is finished, stops waiting, then invokes
         * {@link IWallpaperEngineWrapper#reportShown()}.
         *
         * @hide
         */
        public void reportEngineShown(boolean waitForEngineShown) {
            if (mIWallpaperEngine.mShownReported) return;
            if (!waitForEngineShown) {
                Message message = mCaller.obtainMessage(MSG_REPORT_SHOWN);
                mCaller.removeMessages(MSG_REPORT_SHOWN);
                mCaller.sendMessage(message);
            } else {
                // if we are already waiting, no need to reset the timeout.
                if (!mCaller.hasMessages(MSG_REPORT_SHOWN)) {
                    Message message = mCaller.obtainMessage(MSG_REPORT_SHOWN);
                    mCaller.sendMessageDelayed(message, TimeUnit.SECONDS.toMillis(5));
                }
            }
        }

        /**
         * Control whether this wallpaper will receive raw touch events
         * from the window manager as the user interacts with the window
         * that is currently displaying the wallpaper.  By default they
         * are turned off.  If enabled, the events will be received in
         * {@link #onTouchEvent(MotionEvent)}.
         */
        public void setTouchEventsEnabled(boolean enabled) {
            mWindowFlags = enabled
                    ? (mWindowFlags&~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                    : (mWindowFlags|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            if (mCreated) {
                updateSurface(false, false, false);
            }
        }

        /**
         * Control whether this wallpaper will receive notifications when the wallpaper
         * has been scrolled. By default, wallpapers will receive notifications, although
         * the default static image wallpapers do not. It is a performance optimization to
         * set this to false.
         *
         * @param enabled whether the wallpaper wants to receive offset notifications
         */
        public void setOffsetNotificationsEnabled(boolean enabled) {
            mWindowPrivateFlags = enabled
                    ? (mWindowPrivateFlags |
                        WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS)
                    : (mWindowPrivateFlags &
                        ~WindowManager.LayoutParams.PRIVATE_FLAG_WANTS_OFFSET_NOTIFICATIONS);
            if (mCreated) {
                updateSurface(false, false, false);
            }
        }

        /** @hide */
        public void setShowForAllUsers(boolean show) {
            mWindowPrivateFlags = show
                    ? (mWindowPrivateFlags
                        | WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
                    : (mWindowPrivateFlags
                        & ~WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
            if (mCreated) {
                updateSurface(false, false, false);
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage
        public void setFixedSizeAllowed(boolean allowed) {
            mFixedSizeAllowed = allowed;
        }

        /**
         * Returns the current scale of the surface
         * @hide
         */
        @VisibleForTesting
        public float getZoom() {
            return mZoom;
        }

        /**
         * Called once to initialize the engine.  After returning, the
         * engine's surface will be created by the framework.
         */
        public void onCreate(SurfaceHolder surfaceHolder) {
        }

        /**
         * Called right before the engine is going away.  After this the
         * surface will be destroyed and this Engine object is no longer
         * valid.
         */
        public void onDestroy() {
        }

        /**
         * Called to inform you of the wallpaper becoming visible or
         * hidden.  <em>It is very important that a wallpaper only use
         * CPU while it is visible.</em>.
         */
        public void onVisibilityChanged(boolean visible) {
        }

        /**
         * Called with the current insets that are in effect for the wallpaper.
         * This gives you the part of the overall wallpaper surface that will
         * generally be visible to the user (ignoring position offsets applied to it).
         *
         * @param insets Insets to apply.
         */
        public void onApplyWindowInsets(WindowInsets insets) {
        }

        /**
         * Called as the user performs touch-screen interaction with the
         * window that is currently showing this wallpaper.  Note that the
         * events you receive here are driven by the actual application the
         * user is interacting with, so if it is slow you will get fewer
         * move events.
         */
        public void onTouchEvent(MotionEvent event) {
        }

        /**
         * Called to inform you of the wallpaper's offsets changing
         * within its contain, corresponding to the container's
         * call to {@link WallpaperManager#setWallpaperOffsets(IBinder, float, float)
         * WallpaperManager.setWallpaperOffsets()}.
         */
        public void onOffsetsChanged(float xOffset, float yOffset,
                float xOffsetStep, float yOffsetStep,
                int xPixelOffset, int yPixelOffset) {
        }

        /**
         * Process a command that was sent to the wallpaper with
         * {@link WallpaperManager#sendWallpaperCommand}.
         * The default implementation does nothing, and always returns null
         * as the result.
         *
         * @param action The name of the command to perform.  This tells you
         * what to do and how to interpret the rest of the arguments.
         * @param x Generic integer parameter.
         * @param y Generic integer parameter.
         * @param z Generic integer parameter.
         * @param extras Any additional parameters.
         * @param resultRequested If true, the caller is requesting that
         * a result, appropriate for the command, be returned back.
         * @return If returning a result, create a Bundle and place the
         * result data in to it.  Otherwise return null.
         */
        public Bundle onCommand(String action, int x, int y, int z,
                Bundle extras, boolean resultRequested) {
            return null;
        }

        /**
         * Called when the device enters or exits ambient mode.
         *
         * @param inAmbientMode {@code true} if in ambient mode.
         * @param animationDuration How long the transition animation to change the ambient state
         *                          should run, in milliseconds. If 0 is passed as the argument
         *                          here, the state should be switched immediately.
         *
         * @see #isInAmbientMode()
         * @see WallpaperInfo#supportsAmbientMode()
         * @hide
         */
        @SystemApi
        public void onAmbientModeChanged(boolean inAmbientMode, long animationDuration) {
        }

        /**
         * Called when an application has changed the desired virtual size of
         * the wallpaper.
         */
        public void onDesiredSizeChanged(int desiredWidth, int desiredHeight) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceChanged
         * SurfaceHolder.Callback.surfaceChanged()}.
         */
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback2#surfaceRedrawNeeded
         * SurfaceHolder.Callback.surfaceRedrawNeeded()}.
         */
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceCreated
         * SurfaceHolder.Callback.surfaceCreated()}.
         */
        public void onSurfaceCreated(SurfaceHolder holder) {
        }

        /**
         * Convenience for {@link SurfaceHolder.Callback#surfaceDestroyed
         * SurfaceHolder.Callback.surfaceDestroyed()}.
         */
        public void onSurfaceDestroyed(SurfaceHolder holder) {
        }

        /**
         * Called when the zoom level of the wallpaper changed.
         * This method will be called with the initial zoom level when the surface is created.
         *
         * @param zoom the zoom level, between 0 indicating fully zoomed in and 1 indicating fully
         *             zoomed out.
         */
        public void onZoomChanged(@FloatRange(from = 0f, to = 1f) float zoom) {
        }

        /**
         * Notifies the engine that wallpaper colors changed significantly.
         * This will trigger a {@link #onComputeColors()} call.
         */
        public void notifyColorsChanged() {
            final long now = mClockFunction.get();
            if (now - mLastColorInvalidation < NOTIFY_COLORS_RATE_LIMIT_MS) {
                Log.w(TAG, "This call has been deferred. You should only call "
                        + "notifyColorsChanged() once every "
                        + (NOTIFY_COLORS_RATE_LIMIT_MS / 1000f) + " seconds.");
                if (!mHandler.hasCallbacks(mNotifyColorsChanged)) {
                    mHandler.postDelayed(mNotifyColorsChanged, NOTIFY_COLORS_RATE_LIMIT_MS);
                }
                return;
            }
            mLastColorInvalidation = now;
            mHandler.removeCallbacks(mNotifyColorsChanged);

            try {
                final WallpaperColors newColors = onComputeColors();
                if (mConnection != null) {
                    mConnection.onWallpaperColorsChanged(newColors, mDisplay.getDisplayId());
                } else {
                    Log.w(TAG, "Can't notify system because wallpaper connection "
                            + "was not established.");
                }
                mResetWindowPages = true;
                processLocalColors(mPendingXOffset, mPendingXOffsetStep);
            } catch (RemoteException e) {
                Log.w(TAG, "Can't notify system because wallpaper connection was lost.", e);
            }
        }

        /**
         * Called by the system when it needs to know what colors the wallpaper is using.
         * You might return null if no color information is available at the moment.
         * In that case you might want to call {@link #notifyColorsChanged()} when
         * color information becomes available.
         * <p>
         * The simplest way of creating a {@link android.app.WallpaperColors} object is by using
         * {@link android.app.WallpaperColors#fromBitmap(Bitmap)} or
         * {@link android.app.WallpaperColors#fromDrawable(Drawable)}, but you can also specify
         * your main colors by constructing a {@link android.app.WallpaperColors} object manually.
         *
         * @return Wallpaper colors.
         */
        public @Nullable WallpaperColors onComputeColors() {
            return null;
        }

        /**
         * Send the changed local color areas for the connection
         * @param regions
         * @param colors
         * @hide
         */
        public void notifyLocalColorsChanged(@NonNull List<RectF> regions,
                @NonNull List<WallpaperColors> colors)
                throws RuntimeException {
            for (int i = 0; i < regions.size() && i < colors.size(); i++) {
                WallpaperColors color = colors.get(i);
                RectF area = regions.get(i);
                if (color == null || area == null) {
                    if (DEBUG) {
                        Log.e(TAG, "notifyLocalColorsChanged null values. color: "
                                + color + " area " + area);
                    }
                    continue;
                }
                try {
                    mConnection.onLocalWallpaperColorsChanged(
                            area,
                            color,
                            mDisplayContext.getDisplayId()
                    );
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            WallpaperColors primaryColors = mIWallpaperEngine.mWallpaperManager
                    .getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            setPrimaryWallpaperColors(primaryColors);
        }

        private void setPrimaryWallpaperColors(WallpaperColors colors) {
            if (colors == null) {
                return;
            }
            int colorHints = colors.getColorHints();
            mShouldDimByDefault = ((colorHints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0
                    && (colorHints & WallpaperColors.HINT_SUPPORTS_DARK_THEME) == 0);

            // If default dimming value changes and no additional dimming is applied
            if (mShouldDimByDefault != mShouldDim && mWallpaperDimAmount == 0f) {
                mShouldDim = mShouldDimByDefault;
                updateSurfaceDimming();
            }
        }

        /**
         * Update the dim amount of the wallpaper by updating the surface.
         *
         * @param dimAmount Float amount between [0.0, 1.0] to dim the wallpaper.
         */
        private void updateWallpaperDimming(float dimAmount) {
            if (dimAmount == mWallpaperDimAmount) {
                return;
            }

            // Custom dim amount cannot be less than the default dim amount.
            mWallpaperDimAmount = Math.max(mDefaultDimAmount, dimAmount);
            // If dim amount is 0f (additional dimming is removed), then the wallpaper should dim
            // based on its default wallpaper color hints.
            mShouldDim = dimAmount != 0f || mShouldDimByDefault;
            updateSurfaceDimming();
        }

        private void updateSurfaceDimming() {
            if (!ENABLE_WALLPAPER_DIMMING || mBbqSurfaceControl == null) {
                return;
            }

            SurfaceControl.Transaction surfaceControlTransaction = new SurfaceControl.Transaction();
            // TODO: apply the dimming to preview as well once surface transparency works in
            // preview mode.
            if ((!isPreview() && mShouldDim)
                    || mPreviousWallpaperDimAmount != mWallpaperDimAmount) {
                Log.v(TAG, "Setting wallpaper dimming: " + mWallpaperDimAmount);

                // Animate dimming to gradually change the wallpaper alpha from the previous
                // dim amount to the new amount only if the dim amount changed.
                ValueAnimator animator = ValueAnimator.ofFloat(
                        mPreviousWallpaperDimAmount, mWallpaperDimAmount);
                animator.setDuration(DIMMING_ANIMATION_DURATION_MS);
                animator.addUpdateListener((ValueAnimator va) -> {
                    final float dimValue = (float) va.getAnimatedValue();
                    if (mBbqSurfaceControl != null) {
                        surfaceControlTransaction
                                .setAlpha(mBbqSurfaceControl, 1 - dimValue).apply();
                    }
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        updateSurface(false, false, true);
                    }
                });
                animator.start();
            } else {
                Log.v(TAG, "Setting wallpaper dimming: " + 0);
                surfaceControlTransaction.setAlpha(mBbqSurfaceControl, 1.0f).apply();
                updateSurface(false, false, true);
            }

            mPreviousWallpaperDimAmount = mWallpaperDimAmount;
        }

        /**
         * Sets internal engine state. Only for testing.
         * @param created {@code true} or {@code false}.
         * @hide
         */
        @VisibleForTesting
        public void setCreated(boolean created) {
            mCreated = created;
        }

        protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
            out.print(prefix); out.print("mInitializing="); out.print(mInitializing);
                    out.print(" mDestroyed="); out.println(mDestroyed);
            out.print(prefix); out.print("mVisible="); out.print(mVisible);
                    out.print(" mReportedVisible="); out.println(mReportedVisible);
            out.print(prefix); out.print("mDisplay="); out.println(mDisplay);
            out.print(prefix); out.print("mCreated="); out.print(mCreated);
                    out.print(" mSurfaceCreated="); out.print(mSurfaceCreated);
                    out.print(" mIsCreating="); out.print(mIsCreating);
                    out.print(" mDrawingAllowed="); out.println(mDrawingAllowed);
            out.print(prefix); out.print("mWidth="); out.print(mWidth);
                    out.print(" mCurWidth="); out.print(mCurWidth);
                    out.print(" mHeight="); out.print(mHeight);
                    out.print(" mCurHeight="); out.println(mCurHeight);
            out.print(prefix); out.print("mType="); out.print(mType);
                    out.print(" mWindowFlags="); out.print(mWindowFlags);
                    out.print(" mCurWindowFlags="); out.println(mCurWindowFlags);
            out.print(prefix); out.print("mWindowPrivateFlags="); out.print(mWindowPrivateFlags);
                    out.print(" mCurWindowPrivateFlags="); out.println(mCurWindowPrivateFlags);
            out.print(prefix); out.println("mWinFrames="); out.println(mWinFrames);
            out.print(prefix); out.print("mConfiguration=");
                    out.println(mMergedConfiguration.getMergedConfiguration());
            out.print(prefix); out.print("mLayout="); out.println(mLayout);
            out.print(prefix); out.print("mZoom="); out.println(mZoom);
            out.print(prefix); out.print("mPreviewSurfacePosition=");
                    out.println(mPreviewSurfacePosition);
            synchronized (mLock) {
                out.print(prefix); out.print("mPendingXOffset="); out.print(mPendingXOffset);
                        out.print(" mPendingXOffset="); out.println(mPendingXOffset);
                out.print(prefix); out.print("mPendingXOffsetStep=");
                        out.print(mPendingXOffsetStep);
                        out.print(" mPendingXOffsetStep="); out.println(mPendingXOffsetStep);
                out.print(prefix); out.print("mOffsetMessageEnqueued=");
                        out.print(mOffsetMessageEnqueued);
                        out.print(" mPendingSync="); out.println(mPendingSync);
                if (mPendingMove != null) {
                    out.print(prefix); out.print("mPendingMove="); out.println(mPendingMove);
                }
            }
        }

        /**
         * Set the wallpaper zoom to the given value. This value will be ignored when in ambient
         * mode (and zoom will be reset to 0).
         * @hide
         * @param zoom between 0 and 1 (inclusive) indicating fully zoomed in to fully zoomed out
         *              respectively.
         */
        @VisibleForTesting
        public void setZoom(float zoom) {
            if (DEBUG) {
                Log.v(TAG, "set zoom received: " + zoom);
            }
            boolean updated = false;
            synchronized (mLock) {
                if (DEBUG) {
                    Log.v(TAG, "mZoom: " + mZoom + " updated: " + zoom);
                }
                if (mIsInAmbientMode) {
                    mZoom = 0;
                }
                if (Float.compare(zoom, mZoom) != 0) {
                    mZoom = zoom;
                    updated = true;
                }
            }
            if (DEBUG) Log.v(TAG, "setZoom updated? " + updated);
            if (updated && !mDestroyed) {
                onZoomChanged(mZoom);
            }
        }

        private void dispatchPointer(MotionEvent event) {
            if (event.isTouchEvent()) {
                synchronized (mLock) {
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        mPendingMove = event;
                    } else {
                        mPendingMove = null;
                    }
                }
                Message msg = mCaller.obtainMessageO(MSG_TOUCH_EVENT, event);
                mCaller.sendMessage(msg);
            } else {
                event.recycle();
            }
        }

        private void updateConfiguration(MergedConfiguration mergedConfiguration) {
            mMergedConfiguration.setTo(mergedConfiguration);
        }

        void updateSurface(boolean forceRelayout, boolean forceReport, boolean redrawNeeded) {
            if (mDestroyed) {
                Log.w(TAG, "Ignoring updateSurface due to destroyed");
                return;
            }

            boolean fixedSize = false;
            int myWidth = mSurfaceHolder.getRequestedWidth();
            if (myWidth <= 0) myWidth = ViewGroup.LayoutParams.MATCH_PARENT;
            else fixedSize = true;
            int myHeight = mSurfaceHolder.getRequestedHeight();
            if (myHeight <= 0) myHeight = ViewGroup.LayoutParams.MATCH_PARENT;
            else fixedSize = true;

            final boolean creating = !mCreated;
            final boolean surfaceCreating = !mSurfaceCreated;
            final boolean formatChanged = mFormat != mSurfaceHolder.getRequestedFormat();
            boolean sizeChanged = mWidth != myWidth || mHeight != myHeight;
            boolean insetsChanged = !mCreated;
            final boolean typeChanged = mType != mSurfaceHolder.getRequestedType();
            final boolean flagsChanged = mCurWindowFlags != mWindowFlags ||
                    mCurWindowPrivateFlags != mWindowPrivateFlags;
            if (forceRelayout || creating || surfaceCreating || formatChanged || sizeChanged
                    || typeChanged || flagsChanged || redrawNeeded
                    || !mIWallpaperEngine.mShownReported) {

                if (DEBUG) Log.v(TAG, "Changes: creating=" + creating
                        + " format=" + formatChanged + " size=" + sizeChanged);

                try {
                    mWidth = myWidth;
                    mHeight = myHeight;
                    mFormat = mSurfaceHolder.getRequestedFormat();
                    mType = mSurfaceHolder.getRequestedType();

                    mLayout.x = 0;
                    mLayout.y = 0;

                    mLayout.format = mFormat;

                    mCurWindowFlags = mWindowFlags;
                    mLayout.flags = mWindowFlags
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

                    final Configuration config = mMergedConfiguration.getMergedConfiguration();
                    final Rect maxBounds = config.windowConfiguration.getMaxBounds();
                    if (myWidth == ViewGroup.LayoutParams.MATCH_PARENT
                            && myHeight == ViewGroup.LayoutParams.MATCH_PARENT) {
                        mLayout.width = myWidth;
                        mLayout.height = myHeight;
                        mLayout.flags &= ~WindowManager.LayoutParams.FLAG_SCALED;
                    } else {
                        final float layoutScale = Math.max(
                                maxBounds.width() / (float) myWidth,
                                maxBounds.height() / (float) myHeight);
                        mLayout.width = (int) (myWidth * layoutScale + .5f);
                        mLayout.height = (int) (myHeight * layoutScale + .5f);
                        mLayout.flags |= WindowManager.LayoutParams.FLAG_SCALED;
                    }

                    mCurWindowPrivateFlags = mWindowPrivateFlags;
                    mLayout.privateFlags = mWindowPrivateFlags;

                    mLayout.memoryType = mType;
                    mLayout.token = mWindowToken;

                    if (!mCreated) {
                        // Retrieve watch round info
                        TypedArray windowStyle = obtainStyledAttributes(
                                com.android.internal.R.styleable.Window);
                        windowStyle.recycle();

                        // Add window
                        mLayout.type = mIWallpaperEngine.mWindowType;
                        mLayout.gravity = Gravity.START|Gravity.TOP;
                        mLayout.setFitInsetsTypes(0 /* types */);
                        mLayout.setTitle(WallpaperService.this.getClass().getName());
                        mLayout.windowAnimations =
                                com.android.internal.R.style.Animation_Wallpaper;
                        InputChannel inputChannel = new InputChannel();

                        if (mSession.addToDisplay(mWindow, mLayout, View.VISIBLE,
                                mDisplay.getDisplayId(), mRequestedVisibilities, inputChannel,
                                mInsetsState, mTempControls, new Rect(), new float[1]) < 0) {
                            Log.w(TAG, "Failed to add window while updating wallpaper surface.");
                            return;
                        }
                        mSession.setShouldZoomOutWallpaper(mWindow, shouldZoomOutWallpaper());
                        mCreated = true;

                        mInputEventReceiver = new WallpaperInputEventReceiver(
                                inputChannel, Looper.myLooper());
                    }

                    mSurfaceHolder.mSurfaceLock.lock();
                    mDrawingAllowed = true;

                    if (!fixedSize) {
                        mLayout.surfaceInsets.set(mIWallpaperEngine.mDisplayPadding);
                    } else {
                        mLayout.surfaceInsets.set(0, 0, 0, 0);
                    }
                    final int relayoutResult = mSession.relayout(mWindow, mLayout, mWidth, mHeight,
                            View.VISIBLE, 0, 0, 0, mWinFrames, mMergedConfiguration,
                            mSurfaceControl, mInsetsState, mTempControls, mSyncSeqIdBundle);

                    final int transformHint = SurfaceControl.rotationToBufferTransform(
                            (mDisplayInstallOrientation + mDisplay.getRotation()) % 4);
                    mSurfaceControl.setTransformHint(transformHint);
                    WindowLayout.computeSurfaceSize(mLayout, maxBounds, mWidth, mHeight,
                            mWinFrames.frame, false /* dragResizing */, mSurfaceSize);

                    if (mSurfaceControl.isValid()) {
                        if (mBbqSurfaceControl == null) {
                            mBbqSurfaceControl = new SurfaceControl.Builder()
                                    .setName("Wallpaper BBQ wrapper")
                                    .setHidden(false)
                                    // TODO(b/192291754)
                                    .setMetadata(METADATA_WINDOW_TYPE, TYPE_WALLPAPER)
                                    .setBLASTLayer()
                                    .setParent(mSurfaceControl)
                                    .setCallsite("Wallpaper#relayout")
                                    .build();
                        }
                        // Propagate transform hint from WM, so we can use the right hint for the
                        // first frame.
                        mBbqSurfaceControl.setTransformHint(transformHint);
                        Surface blastSurface = getOrCreateBLASTSurface(mSurfaceSize.x,
                                mSurfaceSize.y, mFormat);
                        // If blastSurface == null that means it hasn't changed since the last
                        // time we called. In this situation, avoid calling transferFrom as we
                        // would then inc the generation ID and cause EGL resources to be recreated.
                        if (blastSurface != null) {
                            mSurfaceHolder.mSurface.transferFrom(blastSurface);
                        }
                    }
                    if (!mLastSurfaceSize.equals(mSurfaceSize)) {
                        mLastSurfaceSize.set(mSurfaceSize.x, mSurfaceSize.y);
                    }

                    if (DEBUG) Log.v(TAG, "New surface: " + mSurfaceHolder.mSurface
                            + ", frame=" + mWinFrames);

                    int w = mWinFrames.frame.width();
                    int h = mWinFrames.frame.height();

                    final DisplayCutout rawCutout = mInsetsState.getDisplayCutout();
                    final Rect visibleFrame = new Rect(mWinFrames.frame);
                    visibleFrame.intersect(mInsetsState.getDisplayFrame());
                    WindowInsets windowInsets = mInsetsState.calculateInsets(visibleFrame,
                            null /* ignoringVisibilityState */, config.isScreenRound(),
                            false /* alwaysConsumeSystemBars */, mLayout.softInputMode,
                            mLayout.flags, SYSTEM_UI_FLAG_VISIBLE, mLayout.type,
                            config.windowConfiguration.getWindowingMode(), null /* typeSideMap */);

                    if (!fixedSize) {
                        final Rect padding = mIWallpaperEngine.mDisplayPadding;
                        w += padding.left + padding.right;
                        h += padding.top + padding.bottom;
                        windowInsets = windowInsets.insetUnchecked(
                                -padding.left, -padding.top, -padding.right, -padding.bottom);
                    } else {
                        w = myWidth;
                        h = myHeight;
                    }

                    if (mCurWidth != w) {
                        sizeChanged = true;
                        mCurWidth = w;
                    }
                    if (mCurHeight != h) {
                        sizeChanged = true;
                        mCurHeight = h;
                    }

                    if (DEBUG) {
                        Log.v(TAG, "Wallpaper size has changed: (" + mCurWidth + ", " + mCurHeight);
                    }

                    final Rect contentInsets = windowInsets.getSystemWindowInsets().toRect();
                    final Rect stableInsets = windowInsets.getStableInsets().toRect();
                    final DisplayCutout displayCutout = windowInsets.getDisplayCutout() != null
                            ? windowInsets.getDisplayCutout() : rawCutout;
                    insetsChanged |= !mDispatchedContentInsets.equals(contentInsets);
                    insetsChanged |= !mDispatchedStableInsets.equals(stableInsets);
                    insetsChanged |= !mDispatchedDisplayCutout.equals(displayCutout);

                    mSurfaceHolder.setSurfaceFrameSize(w, h);
                    mSurfaceHolder.mSurfaceLock.unlock();

                    if (!mSurfaceHolder.mSurface.isValid()) {
                        reportSurfaceDestroyed();
                        if (DEBUG) Log.v(TAG, "Layout: Surface destroyed");
                        return;
                    }

                    boolean didSurface = false;

                    try {
                        mSurfaceHolder.ungetCallbacks();

                        if (surfaceCreating) {
                            mIsCreating = true;
                            didSurface = true;
                            if (DEBUG) Log.v(TAG, "onSurfaceCreated("
                                    + mSurfaceHolder + "): " + this);
                            onSurfaceCreated(mSurfaceHolder);
                            SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceCreated(mSurfaceHolder);
                                }
                            }
                        }

                        redrawNeeded |= creating || (relayoutResult
                                & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0;

                        if (forceReport || creating || surfaceCreating
                                || formatChanged || sizeChanged) {
                            if (DEBUG) {
                                RuntimeException e = new RuntimeException();
                                e.fillInStackTrace();
                                Log.w(TAG, "forceReport=" + forceReport + " creating=" + creating
                                        + " formatChanged=" + formatChanged
                                        + " sizeChanged=" + sizeChanged, e);
                            }
                            if (DEBUG) Log.v(TAG, "onSurfaceChanged("
                                    + mSurfaceHolder + ", " + mFormat
                                    + ", " + mCurWidth + ", " + mCurHeight
                                    + "): " + this);
                            didSurface = true;
                            onSurfaceChanged(mSurfaceHolder, mFormat,
                                    mCurWidth, mCurHeight);
                            SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceChanged(mSurfaceHolder, mFormat,
                                            mCurWidth, mCurHeight);
                                }
                            }
                        }

                        if (insetsChanged) {
                            mDispatchedContentInsets.set(contentInsets);
                            mDispatchedStableInsets.set(stableInsets);
                            mDispatchedDisplayCutout = displayCutout;
                            if (DEBUG) {
                                Log.v(TAG, "dispatching insets=" + windowInsets);
                            }
                            onApplyWindowInsets(windowInsets);
                        }

                        if (redrawNeeded) {
                            onSurfaceRedrawNeeded(mSurfaceHolder);
                            SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    if (c instanceof SurfaceHolder.Callback2) {
                                        ((SurfaceHolder.Callback2)c).surfaceRedrawNeeded(
                                                mSurfaceHolder);
                                    }
                                }
                            }
                        }

                        if (didSurface && !mReportedVisible) {
                            // This wallpaper is currently invisible, but its
                            // surface has changed.  At this point let's tell it
                            // again that it is invisible in case the report about
                            // the surface caused it to start running.  We really
                            // don't want wallpapers running when not visible.
                            if (mIsCreating) {
                                // Some wallpapers will ignore this call if they
                                // had previously been told they were invisble,
                                // so if we are creating a new surface then toggle
                                // the state to get them to notice.
                                if (DEBUG) Log.v(TAG, "onVisibilityChanged(true) at surface: "
                                        + this);
                                onVisibilityChanged(true);
                            }
                            if (DEBUG) Log.v(TAG, "onVisibilityChanged(false) at surface: "
                                        + this);
                            onVisibilityChanged(false);
                        }
                    } finally {
                        mIsCreating = false;
                        mSurfaceCreated = true;
                        if (redrawNeeded) {
                            resetWindowPages();
                            mSession.finishDrawing(mWindow, null /* postDrawTransaction */,
                                                   Integer.MAX_VALUE);
                            processLocalColors(mPendingXOffset, mPendingXOffsetStep);
                        }
                        reposition();
                        reportEngineShown(shouldWaitForEngineShown());
                    }
                } catch (RemoteException ex) {
                }
                if (DEBUG) Log.v(
                    TAG, "Layout: x=" + mLayout.x + " y=" + mLayout.y +
                    " w=" + mLayout.width + " h=" + mLayout.height);
            }
        }

        private void scalePreview(Rect position) {
            if (isPreview() && mPreviewSurfacePosition == null && position != null
                    || mPreviewSurfacePosition != null
                    && !mPreviewSurfacePosition.equals(position)) {
                mPreviewSurfacePosition = position;
                if (mSurfaceControl.isValid()) {
                    reposition();
                } else {
                    updateSurface(false, false, false);
                }
            }
        }

        private void reposition() {
            if (mPreviewSurfacePosition == null) {
                return;
            }
            if (DEBUG) {
                Log.i(TAG, "reposition: rect: " + mPreviewSurfacePosition);
            }

            mTmpMatrix.setTranslate(mPreviewSurfacePosition.left, mPreviewSurfacePosition.top);
            mTmpMatrix.postScale(((float) mPreviewSurfacePosition.width()) / mCurWidth,
                    ((float) mPreviewSurfacePosition.height()) / mCurHeight);
            mTmpMatrix.getValues(mTmpValues);
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setPosition(mSurfaceControl, mPreviewSurfacePosition.left,
                    mPreviewSurfacePosition.top);
            t.setMatrix(mSurfaceControl, mTmpValues[MSCALE_X], mTmpValues[MSKEW_Y],
                    mTmpValues[MSKEW_X], mTmpValues[MSCALE_Y]);
            t.apply();
        }

        void attach(IWallpaperEngineWrapper wrapper) {
            if (DEBUG) Log.v(TAG, "attach: " + this + " wrapper=" + wrapper);
            if (mDestroyed) {
                return;
            }

            mIWallpaperEngine = wrapper;
            mCaller = wrapper.mCaller;
            mConnection = wrapper.mConnection;
            mWindowToken = wrapper.mWindowToken;
            mSurfaceHolder.setSizeFromLayout();
            mInitializing = true;
            mSession = WindowManagerGlobal.getWindowSession();

            mWindow.setSession(mSession);

            mLayout.packageName = getPackageName();
            mIWallpaperEngine.mDisplayManager.registerDisplayListener(mDisplayListener,
                    mCaller.getHandler());
            mDisplay = mIWallpaperEngine.mDisplay;
            // Use window context of TYPE_WALLPAPER so client can access UI resources correctly.
            mDisplayContext = createDisplayContext(mDisplay)
                    .createWindowContext(TYPE_WALLPAPER, null /* options */);
            mDefaultDimAmount = mDisplayContext.getResources().getFloat(
                    com.android.internal.R.dimen.config_wallpaperDimAmount);
            mWallpaperDimAmount = mDefaultDimAmount;
            mPreviousWallpaperDimAmount = mWallpaperDimAmount;
            mDisplayState = mDisplay.getState();
            mDisplayInstallOrientation = mDisplay.getInstallOrientation();

            if (DEBUG) Log.v(TAG, "onCreate(): " + this);
            onCreate(mSurfaceHolder);

            mInitializing = false;

            mReportedVisible = false;
            updateSurface(false, false, false);
        }

        /**
         * The {@link Context} with resources that match the current display the wallpaper is on.
         * For multiple display environment, multiple engines can be created to render on each
         * display, but these displays may have different densities. Use this context to get the
         * corresponding resources for currently display, avoiding the context of the service.
         * <p>
         * The display context will never be {@code null} after
         * {@link Engine#onCreate(SurfaceHolder)} has been called.
         *
         * @return A {@link Context} for current display.
         */
        @Nullable
        public Context getDisplayContext() {
            return mDisplayContext;
        }

        /**
         * Executes life cycle event and updates internal ambient mode state based on
         * message sent from handler.
         *
         * @param inAmbientMode {@code true} if in ambient mode.
         * @param animationDuration For how long the transition will last, in ms.
         * @hide
         */
        @VisibleForTesting
        public void doAmbientModeChanged(boolean inAmbientMode, long animationDuration) {
            if (!mDestroyed) {
                if (DEBUG) {
                    Log.v(TAG, "onAmbientModeChanged(" + inAmbientMode + ", "
                            + animationDuration + "): " + this);
                }
                mIsInAmbientMode = inAmbientMode;
                if (mCreated) {
                    onAmbientModeChanged(inAmbientMode, animationDuration);
                }
            }
        }

        void doDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            if (!mDestroyed) {
                if (DEBUG) Log.v(TAG, "onDesiredSizeChanged("
                        + desiredWidth + "," + desiredHeight + "): " + this);
                mIWallpaperEngine.mReqWidth = desiredWidth;
                mIWallpaperEngine.mReqHeight = desiredHeight;
                onDesiredSizeChanged(desiredWidth, desiredHeight);
                doOffsetsChanged(true);
            }
        }

        void doDisplayPaddingChanged(Rect padding) {
            if (!mDestroyed) {
                if (DEBUG) Log.v(TAG, "onDisplayPaddingChanged(" + padding + "): " + this);
                if (!mIWallpaperEngine.mDisplayPadding.equals(padding)) {
                    mIWallpaperEngine.mDisplayPadding.set(padding);
                    updateSurface(true, false, false);
                }
            }
        }

        void doVisibilityChanged(boolean visible) {
            if (!mDestroyed) {
                mVisible = visible;
                reportVisibility();
                if (mReportedVisible) processLocalColors(mPendingXOffset, mPendingXOffsetStep);
            } else {
                AnimationHandler.requestAnimatorsEnabled(visible, this);
            }
        }

        void reportVisibility() {
            if (mScreenshotSurfaceControl != null && mVisible) {
                if (DEBUG) Log.v(TAG, "Frozen so don't report visibility change");
                return;
            }
            if (!mDestroyed) {
                mDisplayState = mDisplay == null ? Display.STATE_UNKNOWN : mDisplay.getState();
                boolean visible = mVisible && mDisplayState != Display.STATE_OFF;
                if (mReportedVisible != visible) {
                    mReportedVisible = visible;
                    if (DEBUG) Log.v(TAG, "onVisibilityChanged(" + visible
                            + "): " + this);
                    if (visible) {
                        // If becoming visible, in preview mode the surface
                        // may have been destroyed so now we need to make
                        // sure it is re-created.
                        doOffsetsChanged(false);
                        // It will check mSurfaceCreated so no need to force relayout.
                        updateSurface(false /* forceRelayout */, false /* forceReport */,
                                false /* redrawNeeded */);
                    }
                    onVisibilityChanged(visible);
                    if (mReportedVisible && mFrozenRequested) {
                        if (DEBUG) Log.v(TAG, "Freezing wallpaper after visibility update");
                        freeze();
                    }
                    AnimationHandler.requestAnimatorsEnabled(visible, this);
                }
            }
        }

        void doOffsetsChanged(boolean always) {
            if (mDestroyed) {
                return;
            }

            if (!always && !mOffsetsChanged) {
                return;
            }

            float xOffset;
            float yOffset;
            float xOffsetStep;
            float yOffsetStep;
            boolean sync;
            synchronized (mLock) {
                xOffset = mPendingXOffset;
                yOffset = mPendingYOffset;
                xOffsetStep = mPendingXOffsetStep;
                yOffsetStep = mPendingYOffsetStep;
                sync = mPendingSync;
                mPendingSync = false;
                mOffsetMessageEnqueued = false;
            }

            if (mSurfaceCreated) {
                if (mReportedVisible) {
                    if (DEBUG) Log.v(TAG, "Offsets change in " + this
                            + ": " + xOffset + "," + yOffset);
                    final int availw = mIWallpaperEngine.mReqWidth-mCurWidth;
                    final int xPixels = availw > 0 ? -(int)(availw*xOffset+.5f) : 0;
                    final int availh = mIWallpaperEngine.mReqHeight-mCurHeight;
                    final int yPixels = availh > 0 ? -(int)(availh*yOffset+.5f) : 0;
                    onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixels, yPixels);
                } else {
                    mOffsetsChanged = true;
                }
            }

            if (sync) {
                try {
                    if (DEBUG) Log.v(TAG, "Reporting offsets change complete");
                    mSession.wallpaperOffsetsComplete(mWindow.asBinder());
                } catch (RemoteException e) {
                }
            }

            // setup local color extraction data
            processLocalColors(xOffset, xOffsetStep);
        }

        private void processLocalColors(float xOffset, float xOffsetStep) {
            // implemented by the wallpaper
            if (supportsLocalColorExtraction()) return;
            if (DEBUG) {
                Log.d(TAG, "processLocalColors " + xOffset + " of step "
                        + xOffsetStep);
            }
            //below is the default implementation
            if (xOffset % xOffsetStep > MIN_PAGE_ALLOWED_MARGIN
                    || !mSurfaceHolder.getSurface().isValid()) return;
            int xCurrentPage;
            int xPages;
            if (!validStep(xOffsetStep)) {
                if (DEBUG) {
                    Log.w(TAG, "invalid offset step " + xOffsetStep);
                }
                xOffset = 0;
                xOffsetStep = 1;
                xCurrentPage = 0;
                xPages = 1;
            } else {
                xPages = Math.round(1 / xOffsetStep) + 1;
                xOffsetStep = (float) 1 / (float) xPages;
                float shrink = (float) (xPages - 1) / (float) xPages;
                xOffset *= shrink;
                xCurrentPage = Math.round(xOffset / xOffsetStep);
            }
            if (DEBUG) {
                Log.d(TAG, "xPages " + xPages + " xPage " + xCurrentPage);
                Log.d(TAG, "xOffsetStep " + xOffsetStep + " xOffset " + xOffset);
            }

            float finalXOffsetStep = xOffsetStep;
            float finalXOffset = xOffset;
            mHandler.post(() -> {
                Trace.beginSection("WallpaperService#processLocalColors");
                resetWindowPages();
                int xPage = xCurrentPage;
                EngineWindowPage current;
                if (mWindowPages.length == 0 || (mWindowPages.length != xPages)) {
                    mWindowPages = new EngineWindowPage[xPages];
                    initWindowPages(mWindowPages, finalXOffsetStep);
                }
                if (mLocalColorsToAdd.size() != 0) {
                    for (RectF colorArea : mLocalColorsToAdd) {
                        if (!isValid(colorArea)) continue;
                        mLocalColorAreas.add(colorArea);
                        int colorPage = getRectFPage(colorArea, finalXOffsetStep);
                        EngineWindowPage currentPage = mWindowPages[colorPage];
                        currentPage.setLastUpdateTime(0);
                        currentPage.removeColor(colorArea);
                    }
                    mLocalColorsToAdd.clear();
                }
                if (xPage >= mWindowPages.length) {
                    if (DEBUG) {
                        Log.e(TAG, "error xPage >= mWindowPages.length page: " + xPage);
                        Log.e(TAG, "error on page " + xPage + " out of " + xPages);
                        Log.e(TAG,
                                "error on xOffsetStep " + finalXOffsetStep
                                        + " xOffset " + finalXOffset);
                    }
                    xPage = mWindowPages.length - 1;
                }
                current = mWindowPages[xPage];
                updatePage(current, xPage, xPages, finalXOffsetStep);
                Trace.endSection();
            });
        }

        private void initWindowPages(EngineWindowPage[] windowPages, float step) {
            for (int i = 0; i < windowPages.length; i++) {
                windowPages[i] = new EngineWindowPage();
            }
            mLocalColorAreas.addAll(mLocalColorsToAdd);
            mLocalColorsToAdd.clear();
            for (RectF area: mLocalColorAreas) {
                if (!isValid(area)) {
                    mLocalColorAreas.remove(area);
                    continue;
                }
                int pageNum = getRectFPage(area, step);
                windowPages[pageNum].addArea(area);
            }
        }

        void updatePage(EngineWindowPage currentPage, int pageIndx, int numPages,
                float xOffsetStep) {
            // in case the clock is zero, we start with negative time
            long current = SystemClock.elapsedRealtime() - DEFAULT_UPDATE_SCREENSHOT_DURATION;
            long lapsed = current - currentPage.getLastUpdateTime();
            // Always update the page when the last update time is <= 0
            // This is important especially when the device first boots
            if (lapsed < DEFAULT_UPDATE_SCREENSHOT_DURATION) {
                return;
            }
            Surface surface = mSurfaceHolder.getSurface();
            if (!surface.isValid()) return;
            boolean widthIsLarger = mSurfaceSize.x > mSurfaceSize.y;
            int smaller = widthIsLarger ? mSurfaceSize.x
                    : mSurfaceSize.y;
            float ratio = (float) MIN_BITMAP_SCREENSHOT_WIDTH / (float) smaller;
            int width = (int) (ratio * mSurfaceSize.x);
            int height = (int) (ratio * mSurfaceSize.y);
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "wrong width and height values of bitmap " + width + " " + height);
                return;
            }
            Bitmap screenShot = Bitmap.createBitmap(width, height,
                    Bitmap.Config.ARGB_8888);
            final Bitmap finalScreenShot = screenShot;
            Trace.beginSection("WallpaperService#pixelCopy");
            PixelCopy.request(surface, screenShot, (res) -> {
                Trace.endSection();
                if (DEBUG) Log.d(TAG, "result of pixel copy is " + res);
                if (res != PixelCopy.SUCCESS) {
                    Bitmap lastBitmap = currentPage.getBitmap();
                    // assign the last bitmap taken for now
                    currentPage.setBitmap(mLastScreenshot);
                    Bitmap lastScreenshot = mLastScreenshot;
                    if (lastScreenshot != null && !lastScreenshot.isRecycled()
                            && !Objects.equals(lastBitmap, lastScreenshot)) {
                        updatePageColors(currentPage, pageIndx, numPages, xOffsetStep);
                    }
                } else {
                    mLastScreenshot = finalScreenShot;
                    // going to hold this lock for a while
                    currentPage.setBitmap(finalScreenShot);
                    currentPage.setLastUpdateTime(current);
                    updatePageColors(currentPage, pageIndx, numPages, xOffsetStep);
                }
            }, mHandler);

        }
        // locked by the passed page
        private void updatePageColors(EngineWindowPage page, int pageIndx, int numPages,
                float xOffsetStep) {
            if (page.getBitmap() == null) return;
            Trace.beginSection("WallpaperService#updatePageColors");
            if (DEBUG) {
                Log.d(TAG, "updatePageColorsLocked for page " + pageIndx + " with areas "
                        + page.getAreas().size() + " and bitmap size of "
                        + page.getBitmap().getWidth() + " x " + page.getBitmap().getHeight());
            }
            for (RectF area: page.getAreas()) {
                if (area == null) continue;
                RectF subArea = generateSubRect(area, pageIndx, numPages);
                Bitmap b = page.getBitmap();
                int x = Math.round(b.getWidth() * subArea.left);
                int y = Math.round(b.getHeight() * subArea.top);
                int width = Math.round(b.getWidth() * subArea.width());
                int height = Math.round(b.getHeight() * subArea.height());
                Bitmap target;
                try {
                    target = Bitmap.createBitmap(page.getBitmap(), x, y, width, height);
                } catch (Exception e) {
                    Log.e(TAG, "Error creating page local color bitmap", e);
                    continue;
                }
                WallpaperColors color = WallpaperColors.fromBitmap(target, mWallpaperDimAmount);
                target.recycle();
                WallpaperColors currentColor = page.getColors(area);

                if (DEBUG) {
                    Log.d(TAG, "getting local bitmap area x " + x + " y " + y
                            + " width " + width + " height " + height + " for sub area " + subArea
                            + " and with page " + pageIndx + " of " + numPages);

                }
                if (currentColor == null || !color.equals(currentColor)) {
                    page.addWallpaperColors(area, color);
                    if (DEBUG) {
                        Log.d(TAG, "onLocalWallpaperColorsChanged"
                                + " local color callback for area" + area + " for page " + pageIndx
                                + " of " + numPages);
                    }
                    try {
                        mConnection.onLocalWallpaperColorsChanged(area, color,
                                mDisplayContext.getDisplayId());
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error calling Connection.onLocalWallpaperColorsChanged", e);
                    }
                }
            }
            Trace.endSection();
        }

        private RectF generateSubRect(RectF in, int pageInx, int numPages) {
            float minLeft = (float) (pageInx) / (float) (numPages);
            float maxRight = (float) (pageInx + 1) / (float) (numPages);
            float left = in.left;
            float right = in.right;

            // bound rect
            if (left < minLeft) left = minLeft;
            if (right > maxRight) right = maxRight;

            // scale up the sub area then trim
            left = (left * (float) numPages) % 1f;
            right = (right * (float) numPages) % 1f;
            if (right == 0f) {
                right = 1f;
            }

            return new RectF(left, in.top, right, in.bottom);
        }

        private void resetWindowPages() {
            if (supportsLocalColorExtraction()) return;
            if (!mResetWindowPages) return;
            mResetWindowPages = false;
            mLastWindowPage = -1;
            for (int i = 0; i < mWindowPages.length; i++) {
                mWindowPages[i].setLastUpdateTime(0L);
            }
        }

        private int getRectFPage(RectF area, float step) {
            if (!isValid(area)) return 0;
            if (!validStep(step)) return 0;
            int pages = Math.round(1 / step);
            int page = Math.round(area.centerX() * pages);
            if (page == pages) return pages - 1;
            if (page == mWindowPages.length) page = mWindowPages.length - 1;
            return page;
        }

        /**
         * Add local colors areas of interest
         * @param regions list of areas
         * @hide
         */
        public void addLocalColorsAreas(@NonNull List<RectF> regions) {
            if (supportsLocalColorExtraction()) return;
            if (DEBUG) {
                Log.d(TAG, "addLocalColorsAreas adding local color areas " + regions);
            }
            mHandler.post(() -> {
                mLocalColorsToAdd.addAll(regions);
                processLocalColors(mPendingXOffset, mPendingYOffset);
            });


        }

        /**
         * Remove local colors areas of interest if they exist
         * @param regions list of areas
         * @hide
         */
        public void removeLocalColorsAreas(@NonNull List<RectF> regions) {
            if (supportsLocalColorExtraction()) return;
            mHandler.post(() -> {
                float step = mPendingXOffsetStep;
                mLocalColorsToAdd.removeAll(regions);
                mLocalColorAreas.removeAll(regions);
                if (!validStep(step)) {
                    return;
                }
                for (int i = 0; i < mWindowPages.length; i++) {
                    for (int j = 0; j < regions.size(); j++) {
                        mWindowPages[i].removeArea(regions.get(j));
                    }
                }
            });
        }

        // fix the rect to be included within the bounds of the bitmap
        private Rect fixRect(Bitmap b, Rect r) {
            r.left =  r.left >= r.right || r.left >= b.getWidth() || r.left > 0
                    ? 0
                    : r.left;
            r.right =  r.left >= r.right || r.right > b.getWidth()
                    ? b.getWidth()
                    : r.right;
            return r;
        }

        private boolean validStep(float step) {
            return !PROHIBITED_STEPS.contains(step) && step > 0. && step <= 1.;
        }

        void doCommand(WallpaperCommand cmd) {
            Bundle result;
            if (!mDestroyed) {
                if (COMMAND_FREEZE.equals(cmd.action) || COMMAND_UNFREEZE.equals(cmd.action)) {
                    updateFrozenState(/* frozenRequested= */ !COMMAND_UNFREEZE.equals(cmd.action));
                }
                result = onCommand(cmd.action, cmd.x, cmd.y, cmd.z,
                        cmd.extras, cmd.sync);
            } else {
                result = null;
            }
            if (cmd.sync) {
                try {
                    if (DEBUG) Log.v(TAG, "Reporting command complete");
                    mSession.wallpaperCommandComplete(mWindow.asBinder(), result);
                } catch (RemoteException e) {
                }
            }
        }

        private void updateFrozenState(boolean frozenRequested) {
            if (mIWallpaperEngine.mWallpaperManager.getWallpaperInfo() == null
                    // Procees the unfreeze command in case the wallaper became static while
                    // being paused.
                    && frozenRequested) {
                if (DEBUG) Log.v(TAG, "Ignoring the freeze command for static wallpapers");
                return;
            }
            mFrozenRequested = frozenRequested;
            boolean isFrozen = mScreenshotSurfaceControl != null;
            if (mFrozenRequested == isFrozen) {
                return;
            }
            if (mFrozenRequested) {
                freeze();
            } else {
                unfreeze();
            }
        }

        private void freeze() {
            if (!mReportedVisible || mDestroyed) {
                // Screenshot can't be taken until visibility is reported to the wallpaper host.
                return;
            }
            if (!showScreenshotOfWallpaper()) {
                return;
            }
            // Prevent a wallpaper host from rendering wallpaper behind a screeshot.
            doVisibilityChanged(false);
            // Remember that visibility is requested since it's not guaranteed that
            // mWindow#dispatchAppVisibility will be called when letterboxed application with
            // wallpaper background transitions to the Home screen.
            mVisible = true;
        }

        private void unfreeze() {
            cleanUpScreenshotSurfaceControl();
            if (mVisible) {
                doVisibilityChanged(true);
            }
        }

        private void cleanUpScreenshotSurfaceControl() {
            // TODO(b/194399558): Add crossfade transition.
            if (mScreenshotSurfaceControl != null) {
                new SurfaceControl.Transaction()
                        .remove(mScreenshotSurfaceControl)
                        .show(mBbqSurfaceControl)
                        .apply();
                mScreenshotSurfaceControl = null;
            }
        }

        void scaleAndCropScreenshot() {
            if (mScreenshotSurfaceControl == null) {
                return;
            }
            if (mScreenshotSize.x <= 0 || mScreenshotSize.y <= 0) {
                Log.w(TAG, "Unexpected screenshot size: " + mScreenshotSize);
                return;
            }
            // Don't scale down and using the same scaling factor for both dimensions to
            // avoid stretching wallpaper image.
            float scaleFactor = Math.max(1, Math.max(
                    ((float) mSurfaceSize.x) / mScreenshotSize.x,
                    ((float) mSurfaceSize.y) / mScreenshotSize.y));
            int diffX =  ((int) (mScreenshotSize.x * scaleFactor)) - mSurfaceSize.x;
            int diffY =  ((int) (mScreenshotSize.y * scaleFactor)) - mSurfaceSize.y;
            if (DEBUG) {
                Log.v(TAG, "Adjusting screenshot: scaleFactor=" + scaleFactor
                        + " diffX=" + diffX + " diffY=" + diffY + " mSurfaceSize=" + mSurfaceSize
                        + " mScreenshotSize=" + mScreenshotSize);
            }
            new SurfaceControl.Transaction()
                        .setMatrix(
                                mScreenshotSurfaceControl,
                                /* dsdx= */ scaleFactor, /* dtdx= */ 0,
                                /* dtdy= */ 0, /* dsdy= */ scaleFactor)
                        .setWindowCrop(
                                mScreenshotSurfaceControl,
                                new Rect(
                                        /* left= */ diffX / 2,
                                        /* top= */ diffY / 2,
                                        /* right= */ diffX / 2 + mScreenshotSize.x,
                                        /* bottom= */ diffY / 2 + mScreenshotSize.y))
                        .setPosition(mScreenshotSurfaceControl, -diffX / 2, -diffY / 2)
                        .apply();
        }

        private boolean showScreenshotOfWallpaper() {
            if (mDestroyed || mSurfaceControl == null || !mSurfaceControl.isValid()) {
                if (DEBUG) Log.v(TAG, "Failed to screenshot wallpaper: surface isn't valid");
                return false;
            }

            final Rect bounds = new Rect(0, 0, mSurfaceSize.x,  mSurfaceSize.y);
            if (bounds.isEmpty()) {
                Log.w(TAG, "Failed to screenshot wallpaper: surface bounds are empty");
                return false;
            }

            if (mScreenshotSurfaceControl != null) {
                Log.e(TAG, "Screenshot is unexpectedly not null");
                // Destroying previous screenshot since it can have different size.
                cleanUpScreenshotSurfaceControl();
            }

            SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer =
                    SurfaceControl.captureLayers(
                            new SurfaceControl.LayerCaptureArgs.Builder(mSurfaceControl)
                                    // Needed because SurfaceFlinger#validateScreenshotPermissions
                                    // uses this parameter to check whether a caller only attempts
                                    // to screenshot itself when call doesn't come from the system.
                                    .setUid(Process.myUid())
                                    .setChildrenOnly(false)
                                    .setSourceCrop(bounds)
                                    .build());

            if (screenshotBuffer == null) {
                Log.w(TAG, "Failed to screenshot wallpaper: screenshotBuffer is null");
                return false;
            }

            final HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();

            SurfaceControl.Transaction t = new SurfaceControl.Transaction();

            // TODO(b/194399558): Add crossfade transition.
            mScreenshotSurfaceControl = new SurfaceControl.Builder()
                    .setName("Wallpaper snapshot for engine " + this)
                    .setFormat(hardwareBuffer.getFormat())
                    .setParent(mSurfaceControl)
                    .setSecure(screenshotBuffer.containsSecureLayers())
                    .setCallsite("WallpaperService.Engine.showScreenshotOfWallpaper")
                    .setBLASTLayer()
                    .build();

            mScreenshotSize.set(mSurfaceSize.x, mSurfaceSize.y);

            t.setBuffer(mScreenshotSurfaceControl, hardwareBuffer);
            t.setColorSpace(mScreenshotSurfaceControl, screenshotBuffer.getColorSpace());
            // Place on top everything else.
            t.setLayer(mScreenshotSurfaceControl, Integer.MAX_VALUE);
            t.show(mScreenshotSurfaceControl);
            t.hide(mBbqSurfaceControl);
            t.apply();

            return true;
        }

        void reportSurfaceDestroyed() {
            if (mSurfaceCreated) {
                mSurfaceCreated = false;
                mSurfaceHolder.ungetCallbacks();
                SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();
                if (callbacks != null) {
                    for (SurfaceHolder.Callback c : callbacks) {
                        c.surfaceDestroyed(mSurfaceHolder);
                    }
                }
                if (DEBUG) Log.v(TAG, "onSurfaceDestroyed("
                        + mSurfaceHolder + "): " + this);
                onSurfaceDestroyed(mSurfaceHolder);
            }
        }

        void detach() {
            if (mDestroyed) {
                return;
            }

            AnimationHandler.removeRequestor(this);

            mDestroyed = true;

            if (mIWallpaperEngine.mDisplayManager != null) {
                mIWallpaperEngine.mDisplayManager.unregisterDisplayListener(mDisplayListener);
            }

            if (mVisible) {
                mVisible = false;
                if (DEBUG) Log.v(TAG, "onVisibilityChanged(false): " + this);
                onVisibilityChanged(false);
            }

            reportSurfaceDestroyed();

            if (DEBUG) Log.v(TAG, "onDestroy(): " + this);
            onDestroy();

            if (mCreated) {
                try {
                    if (DEBUG) Log.v(TAG, "Removing window and destroying surface "
                            + mSurfaceHolder.getSurface() + " of: " + this);

                    if (mInputEventReceiver != null) {
                        mInputEventReceiver.dispose();
                        mInputEventReceiver = null;
                    }

                    mSession.remove(mWindow);
                } catch (RemoteException e) {
                }
                mSurfaceHolder.mSurface.release();
                if (mBlastBufferQueue != null) {
                    mBlastBufferQueue.destroy();
                    mBlastBufferQueue = null;
                }
                if (mBbqSurfaceControl != null) {
                    new SurfaceControl.Transaction().remove(mBbqSurfaceControl).apply();
                    mBbqSurfaceControl = null;
                }
                mCreated = false;
            }
        }

        private final DisplayListener mDisplayListener = new DisplayListener() {
            @Override
            public void onDisplayChanged(int displayId) {
                if (mDisplay.getDisplayId() == displayId) {
                    reportVisibility();
                }
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayAdded(int displayId) {
            }
        };

        private Surface getOrCreateBLASTSurface(int width, int height, int format) {
            Surface ret = null;
            if (mBlastBufferQueue == null) {
                mBlastBufferQueue = new BLASTBufferQueue("Wallpaper", mBbqSurfaceControl,
                        width, height, format);
                // We only return the Surface the first time, as otherwise
                // it hasn't changed and there is no need to update.
                ret = mBlastBufferQueue.createSurface();
            } else {
                mBlastBufferQueue.update(mBbqSurfaceControl, width, height, format);
            }

            return ret;
        }
    }

    private boolean isValid(RectF area) {
        if (area == null) return false;
        boolean valid = area.bottom > area.top && area.left < area.right
                && LOCAL_COLOR_BOUNDS.contains(area);
        return valid;
    }

    private boolean inRectFRange(float number) {
        return number >= 0f && number <= 1f;
    }

    class IWallpaperEngineWrapper extends IWallpaperEngine.Stub
            implements HandlerCaller.Callback {
        private final HandlerCaller mCaller;

        final IWallpaperConnection mConnection;
        final IBinder mWindowToken;
        final int mWindowType;
        final boolean mIsPreview;
        boolean mShownReported;
        int mReqWidth;
        int mReqHeight;
        final Rect mDisplayPadding = new Rect();
        final int mDisplayId;
        final DisplayManager mDisplayManager;
        final Display mDisplay;
        final WallpaperManager mWallpaperManager;
        private final AtomicBoolean mDetached = new AtomicBoolean();

        Engine mEngine;

        IWallpaperEngineWrapper(WallpaperService context,
                IWallpaperConnection conn, IBinder windowToken,
                int windowType, boolean isPreview, int reqWidth, int reqHeight, Rect padding,
                int displayId) {
            mWallpaperManager = getSystemService(WallpaperManager.class);
            mCaller = new HandlerCaller(context, context.getMainLooper(), this, true);
            mConnection = conn;
            mWindowToken = windowToken;
            mWindowType = windowType;
            mIsPreview = isPreview;
            mReqWidth = reqWidth;
            mReqHeight = reqHeight;
            mDisplayPadding.set(padding);
            mDisplayId = displayId;

            // Create a display context before onCreateEngine.
            mDisplayManager = getSystemService(DisplayManager.class);
            mDisplay = mDisplayManager.getDisplay(mDisplayId);

            if (mDisplay == null) {
                // Ignore this engine.
                throw new IllegalArgumentException("Cannot find display with id" + mDisplayId);
            }
            Message msg = mCaller.obtainMessage(DO_ATTACH);
            mCaller.sendMessage(msg);
        }

        public void setDesiredSize(int width, int height) {
            Message msg = mCaller.obtainMessageII(DO_SET_DESIRED_SIZE, width, height);
            mCaller.sendMessage(msg);
        }

        public void setDisplayPadding(Rect padding) {
            Message msg = mCaller.obtainMessageO(DO_SET_DISPLAY_PADDING, padding);
            mCaller.sendMessage(msg);
        }

        public void setVisibility(boolean visible) {
            Message msg = mCaller.obtainMessageI(MSG_VISIBILITY_CHANGED,
                    visible ? 1 : 0);
            mCaller.sendMessage(msg);
        }

        @Override
        public void setInAmbientMode(boolean inAmbientDisplay, long animationDuration)
                throws RemoteException {
            Message msg = mCaller.obtainMessageIO(DO_IN_AMBIENT_MODE, inAmbientDisplay ? 1 : 0,
                    animationDuration);
            mCaller.sendMessage(msg);
        }

        public void dispatchPointer(MotionEvent event) {
            if (mEngine != null) {
                mEngine.dispatchPointer(event);
            } else {
                event.recycle();
            }
        }

        public void dispatchWallpaperCommand(String action, int x, int y,
                int z, Bundle extras) {
            if (mEngine != null) {
                mEngine.mWindow.dispatchWallpaperCommand(action, x, y, z, extras, false);
            }
        }

        public void setZoomOut(float scale) {
            Message msg = mCaller.obtainMessageI(MSG_ZOOM, Float.floatToIntBits(scale));
            mCaller.sendMessage(msg);
        }

        public void reportShown() {
            if (!mShownReported) {
                mShownReported = true;
                try {
                    mConnection.engineShown(this);
                    Log.d(TAG, "Wallpaper has updated the surface:"
                            + mWallpaperManager.getWallpaperInfo());
                } catch (RemoteException e) {
                    Log.w(TAG, "Wallpaper host disappeared", e);
                    return;
                }
            }
        }

        public void requestWallpaperColors() {
            Message msg = mCaller.obtainMessage(MSG_REQUEST_WALLPAPER_COLORS);
            mCaller.sendMessage(msg);
        }

        public void addLocalColorsAreas(List<RectF> regions) {
            mEngine.addLocalColorsAreas(regions);
        }

        public void removeLocalColorsAreas(List<RectF> regions) {
            mEngine.removeLocalColorsAreas(regions);
        }

        public void destroy() {
            Message msg = mCaller.obtainMessage(DO_DETACH);
            mCaller.sendMessage(msg);
        }

        public void detach() {
            mDetached.set(true);
        }

        public void applyDimming(float dimAmount) throws RemoteException {
            Message msg = mCaller.obtainMessageI(MSG_UPDATE_DIMMING,
                    Float.floatToIntBits(dimAmount));
            mCaller.sendMessage(msg);
        }

        public void scalePreview(Rect position) {
            Message msg = mCaller.obtainMessageO(MSG_SCALE_PREVIEW, position);
            mCaller.sendMessage(msg);
        }

        @Nullable
        public SurfaceControl mirrorSurfaceControl() {
            return mEngine == null ? null : SurfaceControl.mirrorSurface(mEngine.mSurfaceControl);
        }

        private void doDetachEngine() {
            mActiveEngines.remove(mEngine);
            mEngine.detach();
            // Some wallpapers will not trigger the rendering threads of the remaining engines even
            // if they are visible, so we need to toggle the state to get their attention.
            if (!mDetached.get()) {
                for (Engine eng : mActiveEngines) {
                    if (eng.mVisible) {
                        eng.doVisibilityChanged(false);
                        eng.doVisibilityChanged(true);
                    }
                }
            }
        }

        @Override
        public void executeMessage(Message message) {
            if (mDetached.get()) {
                if (mActiveEngines.contains(mEngine)) {
                    doDetachEngine();
                }
                return;
            }
            switch (message.what) {
                case DO_ATTACH: {
                    Engine engine = onCreateEngine();
                    mEngine = engine;
                    try {
                        mConnection.attachEngine(this, mDisplayId);
                    } catch (RemoteException e) {
                        engine.detach();
                        Log.w(TAG, "Wallpaper host disappeared", e);
                        return;
                    }
                    mActiveEngines.add(engine);
                    engine.attach(this);
                    return;
                }
                case DO_DETACH: {
                    doDetachEngine();
                    return;
                }
                case DO_SET_DESIRED_SIZE: {
                    mEngine.doDesiredSizeChanged(message.arg1, message.arg2);
                    return;
                }
                case DO_SET_DISPLAY_PADDING: {
                    mEngine.doDisplayPaddingChanged((Rect) message.obj);
                    return;
                }
                case DO_IN_AMBIENT_MODE: {
                    mEngine.doAmbientModeChanged(message.arg1 != 0, (Long) message.obj);
                    return;
                }
                case MSG_UPDATE_SURFACE:
                    mEngine.updateSurface(true, false, false);
                    break;
                case MSG_ZOOM:
                    mEngine.setZoom(Float.intBitsToFloat(message.arg1));
                    break;
                case MSG_UPDATE_DIMMING:
                    mEngine.updateWallpaperDimming(Float.intBitsToFloat(message.arg1));
                    break;
                case MSG_SCALE_PREVIEW:
                    mEngine.scalePreview((Rect) message.obj);
                    break;
                case MSG_VISIBILITY_CHANGED:
                    if (DEBUG) Log.v(TAG, "Visibility change in " + mEngine
                            + ": " + message.arg1);
                    mEngine.doVisibilityChanged(message.arg1 != 0);
                    break;
                case MSG_WALLPAPER_OFFSETS: {
                    mEngine.doOffsetsChanged(true);
                } break;
                case MSG_WALLPAPER_COMMAND: {
                    WallpaperCommand cmd = (WallpaperCommand)message.obj;
                    mEngine.doCommand(cmd);
                } break;
                case MSG_WINDOW_RESIZED: {
                    final boolean reportDraw = message.arg1 != 0;
                    mEngine.updateConfiguration(((MergedConfiguration) message.obj));
                    mEngine.updateSurface(true, false, reportDraw);
                    mEngine.doOffsetsChanged(true);
                    mEngine.scaleAndCropScreenshot();
                } break;
                case MSG_WINDOW_MOVED: {
                    // Do nothing. What does it mean for a Wallpaper to move?
                } break;
                case MSG_TOUCH_EVENT: {
                    boolean skip = false;
                    MotionEvent ev = (MotionEvent)message.obj;
                    if (ev.getAction() == MotionEvent.ACTION_MOVE) {
                        synchronized (mEngine.mLock) {
                            if (mEngine.mPendingMove == ev) {
                                mEngine.mPendingMove = null;
                            } else {
                                // this is not the motion event we are looking for....
                                skip = true;
                            }
                        }
                    }
                    if (!skip) {
                        if (DEBUG) Log.v(TAG, "Delivering touch event: " + ev);
                        mEngine.onTouchEvent(ev);
                    }
                    ev.recycle();
                } break;
                case MSG_REQUEST_WALLPAPER_COLORS: {
                    if (mConnection == null) {
                        break;
                    }
                    try {
                        WallpaperColors colors = mEngine.onComputeColors();
                        mEngine.setPrimaryWallpaperColors(colors);
                        mConnection.onWallpaperColorsChanged(colors, mDisplayId);
                    } catch (RemoteException e) {
                        // Connection went away, nothing to do in here.
                    }
                } break;
                case MSG_REPORT_SHOWN: {
                    reportShown();
                } break;
                default :
                    Log.w(TAG, "Unknown message type " + message.what);
            }
        }
    }

    /**
     * Implements the internal {@link IWallpaperService} interface to convert
     * incoming calls to it back to calls on an {@link WallpaperService}.
     */
    class IWallpaperServiceWrapper extends IWallpaperService.Stub {
        private final WallpaperService mTarget;
        private IWallpaperEngineWrapper mEngineWrapper;

        public IWallpaperServiceWrapper(WallpaperService context) {
            mTarget = context;
        }

        @Override
        public void attach(IWallpaperConnection conn, IBinder windowToken,
                int windowType, boolean isPreview, int reqWidth, int reqHeight, Rect padding,
                int displayId) {
            mEngineWrapper = new IWallpaperEngineWrapper(mTarget, conn, windowToken,
                    windowType, isPreview, reqWidth, reqHeight, padding, displayId);
        }

        @Override
        public void detach() {
            mEngineWrapper.detach();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (int i=0; i<mActiveEngines.size(); i++) {
            mActiveEngines.get(i).detach();
        }
        mActiveEngines.clear();
    }

    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IWallpaperServiceWrapper(this);
    }

    /**
     * Must be implemented to return a new instance of the wallpaper's engine.
     * Note that multiple instances may be active at the same time, such as
     * when the wallpaper is currently set as the active wallpaper and the user
     * is in the wallpaper picker viewing a preview of it as well.
     */
    public abstract Engine onCreateEngine();

    @Override
    protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        out.print("State of wallpaper "); out.print(this); out.println(":");
        for (int i=0; i<mActiveEngines.size(); i++) {
            Engine engine = mActiveEngines.get(i);
            out.print("  Engine "); out.print(engine); out.println(":");
            engine.dump("    ", fd, out, args);
        }
    }
}
