/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import static android.graphics.HardwareRenderer.SYNC_CONTEXT_IS_STOPPED;
import static android.graphics.HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND;
import static android.os.IInputConstants.INVALID_INPUT_EVENT_ID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.InputDevice.SOURCE_CLASS_NONE;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.SIZE;
import static android.view.View.PFLAG_DRAW_ANIMATION;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
import static android.view.View.SYSTEM_UI_FLAG_LOW_PROFILE;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewRootImplProto.ADDED;
import static android.view.ViewRootImplProto.APP_VISIBLE;
import static android.view.ViewRootImplProto.CUR_SCROLL_Y;
import static android.view.ViewRootImplProto.DISPLAY_ID;
import static android.view.ViewRootImplProto.HEIGHT;
import static android.view.ViewRootImplProto.IS_ANIMATING;
import static android.view.ViewRootImplProto.IS_DRAWING;
import static android.view.ViewRootImplProto.LAST_WINDOW_INSETS;
import static android.view.ViewRootImplProto.REMOVED;
import static android.view.ViewRootImplProto.SCROLL_Y;
import static android.view.ViewRootImplProto.SOFT_INPUT_MODE;
import static android.view.ViewRootImplProto.VIEW;
import static android.view.ViewRootImplProto.VISIBLE_RECT;
import static android.view.ViewRootImplProto.WIDTH;
import static android.view.ViewRootImplProto.WINDOW_ATTRIBUTES;
import static android.view.ViewRootImplProto.WIN_FRAME;
import static android.view.ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;
import static android.view.WindowCallbacks.RESIZE_MODE_FREEFORM;
import static android.view.WindowCallbacks.RESIZE_MODE_INVALID;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;
import static android.view.WindowInsetsController.BEHAVIOR_DEFAULT;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowLayout.UNSPECIFIED_LENGTH;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_APPEARANCE_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FIT_INSETS_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static android.view.WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS;
import static android.view.WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.IME_FOCUS_CONTROLLER;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.INSETS_CONTROLLER;

import android.Manifest;
import android.animation.LayoutTransition;
import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ICompatCameraControlCallback;
import android.app.ResourcesManager;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.BLASTBufferQueue;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.FrameInfo;
import android.graphics.HardwareRenderer;
import android.graphics.HardwareRenderer.FrameDrawingCallback;
import android.graphics.HardwareRendererObserver;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.sysprop.DisplayProperties;
import android.text.TextUtils;
import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.LongArray;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice.InputSourceClass;
import android.view.InsetsState.InternalInsetsType;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl.Transaction;
import android.view.View.AttachInfo;
import android.view.View.FocusDirection;
import android.view.View.MeasureSpec;
import android.view.Window.OnContentApplyWindowInsetsListener;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import android.view.accessibility.AccessibilityManager.HighTextContrastChangeListener;
import android.view.accessibility.AccessibilityNodeIdManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityEmbeddedConnection;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureSession;
import android.view.contentcapture.MainContentCaptureSession;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;
import android.window.ClientWindowFrames;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.SurfaceSyncer;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.drawable.BackgroundBlurDrawable;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.SomeArgs;
import com.android.internal.policy.DecorView;
import com.android.internal.policy.PhoneFallbackEventHandler;
import com.android.internal.util.Preconditions;
import com.android.internal.view.BaseSurfaceHolder;
import com.android.internal.view.RootViewSurfaceTaker;
import com.android.internal.view.SurfaceCallbackHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;

/**
 * The top of a view hierarchy, implementing the needed protocol between View
 * and the WindowManager.  This is for the most part an internal implementation
 * detail of {@link WindowManagerGlobal}.
 *
 * {@hide}
 */
@SuppressWarnings({"EmptyCatchBlock", "PointlessBooleanExpression"})
public final class ViewRootImpl implements ViewParent,
        View.AttachInfo.Callbacks, ThreadedRenderer.DrawCallbacks,
        AttachedSurfaceControl {
    private static final String TAG = "ViewRootImpl";
    private static final boolean DBG = false;
    private static final boolean LOCAL_LOGV = false;
    /** @noinspection PointlessBooleanExpression*/
    private static final boolean DEBUG_DRAW = false || LOCAL_LOGV;
    private static final boolean DEBUG_LAYOUT = false || LOCAL_LOGV;
    private static final boolean DEBUG_DIALOG = false || LOCAL_LOGV;
    private static final boolean DEBUG_INPUT_RESIZE = false || LOCAL_LOGV;
    private static final boolean DEBUG_ORIENTATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_TRACKBALL = false || LOCAL_LOGV;
    private static final boolean DEBUG_IMF = false || LOCAL_LOGV;
    private static final boolean DEBUG_CONFIGURATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_FPS = false;
    private static final boolean DEBUG_INPUT_STAGES = false || LOCAL_LOGV;
    private static final boolean DEBUG_KEEP_SCREEN_ON = false || LOCAL_LOGV;
    private static final boolean DEBUG_CONTENT_CAPTURE = false || LOCAL_LOGV;
    private static final boolean DEBUG_SCROLL_CAPTURE = false || LOCAL_LOGV;
    private static final boolean DEBUG_BLAST = false || LOCAL_LOGV;

    /**
     * Set to false if we do not want to use the multi threaded renderer even though
     * threaded renderer (aka hardware renderering) is used. Note that by disabling
     * this, WindowCallbacks will not fire.
     */
    private static final boolean MT_RENDERER_AVAILABLE = true;

    /**
     * Whether or not to report end-to-end input latency. Can be disabled temporarily as a
     * risk mitigation against potential jank caused by acquiring a weak reference
     * per frame.
     */
    private static final boolean ENABLE_INPUT_LATENCY_TRACKING = true;

    /**
     * Whether the caption is drawn by the shell.
     * @hide
     */
    public static final boolean CAPTION_ON_SHELL =
            SystemProperties.getBoolean("persist.debug.caption_on_shell", false);

    /**
     * Whether the client should compute the window frame on its own.
     * @hide
     */
    public static final boolean LOCAL_LAYOUT =
            SystemProperties.getBoolean("persist.debug.local_layout", false);

    /**
     * Set this system property to true to force the view hierarchy to render
     * at 60 Hz. This can be used to measure the potential framerate.
     */
    private static final String PROPERTY_PROFILE_RENDERING = "viewroot.profile_rendering";

    /**
     * Maximum time we allow the user to roll the trackball enough to generate
     * a key event, before resetting the counters.
     */
    static final int MAX_TRACKBALL_DELAY = 250;

    /**
     * Initial value for {@link #mContentCaptureEnabled}.
     */
    private static final int CONTENT_CAPTURE_ENABLED_NOT_CHECKED = 0;

    /**
     * Value for {@link #mContentCaptureEnabled} when it was checked and set to {@code true}.
     */
    private static final int CONTENT_CAPTURE_ENABLED_TRUE = 1;

    /**
     * Value for {@link #mContentCaptureEnabled} when it was checked and set to {@code false}.
     */
    private static final int CONTENT_CAPTURE_ENABLED_FALSE = 2;

    /**
     * Maximum time to wait for {@link View#dispatchScrollCaptureSearch} to complete.
     */
    private static final int SCROLL_CAPTURE_REQUEST_TIMEOUT_MILLIS = 2500;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    static final ThreadLocal<HandlerActionQueue> sRunQueues = new ThreadLocal<HandlerActionQueue>();

    static final ArrayList<Runnable> sFirstDrawHandlers = new ArrayList<>();
    static boolean sFirstDrawComplete = false;

    private ArrayList<OnBufferTransformHintChangedListener> mTransformHintListeners =
            new ArrayList<>();
    private @SurfaceControl.BufferTransform
            int mPreviousTransformHint = SurfaceControl.BUFFER_TRANSFORM_IDENTITY;
    /**
     * The top level {@link OnBackInvokedDispatcher}.
     */
    private final WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher =
            new WindowOnBackInvokedDispatcher();
    /**
     * Compatibility {@link OnBackInvokedCallback} that dispatches KEYCODE_BACK events
     * to view root for apps using legacy back behavior.
     */
    private OnBackInvokedCallback mCompatOnBackInvokedCallback;

    /**
     * Callback for notifying about global configuration changes.
     */
    public interface ConfigChangedCallback {

        /** Notifies about global config change. */
        void onConfigurationChanged(Configuration globalConfig);
    }

    private static final ArrayList<ConfigChangedCallback> sConfigCallbacks = new ArrayList<>();

    /**
     * Callback for notifying activities.
     */
    public interface ActivityConfigCallback {

        /**
         * Notifies about override config change and/or move to different display.
         * @param overrideConfig New override config to apply to activity.
         * @param newDisplayId New display id, {@link Display#INVALID_DISPLAY} if not changed.
         */
        void onConfigurationChanged(Configuration overrideConfig, int newDisplayId);

        /**
         * Notify the corresponding activity about the request to show or hide a camera compat
         * control for stretched issues in the viewfinder.
         *
         * @param showControl Whether the control should be shown or hidden.
         * @param transformationApplied Whether the treatment is already applied.
         * @param callback The callback executed when the user clicks on a control.
         */
        void requestCompatCameraControl(boolean showControl, boolean transformationApplied,
                ICompatCameraControlCallback callback);
    }

    /**
     * Callback used to notify corresponding activity about camera compat control changes, override
     * configuration change and make sure that all resources are set correctly before updating the
     * ViewRootImpl's internal state.
     */
    private ActivityConfigCallback mActivityConfigCallback;

    /**
     * Used when configuration change first updates the config of corresponding activity.
     * In that case we receive a call back from {@link ActivityThread} and this flag is used to
     * preserve the initial value.
     *
     * @see #performConfigurationChange(MergedConfiguration, boolean, int)
     */
    private boolean mForceNextConfigUpdate;

    private boolean mUseBLASTAdapter;
    private boolean mForceDisableBLAST;

    private boolean mFastScrollSoundEffectsEnabled;

    /**
     * Signals that compatibility booleans have been initialized according to
     * target SDK versions.
     */
    private static boolean sCompatibilityDone = false;

    /**
     * Always assign focus if a focusable View is available.
     */
    private static boolean sAlwaysAssignFocus;

    /**
     * This list must only be modified by the main thread.
     */
    final ArrayList<WindowCallbacks> mWindowCallbacks = new ArrayList<>();
    @UnsupportedAppUsage
    @UiContext
    public final Context mContext;

    @UnsupportedAppUsage
    final IWindowSession mWindowSession;
    @NonNull Display mDisplay;
    final DisplayManager mDisplayManager;
    final String mBasePackageName;

    private @Surface.Rotation int mDisplayInstallOrientation;

    final int[] mTmpLocation = new int[2];

    final TypedValue mTmpValue = new TypedValue();

    final Thread mThread;

    final WindowLeaked mLocation;

    public final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

    final W mWindow;

    final IBinder mLeashToken;

    final int mTargetSdkVersion;

    @UnsupportedAppUsage
    View mView;

    View mAccessibilityFocusedHost;
    // Accessibility-focused virtual view. The bounds and sourceNodeId of
    // mAccessibilityFocusedVirtualView is up-to-date while other fields may be stale.
    AccessibilityNodeInfo mAccessibilityFocusedVirtualView;

    // True if the window currently has pointer capture enabled.
    boolean mPointerCapture;

    int mViewVisibility;
    boolean mAppVisible = true;
    // For recents to freeform transition we need to keep drawing after the app receives information
    // that it became invisible. This will ignore that information and depend on the decor view
    // visibility to control drawing. The decor view visibility will get adjusted when the app get
    // stopped and that's when the app will stop drawing further frames.
    private boolean mForceDecorViewVisibility = false;
    // Used for tracking app visibility updates separately in case we get double change. This will
    // make sure that we always call relayout for the corresponding window.
    private boolean mAppVisibilityChanged;
    int mOrigWindowType = -1;

    /** Whether the window had focus during the most recent traversal. */
    boolean mHadWindowFocus;

    /**
     * Whether the window lost focus during a previous traversal and has not
     * yet gained it back. Used to determine whether a WINDOW_STATE_CHANGE
     * accessibility events should be sent during traversal.
     */
    boolean mLostWindowFocus;

    // Set to true if the owner of this window is in the stopped state,
    // so the window should no longer be active.
    @UnsupportedAppUsage
    boolean mStopped = false;

    // Set to true if the owner of this window is in ambient mode,
    // which means it won't receive input events.
    boolean mIsAmbientMode = false;

    // Set to true to stop input during an Activity Transition.
    boolean mPausedForTransition = false;

    boolean mLastInCompatMode = false;

    SurfaceHolder.Callback2 mSurfaceHolderCallback;
    BaseSurfaceHolder mSurfaceHolder;
    boolean mIsCreating;
    boolean mDrawingAllowed;

    final Region mTransparentRegion;
    final Region mPreviousTransparentRegion;

    Region mTouchableRegion;
    Region mPreviousTouchableRegion;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int mWidth;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int mHeight;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Rect mDirty;
    public boolean mIsAnimating;

    private boolean mUseMTRenderer;
    private boolean mPendingDragResizing;
    private boolean mDragResizing;
    private boolean mInvalidateRootRequested;
    private int mResizeMode = RESIZE_MODE_INVALID;
    private int mCanvasOffsetX;
    private int mCanvasOffsetY;
    private boolean mActivityRelaunched;

    CompatibilityInfo.Translator mTranslator;

    @UnsupportedAppUsage
    final View.AttachInfo mAttachInfo;
    final SystemUiVisibilityInfo mCompatibleVisibilityInfo;
    int mDispatchedSystemUiVisibility;
    int mDispatchedSystemBarAppearance;
    InputQueue.Callback mInputQueueCallback;
    InputQueue mInputQueue;
    @UnsupportedAppUsage
    FallbackEventHandler mFallbackEventHandler;
    final Choreographer mChoreographer;
    protected final ViewFrameInfo mViewFrameInfo = new ViewFrameInfo();
    private final InputEventAssigner mInputEventAssigner = new InputEventAssigner();

    // Whether to draw this surface as DISPLAY_DECORATION.
    boolean mDisplayDecorationCached = false;

    /**
     * Update the Choreographer's FrameInfo object with the timing information for the current
     * ViewRootImpl instance. Erase the data in the current ViewFrameInfo to prepare for the next
     * frame.
     * @return the updated FrameInfo object
     */
    protected @NonNull FrameInfo getUpdatedFrameInfo() {
        // Since Choreographer is a thread-local singleton while we can have multiple
        // ViewRootImpl's, populate the frame information from the current viewRootImpl before
        // starting the draw
        FrameInfo frameInfo = mChoreographer.mFrameInfo;
        mViewFrameInfo.populateFrameInfo(frameInfo);
        mViewFrameInfo.reset();
        mInputEventAssigner.notifyFrameProcessed();
        return frameInfo;
    }

    // used in relayout to get SurfaceControl size
    // for BLAST adapter surface setup
    private final Point mSurfaceSize = new Point();
    private final Point mLastSurfaceSize = new Point();

    private final Rect mVisRect = new Rect(); // used to retrieve visible rect of focused view.
    private final Rect mTempRect = new Rect();

    private final WindowLayout mWindowLayout = new WindowLayout();

    private ViewRootImpl mParentViewRoot;

    // This is used to reduce the race between window focus changes being dispatched from
    // the window manager and input events coming through the input system.
    @GuardedBy("this")
    boolean mWindowFocusChanged;
    @GuardedBy("this")
    boolean mUpcomingWindowFocus;
    @GuardedBy("this")
    boolean mUpcomingInTouchMode;

    public boolean mTraversalScheduled;
    int mTraversalBarrier;
    boolean mWillDrawSoon;
    /** Set to true while in performTraversals for detecting when die(true) is called from internal
     * callbacks such as onMeasure, onPreDraw, onDraw and deferring doDie() until later. */
    boolean mIsInTraversal;
    boolean mApplyInsetsRequested;
    boolean mLayoutRequested;
    boolean mFirst;

    @Nullable
    int mContentCaptureEnabled = CONTENT_CAPTURE_ENABLED_NOT_CHECKED;
    boolean mPerformContentCapture;
    boolean mPerformAutoFill;


    boolean mReportNextDraw;

    /**
     * Set whether the draw should send the buffer to system server. When set to true, VRI will
     * create a sync transaction with BBQ and send the resulting buffer to system server. If false,
     * VRI will not try to sync a buffer in BBQ, but still report when a draw occurred.
     */
    private boolean mSyncBuffer = false;

    int mSyncSeqId = 0;
    int mLastSyncSeqId = 0;

    boolean mFullRedrawNeeded;
    boolean mNewSurfaceNeeded;
    boolean mForceNextWindowRelayout;
    CountDownLatch mWindowDrawCountDown;

    // Whether we have used applyTransactionOnDraw to schedule an RT
    // frame callback consuming a passed in transaction. In this case
    // we also need to schedule a commit callback so we can observe
    // if the draw was skipped, and the BBQ pending transactions.
    boolean mHasPendingTransactions;

    boolean mIsDrawing;
    int mLastSystemUiVisibility;
    int mClientWindowLayoutFlags;

    // Pool of queued input events.
    private static final int MAX_QUEUED_INPUT_EVENT_POOL_SIZE = 10;
    private QueuedInputEvent mQueuedInputEventPool;
    private int mQueuedInputEventPoolSize;

    /* Input event queue.
     * Pending input events are input events waiting to be delivered to the input stages
     * and handled by the application.
     */
    QueuedInputEvent mPendingInputEventHead;
    QueuedInputEvent mPendingInputEventTail;
    int mPendingInputEventCount;
    boolean mProcessInputEventsScheduled;
    boolean mUnbufferedInputDispatch;
    @InputSourceClass
    int mUnbufferedInputSource = SOURCE_CLASS_NONE;

    String mPendingInputEventQueueLengthCounterName = "pq";

    InputStage mFirstInputStage;
    InputStage mFirstPostImeInputStage;
    InputStage mSyntheticInputStage;

    private final UnhandledKeyManager mUnhandledKeyManager = new UnhandledKeyManager();

    boolean mWindowAttributesChanged = false;

    // These can be accessed by any thread, must be protected with a lock.
    // Surface can never be reassigned or cleared (use Surface.clear()).
    @UnsupportedAppUsage
    public final Surface mSurface = new Surface();
    private final SurfaceControl mSurfaceControl = new SurfaceControl();

    private BLASTBufferQueue mBlastBufferQueue;

    /**
     * Transaction object that can be used to synchronize child SurfaceControl changes with
     * ViewRootImpl SurfaceControl changes by the server. The object is passed along with
     * the SurfaceChangedCallback.
     */
    private final Transaction mSurfaceChangedTransaction = new Transaction();
    /**
     * Child container layer of {@code mSurface} with the same bounds as its parent, and cropped to
     * the surface insets. This surface is created only if a client requests it via {@link
     * #getBoundsLayer()}. By parenting to this bounds surface, child surfaces can ensure they do
     * not draw into the surface inset region set by the parent window.
     */
    private SurfaceControl mBoundsLayer;
    private final SurfaceSession mSurfaceSession = new SurfaceSession();
    private final Transaction mTransaction = new Transaction();

    @UnsupportedAppUsage
    boolean mAdded;
    boolean mAddedTouchMode;

    /**
     * It usually keeps the latest layout result from {@link IWindow#resized} or
     * {@link IWindowSession#relayout}.
     */
    private final ClientWindowFrames mTmpFrames = new ClientWindowFrames();

    // These are accessed by multiple threads.
    final Rect mWinFrame; // frame given by window manager.
    Rect mOverrideInsetsFrame;

    final Rect mPendingBackDropFrame = new Rect();

    boolean mPendingAlwaysConsumeSystemBars;
    private final InsetsState mTempInsets = new InsetsState();
    private final InsetsSourceControl[] mTempControls = new InsetsSourceControl[SIZE];
    final ViewTreeObserver.InternalInsetsInfo mLastGivenInsets
            = new ViewTreeObserver.InternalInsetsInfo();

    private WindowInsets mLastWindowInsets;

    // Insets types hidden by legacy window flags or system UI flags.
    private @InsetsType int mTypesHiddenByFlags = 0;

    /** Last applied configuration obtained from resources. */
    private final Configuration mLastConfigurationFromResources = new Configuration();
    /** Last configuration reported from WM or via {@link #MSG_UPDATE_CONFIGURATION}. */
    private final MergedConfiguration mLastReportedMergedConfiguration = new MergedConfiguration();
    /** Configurations waiting to be applied. */
    private final MergedConfiguration mPendingMergedConfiguration = new MergedConfiguration();

    boolean mScrollMayChange;
    @SoftInputModeFlags
    int mSoftInputMode;
    @UnsupportedAppUsage
    WeakReference<View> mLastScrolledFocus;
    int mScrollY;
    int mCurScrollY;
    Scroller mScroller;
    static final Interpolator mResizeInterpolator = new AccelerateDecelerateInterpolator();
    private ArrayList<LayoutTransition> mPendingTransitions;

    final ViewConfiguration mViewConfiguration;

    /* Drag/drop */
    ClipDescription mDragDescription;
    View mCurrentDragView;
    View mStartedDragViewForA11y;
    volatile Object mLocalDragState;
    final PointF mDragPoint = new PointF();
    final PointF mLastTouchPoint = new PointF();
    int mLastTouchSource;

    private boolean mProfileRendering;
    private Choreographer.FrameCallback mRenderProfiler;
    private boolean mRenderProfilingEnabled;

    // Variables to track frames per second, enabled via DEBUG_FPS flag
    private long mFpsStartTime = -1;
    private long mFpsPrevTime = -1;
    private int mFpsNumFrames;

    private int mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
    private PointerIcon mCustomPointerIcon = null;

    /**
     * see {@link #playSoundEffect(int)}
     */
    AudioManager mAudioManager;

    final AccessibilityManager mAccessibilityManager;

    AccessibilityInteractionController mAccessibilityInteractionController;

    final AccessibilityInteractionConnectionManager mAccessibilityInteractionConnectionManager =
            new AccessibilityInteractionConnectionManager();
    final HighContrastTextManager mHighContrastTextManager;

    SendWindowContentChangedAccessibilityEvent mSendWindowContentChangedAccessibilityEvent;

    HashSet<View> mTempHashSet;

    private final int mDensity;
    private final int mNoncompatDensity;

    private boolean mInLayout = false;
    ArrayList<View> mLayoutRequesters = new ArrayList<View>();
    boolean mHandlingLayoutInLayoutRequest = false;

    private int mViewLayoutDirectionInitial;

    /** Set to true once doDie() has been called. */
    private boolean mRemoved;

    private boolean mNeedsRendererSetup;

    private final InputEventCompatProcessor mInputCompatProcessor;

    /**
     * Consistency verifier for debugging purposes.
     */
    protected final InputEventConsistencyVerifier mInputEventConsistencyVerifier =
            InputEventConsistencyVerifier.isInstrumentationEnabled() ?
                    new InputEventConsistencyVerifier(this, 0) : null;

    private final InsetsController mInsetsController;
    private final ImeFocusController mImeFocusController;

    private boolean mIsSurfaceOpaque;

    private final BackgroundBlurDrawable.Aggregator mBlurRegionAggregator =
            new BackgroundBlurDrawable.Aggregator(this);

    /**
     * @return {@link ImeFocusController} for this instance.
     */
    @NonNull
    public ImeFocusController getImeFocusController() {
        return mImeFocusController;
    }

    private final ViewRootRectTracker mGestureExclusionTracker =
            new ViewRootRectTracker(v -> v.getSystemGestureExclusionRects());
    private final ViewRootRectTracker mKeepClearRectsTracker =
            new ViewRootRectTracker(v -> v.collectPreferKeepClearRects());
    private final ViewRootRectTracker mUnrestrictedKeepClearRectsTracker =
            new ViewRootRectTracker(v -> v.collectUnrestrictedPreferKeepClearRects());

    private IAccessibilityEmbeddedConnection mAccessibilityEmbeddedConnection;

    static final class SystemUiVisibilityInfo {
        int globalVisibility;
        int localValue;
        int localChanges;
    }

    private final HandwritingInitiator mHandwritingInitiator;

    /**
     * Used by InputMethodManager.
     * @hide
     */
    @NonNull
    public HandwritingInitiator getHandwritingInitiator() {
        return mHandwritingInitiator;
    }

    private final SurfaceSyncer mSurfaceSyncer = new SurfaceSyncer();
    private int mLastSyncId = -1;
    private SurfaceSyncer.SyncBufferCallback mSyncBufferCallback;
    private int mNumSyncsInProgress = 0;

    private HashSet<ScrollCaptureCallback> mRootScrollCaptureCallbacks;

    private long mScrollCaptureRequestTimeout = SCROLL_CAPTURE_REQUEST_TIMEOUT_MILLIS;

    /**
     * Increment this value when the surface has been replaced.
     */
    private int mSurfaceSequenceId = 0;

    private boolean mRelayoutRequested;

    private int mLastTransformHint = Integer.MIN_VALUE;

    /**
     * A temporary object used so relayoutWindow can return the latest SyncSeqId
     * system. The SyncSeqId system was designed to work without synchronous relayout
     * window, and actually synchronous relayout window presents a problem.  We could have
     * a sequence like this:
     *    1. We send MSG_RESIZED to the client with a new syncSeqId to begin a new sync
     *    2. Due to scheduling the client executes performTraversals before calling MSG_RESIZED
     *    3. Coincidentally for some random reason it also calls relayout
     *    4. It observes the new state from relayout, and so the next frame will contain the state
     * However it hasn't received the seqId yet, and so under the designed operation of
     * seqId flowing through MSG_RESIZED, the next frame wouldn't be synced. Since it
     * contains our target sync state, we need to sync it! This problem won't come up once
     * we get rid of synchronous relayout, until then, we use this bundle to channel the
     * integer back over relayout.
     */
    private Bundle mRelayoutBundle = new Bundle();

    private String mTag = TAG;

    public ViewRootImpl(Context context, Display display) {
        this(context, display, WindowManagerGlobal.getWindowSession(),
                false /* useSfChoreographer */);
    }

    public ViewRootImpl(@UiContext Context context, Display display, IWindowSession session) {
        this(context, display, session, false /* useSfChoreographer */);
    }

    public ViewRootImpl(@UiContext Context context, Display display, IWindowSession session,
            boolean useSfChoreographer) {
        mContext = context;
        mWindowSession = session;
        mDisplay = display;
        mBasePackageName = context.getBasePackageName();
        mThread = Thread.currentThread();
        mLocation = new WindowLeaked(null);
        mLocation.fillInStackTrace();
        mWidth = -1;
        mHeight = -1;
        mDirty = new Rect();
        mWinFrame = new Rect();
        mWindow = new W(this);
        mLeashToken = new Binder();
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        mViewVisibility = View.GONE;
        mTransparentRegion = new Region();
        mPreviousTransparentRegion = new Region();
        mFirst = true; // true for the first time the view is added
        mPerformContentCapture = true; // also true for the first time the view is added
        mPerformAutoFill = true;
        mAdded = false;
        mAttachInfo = new View.AttachInfo(mWindowSession, mWindow, display, this, mHandler, this,
                context);
        mCompatibleVisibilityInfo = new SystemUiVisibilityInfo();
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mHighContrastTextManager = new HighContrastTextManager();
        mViewConfiguration = ViewConfiguration.get(context);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        mNoncompatDensity = context.getResources().getDisplayMetrics().noncompatDensityDpi;
        mFallbackEventHandler = new PhoneFallbackEventHandler(context);
        // TODO(b/222696368): remove getSfInstance usage and use vsyncId for transactions
        mChoreographer = useSfChoreographer
                ? Choreographer.getSfInstance() : Choreographer.getInstance();
        mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
        mInsetsController = new InsetsController(new ViewRootInsetsControllerHost(this));
        mHandwritingInitiator = new HandwritingInitiator(
                mViewConfiguration,
                mContext.getSystemService(InputMethodManager.class),
                context.getResources().getDisplayMetrics());

        String processorOverrideName = context.getResources().getString(
                                    R.string.config_inputEventCompatProcessorOverrideClassName);
        if (processorOverrideName.isEmpty()) {
            // No compatibility processor override, using default.
            mInputCompatProcessor = new InputEventCompatProcessor(context);
        } else {
            InputEventCompatProcessor compatProcessor = null;
            try {
                final Class<? extends InputEventCompatProcessor> klass =
                        (Class<? extends InputEventCompatProcessor>) Class.forName(
                                processorOverrideName);
                compatProcessor = klass.getConstructor(Context.class).newInstance(context);
            } catch (Exception e) {
                Log.e(TAG, "Unable to create the InputEventCompatProcessor. ", e);
            } finally {
                mInputCompatProcessor = compatProcessor;
            }
        }

        if (!sCompatibilityDone) {
            sAlwaysAssignFocus = mTargetSdkVersion < Build.VERSION_CODES.P;

            sCompatibilityDone = true;
        }

        loadSystemProperties();
        mImeFocusController = new ImeFocusController(this);
        AudioManager audioManager = mContext.getSystemService(AudioManager.class);
        mFastScrollSoundEffectsEnabled = audioManager.areNavigationRepeatSoundEffectsEnabled();

        mScrollCaptureRequestTimeout = SCROLL_CAPTURE_REQUEST_TIMEOUT_MILLIS;
    }

    public static void addFirstDrawHandler(Runnable callback) {
        synchronized (sFirstDrawHandlers) {
            if (!sFirstDrawComplete) {
                sFirstDrawHandlers.add(callback);
            }
        }
    }

    /** Add static config callback to be notified about global config changes. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void addConfigCallback(ConfigChangedCallback callback) {
        synchronized (sConfigCallbacks) {
            sConfigCallbacks.add(callback);
        }
    }

    /** Remove a static config callback. */
    public static void removeConfigCallback(ConfigChangedCallback callback) {
        synchronized (sConfigCallbacks) {
            sConfigCallbacks.remove(callback);
        }
    }

    /**
     * Add activity config callback to be notified about override config changes and camera
     * compat control state updates.
     */
    public void setActivityConfigCallback(ActivityConfigCallback callback) {
        mActivityConfigCallback = callback;
    }

    public void setOnContentApplyWindowInsetsListener(OnContentApplyWindowInsetsListener listener) {
        mAttachInfo.mContentOnApplyWindowInsetsListener = listener;

        // System windows will be fitted on first traversal, so no reason to request additional
        // (possibly getting executed after the first traversal).
        if (!mFirst) {
            requestFitSystemWindows();
        }
    }

    public void addWindowCallbacks(WindowCallbacks callback) {
        mWindowCallbacks.add(callback);
    }

    public void removeWindowCallbacks(WindowCallbacks callback) {
        mWindowCallbacks.remove(callback);
    }

    public void reportDrawFinish() {
        if (mWindowDrawCountDown != null) {
            mWindowDrawCountDown.countDown();
        }
    }

    // FIXME for perf testing only
    private boolean mProfile = false;

    /**
     * Call this to profile the next traversal call.
     * FIXME for perf testing only. Remove eventually
     */
    public void profile() {
        mProfile = true;
    }

    /**
     * Indicates whether we are in touch mode. Calling this method triggers an IPC
     * call and should be avoided whenever possible.
     *
     * @return True, if the device is in touch mode, false otherwise.
     *
     * @hide
     */
    static boolean isInTouchMode() {
        IWindowSession windowSession = WindowManagerGlobal.peekWindowSession();
        if (windowSession != null) {
            try {
                return windowSession.getInTouchMode();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     * Notifies us that our child has been rebuilt, following
     * a window preservation operation. In these cases we
     * keep the same DecorView, but the activity controlling it
     * is a different instance, and we need to update our
     * callbacks.
     *
     * @hide
     */
    public void notifyChildRebuilt() {
        if (mView instanceof RootViewSurfaceTaker) {
            if (mSurfaceHolderCallback != null) {
                mSurfaceHolder.removeCallback(mSurfaceHolderCallback);
            }

            mSurfaceHolderCallback =
                ((RootViewSurfaceTaker)mView).willYouTakeTheSurface();

            if (mSurfaceHolderCallback != null) {
                mSurfaceHolder = new TakenSurfaceHolder();
                mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                mSurfaceHolder.addCallback(mSurfaceHolderCallback);
            } else {
                mSurfaceHolder = null;
            }

            mInputQueueCallback =
                ((RootViewSurfaceTaker)mView).willYouTakeTheInputQueue();
            if (mInputQueueCallback != null) {
                mInputQueueCallback.onInputQueueCreated(mInputQueue);
            }
        }
    }

    private Configuration getConfiguration() {
        return mContext.getResources().getConfiguration();
    }

    /**
     * We have one child
     */
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView) {
        setView(view, attrs, panelParentView, UserHandle.myUserId());
    }

    /**
     * We have one child
     */
    public void setView(View view, WindowManager.LayoutParams attrs, View panelParentView,
            int userId) {
        synchronized (this) {
            if (mView == null) {
                mView = view;

                mAttachInfo.mDisplayState = mDisplay.getState();
                mDisplayInstallOrientation = mDisplay.getInstallOrientation();
                mViewLayoutDirectionInitial = mView.getRawLayoutDirection();
                mFallbackEventHandler.setView(view);
                mWindowAttributes.copyFrom(attrs);
                if (mWindowAttributes.packageName == null) {
                    mWindowAttributes.packageName = mBasePackageName;
                }
                mWindowAttributes.privateFlags |=
                        WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST;

                attrs = mWindowAttributes;
                setTag();

                if (DEBUG_KEEP_SCREEN_ON && (mClientWindowLayoutFlags
                        & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                        && (attrs.flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                    Slog.d(mTag, "setView: FLAG_KEEP_SCREEN_ON changed from true to false!");
                }
                // Keep track of the actual window flags supplied by the client.
                mClientWindowLayoutFlags = attrs.flags;

                setAccessibilityFocus(null, null);

                if (view instanceof RootViewSurfaceTaker) {
                    mSurfaceHolderCallback =
                            ((RootViewSurfaceTaker)view).willYouTakeTheSurface();
                    if (mSurfaceHolderCallback != null) {
                        mSurfaceHolder = new TakenSurfaceHolder();
                        mSurfaceHolder.setFormat(PixelFormat.UNKNOWN);
                        mSurfaceHolder.addCallback(mSurfaceHolderCallback);
                    }
                }

                // Compute surface insets required to draw at specified Z value.
                // TODO: Use real shadow insets for a constant max Z.
                if (!attrs.hasManualSurfaceInsets) {
                    attrs.setSurfaceInsets(view, false /*manual*/, true /*preservePrevious*/);
                }

                CompatibilityInfo compatibilityInfo =
                        mDisplay.getDisplayAdjustments().getCompatibilityInfo();
                mTranslator = compatibilityInfo.getTranslator();

                // If the application owns the surface, don't enable hardware acceleration
                if (mSurfaceHolder == null) {
                    // While this is supposed to enable only, it can effectively disable
                    // the acceleration too.
                    enableHardwareAcceleration(attrs);
                    final boolean useMTRenderer = MT_RENDERER_AVAILABLE
                            && mAttachInfo.mThreadedRenderer != null;
                    if (mUseMTRenderer != useMTRenderer) {
                        // Shouldn't be resizing, as it's done only in window setup,
                        // but end just in case.
                        endDragResizing();
                        mUseMTRenderer = useMTRenderer;
                    }
                }

                boolean restore = false;
                if (mTranslator != null) {
                    mSurface.setCompatibilityTranslator(mTranslator);
                    restore = true;
                    attrs.backup();
                    mTranslator.translateWindowLayout(attrs);
                }
                if (DEBUG_LAYOUT) Log.d(mTag, "WindowLayout in setView:" + attrs);

                if (!compatibilityInfo.supportsScreen()) {
                    attrs.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                    mLastInCompatMode = true;
                }

                mSoftInputMode = attrs.softInputMode;
                mWindowAttributesChanged = true;
                mAttachInfo.mRootView = view;
                mAttachInfo.mScalingRequired = mTranslator != null;
                mAttachInfo.mApplicationScale =
                        mTranslator == null ? 1.0f : mTranslator.applicationScale;
                if (panelParentView != null) {
                    mAttachInfo.mPanelParentWindowToken
                            = panelParentView.getApplicationWindowToken();
                    mParentViewRoot = panelParentView.getViewRootImpl();
                }
                mAdded = true;
                int res; /* = WindowManagerImpl.ADD_OKAY; */

                // Schedule the first layout -before- adding to the window
                // manager, to make sure we do the relayout before receiving
                // any other events from the system.
                requestLayout();
                InputChannel inputChannel = null;
                if ((mWindowAttributes.inputFeatures
                        & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                    inputChannel = new InputChannel();
                }
                mForceDecorViewVisibility = (mWindowAttributes.privateFlags
                        & PRIVATE_FLAG_FORCE_DECOR_VIEW_VISIBILITY) != 0;

                if (mView instanceof RootViewSurfaceTaker) {
                    PendingInsetsController pendingInsetsController =
                            ((RootViewSurfaceTaker) mView).providePendingInsetsController();
                    if (pendingInsetsController != null) {
                        pendingInsetsController.replayAndAttach(mInsetsController);
                    }
                }

                try {
                    mOrigWindowType = mWindowAttributes.type;
                    mAttachInfo.mRecomputeGlobalAttributes = true;
                    collectViewAttributes();
                    adjustLayoutParamsForCompatibility(mWindowAttributes);
                    controlInsetsForCompatibility(mWindowAttributes);
                    res = mWindowSession.addToDisplayAsUser(mWindow, mWindowAttributes,
                            getHostVisibility(), mDisplay.getDisplayId(), userId,
                            mInsetsController.getRequestedVisibilities(), inputChannel, mTempInsets,
                            mTempControls);
                    if (mTranslator != null) {
                        mTranslator.translateInsetsStateInScreenToAppWindow(mTempInsets);
                        mTranslator.translateSourceControlsInScreenToAppWindow(mTempControls);
                    }
                } catch (RemoteException e) {
                    mAdded = false;
                    mView = null;
                    mAttachInfo.mRootView = null;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    throw new RuntimeException("Adding window failed", e);
                } finally {
                    if (restore) {
                        attrs.restore();
                    }
                }

                mAttachInfo.mAlwaysConsumeSystemBars =
                        (res & WindowManagerGlobal.ADD_FLAG_ALWAYS_CONSUME_SYSTEM_BARS) != 0;
                mPendingAlwaysConsumeSystemBars = mAttachInfo.mAlwaysConsumeSystemBars;
                mInsetsController.onStateChanged(mTempInsets);
                mInsetsController.onControlsChanged(mTempControls);
                final InsetsState state = mInsetsController.getState();
                final Rect displayCutoutSafe = mTempRect;
                state.getDisplayCutoutSafe(displayCutoutSafe);
                final WindowConfiguration winConfig = getConfiguration().windowConfiguration;
                mWindowLayout.computeFrames(mWindowAttributes, state,
                        displayCutoutSafe, winConfig.getBounds(), winConfig.getWindowingMode(),
                        UNSPECIFIED_LENGTH, UNSPECIFIED_LENGTH,
                        mInsetsController.getRequestedVisibilities(),
                        getAttachedWindowFrame(), 1f /* compactScale */, mTmpFrames);
                setFrame(mTmpFrames.frame);
                registerBackCallbackOnWindow();
                if (!WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(mContext)) {
                    // For apps requesting legacy back behavior, we add a compat callback that
                    // dispatches {@link KeyEvent#KEYCODE_BACK} to their root views.
                    // This way from system point of view, these apps are providing custom
                    // {@link OnBackInvokedCallback}s, and will not play system back animations
                    // for them.
                    registerCompatOnBackInvokedCallback();
                }
                if (DEBUG_LAYOUT) Log.v(mTag, "Added window " + mWindow);
                if (res < WindowManagerGlobal.ADD_OKAY) {
                    mAttachInfo.mRootView = null;
                    mAdded = false;
                    mFallbackEventHandler.setView(null);
                    unscheduleTraversals();
                    setAccessibilityFocus(null, null);
                    switch (res) {
                        case WindowManagerGlobal.ADD_BAD_APP_TOKEN:
                        case WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- token " + attrs.token
                                    + " is not valid; is your activity running?");
                        case WindowManagerGlobal.ADD_NOT_APP_TOKEN:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- token " + attrs.token
                                    + " is not for an application");
                        case WindowManagerGlobal.ADD_APP_EXITING:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- app for token " + attrs.token
                                    + " is exiting");
                        case WindowManagerGlobal.ADD_DUPLICATE_ADD:
                            throw new WindowManager.BadTokenException(
                                    "Unable to add window -- window " + mWindow
                                    + " has already been added");
                        case WindowManagerGlobal.ADD_STARTING_NOT_NEEDED:
                            // Silently ignore -- we would have just removed it
                            // right away, anyway.
                            return;
                        case WindowManagerGlobal.ADD_MULTIPLE_SINGLETON:
                            throw new WindowManager.BadTokenException("Unable to add window "
                                    + mWindow + " -- another window of type "
                                    + mWindowAttributes.type + " already exists");
                        case WindowManagerGlobal.ADD_PERMISSION_DENIED:
                            throw new WindowManager.BadTokenException("Unable to add window "
                                    + mWindow + " -- permission denied for window type "
                                    + mWindowAttributes.type);
                        case WindowManagerGlobal.ADD_INVALID_DISPLAY:
                            throw new WindowManager.InvalidDisplayException("Unable to add window "
                                    + mWindow + " -- the specified display can not be found");
                        case WindowManagerGlobal.ADD_INVALID_TYPE:
                            throw new WindowManager.InvalidDisplayException("Unable to add window "
                                    + mWindow + " -- the specified window type "
                                    + mWindowAttributes.type + " is not valid");
                        case WindowManagerGlobal.ADD_INVALID_USER:
                            throw new WindowManager.BadTokenException("Unable to add Window "
                                    + mWindow + " -- requested userId is not valid");
                    }
                    throw new RuntimeException(
                            "Unable to add window -- unknown error code " + res);
                }

                registerListeners();
                if ((res & WindowManagerGlobal.ADD_FLAG_USE_BLAST) != 0) {
                    mUseBLASTAdapter = true;
                }

                if (view instanceof RootViewSurfaceTaker) {
                    mInputQueueCallback =
                        ((RootViewSurfaceTaker)view).willYouTakeTheInputQueue();
                }
                if (inputChannel != null) {
                    if (mInputQueueCallback != null) {
                        mInputQueue = new InputQueue();
                        mInputQueueCallback.onInputQueueCreated(mInputQueue);
                    }
                    mInputEventReceiver = new WindowInputEventReceiver(inputChannel,
                            Looper.myLooper());

                    if (ENABLE_INPUT_LATENCY_TRACKING && mAttachInfo.mThreadedRenderer != null) {
                        InputMetricsListener listener = new InputMetricsListener();
                        mHardwareRendererObserver = new HardwareRendererObserver(
                                listener, listener.data, mHandler, true /*waitForPresentTime*/);
                        mAttachInfo.mThreadedRenderer.addObserver(mHardwareRendererObserver);
                    }
                }

                view.assignParent(this);
                mAddedTouchMode = (res & WindowManagerGlobal.ADD_FLAG_IN_TOUCH_MODE) != 0;
                mAppVisible = (res & WindowManagerGlobal.ADD_FLAG_APP_VISIBLE) != 0;

                if (mAccessibilityManager.isEnabled()) {
                    mAccessibilityInteractionConnectionManager.ensureConnection();
                }

                if (view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                }

                // Set up the input pipeline.
                CharSequence counterSuffix = attrs.getTitle();
                mSyntheticInputStage = new SyntheticInputStage();
                InputStage viewPostImeStage = new ViewPostImeInputStage(mSyntheticInputStage);
                InputStage nativePostImeStage = new NativePostImeInputStage(viewPostImeStage,
                        "aq:native-post-ime:" + counterSuffix);
                InputStage earlyPostImeStage = new EarlyPostImeInputStage(nativePostImeStage);
                InputStage imeStage = new ImeInputStage(earlyPostImeStage,
                        "aq:ime:" + counterSuffix);
                InputStage viewPreImeStage = new ViewPreImeInputStage(imeStage);
                InputStage nativePreImeStage = new NativePreImeInputStage(viewPreImeStage,
                        "aq:native-pre-ime:" + counterSuffix);

                mFirstInputStage = nativePreImeStage;
                mFirstPostImeInputStage = earlyPostImeStage;
                mPendingInputEventQueueLengthCounterName = "aq:pending:" + counterSuffix;
            }
        }
    }

    private Rect getAttachedWindowFrame() {
        final int type = mWindowAttributes.type;
        final boolean layoutAttached = (mParentViewRoot != null
                && type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW
                && type != TYPE_APPLICATION_ATTACHED_DIALOG);
        return layoutAttached ? mParentViewRoot.mWinFrame : null;
    }

    /**
     * Register any kind of listeners if setView was success.
     */
    private void registerListeners() {
        mAccessibilityManager.addAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager, mHandler);
        mAccessibilityManager.addHighTextContrastStateChangeListener(
                mHighContrastTextManager, mHandler);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
    }

    /**
     * Unregister all listeners while detachedFromWindow.
     */
    private void unregisterListeners() {
        mAccessibilityManager.removeAccessibilityStateChangeListener(
                mAccessibilityInteractionConnectionManager);
        mAccessibilityManager.removeHighTextContrastStateChangeListener(
                mHighContrastTextManager);
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
    }

    private void setTag() {
        final String[] split = mWindowAttributes.getTitle().toString().split("\\.");
        if (split.length > 0) {
            mTag =  "VRI[" + split[split.length - 1] + "]";
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getWindowFlags() {
        return mWindowAttributes.flags;
    }

    public int getDisplayId() {
        return mDisplay.getDisplayId();
    }

    public CharSequence getTitle() {
        return mWindowAttributes.getTitle();
    }

    /**
     * @return the width of the root view. Note that this will return {@code -1} until the first
     *         layout traversal, when the width is set.
     *
     * @hide
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * @return the height of the root view. Note that this will return {@code -1} until the first
     *         layout traversal, when the height is set.
     *
     * @hide
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Destroys hardware rendering resources for this ViewRootImpl
     *
     * May be called on any thread
     */
    @AnyThread
    void destroyHardwareResources() {
        final ThreadedRenderer renderer = mAttachInfo.mThreadedRenderer;
        if (renderer != null) {
            // This is called by WindowManagerGlobal which may or may not be on the right thread
            if (Looper.myLooper() != mAttachInfo.mHandler.getLooper()) {
                mAttachInfo.mHandler.postAtFrontOfQueue(this::destroyHardwareResources);
                return;
            }
            renderer.destroyHardwareResources(mView);
            renderer.destroy();
        }
    }

    /**
     * Does nothing; Here only because of @UnsupportedAppUsage
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link android.webkit.WebView} instead")
    public void detachFunctor(long functor) { }

    /**
     * Does nothing; Here only because of @UnsupportedAppUsage
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R,
            publicAlternatives = "Use {@link android.webkit.WebView} instead")
    public static void invokeFunctor(long functor, boolean waitForCompletion) { }

    /**
     * @param animator animator to register with the hardware renderer
     */
    public void registerAnimatingRenderNode(RenderNode animator) {
        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.registerAnimatingRenderNode(animator);
        } else {
            if (mAttachInfo.mPendingAnimatingRenderNodes == null) {
                mAttachInfo.mPendingAnimatingRenderNodes = new ArrayList<RenderNode>();
            }
            mAttachInfo.mPendingAnimatingRenderNodes.add(animator);
        }
    }

    /**
     * @param animator animator to register with the hardware renderer
     */
    public void registerVectorDrawableAnimator(NativeVectorDrawableAnimator animator) {
        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.registerVectorDrawableAnimator(animator);
        }
    }

    /**
     * Registers a callback to be executed when the next frame is being drawn on RenderThread. This
     * callback will be executed on a RenderThread worker thread, and only used for the next frame
     * and thus it will only fire once.
     *
     * @param callback The callback to register.
     */
    public void registerRtFrameCallback(@NonNull FrameDrawingCallback callback) {
        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.registerRtFrameCallback(new FrameDrawingCallback() {
                @Override
                public void onFrameDraw(long frame) {
                }

                @Override
                public HardwareRenderer.FrameCommitCallback onFrameDraw(int syncResult,
                        long frame) {
                    try {
                        return callback.onFrameDraw(syncResult, frame);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception while executing onFrameDraw", e);
                    }
                    return null;
                }
            });
        }
    }

    @UnsupportedAppUsage
    private void enableHardwareAcceleration(WindowManager.LayoutParams attrs) {
        mAttachInfo.mHardwareAccelerated = false;
        mAttachInfo.mHardwareAccelerationRequested = false;

        // Don't enable hardware acceleration when the application is in compatibility mode
        if (mTranslator != null) return;

        // Try to enable hardware acceleration if requested
        final boolean hardwareAccelerated =
                (attrs.flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0;

        if (hardwareAccelerated) {
            // Persistent processes (including the system) should not do
            // accelerated rendering on low-end devices.  In that case,
            // sRendererDisabled will be set.  In addition, the system process
            // itself should never do accelerated rendering.  In that case, both
            // sRendererDisabled and sSystemRendererDisabled are set.  When
            // sSystemRendererDisabled is set, PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED
            // can be used by code on the system process to escape that and enable
            // HW accelerated drawing.  (This is basically for the lock screen.)

            final boolean forceHwAccelerated = (attrs.privateFlags &
                    WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED) != 0;

            if (ThreadedRenderer.sRendererEnabled || forceHwAccelerated) {
                if (mAttachInfo.mThreadedRenderer != null) {
                    mAttachInfo.mThreadedRenderer.destroy();
                }

                final Rect insets = attrs.surfaceInsets;
                final boolean hasSurfaceInsets = insets.left != 0 || insets.right != 0
                        || insets.top != 0 || insets.bottom != 0;
                final boolean translucent = attrs.format != PixelFormat.OPAQUE || hasSurfaceInsets;
                mAttachInfo.mThreadedRenderer = ThreadedRenderer.create(mContext, translucent,
                        attrs.getTitle().toString());
                updateColorModeIfNeeded(attrs.getColorMode());
                updateForceDarkMode();
                if (mAttachInfo.mThreadedRenderer != null) {
                    mAttachInfo.mHardwareAccelerated =
                            mAttachInfo.mHardwareAccelerationRequested = true;
                    if (mHardwareRendererObserver != null) {
                        mAttachInfo.mThreadedRenderer.addObserver(mHardwareRendererObserver);
                    }
                    mAttachInfo.mThreadedRenderer.setSurfaceControl(mSurfaceControl);
                    mAttachInfo.mThreadedRenderer.setBlastBufferQueue(mBlastBufferQueue);
                }
            }
        }
    }

    private int getNightMode() {
        return getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    }

    private void updateForceDarkMode() {
        if (mAttachInfo.mThreadedRenderer == null) return;

        boolean useAutoDark = getNightMode() == Configuration.UI_MODE_NIGHT_YES;

        if (useAutoDark) {
            boolean forceDarkAllowedDefault =
                    SystemProperties.getBoolean(ThreadedRenderer.DEBUG_FORCE_DARK, false);
            TypedArray a = mContext.obtainStyledAttributes(R.styleable.Theme);
            useAutoDark = a.getBoolean(R.styleable.Theme_isLightTheme, true)
                    && a.getBoolean(R.styleable.Theme_forceDarkAllowed, forceDarkAllowedDefault);
            a.recycle();
        }

        if (mAttachInfo.mThreadedRenderer.setForceDark(useAutoDark)) {
            // TODO: Don't require regenerating all display lists to apply this setting
            invalidateWorld(mView);
        }
    }

    @UnsupportedAppUsage
    public View getView() {
        return mView;
    }

    final WindowLeaked getLocation() {
        return mLocation;
    }

    @VisibleForTesting
    public void setLayoutParams(WindowManager.LayoutParams attrs, boolean newView) {
        synchronized (this) {
            final int oldInsetLeft = mWindowAttributes.surfaceInsets.left;
            final int oldInsetTop = mWindowAttributes.surfaceInsets.top;
            final int oldInsetRight = mWindowAttributes.surfaceInsets.right;
            final int oldInsetBottom = mWindowAttributes.surfaceInsets.bottom;
            final int oldSoftInputMode = mWindowAttributes.softInputMode;
            final boolean oldHasManualSurfaceInsets = mWindowAttributes.hasManualSurfaceInsets;

            if (DEBUG_KEEP_SCREEN_ON && (mClientWindowLayoutFlags
                    & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
                    && (attrs.flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) == 0) {
                Slog.d(mTag, "setLayoutParams: FLAG_KEEP_SCREEN_ON from true to false!");
            }

            // Keep track of the actual window flags supplied by the client.
            mClientWindowLayoutFlags = attrs.flags;

            // Preserve compatible window flag if exists.
            final int compatibleWindowFlag = mWindowAttributes.privateFlags
                    & WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;

            // Preserve system UI visibility.
            final int systemUiVisibility = mWindowAttributes.systemUiVisibility;
            final int subtreeSystemUiVisibility = mWindowAttributes.subtreeSystemUiVisibility;

            // Preserve appearance and behavior.
            final int appearance = mWindowAttributes.insetsFlags.appearance;
            final int behavior = mWindowAttributes.insetsFlags.behavior;
            final int appearanceAndBehaviorPrivateFlags = mWindowAttributes.privateFlags
                    & (PRIVATE_FLAG_APPEARANCE_CONTROLLED | PRIVATE_FLAG_BEHAVIOR_CONTROLLED);

            final int changes = mWindowAttributes.copyFrom(attrs);
            if ((changes & WindowManager.LayoutParams.TRANSLUCENT_FLAGS_CHANGED) != 0) {
                // Recompute system ui visibility.
                mAttachInfo.mRecomputeGlobalAttributes = true;
            }
            if ((changes & WindowManager.LayoutParams.LAYOUT_CHANGED) != 0) {
                // Request to update light center.
                mAttachInfo.mNeedsUpdateLightCenter = true;
            }
            if (mWindowAttributes.packageName == null) {
                mWindowAttributes.packageName = mBasePackageName;
            }

            // Restore preserved flags.
            mWindowAttributes.systemUiVisibility = systemUiVisibility;
            mWindowAttributes.subtreeSystemUiVisibility = subtreeSystemUiVisibility;
            mWindowAttributes.insetsFlags.appearance = appearance;
            mWindowAttributes.insetsFlags.behavior = behavior;
            mWindowAttributes.privateFlags |= compatibleWindowFlag
                    | appearanceAndBehaviorPrivateFlags
                    | WindowManager.LayoutParams.PRIVATE_FLAG_USE_BLAST;

            if (mWindowAttributes.preservePreviousSurfaceInsets) {
                // Restore old surface insets.
                mWindowAttributes.surfaceInsets.set(
                        oldInsetLeft, oldInsetTop, oldInsetRight, oldInsetBottom);
                mWindowAttributes.hasManualSurfaceInsets = oldHasManualSurfaceInsets;
            } else if (mWindowAttributes.surfaceInsets.left != oldInsetLeft
                    || mWindowAttributes.surfaceInsets.top != oldInsetTop
                    || mWindowAttributes.surfaceInsets.right != oldInsetRight
                    || mWindowAttributes.surfaceInsets.bottom != oldInsetBottom) {
                mNeedsRendererSetup = true;
            }

            applyKeepScreenOnFlag(mWindowAttributes);

            if (newView) {
                mSoftInputMode = attrs.softInputMode;
                requestLayout();
            }

            // Don't lose the mode we last auto-computed.
            if ((attrs.softInputMode & SOFT_INPUT_MASK_ADJUST)
                    == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                mWindowAttributes.softInputMode = (mWindowAttributes.softInputMode
                        & ~SOFT_INPUT_MASK_ADJUST) | (oldSoftInputMode & SOFT_INPUT_MASK_ADJUST);
            }

            if (mWindowAttributes.softInputMode != oldSoftInputMode) {
                requestFitSystemWindows();
            }

            mWindowAttributesChanged = true;
            scheduleTraversals();
        }
    }

    void handleAppVisibility(boolean visible) {
        if (mAppVisible != visible) {
            final boolean previousVisible = getHostVisibility() == View.VISIBLE;
            mAppVisible = visible;
            final boolean currentVisible = getHostVisibility() == View.VISIBLE;
            // Root view only cares about whether it is visible or not.
            if (previousVisible != currentVisible) {
                mAppVisibilityChanged = true;
                scheduleTraversals();
            }
            if (!mAppVisible) {
                WindowManagerGlobal.trimForeground();
            }
        }
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }

    /** Handles messages {@link #MSG_RESIZED} and {@link #MSG_RESIZED_REPORT}. */
    private void handleResized(int msg, SomeArgs args) {
        if (!mAdded) {
            return;
        }

        final ClientWindowFrames frames = (ClientWindowFrames) args.arg1;
        final MergedConfiguration mergedConfiguration = (MergedConfiguration) args.arg2;
        final boolean forceNextWindowRelayout = args.argi1 != 0;
        final int displayId = args.argi3;
        final int resizeMode = args.argi5;

        final Rect frame = frames.frame;
        final Rect displayFrame = frames.displayFrame;
        if (mTranslator != null) {
            mTranslator.translateRectInScreenToAppWindow(frame);
            mTranslator.translateRectInScreenToAppWindow(displayFrame);
        }
        final boolean frameChanged = !mWinFrame.equals(frame);
        final boolean configChanged = !mLastReportedMergedConfiguration.equals(mergedConfiguration);
        final boolean displayChanged = mDisplay.getDisplayId() != displayId;
        final boolean resizeModeChanged = mResizeMode != resizeMode;
        if (msg == MSG_RESIZED && !frameChanged && !configChanged && !displayChanged
                && !resizeModeChanged && !forceNextWindowRelayout) {
            return;
        }

        mPendingDragResizing = resizeMode != RESIZE_MODE_INVALID;
        mResizeMode = resizeMode;

        if (configChanged) {
            // If configuration changed - notify about that and, maybe, about move to display.
            performConfigurationChange(mergedConfiguration, false /* force */,
                    displayChanged ? displayId : INVALID_DISPLAY /* same display */);
        } else if (displayChanged) {
            // Moved to display without config change - report last applied one.
            onMovedToDisplay(displayId, mLastConfigurationFromResources);
        }

        setFrame(frame);
        mTmpFrames.displayFrame.set(displayFrame);

        if (mDragResizing && mUseMTRenderer) {
            boolean fullscreen = frame.equals(mPendingBackDropFrame);
            for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                mWindowCallbacks.get(i).onWindowSizeIsChanging(mPendingBackDropFrame, fullscreen,
                        mAttachInfo.mVisibleInsets, mAttachInfo.mStableInsets);
            }
        }

        mForceNextWindowRelayout = forceNextWindowRelayout;
        mPendingAlwaysConsumeSystemBars = args.argi2 != 0;
        mSyncSeqId = args.argi4;

        if (msg == MSG_RESIZED_REPORT) {
            reportNextDraw();
        }

        if (mView != null && (frameChanged || configChanged)) {
            forceLayout(mView);
        }
        requestLayout();
    }

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (mView != null && mDisplay.getDisplayId() == displayId) {
                final int oldDisplayState = mAttachInfo.mDisplayState;
                final int newDisplayState = mDisplay.getState();
                if (oldDisplayState != newDisplayState) {
                    mAttachInfo.mDisplayState = newDisplayState;
                    pokeDrawLockIfNeeded();
                    if (oldDisplayState != Display.STATE_UNKNOWN) {
                        final int oldScreenState = toViewScreenState(oldDisplayState);
                        final int newScreenState = toViewScreenState(newDisplayState);
                        if (oldScreenState != newScreenState) {
                            mView.dispatchScreenStateChanged(newScreenState);
                        }
                        if (oldDisplayState == Display.STATE_OFF) {
                            // Draw was suppressed so we need to for it to happen here.
                            mFullRedrawNeeded = true;
                            scheduleTraversals();
                        }
                    }
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        private int toViewScreenState(int displayState) {
            return displayState == Display.STATE_OFF ?
                    View.SCREEN_STATE_OFF : View.SCREEN_STATE_ON;
        }
    };

    /**
     * Notify about move to a different display.
     * @param displayId The id of the display where this view root is moved to.
     * @param config Configuration of the resources on new display after move.
     *
     * @hide
     */
    public void onMovedToDisplay(int displayId, Configuration config) {
        if (mDisplay.getDisplayId() == displayId) {
            return;
        }

        // Get new instance of display based on current display adjustments. It may be updated later
        // if moving between the displays also involved a configuration change.
        updateInternalDisplay(displayId, mView.getResources());
        mImeFocusController.onMovedToDisplay();
        mAttachInfo.mDisplayState = mDisplay.getState();
        mDisplayInstallOrientation = mDisplay.getInstallOrientation();
        // Internal state updated, now notify the view hierarchy.
        mView.dispatchMovedToDisplay(mDisplay, config);
    }

    /**
     * Updates {@link #mDisplay} to the display object corresponding to {@param displayId}.
     * Uses DEFAULT_DISPLAY if there isn't a display object in the system corresponding
     * to {@param displayId}.
     */
    private void updateInternalDisplay(int displayId, Resources resources) {
        final Display preferredDisplay =
                ResourcesManager.getInstance().getAdjustedDisplay(displayId, resources);
        if (preferredDisplay == null) {
            // Fallback to use default display.
            Slog.w(TAG, "Cannot get desired display with Id: " + displayId);
            mDisplay = ResourcesManager.getInstance()
                    .getAdjustedDisplay(DEFAULT_DISPLAY, resources);
        } else {
            mDisplay = preferredDisplay;
        }
        mContext.updateDisplay(mDisplay.getDisplayId());
    }

    void pokeDrawLockIfNeeded() {
        if (!Display.isDozeState(mAttachInfo.mDisplayState)) {
            // Only need to acquire wake lock for DOZE state.
            return;
        }
        if (mWindowAttributes.type != WindowManager.LayoutParams.TYPE_BASE_APPLICATION) {
            // Non-activity windows should be responsible to hold wake lock by themself, because
            // usually they are system windows.
            return;
        }
        if (mAdded && mTraversalScheduled && mAttachInfo.mHasWindowFocus) {
            try {
                mWindowSession.pokeDrawLock(mWindow);
            } catch (RemoteException ex) {
                // System server died, oh well.
            }
        }
    }

    @Override
    public void requestFitSystemWindows() {
        checkThread();
        mApplyInsetsRequested = true;
        scheduleTraversals();
    }

    void notifyInsetsChanged() {
        mApplyInsetsRequested = true;
        requestLayout();

        // See comment for View.sForceLayoutWhenInsetsChanged
        if (View.sForceLayoutWhenInsetsChanged && mView != null
                && (mWindowAttributes.softInputMode & SOFT_INPUT_MASK_ADJUST)
                        == SOFT_INPUT_ADJUST_RESIZE) {
            forceLayout(mView);
        }

        // If this changes during traversal, no need to schedule another one as it will dispatch it
        // during the current traversal.
        if (!mIsInTraversal) {
            scheduleTraversals();
        }
    }

    @Override
    public void requestLayout() {
        if (!mHandlingLayoutInLayoutRequest) {
            checkThread();
            mLayoutRequested = true;
            scheduleTraversals();
        }
    }

    @Override
    public boolean isLayoutRequested() {
        return mLayoutRequested;
    }

    @Override
    public void onDescendantInvalidated(@NonNull View child, @NonNull View descendant) {
        // TODO: Re-enable after camera is fixed or consider targetSdk checking this
        // checkThread();
        if ((descendant.mPrivateFlags & PFLAG_DRAW_ANIMATION) != 0) {
            mIsAnimating = true;
        }
        invalidate();
    }

    @UnsupportedAppUsage
    void invalidate() {
        mDirty.set(0, 0, mWidth, mHeight);
        if (!mWillDrawSoon) {
            scheduleTraversals();
        }
    }

    void invalidateWorld(View view) {
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                invalidateWorld(parent.getChildAt(i));
            }
        }
    }

    @Override
    public void invalidateChild(View child, Rect dirty) {
        invalidateChildInParent(null, dirty);
    }

    @Override
    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        checkThread();
        if (DEBUG_DRAW) Log.v(mTag, "Invalidate child: " + dirty);

        if (dirty == null) {
            invalidate();
            return null;
        } else if (dirty.isEmpty() && !mIsAnimating) {
            return null;
        }

        if (mCurScrollY != 0 || mTranslator != null) {
            mTempRect.set(dirty);
            dirty = mTempRect;
            if (mCurScrollY != 0) {
                dirty.offset(0, -mCurScrollY);
            }
            if (mTranslator != null) {
                mTranslator.translateRectInAppWindowToScreen(dirty);
            }
            if (mAttachInfo.mScalingRequired) {
                dirty.inset(-1, -1);
            }
        }

        invalidateRectOnScreen(dirty);

        return null;
    }

    private void invalidateRectOnScreen(Rect dirty) {
        final Rect localDirty = mDirty;

        // Add the new dirty rect to the current one
        localDirty.union(dirty.left, dirty.top, dirty.right, dirty.bottom);
        // Intersect with the bounds of the window to skip
        // updates that lie outside of the visible region
        final float appScale = mAttachInfo.mApplicationScale;
        final boolean intersected = localDirty.intersect(0, 0,
                (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        if (!intersected) {
            localDirty.setEmpty();
        }
        if (!mWillDrawSoon && (intersected || mIsAnimating)) {
            scheduleTraversals();
        }
    }

    public void setIsAmbientMode(boolean ambient) {
        mIsAmbientMode = ambient;
    }

    void setWindowStopped(boolean stopped) {
        checkThread();
        if (mStopped != stopped) {
            mStopped = stopped;
            final ThreadedRenderer renderer = mAttachInfo.mThreadedRenderer;
            if (renderer != null) {
                if (DEBUG_DRAW) Log.d(mTag, "WindowStopped on " + getTitle() + " set to " + mStopped);
                renderer.setStopped(mStopped);
            }
            if (!mStopped) {
                // Unnecessary to traverse if the window is not yet visible.
                if (getHostVisibility() == View.VISIBLE) {
                    // Make sure that relayoutWindow will be called to get valid surface because
                    // the previous surface may have been released.
                    mAppVisibilityChanged = true;
                    scheduleTraversals();
                }
            } else {
                if (renderer != null) {
                    renderer.destroyHardwareResources(mView);
                }

                if (mSurface.isValid()) {
                    if (mSurfaceHolder != null) {
                        notifyHolderSurfaceDestroyed();
                    }
                    notifySurfaceDestroyed();
                }
                destroySurface();
            }
        }
    }


    /** Register callbacks to be notified when the ViewRootImpl surface changes. */
    public interface SurfaceChangedCallback {
        void surfaceCreated(Transaction t);
        void surfaceReplaced(Transaction t);
        void surfaceDestroyed();
    }

    private final ArrayList<SurfaceChangedCallback> mSurfaceChangedCallbacks = new ArrayList<>();
    public void addSurfaceChangedCallback(SurfaceChangedCallback c) {
        mSurfaceChangedCallbacks.add(c);
    }

    public void removeSurfaceChangedCallback(SurfaceChangedCallback c) {
        mSurfaceChangedCallbacks.remove(c);
    }

    private void notifySurfaceCreated() {
        for (int i = 0; i < mSurfaceChangedCallbacks.size(); i++) {
            mSurfaceChangedCallbacks.get(i).surfaceCreated(mSurfaceChangedTransaction);
        }
    }

    /**
     * Notify listeners when the ViewRootImpl surface has been replaced. This callback will not be
     * called if a new surface is created, only if the valid surface has been replaced with another
     * valid surface.
     */
    private void notifySurfaceReplaced() {
        for (int i = 0; i < mSurfaceChangedCallbacks.size(); i++) {
            mSurfaceChangedCallbacks.get(i).surfaceReplaced(mSurfaceChangedTransaction);
        }
    }

    private void notifySurfaceDestroyed() {
        for (int i = 0; i < mSurfaceChangedCallbacks.size(); i++) {
            mSurfaceChangedCallbacks.get(i).surfaceDestroyed();
        }
    }

    /**
     * @return child layer with the same bounds as its parent {@code mSurface} and cropped to the
     * surface insets. If the layer does not exist, it is created.
     *
     * <p>Parenting to this layer will ensure that its children are cropped by the view's surface
     * insets.
     */
    public SurfaceControl getBoundsLayer() {
        if (mBoundsLayer == null) {
            mBoundsLayer = new SurfaceControl.Builder(mSurfaceSession)
                    .setContainerLayer()
                    .setName("Bounds for - " + getTitle().toString())
                    .setParent(getSurfaceControl())
                    .setCallsite("ViewRootImpl.getBoundsLayer")
                    .build();
            setBoundsLayerCrop(mTransaction);
            mTransaction.show(mBoundsLayer).apply();
        }
       return mBoundsLayer;
    }

    void updateBlastSurfaceIfNeeded() {
        if (!mSurfaceControl.isValid()) {
            return;
        }

        if (mBlastBufferQueue != null && mBlastBufferQueue.isSameSurfaceControl(mSurfaceControl)) {
            mBlastBufferQueue.update(mSurfaceControl,
                mSurfaceSize.x, mSurfaceSize.y,
                mWindowAttributes.format);
            return;
        }

        // If the SurfaceControl has been updated, destroy and recreate the BBQ to reset the BQ and
        // BBQ states.
        if (mBlastBufferQueue != null) {
            mBlastBufferQueue.destroy();
        }
        mBlastBufferQueue = new BLASTBufferQueue(mTag, mSurfaceControl,
                mSurfaceSize.x, mSurfaceSize.y, mWindowAttributes.format);
        Surface blastSurface = mBlastBufferQueue.createSurface();
        // Only call transferFrom if the surface has changed to prevent inc the generation ID and
        // causing EGL resources to be recreated.
        mSurface.transferFrom(blastSurface);
    }

    private void setBoundsLayerCrop(Transaction t) {
        // Adjust of insets and update the bounds layer so child surfaces do not draw into
        // the surface inset region.
        mTempRect.set(0, 0, mSurfaceSize.x, mSurfaceSize.y);
        mTempRect.inset(mWindowAttributes.surfaceInsets.left,
                mWindowAttributes.surfaceInsets.top,
                mWindowAttributes.surfaceInsets.right, mWindowAttributes.surfaceInsets.bottom);
        t.setWindowCrop(mBoundsLayer, mTempRect);
    }

    /**
     * Called after window layout to update the bounds surface. If the surface insets have changed
     * or the surface has resized, update the bounds surface.
     */
    private boolean updateBoundsLayer(SurfaceControl.Transaction t) {
        if (mBoundsLayer != null) {
            setBoundsLayerCrop(t);
            return true;
        }
        return false;
    }

    private void prepareSurfaces() {
        final SurfaceControl.Transaction t = mTransaction;
        final SurfaceControl sc = getSurfaceControl();
        if (!sc.isValid()) return;

        if (updateBoundsLayer(t)) {
              mergeWithNextTransaction(t, mSurface.getNextFrameNumber());
        }
    }

    private void destroySurface() {
        if (mBoundsLayer != null) {
            mBoundsLayer.release();
            mBoundsLayer = null;
        }
        mSurface.release();
        mSurfaceControl.release();

        if (mBlastBufferQueue != null) {
            mBlastBufferQueue.destroy();
            mBlastBufferQueue = null;
        }

        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.setSurfaceControl(null);
            mAttachInfo.mThreadedRenderer.setBlastBufferQueue(null);
        }
    }

    /**
     * Block the input events during an Activity Transition. The KEYCODE_BACK event is allowed
     * through to allow quick reversal of the Activity Transition.
     *
     * @param paused true to pause, false to resume.
     */
    public void setPausedForTransition(boolean paused) {
        mPausedForTransition = paused;
    }

    @Override
    public ViewParent getParent() {
        return null;
    }

    @Override
    public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        if (child != mView) {
            throw new RuntimeException("child is not mine, honest!");
        }
        // Note: don't apply scroll offset, because we want to know its
        // visibility in the virtual canvas being given to the view hierarchy.
        return r.intersect(0, 0, mWidth, mHeight);
    }

    @Override
    public void bringChildToFront(View child) {
    }

    int getHostVisibility() {
        return mView != null && (mAppVisible || mForceDecorViewVisibility)
                ? mView.getVisibility() : View.GONE;
    }

    /**
     * Add LayoutTransition to the list of transitions to be started in the next traversal.
     * This list will be cleared after the transitions on the list are start()'ed. These
     * transitionsa re added by LayoutTransition itself when it sets up animations. The setup
     * happens during the layout phase of traversal, which we want to complete before any of the
     * animations are started (because those animations may side-effect properties that layout
     * depends upon, like the bounding rectangles of the affected views). So we add the transition
     * to the list and it is started just prior to starting the drawing phase of traversal.
     *
     * @param transition The LayoutTransition to be started on the next traversal.
     *
     * @hide
     */
    public void requestTransitionStart(LayoutTransition transition) {
        if (mPendingTransitions == null || !mPendingTransitions.contains(transition)) {
            if (mPendingTransitions == null) {
                 mPendingTransitions = new ArrayList<LayoutTransition>();
            }
            mPendingTransitions.add(transition);
        }
    }

    /**
     * Notifies the HardwareRenderer that a new frame will be coming soon.
     * Currently only {@link ThreadedRenderer} cares about this, and uses
     * this knowledge to adjust the scheduling of off-thread animations
     */
    void notifyRendererOfFramePending() {
        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.notifyFramePending();
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
            mChoreographer.postCallback(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
            notifyRendererOfFramePending();
            pokeDrawLockIfNeeded();
        }
    }

    void unscheduleTraversals() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
            mChoreographer.removeCallbacks(
                    Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        }
    }

    void doTraversal() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);

            if (mProfile) {
                Debug.startMethodTracing("ViewAncestor");
            }

            performTraversals();

            if (mProfile) {
                Debug.stopMethodTracing();
                mProfile = false;
            }
        }
    }

    private void applyKeepScreenOnFlag(WindowManager.LayoutParams params) {
        // Update window's global keep screen on flag: if a view has requested
        // that the screen be kept on, then it is always set; otherwise, it is
        // set to whatever the client last requested for the global state.
        if (mAttachInfo.mKeepScreenOn) {
            params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        } else {
            params.flags = (params.flags&~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    | (mClientWindowLayoutFlags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private boolean collectViewAttributes() {
        if (mAttachInfo.mRecomputeGlobalAttributes) {
            //Log.i(mTag, "Computing view hierarchy attributes!");
            mAttachInfo.mRecomputeGlobalAttributes = false;
            boolean oldScreenOn = mAttachInfo.mKeepScreenOn;
            mAttachInfo.mKeepScreenOn = false;
            mAttachInfo.mSystemUiVisibility = 0;
            mAttachInfo.mHasSystemUiListeners = false;
            mView.dispatchCollectViewAttributes(mAttachInfo, 0);
            mAttachInfo.mSystemUiVisibility &= ~mAttachInfo.mDisabledSystemUiVisibility;
            WindowManager.LayoutParams params = mWindowAttributes;
            mAttachInfo.mSystemUiVisibility |= getImpliedSystemUiVisibility(params);
            mCompatibleVisibilityInfo.globalVisibility =
                    (mCompatibleVisibilityInfo.globalVisibility & ~View.SYSTEM_UI_FLAG_LOW_PROFILE)
                            | (mAttachInfo.mSystemUiVisibility & View.SYSTEM_UI_FLAG_LOW_PROFILE);
            dispatchDispatchSystemUiVisibilityChanged(mCompatibleVisibilityInfo);
            if (mAttachInfo.mKeepScreenOn != oldScreenOn
                    || mAttachInfo.mSystemUiVisibility != params.subtreeSystemUiVisibility
                    || mAttachInfo.mHasSystemUiListeners != params.hasSystemUiListeners) {
                applyKeepScreenOnFlag(params);
                params.subtreeSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                params.hasSystemUiListeners = mAttachInfo.mHasSystemUiListeners;
                mView.dispatchWindowSystemUiVisiblityChanged(mAttachInfo.mSystemUiVisibility);
                return true;
            }
        }
        return false;
    }

    private int getImpliedSystemUiVisibility(WindowManager.LayoutParams params) {
        int vis = 0;
        // Translucent decor window flags imply stable system ui visibility.
        if ((params.flags & FLAG_TRANSLUCENT_STATUS) != 0) {
            vis |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        }
        if ((params.flags & FLAG_TRANSLUCENT_NAVIGATION) != 0) {
            vis |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        }
        return vis;
    }

    /**
     * Update the compatible system UI visibility for dispatching it to the legacy app.
     *
     * @param type Indicates which type of the insets source we are handling.
     * @param visible True if the insets source is visible.
     * @param hasControl True if we can control the insets source.
     */
    void updateCompatSysUiVisibility(@InternalInsetsType int type, boolean visible,
            boolean hasControl) {
        @InsetsType final int publicType = InsetsState.toPublicType(type);
        if (publicType != Type.statusBars() && publicType != Type.navigationBars()) {
            return;
        }
        final SystemUiVisibilityInfo info = mCompatibleVisibilityInfo;
        final int systemUiFlag = publicType == Type.statusBars()
                ? View.SYSTEM_UI_FLAG_FULLSCREEN
                : View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        if (visible) {
            info.globalVisibility &= ~systemUiFlag;
            if (hasControl && (mAttachInfo.mSystemUiVisibility & systemUiFlag) != 0) {
                // The local system UI visibility can only be cleared while we have the control.
                info.localChanges |= systemUiFlag;
            }
        } else {
            info.globalVisibility |= systemUiFlag;
            info.localChanges &= ~systemUiFlag;
        }
        dispatchDispatchSystemUiVisibilityChanged(info);
    }

    /**
     * If the system is forcing showing any system bar, the legacy low profile flag should be
     * cleared for compatibility.
     *
     * @param showTypes {@link InsetsType types} shown by the system.
     * @param fromIme {@code true} if the invocation is from IME.
     */
    private void clearLowProfileModeIfNeeded(@InsetsType int showTypes, boolean fromIme) {
        final SystemUiVisibilityInfo info = mCompatibleVisibilityInfo;
        if ((showTypes & Type.systemBars()) != 0 && !fromIme
                && (info.globalVisibility & SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
            info.globalVisibility &= ~SYSTEM_UI_FLAG_LOW_PROFILE;
            info.localChanges |= SYSTEM_UI_FLAG_LOW_PROFILE;
            dispatchDispatchSystemUiVisibilityChanged(info);
        }
    }

    private void dispatchDispatchSystemUiVisibilityChanged(SystemUiVisibilityInfo args) {
        if (mDispatchedSystemUiVisibility != args.globalVisibility) {
            mHandler.removeMessages(MSG_DISPATCH_SYSTEM_UI_VISIBILITY);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_DISPATCH_SYSTEM_UI_VISIBILITY, args));
        }
    }

    private void handleDispatchSystemUiVisibilityChanged(SystemUiVisibilityInfo args) {
        if (mView == null) return;
        if (args.localChanges != 0) {
            mView.updateLocalSystemUiVisibility(args.localValue, args.localChanges);
            args.localChanges = 0;
        }

        final int visibility = args.globalVisibility & View.SYSTEM_UI_CLEARABLE_FLAGS;
        if (mDispatchedSystemUiVisibility != visibility) {
            mDispatchedSystemUiVisibility = visibility;
            mView.dispatchSystemUiVisibilityChanged(visibility);
        }
    }

    @VisibleForTesting
    public static void adjustLayoutParamsForCompatibility(WindowManager.LayoutParams inOutParams) {
        final int sysUiVis = inOutParams.systemUiVisibility | inOutParams.subtreeSystemUiVisibility;
        final int flags = inOutParams.flags;
        final int type = inOutParams.type;
        final int adjust = inOutParams.softInputMode & SOFT_INPUT_MASK_ADJUST;

        if ((inOutParams.privateFlags & PRIVATE_FLAG_APPEARANCE_CONTROLLED) == 0) {
            inOutParams.insetsFlags.appearance = 0;
            if ((sysUiVis & SYSTEM_UI_FLAG_LOW_PROFILE) != 0) {
                inOutParams.insetsFlags.appearance |= APPEARANCE_LOW_PROFILE_BARS;
            }
            if ((sysUiVis & SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0) {
                inOutParams.insetsFlags.appearance |= APPEARANCE_LIGHT_STATUS_BARS;
            }
            if ((sysUiVis & SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) != 0) {
                inOutParams.insetsFlags.appearance |= APPEARANCE_LIGHT_NAVIGATION_BARS;
            }
        }

        if ((inOutParams.privateFlags & PRIVATE_FLAG_BEHAVIOR_CONTROLLED) == 0) {
            if ((sysUiVis & SYSTEM_UI_FLAG_IMMERSIVE_STICKY) != 0
                    || (flags & FLAG_FULLSCREEN) != 0) {
                inOutParams.insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
            } else {
                inOutParams.insetsFlags.behavior = BEHAVIOR_DEFAULT;
            }
        }

        inOutParams.privateFlags &= ~PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;

        if ((inOutParams.privateFlags & PRIVATE_FLAG_FIT_INSETS_CONTROLLED) != 0) {
            return;
        }

        int types = inOutParams.getFitInsetsTypes();
        boolean ignoreVis = inOutParams.isFitInsetsIgnoringVisibility();

        if (((sysUiVis & SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN) != 0
                || (flags & FLAG_LAYOUT_IN_SCREEN) != 0)
                || (flags & FLAG_TRANSLUCENT_STATUS) != 0) {
            types &= ~Type.statusBars();
        }
        if ((sysUiVis & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) != 0
                || (flags & FLAG_TRANSLUCENT_NAVIGATION) != 0) {
            types &= ~Type.systemBars();
        }
        if (type == TYPE_TOAST || type == TYPE_SYSTEM_ALERT) {
            ignoreVis = true;
        } else if ((types & Type.systemBars()) == Type.systemBars()) {
            if (adjust == SOFT_INPUT_ADJUST_RESIZE) {
                types |= Type.ime();
            } else {
                inOutParams.privateFlags |= PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
            }
        }
        inOutParams.setFitInsetsTypes(types);
        inOutParams.setFitInsetsIgnoringVisibility(ignoreVis);

        // The fitting of insets are not really controlled by the clients, so we remove the flag.
        inOutParams.privateFlags &= ~PRIVATE_FLAG_FIT_INSETS_CONTROLLED;
    }

    private void controlInsetsForCompatibility(WindowManager.LayoutParams params) {
        final int sysUiVis = params.systemUiVisibility | params.subtreeSystemUiVisibility;
        final int flags = params.flags;
        final boolean matchParent = params.width == MATCH_PARENT && params.height == MATCH_PARENT;
        final boolean nonAttachedAppWindow = params.type >= FIRST_APPLICATION_WINDOW
                && params.type <= LAST_APPLICATION_WINDOW;
        final boolean statusWasHiddenByFlags = (mTypesHiddenByFlags & Type.statusBars()) != 0;
        final boolean statusIsHiddenByFlags = (sysUiVis & SYSTEM_UI_FLAG_FULLSCREEN) != 0
                || ((flags & FLAG_FULLSCREEN) != 0 && matchParent && nonAttachedAppWindow);
        final boolean navWasHiddenByFlags = (mTypesHiddenByFlags & Type.navigationBars()) != 0;
        final boolean navIsHiddenByFlags = (sysUiVis & SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0;

        @InsetsType int typesToHide = 0;
        @InsetsType int typesToShow = 0;
        if (statusIsHiddenByFlags && !statusWasHiddenByFlags) {
            typesToHide |= Type.statusBars();
        } else if (!statusIsHiddenByFlags && statusWasHiddenByFlags) {
            typesToShow |= Type.statusBars();
        }
        if (navIsHiddenByFlags && !navWasHiddenByFlags) {
            typesToHide |= Type.navigationBars();
        } else if (!navIsHiddenByFlags && navWasHiddenByFlags) {
            typesToShow |= Type.navigationBars();
        }
        if (typesToHide != 0) {
            getInsetsController().hide(typesToHide);
        }
        if (typesToShow != 0) {
            getInsetsController().show(typesToShow);
        }
        mTypesHiddenByFlags |= typesToHide;
        mTypesHiddenByFlags &= ~typesToShow;
    }

    private boolean measureHierarchy(final View host, final WindowManager.LayoutParams lp,
            final Resources res, final int desiredWindowWidth, final int desiredWindowHeight) {
        int childWidthMeasureSpec;
        int childHeightMeasureSpec;
        boolean windowSizeMayChange = false;

        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) Log.v(mTag,
                "Measuring " + host + " in display " + desiredWindowWidth
                + "x" + desiredWindowHeight + "...");

        boolean goodMeasure = false;
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
            // On large screens, we don't want to allow dialogs to just
            // stretch to fill the entire width of the screen to display
            // one line of text.  First try doing the layout at a smaller
            // size to see if it will fit.
            final DisplayMetrics packageMetrics = res.getDisplayMetrics();
            res.getValue(com.android.internal.R.dimen.config_prefDialogWidth, mTmpValue, true);
            int baseSize = 0;
            if (mTmpValue.type == TypedValue.TYPE_DIMENSION) {
                baseSize = (int)mTmpValue.getDimension(packageMetrics);
            }
            if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": baseSize=" + baseSize
                    + ", desiredWindowWidth=" + desiredWindowWidth);
            if (baseSize != 0 && desiredWindowWidth > baseSize) {
                childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width, lp.privateFlags);
                childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height,
                        lp.privateFlags);
                performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": measured ("
                        + host.getMeasuredWidth() + "," + host.getMeasuredHeight()
                        + ") from width spec: " + MeasureSpec.toString(childWidthMeasureSpec)
                        + " and height spec: " + MeasureSpec.toString(childHeightMeasureSpec));
                if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                    goodMeasure = true;
                } else {
                    // Didn't fit in that size... try expanding a bit.
                    baseSize = (baseSize+desiredWindowWidth)/2;
                    if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": next baseSize="
                            + baseSize);
                    childWidthMeasureSpec = getRootMeasureSpec(baseSize, lp.width, lp.privateFlags);
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    if (DEBUG_DIALOG) Log.v(mTag, "Window " + mView + ": measured ("
                            + host.getMeasuredWidth() + "," + host.getMeasuredHeight() + ")");
                    if ((host.getMeasuredWidthAndState()&View.MEASURED_STATE_TOO_SMALL) == 0) {
                        if (DEBUG_DIALOG) Log.v(mTag, "Good!");
                        goodMeasure = true;
                    }
                }
            }
        }

        if (!goodMeasure) {
            childWidthMeasureSpec = getRootMeasureSpec(desiredWindowWidth, lp.width,
                    lp.privateFlags);
            childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height,
                    lp.privateFlags);
            performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
            if (mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight()) {
                windowSizeMayChange = true;
            }
        }

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals -- after measure");
            host.debug();
        }

        return windowSizeMayChange;
    }

    /**
     * Modifies the input matrix such that it maps view-local coordinates to
     * on-screen coordinates.
     *
     * @param m input matrix to modify
     */
    void transformMatrixToGlobal(Matrix m) {
        m.preTranslate(mAttachInfo.mWindowLeft, mAttachInfo.mWindowTop);
    }

    /**
     * Modifies the input matrix such that it maps on-screen coordinates to
     * view-local coordinates.
     *
     * @param m input matrix to modify
     */
    void transformMatrixToLocal(Matrix m) {
        m.postTranslate(-mAttachInfo.mWindowLeft, -mAttachInfo.mWindowTop);
    }

    /* package */ WindowInsets getWindowInsets(boolean forceConstruct) {
        if (mLastWindowInsets == null || forceConstruct) {
            final Configuration config = getConfiguration();
            mLastWindowInsets = mInsetsController.calculateInsets(
                    config.isScreenRound(), mAttachInfo.mAlwaysConsumeSystemBars,
                    mWindowAttributes.type, config.windowConfiguration.getWindowingMode(),
                    mWindowAttributes.softInputMode, mWindowAttributes.flags,
                    (mWindowAttributes.systemUiVisibility
                            | mWindowAttributes.subtreeSystemUiVisibility));

            mAttachInfo.mContentInsets.set(mLastWindowInsets.getSystemWindowInsets().toRect());
            mAttachInfo.mStableInsets.set(mLastWindowInsets.getStableInsets().toRect());
            mAttachInfo.mVisibleInsets.set(mInsetsController.calculateVisibleInsets(
                    mWindowAttributes.softInputMode).toRect());
        }
        return mLastWindowInsets;
    }

    public void dispatchApplyInsets(View host) {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "dispatchApplyInsets");
        mApplyInsetsRequested = false;
        WindowInsets insets = getWindowInsets(true /* forceConstruct */);
        if (!shouldDispatchCutout()) {
            // Window is either not laid out in cutout or the status bar inset takes care of
            // clearing the cutout, so we don't need to dispatch the cutout to the hierarchy.
            insets = insets.consumeDisplayCutout();
        }
        host.dispatchApplyWindowInsets(insets);
        mAttachInfo.delayNotifyContentCaptureInsetsEvent(insets.getInsets(Type.all()));
        Trace.traceEnd(Trace.TRACE_TAG_VIEW);
    }

    private boolean updateCaptionInsets() {
        if (CAPTION_ON_SHELL) {
            return false;
        }
        if (!(mView instanceof DecorView)) return false;
        final int captionInsetsHeight = ((DecorView) mView).getCaptionInsetsHeight();
        final Rect captionFrame = new Rect();
        if (captionInsetsHeight != 0) {
            captionFrame.set(mWinFrame.left, mWinFrame.top, mWinFrame.right,
                            mWinFrame.top + captionInsetsHeight);
        }
        if (mAttachInfo.mCaptionInsets.equals(captionFrame)) return false;
        mAttachInfo.mCaptionInsets.set(captionFrame);
        return true;
    }

    private boolean shouldDispatchCutout() {
        return mWindowAttributes.layoutInDisplayCutoutMode
                        == LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                || mWindowAttributes.layoutInDisplayCutoutMode
                        == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
    }

    @VisibleForTesting
    public InsetsController getInsetsController() {
        return mInsetsController;
    }

    private static boolean shouldUseDisplaySize(final WindowManager.LayoutParams lp) {
        return lp.type == TYPE_STATUS_BAR_ADDITIONAL
                || lp.type == TYPE_INPUT_METHOD
                || lp.type == TYPE_VOLUME_OVERLAY;
    }

    private Rect getWindowBoundsInsetSystemBars() {
        final Rect bounds = new Rect(
                mContext.getResources().getConfiguration().windowConfiguration.getBounds());
        bounds.inset(mInsetsController.getState().calculateInsets(
                bounds, Type.systemBars(), false /* ignoreVisibility */));
        return bounds;
    }

    int dipToPx(int dip) {
        final DisplayMetrics displayMetrics = mContext.getResources().getDisplayMetrics();
        return (int) (displayMetrics.density * dip + 0.5f);
    }

    private void performTraversals() {
        // cache mView since it is used so much below...
        final View host = mView;
        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals");
            host.debug();
        }

        if (host == null || !mAdded) {
            return;
        }

        mIsInTraversal = true;
        mWillDrawSoon = true;
        boolean windowSizeMayChange = false;
        WindowManager.LayoutParams lp = mWindowAttributes;

        int desiredWindowWidth;
        int desiredWindowHeight;

        final int viewVisibility = getHostVisibility();
        final boolean viewVisibilityChanged = !mFirst
                && (mViewVisibility != viewVisibility || mNewSurfaceNeeded
                // Also check for possible double visibility update, which will make current
                // viewVisibility value equal to mViewVisibility and we may miss it.
                || mAppVisibilityChanged);
        mAppVisibilityChanged = false;
        final boolean viewUserVisibilityChanged = !mFirst &&
                ((mViewVisibility == View.VISIBLE) != (viewVisibility == View.VISIBLE));

        WindowManager.LayoutParams params = null;
        CompatibilityInfo compatibilityInfo =
                mDisplay.getDisplayAdjustments().getCompatibilityInfo();
        if (compatibilityInfo.supportsScreen() == mLastInCompatMode) {
            params = lp;
            mFullRedrawNeeded = true;
            mLayoutRequested = true;
            if (mLastInCompatMode) {
                params.privateFlags &= ~WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = false;
            } else {
                params.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                mLastInCompatMode = true;
            }
        }

        Rect frame = mWinFrame;
        if (mFirst) {
            mFullRedrawNeeded = true;
            mLayoutRequested = true;

            final Configuration config = getConfiguration();
            if (shouldUseDisplaySize(lp)) {
                // NOTE -- system code, won't try to do compat mode.
                Point size = new Point();
                mDisplay.getRealSize(size);
                desiredWindowWidth = size.x;
                desiredWindowHeight = size.y;
            } else if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                    || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                // For wrap content, we have to remeasure later on anyways. Use size consistent with
                // below so we get best use of the measure cache.
                final Rect bounds = getWindowBoundsInsetSystemBars();
                desiredWindowWidth = bounds.width();
                desiredWindowHeight = bounds.height();
            } else {
                // After addToDisplay, the frame contains the frameHint from window manager, which
                // for most windows is going to be the same size as the result of relayoutWindow.
                // Using this here allows us to avoid remeasuring after relayoutWindow
                desiredWindowWidth = frame.width();
                desiredWindowHeight = frame.height();
            }

            // We used to use the following condition to choose 32 bits drawing caches:
            // PixelFormat.hasAlpha(lp.format) || lp.format == PixelFormat.RGBX_8888
            // However, windows are now always 32 bits by default, so choose 32 bits
            mAttachInfo.mUse32BitDrawingCache = true;
            mAttachInfo.mWindowVisibility = viewVisibility;
            mAttachInfo.mRecomputeGlobalAttributes = false;
            mLastConfigurationFromResources.setTo(config);
            mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
            // Set the layout direction if it has not been set before (inherit is the default)
            if (mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                host.setLayoutDirection(config.getLayoutDirection());
            }
            host.dispatchAttachedToWindow(mAttachInfo, 0);
            mAttachInfo.mTreeObserver.dispatchOnWindowAttachedChange(true);
            dispatchApplyInsets(host);
        } else {
            desiredWindowWidth = frame.width();
            desiredWindowHeight = frame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                if (DEBUG_ORIENTATION) Log.v(mTag, "View " + host + " resized to: " + frame);
                mFullRedrawNeeded = true;
                mLayoutRequested = true;
                windowSizeMayChange = true;
            }
        }

        if (viewVisibilityChanged) {
            mAttachInfo.mWindowVisibility = viewVisibility;
            host.dispatchWindowVisibilityChanged(viewVisibility);
            if (viewUserVisibilityChanged) {
                host.dispatchVisibilityAggregated(viewVisibility == View.VISIBLE);
            }
            if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
                endDragResizing();
                destroyHardwareResources();
            }
        }

        // Non-visible windows can't hold accessibility focus.
        if (mAttachInfo.mWindowVisibility != View.VISIBLE) {
            host.clearAccessibilityFocus();
        }

        // Execute enqueued actions on every traversal in case a detached view enqueued an action
        getRunQueue().executeActions(mAttachInfo.mHandler);

        if (mApplyInsetsRequested) {
            dispatchApplyInsets(host);
        }

        boolean layoutRequested = mLayoutRequested && (!mStopped || mReportNextDraw);
        if (layoutRequested) {

            final Resources res = mView.getContext().getResources();

            if (mFirst) {
                // make sure touch mode code executes by setting cached value
                // to opposite of the added touch mode.
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    windowSizeMayChange = true;

                    if (shouldUseDisplaySize(lp)) {
                        // NOTE -- system code, won't try to do compat mode.
                        Point size = new Point();
                        mDisplay.getRealSize(size);
                        desiredWindowWidth = size.x;
                        desiredWindowHeight = size.y;
                    } else {
                        final Rect bounds = getWindowBoundsInsetSystemBars();
                        desiredWindowWidth = bounds.width();
                        desiredWindowHeight = bounds.height();
                    }
                }
            }

            // Ask host how big it wants to be
            windowSizeMayChange |= measureHierarchy(host, lp, res,
                    desiredWindowWidth, desiredWindowHeight);
        }

        if (collectViewAttributes()) {
            params = lp;
        }
        if (mAttachInfo.mForceReportNewAttributes) {
            mAttachInfo.mForceReportNewAttributes = false;
            params = lp;
        }

        if (mFirst || mAttachInfo.mViewVisibilityChanged) {
            mAttachInfo.mViewVisibilityChanged = false;
            int resizeMode = mSoftInputMode & SOFT_INPUT_MASK_ADJUST;
            // If we are in auto resize mode, then we need to determine
            // what mode to use now.
            if (resizeMode == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED) {
                final int N = mAttachInfo.mScrollContainers.size();
                for (int i=0; i<N; i++) {
                    if (mAttachInfo.mScrollContainers.get(i).isShown()) {
                        resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
                    }
                }
                if (resizeMode == 0) {
                    resizeMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
                }
                if ((lp.softInputMode & SOFT_INPUT_MASK_ADJUST) != resizeMode) {
                    lp.softInputMode = (lp.softInputMode & ~SOFT_INPUT_MASK_ADJUST) | resizeMode;
                    params = lp;
                }
            }
        }

        if (layoutRequested) {
            // Clear this now, so that if anything requests a layout in the
            // rest of this function we will catch it and re-run a full
            // layout pass.
            mLayoutRequested = false;
        }

        boolean windowShouldResize = layoutRequested && windowSizeMayChange
            && ((mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight())
                || (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.width() < desiredWindowWidth && frame.width() != mWidth)
                || (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        frame.height() < desiredWindowHeight && frame.height() != mHeight));
        windowShouldResize |= mDragResizing && mResizeMode == RESIZE_MODE_FREEFORM;

        // If the activity was just relaunched, it might have unfrozen the task bounds (while
        // relaunching), so we need to force a call into window manager to pick up the latest
        // bounds.
        windowShouldResize |= mActivityRelaunched;

        // Determine whether to compute insets.
        // If there are no inset listeners remaining then we may still need to compute
        // insets in case the old insets were non-empty and must be reset.
        final boolean computesInternalInsets =
                mAttachInfo.mTreeObserver.hasComputeInternalInsetsListeners()
                || mAttachInfo.mHasNonEmptyGivenInternalInsets;

        boolean insetsPending = false;
        int relayoutResult = 0;
        boolean updatedConfiguration = false;

        final int surfaceGenerationId = mSurface.getGenerationId();

        final boolean isViewVisible = viewVisibility == View.VISIBLE;
        final boolean windowRelayoutWasForced = mForceNextWindowRelayout;
        boolean surfaceSizeChanged = false;
        boolean surfaceCreated = false;
        boolean surfaceDestroyed = false;
        // True if surface generation id changes or relayout result is RELAYOUT_RES_SURFACE_CHANGED.
        boolean surfaceReplaced = false;

        final boolean windowAttributesChanged = mWindowAttributesChanged;
        if (windowAttributesChanged) {
            mWindowAttributesChanged = false;
            params = lp;
        }

        if (params != null) {
            if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0
                    && !PixelFormat.formatHasAlpha(params.format)) {
                params.format = PixelFormat.TRANSLUCENT;
            }
            adjustLayoutParamsForCompatibility(params);
            controlInsetsForCompatibility(params);
            if (mDispatchedSystemBarAppearance != params.insetsFlags.appearance) {
                mDispatchedSystemBarAppearance = params.insetsFlags.appearance;
                mView.onSystemBarAppearanceChanged(mDispatchedSystemBarAppearance);
            }
        }

        if (mFirst || windowShouldResize || viewVisibilityChanged || params != null
                || mForceNextWindowRelayout) {
            mForceNextWindowRelayout = false;

            // If this window is giving internal insets to the window manager, then we want to first
            // make the provided insets unchanged during layout. This avoids it briefly causing
            // other windows to resize/move based on the raw frame of the window, waiting until we
            // can finish laying out this window and get back to the window manager with the
            // ultimately computed insets.
            insetsPending = computesInternalInsets;

            if (mSurfaceHolder != null) {
                mSurfaceHolder.mSurfaceLock.lock();
                mDrawingAllowed = true;
            }

            boolean hwInitialized = false;
            boolean dispatchApplyInsets = false;
            boolean hadSurface = mSurface.isValid();

            try {
                if (DEBUG_LAYOUT) {
                    Log.i(mTag, "host=w:" + host.getMeasuredWidth() + ", h:" +
                            host.getMeasuredHeight() + ", params=" + params);
                }

                if (mFirst || viewVisibilityChanged) {
                    mViewFrameInfo.flags |= FrameInfo.FLAG_WINDOW_VISIBILITY_CHANGED;
                }
                relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
                final boolean dragResizing = mPendingDragResizing;
                if (mSyncSeqId > mLastSyncSeqId) {
                    mLastSyncSeqId = mSyncSeqId;
                    if (DEBUG_BLAST) {
                        Log.d(mTag, "Relayout called with blastSync");
                    }
                    reportNextDraw();
                    mSyncBuffer = true;
                }

                final boolean surfaceControlChanged =
                        (relayoutResult & RELAYOUT_RES_SURFACE_CHANGED)
                                == RELAYOUT_RES_SURFACE_CHANGED;

                if (mSurfaceControl.isValid()) {
                    updateOpacity(mWindowAttributes, dragResizing,
                            surfaceControlChanged /*forceUpdate */);
                    // No need to updateDisplayDecoration if it's a new SurfaceControl and
                    // mDisplayDecorationCached is false, since that's the default for a new
                    // SurfaceControl.
                    if (surfaceControlChanged && mDisplayDecorationCached) {
                        updateDisplayDecoration();
                    }
                }

                if (DEBUG_LAYOUT) Log.v(mTag, "relayout: frame=" + frame.toShortString()
                        + " surface=" + mSurface);

                // If the pending {@link MergedConfiguration} handed back from
                // {@link #relayoutWindow} does not match the one last reported,
                // WindowManagerService has reported back a frame from a configuration not yet
                // handled by the client. In this case, we need to accept the configuration so we
                // do not lay out and draw with the wrong configuration.
                if (!mPendingMergedConfiguration.equals(mLastReportedMergedConfiguration)) {
                    if (DEBUG_CONFIGURATION) Log.v(mTag, "Visible with new config: "
                            + mPendingMergedConfiguration.getMergedConfiguration());
                    performConfigurationChange(new MergedConfiguration(mPendingMergedConfiguration),
                            !mFirst, INVALID_DISPLAY /* same display */);
                    updatedConfiguration = true;
                }

                surfaceSizeChanged = false;
                if (!mLastSurfaceSize.equals(mSurfaceSize)) {
                    surfaceSizeChanged = true;
                    mLastSurfaceSize.set(mSurfaceSize.x, mSurfaceSize.y);
                }
                final boolean alwaysConsumeSystemBarsChanged =
                        mPendingAlwaysConsumeSystemBars != mAttachInfo.mAlwaysConsumeSystemBars;
                updateColorModeIfNeeded(lp.getColorMode());
                surfaceCreated = !hadSurface && mSurface.isValid();
                surfaceDestroyed = hadSurface && !mSurface.isValid();

                // When using Blast, the surface generation id may not change when there's a new
                // SurfaceControl. In that case, we also check relayout flag
                // RELAYOUT_RES_SURFACE_CHANGED since it should indicate that WMS created a new
                // SurfaceControl.
                surfaceReplaced = (surfaceGenerationId != mSurface.getGenerationId()
                        || surfaceControlChanged) && mSurface.isValid();
                if (surfaceReplaced) {
                    mSurfaceSequenceId++;
                }

                if (alwaysConsumeSystemBarsChanged) {
                    mAttachInfo.mAlwaysConsumeSystemBars = mPendingAlwaysConsumeSystemBars;
                    dispatchApplyInsets = true;
                }
                if (updateCaptionInsets()) {
                    dispatchApplyInsets = true;
                }
                if (dispatchApplyInsets || mLastSystemUiVisibility !=
                        mAttachInfo.mSystemUiVisibility || mApplyInsetsRequested) {
                    mLastSystemUiVisibility = mAttachInfo.mSystemUiVisibility;
                    dispatchApplyInsets(host);
                    // We applied insets so force contentInsetsChanged to ensure the
                    // hierarchy is measured below.
                    dispatchApplyInsets = true;
                }

                if (surfaceCreated) {
                    // If we are creating a new surface, then we need to
                    // completely redraw it.
                    mFullRedrawNeeded = true;
                    mPreviousTransparentRegion.setEmpty();

                    // Only initialize up-front if transparent regions are not
                    // requested, otherwise defer to see if the entire window
                    // will be transparent
                    if (mAttachInfo.mThreadedRenderer != null) {
                        try {
                            hwInitialized = mAttachInfo.mThreadedRenderer.initialize(mSurface);
                            if (hwInitialized && (host.mPrivateFlags
                                            & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0) {
                                // Don't pre-allocate if transparent regions
                                // are requested as they may not be needed
                                mAttachInfo.mThreadedRenderer.allocateBuffers();
                            }
                        } catch (OutOfResourcesException e) {
                            handleOutOfResourcesException(e);
                            return;
                        }
                    }
                } else if (surfaceDestroyed) {
                    // If the surface has been removed, then reset the scroll
                    // positions.
                    if (mLastScrolledFocus != null) {
                        mLastScrolledFocus.clear();
                    }
                    mScrollY = mCurScrollY = 0;
                    if (mView instanceof RootViewSurfaceTaker) {
                        ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
                    }
                    if (mScroller != null) {
                        mScroller.abortAnimation();
                    }
                    // Our surface is gone
                    if (isHardwareEnabled()) {
                        mAttachInfo.mThreadedRenderer.destroy();
                    }
                } else if ((surfaceReplaced
                        || surfaceSizeChanged || windowRelayoutWasForced)
                        && mSurfaceHolder == null
                        && mAttachInfo.mThreadedRenderer != null
                        && mSurface.isValid()) {
                    mFullRedrawNeeded = true;
                    try {
                        // Need to do updateSurface (which leads to CanvasContext::setSurface and
                        // re-create the EGLSurface) if either the Surface changed (as indicated by
                        // generation id), or WindowManager changed the surface size. The latter is
                        // because on some chips, changing the consumer side's BufferQueue size may
                        // not take effect immediately unless we create a new EGLSurface.
                        // Note that frame size change doesn't always imply surface size change (eg.
                        // drag resizing uses fullscreen surface), need to check surfaceSizeChanged
                        // flag from WindowManager.
                        mAttachInfo.mThreadedRenderer.updateSurface(mSurface);
                    } catch (OutOfResourcesException e) {
                        handleOutOfResourcesException(e);
                        return;
                    }
                }

                if (mDragResizing != dragResizing) {
                    if (dragResizing) {
                        final boolean backdropSizeMatchesFrame =
                                mWinFrame.width() == mPendingBackDropFrame.width()
                                        && mWinFrame.height() == mPendingBackDropFrame.height();
                        // TODO: Need cutout?
                        startDragResizing(mPendingBackDropFrame, !backdropSizeMatchesFrame,
                                mAttachInfo.mContentInsets, mAttachInfo.mStableInsets, mResizeMode);
                    } else {
                        // We shouldn't come here, but if we come we should end the resize.
                        endDragResizing();
                    }
                }
                if (!mUseMTRenderer) {
                    if (dragResizing) {
                        mCanvasOffsetX = mWinFrame.left;
                        mCanvasOffsetY = mWinFrame.top;
                    } else {
                        mCanvasOffsetX = mCanvasOffsetY = 0;
                    }
                }
            } catch (RemoteException e) {
            }

            if (DEBUG_ORIENTATION) Log.v(
                    TAG, "Relayout returned: frame=" + frame + ", surface=" + mSurface);

            mAttachInfo.mWindowLeft = frame.left;
            mAttachInfo.mWindowTop = frame.top;

            // !!FIXME!! This next section handles the case where we did not get the
            // window size we asked for. We should avoid this by getting a maximum size from
            // the window session beforehand.
            if (mWidth != frame.width() || mHeight != frame.height()) {
                mWidth = frame.width();
                mHeight = frame.height();
            }

            if (mSurfaceHolder != null) {
                // The app owns the surface; tell it about what is going on.
                if (mSurface.isValid()) {
                    // XXX .copyFrom() doesn't work!
                    //mSurfaceHolder.mSurface.copyFrom(mSurface);
                    mSurfaceHolder.mSurface = mSurface;
                }
                mSurfaceHolder.setSurfaceFrameSize(mWidth, mHeight);
                mSurfaceHolder.mSurfaceLock.unlock();
                if (surfaceCreated) {
                    mSurfaceHolder.ungetCallbacks();

                    mIsCreating = true;
                    SurfaceHolder.Callback[] callbacks = mSurfaceHolder.getCallbacks();
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceCreated(mSurfaceHolder);
                        }
                    }
                }

                if ((surfaceCreated || surfaceReplaced || surfaceSizeChanged
                        || windowAttributesChanged) && mSurface.isValid()) {
                    SurfaceHolder.Callback[] callbacks = mSurfaceHolder.getCallbacks();
                    if (callbacks != null) {
                        for (SurfaceHolder.Callback c : callbacks) {
                            c.surfaceChanged(mSurfaceHolder, lp.format,
                                    mWidth, mHeight);
                        }
                    }
                    mIsCreating = false;
                }

                if (surfaceDestroyed) {
                    notifyHolderSurfaceDestroyed();
                    mSurfaceHolder.mSurfaceLock.lock();
                    try {
                        mSurfaceHolder.mSurface = new Surface();
                    } finally {
                        mSurfaceHolder.mSurfaceLock.unlock();
                    }
                }
            }

            final ThreadedRenderer threadedRenderer = mAttachInfo.mThreadedRenderer;
            if (threadedRenderer != null && threadedRenderer.isEnabled()) {
                if (hwInitialized
                        || mWidth != threadedRenderer.getWidth()
                        || mHeight != threadedRenderer.getHeight()
                        || mNeedsRendererSetup) {
                    threadedRenderer.setup(mWidth, mHeight, mAttachInfo,
                            mWindowAttributes.surfaceInsets);
                    mNeedsRendererSetup = false;
                }
            }

            // TODO: In the CL "ViewRootImpl: Fix issue with early draw report in
            // seamless rotation". We moved processing of RELAYOUT_RES_BLAST_SYNC
            // earlier in the function, potentially triggering a call to
            // reportNextDraw(). That same CL changed this and the next reference
            // to wasReportNextDraw, such that this logic would remain undisturbed
            // (it continues to operate as if the code was never moved). This was
            // done to achieve a more hermetic fix for S, but it's entirely
            // possible that checking the most recent value is actually more
            // correct here.
            if (!mStopped || mReportNextDraw) {
                if (mWidth != host.getMeasuredWidth() || mHeight != host.getMeasuredHeight()
                        || dispatchApplyInsets || updatedConfiguration) {
                    int childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width,
                            lp.privateFlags);
                    int childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height,
                            lp.privateFlags);

                    if (DEBUG_LAYOUT) Log.v(mTag, "Ooops, something changed!  mWidth="
                            + mWidth + " measuredWidth=" + host.getMeasuredWidth()
                            + " mHeight=" + mHeight
                            + " measuredHeight=" + host.getMeasuredHeight()
                            + " dispatchApplyInsets=" + dispatchApplyInsets);

                     // Ask host how big it wants to be
                    performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);

                    // Implementation of weights from WindowManager.LayoutParams
                    // We just grow the dimensions as needed and re-measure if
                    // needs be
                    int width = host.getMeasuredWidth();
                    int height = host.getMeasuredHeight();
                    boolean measureAgain = false;

                    if (lp.horizontalWeight > 0.0f) {
                        width += (int) ((mWidth - width) * lp.horizontalWeight);
                        childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }
                    if (lp.verticalWeight > 0.0f) {
                        height += (int) ((mHeight - height) * lp.verticalWeight);
                        childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                                MeasureSpec.EXACTLY);
                        measureAgain = true;
                    }

                    if (measureAgain) {
                        if (DEBUG_LAYOUT) Log.v(mTag,
                                "And hey let's measure once more: width=" + width
                                + " height=" + height);
                        performMeasure(childWidthMeasureSpec, childHeightMeasureSpec);
                    }

                    layoutRequested = true;
                }
            }
        } else {
            // Not the first pass and no window/insets/visibility change but the window
            // may have moved and we need check that and if so to update the left and right
            // in the attach info. We translate only the window frame since on window move
            // the window manager tells us only for the new frame but the insets are the
            // same and we do not want to translate them more than once.
            maybeHandleWindowMove(frame);
        }

        if (surfaceSizeChanged || surfaceReplaced || surfaceCreated || windowAttributesChanged) {
            // If the surface has been replaced, there's a chance the bounds layer is not parented
            // to the new layer. When updating bounds layer, also reparent to the main VRI
            // SurfaceControl to ensure it's correctly placed in the hierarchy.
            //
            // This needs to be done on the client side since WMS won't reparent the children to the
            // new surface if it thinks the app is closing. WMS gets the signal that the app is
            // stopping, but on the client side it doesn't get stopped since it's restarted quick
            // enough. WMS doesn't want to keep around old children since they will leak when the
            // client creates new children.
            prepareSurfaces();
        }

        final boolean didLayout = layoutRequested && (!mStopped || mReportNextDraw);
        boolean triggerGlobalLayoutListener = didLayout
                || mAttachInfo.mRecomputeGlobalAttributes;
        if (didLayout) {
            performLayout(lp, mWidth, mHeight);

            // By this point all views have been sized and positioned
            // We can compute the transparent area

            if ((host.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) != 0) {
                // start out transparent
                // TODO: AVOID THAT CALL BY CACHING THE RESULT?
                host.getLocationInWindow(mTmpLocation);
                mTransparentRegion.set(mTmpLocation[0], mTmpLocation[1],
                        mTmpLocation[0] + host.mRight - host.mLeft,
                        mTmpLocation[1] + host.mBottom - host.mTop);

                host.gatherTransparentRegion(mTransparentRegion);
                if (mTranslator != null) {
                    mTranslator.translateRegionInWindowToScreen(mTransparentRegion);
                }

                if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                    mPreviousTransparentRegion.set(mTransparentRegion);
                    mFullRedrawNeeded = true;
                    // TODO: Ideally we would do this in prepareSurfaces,
                    // but prepareSurfaces is currently working under
                    // the assumption that we paused the render thread
                    // via the WM relayout code path. We probably eventually
                    // want to synchronize transparent region hint changes
                    // with draws.
                    SurfaceControl sc = getSurfaceControl();
                    if (sc.isValid()) {
                        mTransaction.setTransparentRegionHint(sc, mTransparentRegion).apply();
                    }
                }
            }

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after setFrame");
                host.debug();
            }
        }

        // These callbacks will trigger SurfaceView SurfaceHolder.Callbacks and must be invoked
        // after the measure pass. If its invoked before the measure pass and the app modifies
        // the view hierarchy in the callbacks, we could leave the views in a broken state.
        if (surfaceCreated) {
            notifySurfaceCreated();
        } else if (surfaceReplaced) {
            notifySurfaceReplaced();
        } else if (surfaceDestroyed)  {
            notifySurfaceDestroyed();
        }

        if (triggerGlobalLayoutListener) {
            mAttachInfo.mRecomputeGlobalAttributes = false;
            mAttachInfo.mTreeObserver.dispatchOnGlobalLayout();
        }

        Rect contentInsets = null;
        Rect visibleInsets = null;
        Region touchableRegion = null;
        int touchableInsetMode = TOUCHABLE_INSETS_REGION;
        boolean computedInternalInsets = false;
        if (computesInternalInsets) {
            final ViewTreeObserver.InternalInsetsInfo insets = mAttachInfo.mGivenInternalInsets;

            // Clear the original insets.
            insets.reset();

            // Compute new insets in place.
            mAttachInfo.mTreeObserver.dispatchOnComputeInternalInsets(insets);
            mAttachInfo.mHasNonEmptyGivenInternalInsets = !insets.isEmpty();

            // Tell the window manager.
            if (insetsPending || !mLastGivenInsets.equals(insets)) {
                mLastGivenInsets.set(insets);

                // Translate insets to screen coordinates if needed.
                if (mTranslator != null) {
                    contentInsets = mTranslator.getTranslatedContentInsets(insets.contentInsets);
                    visibleInsets = mTranslator.getTranslatedVisibleInsets(insets.visibleInsets);
                    touchableRegion = mTranslator.getTranslatedTouchableArea(insets.touchableRegion);
                } else {
                    contentInsets = insets.contentInsets;
                    visibleInsets = insets.visibleInsets;
                    touchableRegion = insets.touchableRegion;
                }
                computedInternalInsets = true;
            }
            touchableInsetMode = insets.mTouchableInsets;
        }
        boolean needsSetInsets = computedInternalInsets;
        needsSetInsets |= !Objects.equals(mPreviousTouchableRegion, mTouchableRegion) &&
            (mTouchableRegion != null);
        if (needsSetInsets) {
            if (mTouchableRegion != null) {
                if (mPreviousTouchableRegion == null) {
                    mPreviousTouchableRegion = new Region();
                }
                mPreviousTouchableRegion.set(mTouchableRegion);
                if (touchableInsetMode != TOUCHABLE_INSETS_REGION) {
                    Log.e(mTag, "Setting touchableInsetMode to non TOUCHABLE_INSETS_REGION" +
                          " from OnComputeInternalInsets, while also using setTouchableRegion" +
                          " causes setTouchableRegion to be ignored");
                }
            } else {
                mPreviousTouchableRegion = null;
            }
            if (contentInsets == null) contentInsets = new Rect(0,0,0,0);
            if (visibleInsets == null) visibleInsets = new Rect(0,0,0,0);
            if (touchableRegion == null) {
                touchableRegion = mTouchableRegion;
            } else if (touchableRegion != null && mTouchableRegion != null) {
                touchableRegion.op(touchableRegion, mTouchableRegion, Region.Op.UNION);
            }
            try {
                mWindowSession.setInsets(mWindow, touchableInsetMode,
                                         contentInsets, visibleInsets, touchableRegion);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else if (mTouchableRegion == null && mPreviousTouchableRegion != null) {
            mPreviousTouchableRegion = null;
            try {
                mWindowSession.clearTouchableRegion(mWindow);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        if (mFirst) {
            if (sAlwaysAssignFocus || !isInTouchMode()) {
                // handle first focus request
                if (DEBUG_INPUT_RESIZE) {
                    Log.v(mTag, "First: mView.hasFocus()=" + mView.hasFocus());
                }
                if (mView != null) {
                    if (!mView.hasFocus()) {
                        mView.restoreDefaultFocus();
                        if (DEBUG_INPUT_RESIZE) {
                            Log.v(mTag, "First: requested focused view=" + mView.findFocus());
                        }
                    } else {
                        if (DEBUG_INPUT_RESIZE) {
                            Log.v(mTag, "First: existing focused view=" + mView.findFocus());
                        }
                    }
                }
            } else {
                // Some views (like ScrollView) won't hand focus to descendants that aren't within
                // their viewport. Before layout, there's a good change these views are size 0
                // which means no children can get focus. After layout, this view now has size, but
                // is not guaranteed to hand-off focus to a focusable child (specifically, the edge-
                // case where the child has a size prior to layout and thus won't trigger
                // focusableViewAvailable).
                View focused = mView.findFocus();
                if (focused instanceof ViewGroup
                        && ((ViewGroup) focused).getDescendantFocusability()
                                == ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    focused.restoreDefaultFocus();
                }
            }
        }

        final boolean changedVisibility = (viewVisibilityChanged || mFirst) && isViewVisible;
        final boolean hasWindowFocus = mAttachInfo.mHasWindowFocus && isViewVisible;
        final boolean regainedFocus = hasWindowFocus && mLostWindowFocus;
        if (regainedFocus) {
            mLostWindowFocus = false;
        } else if (!hasWindowFocus && mHadWindowFocus) {
            mLostWindowFocus = true;
        }

        if (changedVisibility || regainedFocus) {
            // Toasts are presented as notifications - don't present them as windows as well
            boolean isToast = mWindowAttributes.type == TYPE_TOAST;
            if (!isToast) {
                host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            }
        }

        mFirst = false;
        mWillDrawSoon = false;
        mNewSurfaceNeeded = false;
        mActivityRelaunched = false;
        mViewVisibility = viewVisibility;
        mHadWindowFocus = hasWindowFocus;

        mImeFocusController.onTraversal(hasWindowFocus, mWindowAttributes);

        if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
            reportNextDraw();
        }

        boolean cancelAndRedraw = mAttachInfo.mTreeObserver.dispatchOnPreDraw();
        if (!cancelAndRedraw) {
            createSyncIfNeeded();
        }

        if (!isViewVisible) {
            if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                for (int i = 0; i < mPendingTransitions.size(); ++i) {
                    mPendingTransitions.get(i).endChangingAnimations();
                }
                mPendingTransitions.clear();
            }

            if (mSyncBufferCallback != null) {
                mSyncBufferCallback.onBufferReady(null);
            }
        } else if (cancelAndRedraw) {
            // Try again
            scheduleTraversals();
        } else {
            if (mPendingTransitions != null && mPendingTransitions.size() > 0) {
                for (int i = 0; i < mPendingTransitions.size(); ++i) {
                    mPendingTransitions.get(i).startChangingAnimations();
                }
                mPendingTransitions.clear();
            }
            performDraw();
        }

        if (mAttachInfo.mContentCaptureEvents != null) {
            notifyContentCatpureEvents();
        }

        mIsInTraversal = false;
        mRelayoutRequested = false;

        if (!cancelAndRedraw) {
            mReportNextDraw = false;
            mSyncBufferCallback = null;
            mSyncBuffer = false;
            if (mLastSyncId != -1) {
                mSurfaceSyncer.markSyncReady(mLastSyncId);
                mLastSyncId = -1;
            }
        }
    }

    private void createSyncIfNeeded() {
        // Started a sync already or there's nothing needing to sync
        if (mLastSyncId != -1 || !mReportNextDraw) {
            return;
        }

        final int seqId = mSyncSeqId;
        mLastSyncId = mSurfaceSyncer.setupSync(transaction -> {
            // Callback will be invoked on executor thread so post to main thread.
            mHandler.postAtFrontOfQueue(() -> {
                mSurfaceChangedTransaction.merge(transaction);
                reportDrawFinished(seqId);
            });
        });
        if (DEBUG_BLAST) {
            Log.d(mTag, "Setup new sync id=" + mLastSyncId);
        }
        mSurfaceSyncer.addToSync(mLastSyncId, mSyncTarget);
    }

    private void notifyContentCatpureEvents() {
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "notifyContentCaptureEvents");
        try {
            MainContentCaptureSession mainSession = mAttachInfo.mContentCaptureManager
                    .getMainContentCaptureSession();
            for (int i = 0; i < mAttachInfo.mContentCaptureEvents.size(); i++) {
                int sessionId = mAttachInfo.mContentCaptureEvents.keyAt(i);
                mainSession.notifyViewTreeEvent(sessionId, /* started= */ true);
                ArrayList<Object> events = mAttachInfo.mContentCaptureEvents
                        .valueAt(i);
                for_each_event: for (int j = 0; j < events.size(); j++) {
                    Object event = events.get(j);
                    if (event instanceof AutofillId) {
                        mainSession.notifyViewDisappeared(sessionId, (AutofillId) event);
                    } else if (event instanceof View) {
                        View view = (View) event;
                        ContentCaptureSession session = view.getContentCaptureSession();
                        if (session == null) {
                            Log.w(mTag, "no content capture session on view: " + view);
                            continue for_each_event;
                        }
                        int actualId = session.getId();
                        if (actualId != sessionId) {
                            Log.w(mTag, "content capture session mismatch for view (" + view
                                    + "): was " + sessionId + " before, it's " + actualId + " now");
                            continue for_each_event;
                        }
                        ViewStructure structure = session.newViewStructure(view);
                        view.onProvideContentCaptureStructure(structure, /* flags= */ 0);
                        session.notifyViewAppeared(structure);
                    } else if (event instanceof Insets) {
                        mainSession.notifyViewInsetsChanged(sessionId, (Insets) event);
                    } else {
                        Log.w(mTag, "invalid content capture event: " + event);
                    }
                }
                mainSession.notifyViewTreeEvent(sessionId, /* started= */ false);
            }
            mAttachInfo.mContentCaptureEvents = null;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private void notifyHolderSurfaceDestroyed() {
        mSurfaceHolder.ungetCallbacks();
        SurfaceHolder.Callback[] callbacks = mSurfaceHolder.getCallbacks();
        if (callbacks != null) {
            for (SurfaceHolder.Callback c : callbacks) {
                c.surfaceDestroyed(mSurfaceHolder);
            }
        }
    }

    private void maybeHandleWindowMove(Rect frame) {
        // TODO: Well, we are checking whether the frame has changed similarly
        // to how this is done for the insets. This is however incorrect since
        // the insets and the frame are translated. For example, the old frame
        // was (1, 1 - 1, 1) and was translated to say (2, 2 - 2, 2), now the new
        // reported frame is (2, 2 - 2, 2) which implies no change but this is not
        // true since we are comparing a not translated value to a translated one.
        // This scenario is rare but we may want to fix that.

        final boolean windowMoved = mAttachInfo.mWindowLeft != frame.left
                || mAttachInfo.mWindowTop != frame.top;
        if (windowMoved) {
            mAttachInfo.mWindowLeft = frame.left;
            mAttachInfo.mWindowTop = frame.top;
        }
        if (windowMoved || mAttachInfo.mNeedsUpdateLightCenter) {
            // Update the light position for the new offsets.
            if (mAttachInfo.mThreadedRenderer != null) {
                mAttachInfo.mThreadedRenderer.setLightCenter(mAttachInfo);
            }
            mAttachInfo.mNeedsUpdateLightCenter = false;
        }
    }

    private void handleWindowFocusChanged() {
        final boolean hasWindowFocus;
        synchronized (this) {
            if (!mWindowFocusChanged) {
                return;
            }
            mWindowFocusChanged = false;
            hasWindowFocus = mUpcomingWindowFocus;
        }
        // TODO (b/131181940): Make sure this doesn't leak Activity with mActivityConfigCallback
        // config changes.
        if (hasWindowFocus) {
            mInsetsController.onWindowFocusGained(
                    getFocusedViewOrNull() != null /* hasViewFocused */);
        } else {
            mInsetsController.onWindowFocusLost();
        }

        if (mAdded) {
            profileRendering(hasWindowFocus);
            if (hasWindowFocus) {
                if (mAttachInfo.mThreadedRenderer != null && mSurface.isValid()) {
                    mFullRedrawNeeded = true;
                    try {
                        final Rect surfaceInsets = mWindowAttributes.surfaceInsets;
                        mAttachInfo.mThreadedRenderer.initializeIfNeeded(
                                mWidth, mHeight, mAttachInfo, mSurface, surfaceInsets);
                    } catch (OutOfResourcesException e) {
                        Log.e(mTag, "OutOfResourcesException locking surface", e);
                        try {
                            if (!mWindowSession.outOfMemory(mWindow)) {
                                Slog.w(mTag, "No processes killed for memory;"
                                        + " killing self");
                                Process.killProcess(Process.myPid());
                            }
                        } catch (RemoteException ex) {
                        }
                        // Retry in a bit.
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                MSG_WINDOW_FOCUS_CHANGED), 500);
                        return;
                    }
                }
            }

            mAttachInfo.mHasWindowFocus = hasWindowFocus;
            mImeFocusController.updateImeFocusable(mWindowAttributes, true /* force */);
            mImeFocusController.onPreWindowFocus(hasWindowFocus, mWindowAttributes);

            if (mView != null) {
                mAttachInfo.mKeyDispatchState.reset();
                mView.dispatchWindowFocusChanged(hasWindowFocus);
                mAttachInfo.mTreeObserver.dispatchOnWindowFocusChange(hasWindowFocus);
                if (mAttachInfo.mTooltipHost != null) {
                    mAttachInfo.mTooltipHost.hideTooltip();
                }
            }

            // Note: must be done after the focus change callbacks,
            // so all of the view state is set up correctly.
            mImeFocusController.onPostWindowFocus(
                    getFocusedViewOrNull(), hasWindowFocus, mWindowAttributes);

            if (hasWindowFocus) {
                // Clear the forward bit.  We can just do this directly, since
                // the window manager doesn't care about it.
                mWindowAttributes.softInputMode &=
                        ~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
                ((WindowManager.LayoutParams) mView.getLayoutParams())
                        .softInputMode &=
                        ~WindowManager.LayoutParams
                                .SOFT_INPUT_IS_FORWARD_NAVIGATION;

                // Refocusing a window that has a focused view should fire a
                // focus event for the view since the global focused view changed.
                fireAccessibilityFocusEventIfHasFocusedNode();
            } else {
                if (mPointerCapture) {
                    handlePointerCaptureChanged(false);
                }
            }
        }
        mFirstInputStage.onWindowFocusChanged(hasWindowFocus);

        // NOTE: there's no view visibility (appeared / disapparead) events when the windows focus
        // is lost, so we don't need to to force a flush - there might be other events such as
        // text changes, but these should be flushed independently.
        if (hasWindowFocus) {
            handleContentCaptureFlush();
        }
    }

    private void handleWindowTouchModeChanged() {
        final boolean inTouchMode;
        synchronized (this) {
            inTouchMode = mUpcomingInTouchMode;
        }
        ensureTouchModeLocally(inTouchMode);
    }

    private void fireAccessibilityFocusEventIfHasFocusedNode() {
        if (!AccessibilityManager.getInstance(mContext).isEnabled()) {
            return;
        }
        final View focusedView = mView.findFocus();
        if (focusedView == null) {
            return;
        }
        final AccessibilityNodeProvider provider = focusedView.getAccessibilityNodeProvider();
        if (provider == null) {
            focusedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
        } else {
            final AccessibilityNodeInfo focusedNode = findFocusedVirtualNode(provider);
            if (focusedNode != null) {
                final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(
                        focusedNode.getSourceNodeId());
                // This is a best effort since clearing and setting the focus via the
                // provider APIs could have side effects. We don't have a provider API
                // similar to that on View to ask a given event to be fired.
                final AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_VIEW_FOCUSED);
                event.setSource(focusedView, virtualId);
                event.setPackageName(focusedNode.getPackageName());
                event.setChecked(focusedNode.isChecked());
                event.setContentDescription(focusedNode.getContentDescription());
                event.setPassword(focusedNode.isPassword());
                event.getText().add(focusedNode.getText());
                event.setEnabled(focusedNode.isEnabled());
                focusedView.getParent().requestSendAccessibilityEvent(focusedView, event);
                focusedNode.recycle();
            }
        }
    }

    private AccessibilityNodeInfo findFocusedVirtualNode(AccessibilityNodeProvider provider) {
        AccessibilityNodeInfo focusedNode = provider.findFocus(
                AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            return focusedNode;
        }

        if (!mContext.isAutofillCompatibilityEnabled()) {
            return null;
        }

        // Unfortunately some provider implementations don't properly
        // implement AccessibilityNodeProvider#findFocus
        AccessibilityNodeInfo current = provider.createAccessibilityNodeInfo(
                AccessibilityNodeProvider.HOST_VIEW_ID);
        if (current.isFocused()) {
            return current;
        }

        final Queue<AccessibilityNodeInfo> fringe = new ArrayDeque<>();
        fringe.offer(current);

        while (!fringe.isEmpty()) {
            current = fringe.poll();
            final LongArray childNodeIds = current.getChildNodeIds();
            if (childNodeIds== null || childNodeIds.size() <= 0) {
                continue;
            }
            final int childCount = childNodeIds.size();
            for (int i = 0; i < childCount; i++) {
                final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(
                        childNodeIds.get(i));
                final AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(virtualId);
                if (child != null) {
                    if (child.isFocused()) {
                        return child;
                    }
                    fringe.offer(child);
                }
            }
            current.recycle();
        }

        return null;
    }

    private void handleOutOfResourcesException(Surface.OutOfResourcesException e) {
        Log.e(mTag, "OutOfResourcesException initializing HW surface", e);
        try {
            if (!mWindowSession.outOfMemory(mWindow) &&
                    Process.myUid() != Process.SYSTEM_UID) {
                Slog.w(mTag, "No processes killed for memory; killing self");
                Process.killProcess(Process.myPid());
            }
        } catch (RemoteException ex) {
        }
        mLayoutRequested = true;    // ask wm for a new surface next time.
    }

    private void performMeasure(int childWidthMeasureSpec, int childHeightMeasureSpec) {
        if (mView == null) {
            return;
        }
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "measure");
        try {
            mView.measure(childWidthMeasureSpec, childHeightMeasureSpec);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    /**
     * Called by {@link android.view.View#isInLayout()} to determine whether the view hierarchy
     * is currently undergoing a layout pass.
     *
     * @return whether the view hierarchy is currently undergoing a layout pass
     */
    boolean isInLayout() {
        return mInLayout;
    }

    /**
     * Called by {@link android.view.View#requestLayout()} if the view hierarchy is currently
     * undergoing a layout pass. requestLayout() should not generally be called during layout,
     * unless the container hierarchy knows what it is doing (i.e., it is fine as long as
     * all children in that container hierarchy are measured and laid out at the end of the layout
     * pass for that container). If requestLayout() is called anyway, we handle it correctly
     * by registering all requesters during a frame as it proceeds. At the end of the frame,
     * we check all of those views to see if any still have pending layout requests, which
     * indicates that they were not correctly handled by their container hierarchy. If that is
     * the case, we clear all such flags in the tree, to remove the buggy flag state that leads
     * to blank containers, and force a second request/measure/layout pass in this frame. If
     * more requestLayout() calls are received during that second layout pass, we post those
     * requests to the next frame to avoid possible infinite loops.
     *
     * <p>The return value from this method indicates whether the request should proceed
     * (if it is a request during the first layout pass) or should be skipped and posted to the
     * next frame (if it is a request during the second layout pass).</p>
     *
     * @param view the view that requested the layout.
     *
     * @return true if request should proceed, false otherwise.
     */
    boolean requestLayoutDuringLayout(final View view) {
        if (view.mParent == null || view.mAttachInfo == null) {
            // Would not normally trigger another layout, so just let it pass through as usual
            return true;
        }
        if (!mLayoutRequesters.contains(view)) {
            mLayoutRequesters.add(view);
        }
        if (!mHandlingLayoutInLayoutRequest) {
            // Let the request proceed normally; it will be processed in a second layout pass
            // if necessary
            return true;
        } else {
            // Don't let the request proceed during the second layout pass.
            // It will post to the next frame instead.
            return false;
        }
    }

    private void performLayout(WindowManager.LayoutParams lp, int desiredWindowWidth,
            int desiredWindowHeight) {
        mScrollMayChange = true;
        mInLayout = true;

        final View host = mView;
        if (host == null) {
            return;
        }
        if (DEBUG_ORIENTATION || DEBUG_LAYOUT) {
            Log.v(mTag, "Laying out " + host + " to (" +
                    host.getMeasuredWidth() + ", " + host.getMeasuredHeight() + ")");
        }

        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "layout");
        try {
            host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

            mInLayout = false;
            int numViewsRequestingLayout = mLayoutRequesters.size();
            if (numViewsRequestingLayout > 0) {
                // requestLayout() was called during layout.
                // If no layout-request flags are set on the requesting views, there is no problem.
                // If some requests are still pending, then we need to clear those flags and do
                // a full request/measure/layout pass to handle this situation.
                ArrayList<View> validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters,
                        false);
                if (validLayoutRequesters != null) {
                    // Set this flag to indicate that any further requests are happening during
                    // the second pass, which may result in posting those requests to the next
                    // frame instead
                    mHandlingLayoutInLayoutRequest = true;

                    // Process fresh layout requests, then measure and layout
                    int numValidRequests = validLayoutRequesters.size();
                    for (int i = 0; i < numValidRequests; ++i) {
                        final View view = validLayoutRequesters.get(i);
                        Log.w("View", "requestLayout() improperly called by " + view +
                                " during layout: running second layout pass");
                        view.requestLayout();
                    }
                    measureHierarchy(host, lp, mView.getContext().getResources(),
                            desiredWindowWidth, desiredWindowHeight);
                    mInLayout = true;
                    host.layout(0, 0, host.getMeasuredWidth(), host.getMeasuredHeight());

                    mHandlingLayoutInLayoutRequest = false;

                    // Check the valid requests again, this time without checking/clearing the
                    // layout flags, since requests happening during the second pass get noop'd
                    validLayoutRequesters = getValidLayoutRequesters(mLayoutRequesters, true);
                    if (validLayoutRequesters != null) {
                        final ArrayList<View> finalRequesters = validLayoutRequesters;
                        // Post second-pass requests to the next frame
                        getRunQueue().post(new Runnable() {
                            @Override
                            public void run() {
                                int numValidRequests = finalRequesters.size();
                                for (int i = 0; i < numValidRequests; ++i) {
                                    final View view = finalRequesters.get(i);
                                    Log.w("View", "requestLayout() improperly called by " + view +
                                            " during second layout pass: posting in next frame");
                                    view.requestLayout();
                                }
                            }
                        });
                    }
                }

            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
        mInLayout = false;
    }

    /**
     * This method is called during layout when there have been calls to requestLayout() during
     * layout. It walks through the list of views that requested layout to determine which ones
     * still need it, based on visibility in the hierarchy and whether they have already been
     * handled (as is usually the case with ListView children).
     *
     * @param layoutRequesters The list of views that requested layout during layout
     * @param secondLayoutRequests Whether the requests were issued during the second layout pass.
     * If so, the FORCE_LAYOUT flag was not set on requesters.
     * @return A list of the actual views that still need to be laid out.
     */
    private ArrayList<View> getValidLayoutRequesters(ArrayList<View> layoutRequesters,
            boolean secondLayoutRequests) {

        int numViewsRequestingLayout = layoutRequesters.size();
        ArrayList<View> validLayoutRequesters = null;
        for (int i = 0; i < numViewsRequestingLayout; ++i) {
            View view = layoutRequesters.get(i);
            if (view != null && view.mAttachInfo != null && view.mParent != null &&
                    (secondLayoutRequests || (view.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) ==
                            View.PFLAG_FORCE_LAYOUT)) {
                boolean gone = false;
                View parent = view;
                // Only trigger new requests for views in a non-GONE hierarchy
                while (parent != null) {
                    if ((parent.mViewFlags & View.VISIBILITY_MASK) == View.GONE) {
                        gone = true;
                        break;
                    }
                    if (parent.mParent instanceof View) {
                        parent = (View) parent.mParent;
                    } else {
                        parent = null;
                    }
                }
                if (!gone) {
                    if (validLayoutRequesters == null) {
                        validLayoutRequesters = new ArrayList<View>();
                    }
                    validLayoutRequesters.add(view);
                }
            }
        }
        if (!secondLayoutRequests) {
            // If we're checking the layout flags, then we need to clean them up also
            for (int i = 0; i < numViewsRequestingLayout; ++i) {
                View view = layoutRequesters.get(i);
                while (view != null &&
                        (view.mPrivateFlags & View.PFLAG_FORCE_LAYOUT) != 0) {
                    view.mPrivateFlags &= ~View.PFLAG_FORCE_LAYOUT;
                    if (view.mParent instanceof View) {
                        view = (View) view.mParent;
                    } else {
                        view = null;
                    }
                }
            }
        }
        layoutRequesters.clear();
        return validLayoutRequesters;
    }

    @Override
    public void requestTransparentRegion(View child) {
        // the test below should not fail unless someone is messing with us
        checkThread();
        if (mView != child) {
            return;
        }

        if ((mView.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0) {
            mView.mPrivateFlags |= View.PFLAG_REQUEST_TRANSPARENT_REGIONS;
            // Need to make sure we re-evaluate the window attributes next
            // time around, to ensure the window has the correct format.
            mWindowAttributesChanged = true;
        }

        // Always request layout to apply the latest transparent region.
        requestLayout();
    }

    /**
     * Figures out the measure spec for the root view in a window based on it's
     * layout params.
     *
     * @param windowSize The available width or height of the window.
     * @param measurement The layout width or height requested in the layout params.
     * @param privateFlags The private flags in the layout params of the window.
     * @return The measure spec to use to measure the root view.
     */
    private static int getRootMeasureSpec(int windowSize, int measurement, int privateFlags) {
        int measureSpec;
        final int rootDimension = (privateFlags & PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT) != 0
                ? MATCH_PARENT : measurement;
        switch (rootDimension) {
            case ViewGroup.LayoutParams.MATCH_PARENT:
                // Window can't resize. Force root view to be windowSize.
                measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.EXACTLY);
                break;
            case ViewGroup.LayoutParams.WRAP_CONTENT:
                // Window can resize. Set max size for root view.
                measureSpec = MeasureSpec.makeMeasureSpec(windowSize, MeasureSpec.AT_MOST);
                break;
            default:
                // Window wants to be an exact size. Force root view to be that size.
                measureSpec = MeasureSpec.makeMeasureSpec(rootDimension, MeasureSpec.EXACTLY);
                break;
        }
        return measureSpec;
    }

    int mHardwareXOffset;
    int mHardwareYOffset;

    @Override
    public void onPreDraw(RecordingCanvas canvas) {
        // If mCurScrollY is not 0 then this influences the hardwareYOffset. The end result is we
        // can apply offsets that are not handled by anything else, resulting in underdraw as
        // the View is shifted (thus shifting the window background) exposing unpainted
        // content. To handle this with minimal glitches we just clear to BLACK if the window
        // is opaque. If it's not opaque then HWUI already internally does a glClear to
        // transparent, so there's no risk of underdraw on non-opaque surfaces.
        if (mCurScrollY != 0 && mHardwareYOffset != 0 && mAttachInfo.mThreadedRenderer.isOpaque()) {
            canvas.drawColor(Color.BLACK);
        }
        canvas.translate(-mHardwareXOffset, -mHardwareYOffset);
    }

    @Override
    public void onPostDraw(RecordingCanvas canvas) {
        drawAccessibilityFocusedDrawableIfNeeded(canvas);
        if (mUseMTRenderer) {
            for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                mWindowCallbacks.get(i).onPostDraw(canvas);
            }
        }
    }

    /**
     * @hide
     */
    void outputDisplayList(View view) {
        view.mRenderNode.output();
    }

    /**
     * @see #PROPERTY_PROFILE_RENDERING
     */
    private void profileRendering(boolean enabled) {
        if (mProfileRendering) {
            mRenderProfilingEnabled = enabled;

            if (mRenderProfiler != null) {
                mChoreographer.removeFrameCallback(mRenderProfiler);
            }
            if (mRenderProfilingEnabled) {
                if (mRenderProfiler == null) {
                    mRenderProfiler = new Choreographer.FrameCallback() {
                        @Override
                        public void doFrame(long frameTimeNanos) {
                            mDirty.set(0, 0, mWidth, mHeight);
                            scheduleTraversals();
                            if (mRenderProfilingEnabled) {
                                mChoreographer.postFrameCallback(mRenderProfiler);
                            }
                        }
                    };
                }
                mChoreographer.postFrameCallback(mRenderProfiler);
            } else {
                mRenderProfiler = null;
            }
        }
    }

    /**
     * Called from draw() when DEBUG_FPS is enabled
     */
    private void trackFPS() {
        // Tracks frames per second drawn. First value in a series of draws may be bogus
        // because it down not account for the intervening idle time
        long nowTime = System.currentTimeMillis();
        if (mFpsStartTime < 0) {
            mFpsStartTime = mFpsPrevTime = nowTime;
            mFpsNumFrames = 0;
        } else {
            ++mFpsNumFrames;
            String thisHash = Integer.toHexString(System.identityHashCode(this));
            long frameTime = nowTime - mFpsPrevTime;
            long totalTime = nowTime - mFpsStartTime;
            Log.v(mTag, "0x" + thisHash + "\tFrame time:\t" + frameTime);
            mFpsPrevTime = nowTime;
            if (totalTime > 1000) {
                float fps = (float) mFpsNumFrames * 1000 / totalTime;
                Log.v(mTag, "0x" + thisHash + "\tFPS:\t" + fps);
                mFpsStartTime = nowTime;
                mFpsNumFrames = 0;
            }
        }
    }

    private void reportDrawFinished(int seqId) {
        if (DEBUG_BLAST) {
            Log.d(mTag, "reportDrawFinished " + Debug.getCallers(5));
        }

        try {
            mWindowSession.finishDrawing(mWindow, mSurfaceChangedTransaction, seqId);
        } catch (RemoteException e) {
            Log.e(mTag, "Unable to report draw finished", e);
            mSurfaceChangedTransaction.apply();
        } finally {
            mSurfaceChangedTransaction.clear();
        }
    }

    /**
     * @hide
     */
    public boolean isHardwareEnabled() {
        return mAttachInfo.mThreadedRenderer != null && mAttachInfo.mThreadedRenderer.isEnabled();
    }

    boolean addToSync(SurfaceSyncer.SyncTarget syncable) {
        if (mLastSyncId == -1) {
            return false;
        }
        mSurfaceSyncer.addToSync(mLastSyncId, syncable);
        return true;
    }


    public boolean isInSync() {
        return mLastSyncId != -1;
    }

    private void addFrameCommitCallbackIfNeeded() {
        if (!isHardwareEnabled()) {
            return;
        }

        ArrayList<Runnable> commitCallbacks = mAttachInfo.mTreeObserver
                .captureFrameCommitCallbacks();
        final boolean needFrameCommitCallback =
                (commitCallbacks != null && commitCallbacks.size() > 0);
        if (!needFrameCommitCallback) {
            return;
        }

        if (DEBUG_DRAW) {
            Log.d(mTag, "Creating frameCommitCallback"
                    + " commitCallbacks size=" + commitCallbacks.size());
        }
        mAttachInfo.mThreadedRenderer.setFrameCommitCallback(didProduceBuffer -> {
            if (DEBUG_DRAW) {
                Log.d(mTag, "Received frameCommitCallback didProduceBuffer=" + didProduceBuffer);
            }

            mHandler.postAtFrontOfQueue(() -> {
                for (int i = 0; i < commitCallbacks.size(); i++) {
                    commitCallbacks.get(i).run();
                }
            });
        });
    }

    @Nullable
    private void registerFrameDrawingCallbackForBlur() {
        if (!isHardwareEnabled()) {
            return;
        }
        final boolean hasBlurUpdates = mBlurRegionAggregator.hasUpdates();
        final boolean needsCallbackForBlur = hasBlurUpdates || mBlurRegionAggregator.hasRegions();

        if (!needsCallbackForBlur) {
            return;
        }

        final BackgroundBlurDrawable.BlurRegion[] blurRegionsForFrame =
                mBlurRegionAggregator.getBlurRegionsCopyForRT();

        // The callback will run on the render thread.
        registerRtFrameCallback((frame) -> mBlurRegionAggregator
                .dispatchBlurTransactionIfNeeded(frame, blurRegionsForFrame, hasBlurUpdates));
    }

    private void registerCallbackForPendingTransactions() {
        registerRtFrameCallback(new FrameDrawingCallback() {
            @Override
            public HardwareRenderer.FrameCommitCallback onFrameDraw(int syncResult, long frame) {
                if ((syncResult
                        & (SYNC_LOST_SURFACE_REWARD_IF_FOUND | SYNC_CONTEXT_IS_STOPPED)) != 0) {
                    mBlastBufferQueue.applyPendingTransactions(frame);
                    return null;
                }

                return didProduceBuffer -> {
                    if (!didProduceBuffer) {
                        mBlastBufferQueue.applyPendingTransactions(frame);
                    }
                };

            }

            @Override
            public void onFrameDraw(long frame) {
            }
        });
    }

    private void performDraw() {
        if (mAttachInfo.mDisplayState == Display.STATE_OFF && !mReportNextDraw) {
            return;
        } else if (mView == null) {
            return;
        }

        final boolean fullRedrawNeeded = mFullRedrawNeeded || mSyncBufferCallback != null;
        mFullRedrawNeeded = false;

        mIsDrawing = true;
        Trace.traceBegin(Trace.TRACE_TAG_VIEW, "draw");

        registerFrameDrawingCallbackForBlur();
        addFrameCommitCallbackIfNeeded();

        boolean usingAsyncReport = isHardwareEnabled() && mSyncBufferCallback != null;
        if (usingAsyncReport) {
            registerCallbacksForSync(mSyncBuffer, mSyncBufferCallback);
        } else if (mHasPendingTransactions) {
            // These callbacks are only needed if there's no sync involved and there were calls to
            // applyTransactionOnDraw. These callbacks check if the draw failed for any reason and
            // apply those transactions directly so they don't get stuck forever.
            registerCallbackForPendingTransactions();
        }
        mHasPendingTransactions = false;

        try {
            boolean canUseAsync = draw(fullRedrawNeeded, usingAsyncReport && mSyncBuffer);
            if (usingAsyncReport && !canUseAsync) {
                mAttachInfo.mThreadedRenderer.setFrameCallback(null);
                usingAsyncReport = false;
            }
        } finally {
            mIsDrawing = false;
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }

        // For whatever reason we didn't create a HardwareRenderer, end any
        // hardware animations that are now dangling
        if (mAttachInfo.mPendingAnimatingRenderNodes != null) {
            final int count = mAttachInfo.mPendingAnimatingRenderNodes.size();
            for (int i = 0; i < count; i++) {
                mAttachInfo.mPendingAnimatingRenderNodes.get(i).endAllAnimators();
            }
            mAttachInfo.mPendingAnimatingRenderNodes.clear();
        }

        if (mReportNextDraw) {

            // if we're using multi-thread renderer, wait for the window frame draws
            if (mWindowDrawCountDown != null) {
                try {
                    mWindowDrawCountDown.await();
                } catch (InterruptedException e) {
                    Log.e(mTag, "Window redraw count down interrupted!");
                }
                mWindowDrawCountDown = null;
            }

            if (mAttachInfo.mThreadedRenderer != null) {
                mAttachInfo.mThreadedRenderer.setStopped(mStopped);
            }

            if (LOCAL_LOGV) {
                Log.v(mTag, "FINISHED DRAWING: " + mWindowAttributes.getTitle());
            }

            if (mSurfaceHolder != null && mSurface.isValid()) {
                final SurfaceSyncer.SyncBufferCallback syncBufferCallback = mSyncBufferCallback;
                SurfaceCallbackHelper sch = new SurfaceCallbackHelper(() ->
                        mHandler.post(() -> syncBufferCallback.onBufferReady(null)));
                mSyncBufferCallback = null;

                SurfaceHolder.Callback callbacks[] = mSurfaceHolder.getCallbacks();

                sch.dispatchSurfaceRedrawNeededAsync(mSurfaceHolder, callbacks);
            } else if (!usingAsyncReport) {
                if (mAttachInfo.mThreadedRenderer != null) {
                    mAttachInfo.mThreadedRenderer.fence();
                }
            }
        }
        if (mSyncBufferCallback != null && !usingAsyncReport) {
            mSyncBufferCallback.onBufferReady(null);
        }
        if (mPerformContentCapture) {
            performContentCaptureInitialReport();
        }

        if (mPerformAutoFill) {
            notifyEnterForAutoFillIfNeeded();
        }
    }

    private void notifyEnterForAutoFillIfNeeded() {
        mPerformAutoFill = false;
        final AutofillManager afm = getAutofillManager();
        if (afm != null) {
            afm.notifyViewEnteredForActivityStarted(mView);
        }
    }

    /**
     * Checks (and caches) if content capture is enabled for this context.
     */
    private boolean isContentCaptureEnabled() {
        switch (mContentCaptureEnabled) {
            case CONTENT_CAPTURE_ENABLED_TRUE:
                return true;
            case CONTENT_CAPTURE_ENABLED_FALSE:
                return false;
            case CONTENT_CAPTURE_ENABLED_NOT_CHECKED:
                final boolean reallyEnabled = isContentCaptureReallyEnabled();
                mContentCaptureEnabled = reallyEnabled ? CONTENT_CAPTURE_ENABLED_TRUE
                        : CONTENT_CAPTURE_ENABLED_FALSE;
                return reallyEnabled;
            default:
                Log.w(TAG, "isContentCaptureEnabled(): invalid state " + mContentCaptureEnabled);
                return false;
        }

    }

    /**
     * Checks (without caching) if content capture is enabled for this context.
     */
    private boolean isContentCaptureReallyEnabled() {
        // First check if context supports it, so it saves a service lookup when it doesn't
        if (mContext.getContentCaptureOptions() == null) return false;

        final ContentCaptureManager ccm = mAttachInfo.getContentCaptureManager(mContext);
        // Then check if it's enabled in the contex itself.
        if (ccm == null || !ccm.isContentCaptureEnabled()) return false;

        return true;
    }

    private void performContentCaptureInitialReport() {
        mPerformContentCapture = false; // One-time offer!
        final View rootView = mView;
        if (DEBUG_CONTENT_CAPTURE) {
            Log.v(mTag, "performContentCaptureInitialReport() on " + rootView);
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "dispatchContentCapture() for "
                    + getClass().getSimpleName());
        }
        try {
            if (!isContentCaptureEnabled()) return;

            // Initial dispatch of window bounds to content capture
            if (mAttachInfo.mContentCaptureManager != null) {
                MainContentCaptureSession session =
                        mAttachInfo.mContentCaptureManager.getMainContentCaptureSession();
                session.notifyWindowBoundsChanged(session.getId(),
                        getConfiguration().windowConfiguration.getBounds());
            }

            // Content capture is a go!
            rootView.dispatchInitialProvideContentCaptureStructure();
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private void handleContentCaptureFlush() {
        if (DEBUG_CONTENT_CAPTURE) {
            Log.v(mTag, "handleContentCaptureFlush()");
        }
        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "flushContentCapture for "
                    + getClass().getSimpleName());
        }
        try {
            if (!isContentCaptureEnabled()) return;

            final ContentCaptureManager ccm = mAttachInfo.mContentCaptureManager;
            if (ccm == null) {
                Log.w(TAG, "No ContentCapture on AttachInfo");
                return;
            }
            ccm.flush(ContentCaptureSession.FLUSH_REASON_VIEW_ROOT_ENTERED);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private boolean draw(boolean fullRedrawNeeded, boolean forceDraw) {
        Surface surface = mSurface;
        if (!surface.isValid()) {
            return false;
        }

        if (DEBUG_FPS) {
            trackFPS();
        }

        if (!sFirstDrawComplete) {
            synchronized (sFirstDrawHandlers) {
                sFirstDrawComplete = true;
                final int count = sFirstDrawHandlers.size();
                for (int i = 0; i< count; i++) {
                    mHandler.post(sFirstDrawHandlers.get(i));
                }
            }
        }

        scrollToRectOrFocus(null, false);

        if (mAttachInfo.mViewScrollChanged) {
            mAttachInfo.mViewScrollChanged = false;
            mAttachInfo.mTreeObserver.dispatchOnScrollChanged();
        }

        boolean animating = mScroller != null && mScroller.computeScrollOffset();
        final int curScrollY;
        if (animating) {
            curScrollY = mScroller.getCurrY();
        } else {
            curScrollY = mScrollY;
        }
        if (mCurScrollY != curScrollY) {
            mCurScrollY = curScrollY;
            fullRedrawNeeded = true;
            if (mView instanceof RootViewSurfaceTaker) {
                ((RootViewSurfaceTaker) mView).onRootViewScrollYChanged(mCurScrollY);
            }
        }

        final float appScale = mAttachInfo.mApplicationScale;
        final boolean scalingRequired = mAttachInfo.mScalingRequired;

        final Rect dirty = mDirty;
        if (mSurfaceHolder != null) {
            // The app owns the surface, we won't draw.
            dirty.setEmpty();
            if (animating && mScroller != null) {
                mScroller.abortAnimation();
            }
            return false;
        }

        if (fullRedrawNeeded) {
            dirty.set(0, 0, (int) (mWidth * appScale + 0.5f), (int) (mHeight * appScale + 0.5f));
        }

        if (DEBUG_ORIENTATION || DEBUG_DRAW) {
            Log.v(mTag, "Draw " + mView + "/"
                    + mWindowAttributes.getTitle()
                    + ": dirty={" + dirty.left + "," + dirty.top
                    + "," + dirty.right + "," + dirty.bottom + "} surface="
                    + surface + " surface.isValid()=" + surface.isValid() + ", appScale:" +
                    appScale + ", width=" + mWidth + ", height=" + mHeight);
        }

        mAttachInfo.mTreeObserver.dispatchOnDraw();

        int xOffset = -mCanvasOffsetX;
        int yOffset = -mCanvasOffsetY + curScrollY;
        final WindowManager.LayoutParams params = mWindowAttributes;
        final Rect surfaceInsets = params != null ? params.surfaceInsets : null;
        if (surfaceInsets != null) {
            xOffset -= surfaceInsets.left;
            yOffset -= surfaceInsets.top;

            // Offset dirty rect for surface insets.
            dirty.offset(surfaceInsets.left, surfaceInsets.top);
        }

        boolean accessibilityFocusDirty = false;
        final Drawable drawable = mAttachInfo.mAccessibilityFocusDrawable;
        if (drawable != null) {
            final Rect bounds = mAttachInfo.mTmpInvalRect;
            final boolean hasFocus = getAccessibilityFocusedRect(bounds);
            if (!hasFocus) {
                bounds.setEmpty();
            }
            if (!bounds.equals(drawable.getBounds())) {
                accessibilityFocusDirty = true;
            }
        }

        mAttachInfo.mDrawingTime =
                mChoreographer.getFrameTimeNanos() / TimeUtils.NANOS_PER_MS;

        boolean useAsyncReport = false;
        if (!dirty.isEmpty() || mIsAnimating || accessibilityFocusDirty) {
            if (isHardwareEnabled()) {
                // If accessibility focus moved, always invalidate the root.
                boolean invalidateRoot = accessibilityFocusDirty || mInvalidateRootRequested;
                mInvalidateRootRequested = false;

                // Draw with hardware renderer.
                mIsAnimating = false;

                if (mHardwareYOffset != yOffset || mHardwareXOffset != xOffset) {
                    mHardwareYOffset = yOffset;
                    mHardwareXOffset = xOffset;
                    invalidateRoot = true;
                }

                if (invalidateRoot) {
                    mAttachInfo.mThreadedRenderer.invalidateRoot();
                }

                dirty.setEmpty();

                // Stage the content drawn size now. It will be transferred to the renderer
                // shortly before the draw commands get send to the renderer.
                final boolean updated = updateContentDrawBounds();

                if (mReportNextDraw) {
                    // report next draw overrides setStopped()
                    // This value is re-sync'd to the value of mStopped
                    // in the handling of mReportNextDraw post-draw.
                    mAttachInfo.mThreadedRenderer.setStopped(false);
                }

                if (updated) {
                    requestDrawWindow();
                }

                useAsyncReport = true;

                if (forceDraw) {
                    mAttachInfo.mThreadedRenderer.forceDrawNextFrame();
                }
                mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this);
            } else {
                // If we get here with a disabled & requested hardware renderer, something went
                // wrong (an invalidate posted right before we destroyed the hardware surface
                // for instance) so we should just bail out. Locking the surface with software
                // rendering at this point would lock it forever and prevent hardware renderer
                // from doing its job when it comes back.
                // Before we request a new frame we must however attempt to reinitiliaze the
                // hardware renderer if it's in requested state. This would happen after an
                // eglTerminate() for instance.
                if (mAttachInfo.mThreadedRenderer != null &&
                        !mAttachInfo.mThreadedRenderer.isEnabled() &&
                        mAttachInfo.mThreadedRenderer.isRequested() &&
                        mSurface.isValid()) {

                    try {
                        mAttachInfo.mThreadedRenderer.initializeIfNeeded(
                                mWidth, mHeight, mAttachInfo, mSurface, surfaceInsets);
                    } catch (OutOfResourcesException e) {
                        handleOutOfResourcesException(e);
                        return false;
                    }

                    mFullRedrawNeeded = true;
                    scheduleTraversals();
                    return false;
                }

                if (!drawSoftware(surface, mAttachInfo, xOffset, yOffset,
                        scalingRequired, dirty, surfaceInsets)) {
                    return false;
                }
            }
        }

        if (animating) {
            mFullRedrawNeeded = true;
            scheduleTraversals();
        }
        return useAsyncReport;
    }

    /**
     * @return true if drawing was successful, false if an error occurred
     */
    private boolean drawSoftware(Surface surface, AttachInfo attachInfo, int xoff, int yoff,
            boolean scalingRequired, Rect dirty, Rect surfaceInsets) {

        // Draw with software renderer.
        final Canvas canvas;

        // We already have the offset of surfaceInsets in xoff, yoff and dirty region,
        // therefore we need to add it back when moving the dirty region.
        int dirtyXOffset = xoff;
        int dirtyYOffset = yoff;
        if (surfaceInsets != null) {
            dirtyXOffset += surfaceInsets.left;
            dirtyYOffset += surfaceInsets.top;
        }

        try {
            dirty.offset(-dirtyXOffset, -dirtyYOffset);
            final int left = dirty.left;
            final int top = dirty.top;
            final int right = dirty.right;
            final int bottom = dirty.bottom;

            canvas = mSurface.lockCanvas(dirty);

            // TODO: Do this in native
            canvas.setDensity(mDensity);
        } catch (Surface.OutOfResourcesException e) {
            handleOutOfResourcesException(e);
            return false;
        } catch (IllegalArgumentException e) {
            Log.e(mTag, "Could not lock surface", e);
            // Don't assume this is due to out of memory, it could be
            // something else, and if it is something else then we could
            // kill stuff (or ourself) for no reason.
            mLayoutRequested = true;    // ask wm for a new surface next time.
            return false;
        } finally {
            dirty.offset(dirtyXOffset, dirtyYOffset);  // Reset to the original value.
        }

        try {
            if (DEBUG_ORIENTATION || DEBUG_DRAW) {
                Log.v(mTag, "Surface " + surface + " drawing to bitmap w="
                        + canvas.getWidth() + ", h=" + canvas.getHeight());
                //canvas.drawARGB(255, 255, 0, 0);
            }

            // If this bitmap's format includes an alpha channel, we
            // need to clear it before drawing so that the child will
            // properly re-composite its drawing on a transparent
            // background. This automatically respects the clip/dirty region
            // or
            // If we are applying an offset, we need to clear the area
            // where the offset doesn't appear to avoid having garbage
            // left in the blank areas.
            if (!canvas.isOpaque() || yoff != 0 || xoff != 0) {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }

            dirty.setEmpty();
            mIsAnimating = false;
            mView.mPrivateFlags |= View.PFLAG_DRAWN;

            if (DEBUG_DRAW) {
                Context cxt = mView.getContext();
                Log.i(mTag, "Drawing: package:" + cxt.getPackageName() +
                        ", metrics=" + cxt.getResources().getDisplayMetrics() +
                        ", compatibilityInfo=" + cxt.getResources().getCompatibilityInfo());
            }
            canvas.translate(-xoff, -yoff);
            if (mTranslator != null) {
                mTranslator.translateCanvas(canvas);
            }
            canvas.setScreenDensity(scalingRequired ? mNoncompatDensity : 0);

            mView.draw(canvas);

            drawAccessibilityFocusedDrawableIfNeeded(canvas);
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas);
            } catch (IllegalArgumentException e) {
                Log.e(mTag, "Could not unlock surface", e);
                mLayoutRequested = true;    // ask wm for a new surface next time.
                //noinspection ReturnInsideFinallyBlock
                return false;
            }

            if (LOCAL_LOGV) {
                Log.v(mTag, "Surface " + surface + " unlockCanvasAndPost");
            }
        }
        return true;
    }

    /**
     * We want to draw a highlight around the current accessibility focused.
     * Since adding a style for all possible view is not a viable option we
     * have this specialized drawing method.
     *
     * Note: We are doing this here to be able to draw the highlight for
     *       virtual views in addition to real ones.
     *
     * @param canvas The canvas on which to draw.
     */
    private void drawAccessibilityFocusedDrawableIfNeeded(Canvas canvas) {
        final Rect bounds = mAttachInfo.mTmpInvalRect;
        if (getAccessibilityFocusedRect(bounds)) {
            final Drawable drawable = getAccessibilityFocusedDrawable();
            if (drawable != null) {
                drawable.setBounds(bounds);
                drawable.draw(canvas);
            }
        } else if (mAttachInfo.mAccessibilityFocusDrawable != null) {
            mAttachInfo.mAccessibilityFocusDrawable.setBounds(0, 0, 0, 0);
        }
    }

    private boolean getAccessibilityFocusedRect(Rect bounds) {
        final AccessibilityManager manager = AccessibilityManager.getInstance(mView.mContext);
        if (!manager.isEnabled() || !manager.isTouchExplorationEnabled()) {
            return false;
        }

        final View host = mAccessibilityFocusedHost;
        if (host == null || host.mAttachInfo == null) {
            return false;
        }

        final AccessibilityNodeProvider provider = host.getAccessibilityNodeProvider();
        if (provider == null) {
            host.getBoundsOnScreen(bounds, true);
        } else if (mAccessibilityFocusedVirtualView != null) {
            mAccessibilityFocusedVirtualView.getBoundsInScreen(bounds);
        } else {
            return false;
        }

        // Transform the rect into window-relative coordinates.
        final AttachInfo attachInfo = mAttachInfo;
        bounds.offset(0, attachInfo.mViewRootImpl.mScrollY);
        bounds.offset(-attachInfo.mWindowLeft, -attachInfo.mWindowTop);
        if (!bounds.intersect(0, 0, attachInfo.mViewRootImpl.mWidth,
                attachInfo.mViewRootImpl.mHeight)) {
            // If no intersection, set bounds to empty.
            bounds.setEmpty();
        }
        return !bounds.isEmpty();
    }

    private Drawable getAccessibilityFocusedDrawable() {
        // Lazily load the accessibility focus drawable.
        if (mAttachInfo.mAccessibilityFocusDrawable == null) {
            final TypedValue value = new TypedValue();
            final boolean resolved = mView.mContext.getTheme().resolveAttribute(
                    R.attr.accessibilityFocusedDrawable, value, true);
            if (resolved) {
                mAttachInfo.mAccessibilityFocusDrawable =
                        mView.mContext.getDrawable(value.resourceId);
            }
        }
        // Sets the focus appearance data into the accessibility focus drawable.
        if (mAttachInfo.mAccessibilityFocusDrawable instanceof GradientDrawable) {
            final GradientDrawable drawable =
                    (GradientDrawable) mAttachInfo.mAccessibilityFocusDrawable;
            drawable.setStroke(mAccessibilityManager.getAccessibilityFocusStrokeWidth(),
                    mAccessibilityManager.getAccessibilityFocusColor());
        }

        return mAttachInfo.mAccessibilityFocusDrawable;
    }

    void updateSystemGestureExclusionRectsForView(View view) {
        mGestureExclusionTracker.updateRectsForView(view);
        mHandler.sendEmptyMessage(MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED);
    }

    void systemGestureExclusionChanged() {
        final List<Rect> rectsForWindowManager = mGestureExclusionTracker.computeChangedRects();
        if (rectsForWindowManager != null && mView != null) {
            try {
                mWindowSession.reportSystemGestureExclusionChanged(mWindow, rectsForWindowManager);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mAttachInfo.mTreeObserver
                    .dispatchOnSystemGestureExclusionRectsChanged(rectsForWindowManager);
        }
    }

    /**
     * Set the root-level system gesture exclusion rects. These are added to those provided by
     * the root's view hierarchy.
     */
    public void setRootSystemGestureExclusionRects(@NonNull List<Rect> rects) {
        mGestureExclusionTracker.setRootRects(rects);
        mHandler.sendEmptyMessage(MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED);
    }

    /**
     * Returns the root-level system gesture exclusion rects. These do not include those provided by
     * the root's view hierarchy.
     */
    @NonNull
    public List<Rect> getRootSystemGestureExclusionRects() {
        return mGestureExclusionTracker.getRootRects();
    }

    /**
     * Called from View when the position listener is triggered
     */
    void updateKeepClearRectsForView(View view) {
        mKeepClearRectsTracker.updateRectsForView(view);
        mUnrestrictedKeepClearRectsTracker.updateRectsForView(view);
        mHandler.sendEmptyMessage(MSG_KEEP_CLEAR_RECTS_CHANGED);
    }

    void keepClearRectsChanged() {
        List<Rect> restrictedKeepClearRects = mKeepClearRectsTracker.computeChangedRects();
        List<Rect> unrestrictedKeepClearRects =
                mUnrestrictedKeepClearRectsTracker.computeChangedRects();
        if ((restrictedKeepClearRects != null || unrestrictedKeepClearRects != null)
                && mView != null) {
            if (restrictedKeepClearRects == null) {
                restrictedKeepClearRects = Collections.emptyList();
            }
            if (unrestrictedKeepClearRects == null) {
                unrestrictedKeepClearRects = Collections.emptyList();
            }

            try {
                mWindowSession.reportKeepClearAreasChanged(mWindow, restrictedKeepClearRects,
                        unrestrictedKeepClearRects);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Requests that the root render node is invalidated next time we perform a draw, such that
     * {@link WindowCallbacks#onPostDraw} gets called.
     */
    public void requestInvalidateRootRenderNode() {
        mInvalidateRootRequested = true;
    }

    boolean scrollToRectOrFocus(Rect rectangle, boolean immediate) {
        final Rect ci = mAttachInfo.mContentInsets;
        final Rect vi = mAttachInfo.mVisibleInsets;
        int scrollY = 0;
        boolean handled = false;

        if (vi.left > ci.left || vi.top > ci.top
                || vi.right > ci.right || vi.bottom > ci.bottom) {
            // We'll assume that we aren't going to change the scroll
            // offset, since we want to avoid that unless it is actually
            // going to make the focus visible...  otherwise we scroll
            // all over the place.
            scrollY = mScrollY;
            // We can be called for two different situations: during a draw,
            // to update the scroll position if the focus has changed (in which
            // case 'rectangle' is null), or in response to a
            // requestChildRectangleOnScreen() call (in which case 'rectangle'
            // is non-null and we just want to scroll to whatever that
            // rectangle is).
            final View focus = mView.findFocus();
            if (focus == null) {
                return false;
            }
            View lastScrolledFocus = (mLastScrolledFocus != null) ? mLastScrolledFocus.get() : null;
            if (focus != lastScrolledFocus) {
                // If the focus has changed, then ignore any requests to scroll
                // to a rectangle; first we want to make sure the entire focus
                // view is visible.
                rectangle = null;
            }
            if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Eval scroll: focus=" + focus
                    + " rectangle=" + rectangle + " ci=" + ci
                    + " vi=" + vi);
            if (focus == lastScrolledFocus && !mScrollMayChange && rectangle == null) {
                // Optimization: if the focus hasn't changed since last
                // time, and no layout has happened, then just leave things
                // as they are.
                if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Keeping scroll y="
                        + mScrollY + " vi=" + vi.toShortString());
            } else {
                // We need to determine if the currently focused view is
                // within the visible part of the window and, if not, apply
                // a pan so it can be seen.
                mLastScrolledFocus = new WeakReference<View>(focus);
                mScrollMayChange = false;
                if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Need to scroll?");
                // Try to find the rectangle from the focus view.
                if (focus.getGlobalVisibleRect(mVisRect, null)) {
                    if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Root w="
                            + mView.getWidth() + " h=" + mView.getHeight()
                            + " ci=" + ci.toShortString()
                            + " vi=" + vi.toShortString());
                    if (rectangle == null) {
                        focus.getFocusedRect(mTempRect);
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Focus " + focus
                                + ": focusRect=" + mTempRect.toShortString());
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focus, mTempRect);
                        }
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Focus in window: focusRect="
                                + mTempRect.toShortString()
                                + " visRect=" + mVisRect.toShortString());
                    } else {
                        mTempRect.set(rectangle);
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Request scroll to rect: "
                                + mTempRect.toShortString()
                                + " visRect=" + mVisRect.toShortString());
                    }
                    if (mTempRect.intersect(mVisRect)) {
                        if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                "Focus window visible rect: "
                                + mTempRect.toShortString());
                        if (mTempRect.height() >
                                (mView.getHeight()-vi.top-vi.bottom)) {
                            // If the focus simply is not going to fit, then
                            // best is probably just to leave things as-is.
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Too tall; leaving scrollY=" + scrollY);
                        }
                        // Next, check whether top or bottom is covered based on the non-scrolled
                        // position, and calculate new scrollY (or set it to 0).
                        // We can't keep using mScrollY here. For example mScrollY is non-zero
                        // due to IME, then IME goes away. The current value of mScrollY leaves top
                        // and bottom both visible, but we still need to scroll it back to 0.
                        else if (mTempRect.top < vi.top) {
                            scrollY = mTempRect.top - vi.top;
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Top covered; scrollY=" + scrollY);
                        } else if (mTempRect.bottom > (mView.getHeight()-vi.bottom)) {
                            scrollY = mTempRect.bottom - (mView.getHeight()-vi.bottom);
                            if (DEBUG_INPUT_RESIZE) Log.v(mTag,
                                    "Bottom covered; scrollY=" + scrollY);
                        } else {
                            scrollY = 0;
                        }
                        handled = true;
                    }
                }
            }
        }

        if (scrollY != mScrollY) {
            if (DEBUG_INPUT_RESIZE) Log.v(mTag, "Pan scroll changed: old="
                    + mScrollY + " , new=" + scrollY);
            if (!immediate) {
                if (mScroller == null) {
                    mScroller = new Scroller(mView.getContext());
                }
                mScroller.startScroll(0, mScrollY, 0, scrollY-mScrollY);
            } else if (mScroller != null) {
                mScroller.abortAnimation();
            }
            mScrollY = scrollY;
        }

        return handled;
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public View getAccessibilityFocusedHost() {
        return mAccessibilityFocusedHost;
    }

    /**
     * Get accessibility-focused virtual view. The bounds and sourceNodeId of the returned node is
     * up-to-date while other fields may be stale.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public AccessibilityNodeInfo getAccessibilityFocusedVirtualView() {
        return mAccessibilityFocusedVirtualView;
    }

    void setAccessibilityFocus(View view, AccessibilityNodeInfo node) {
        // If we have a virtual view with accessibility focus we need
        // to clear the focus and invalidate the virtual view bounds.
        if (mAccessibilityFocusedVirtualView != null) {

            AccessibilityNodeInfo focusNode = mAccessibilityFocusedVirtualView;
            View focusHost = mAccessibilityFocusedHost;

            // Wipe the state of the current accessibility focus since
            // the call into the provider to clear accessibility focus
            // will fire an accessibility event which will end up calling
            // this method and we want to have clean state when this
            // invocation happens.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;

            // Clear accessibility focus on the host after clearing state since
            // this method may be reentrant.
            focusHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);

            AccessibilityNodeProvider provider = focusHost.getAccessibilityNodeProvider();
            if (provider != null) {
                // Invalidate the area of the cleared accessibility focus.
                focusNode.getBoundsInParent(mTempRect);
                focusHost.invalidate(mTempRect);
                // Clear accessibility focus in the virtual node.
                final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                        focusNode.getSourceNodeId());
                provider.performAction(virtualNodeId,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
            }
            focusNode.recycle();
        }
        if ((mAccessibilityFocusedHost != null) && (mAccessibilityFocusedHost != view))  {
            // Clear accessibility focus in the view.
            mAccessibilityFocusedHost.clearAccessibilityFocusNoCallbacks(
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }

        // Set the new focus host and node.
        mAccessibilityFocusedHost = view;
        mAccessibilityFocusedVirtualView = node;

        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.invalidateRoot();
        }
    }

    boolean hasPointerCapture() {
        return mPointerCapture;
    }

    void requestPointerCapture(boolean enabled) {
        if (mPointerCapture == enabled) {
            return;
        }
        final IBinder inputToken = getInputToken();
        if (inputToken == null) {
            Log.e(mTag, "No input channel to request Pointer Capture.");
            return;
        }
        InputManager.getInstance().requestPointerCapture(inputToken, enabled);
    }

    private void handlePointerCaptureChanged(boolean hasCapture) {
        if (mPointerCapture == hasCapture) {
            return;
        }
        mPointerCapture = hasCapture;
        if (mView != null) {
            mView.dispatchPointerCaptureChanged(hasCapture);
        }
    }

    private void updateColorModeIfNeeded(@ActivityInfo.ColorMode int colorMode) {
        if (mAttachInfo.mThreadedRenderer == null) {
            return;
        }
        // TODO: Centralize this sanitization? Why do we let setting bad modes?
        // Alternatively, can we just let HWUI figure it out? Do we need to care here?
        if (colorMode != ActivityInfo.COLOR_MODE_A8
                && !getConfiguration().isScreenWideColorGamut()) {
            colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        }
        mAttachInfo.mThreadedRenderer.setColorMode(colorMode);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        if (DEBUG_INPUT_RESIZE) {
            Log.v(mTag, "Request child focus: focus now " + focused);
        }
        checkThread();
        scheduleTraversals();
    }

    @Override
    public void clearChildFocus(View child) {
        if (DEBUG_INPUT_RESIZE) {
            Log.v(mTag, "Clearing child focus");
        }
        checkThread();
        scheduleTraversals();
    }

    @Override
    public ViewParent getParentForAccessibility() {
        return null;
    }

    @Override
    public void focusableViewAvailable(View v) {
        checkThread();
        if (mView != null) {
            if (!mView.hasFocus()) {
                if (sAlwaysAssignFocus || !mAttachInfo.mInTouchMode) {
                    v.requestFocus();
                }
            } else {
                // the one case where will transfer focus away from the current one
                // is if the current view is a view group that prefers to give focus
                // to its children first AND the view is a descendant of it.
                View focused = mView.findFocus();
                if (focused instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) focused;
                    if (group.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                            && isViewDescendantOf(v, focused)) {
                        v.requestFocus();
                    }
                }
            }
        }
    }

    @Override
    public void recomputeViewAttributes(View child) {
        checkThread();
        if (mView == child) {
            mAttachInfo.mRecomputeGlobalAttributes = true;
            if (!mWillDrawSoon) {
                scheduleTraversals();
            }
        }
    }

    void dispatchDetachedFromWindow() {
        // Make sure we free-up insets resources if view never received onWindowFocusLost()
        // because of a die-signal
        mInsetsController.onWindowFocusLost();
        mFirstInputStage.onDetachedFromWindow();
        if (mView != null && mView.mAttachInfo != null) {
            mAttachInfo.mTreeObserver.dispatchOnWindowAttachedChange(false);
            mView.dispatchDetachedFromWindow();
        }

        mAccessibilityInteractionConnectionManager.ensureNoConnection();
        removeSendWindowContentChangedCallback();

        destroyHardwareRenderer();

        setAccessibilityFocus(null, null);

        mInsetsController.cancelExistingAnimations();

        mView.assignParent(null);
        mView = null;
        mAttachInfo.mRootView = null;

        destroySurface();

        if (mInputQueueCallback != null && mInputQueue != null) {
            mInputQueueCallback.onInputQueueDestroyed(mInputQueue);
            mInputQueue.dispose();
            mInputQueueCallback = null;
            mInputQueue = null;
        }
        try {
            mWindowSession.remove(mWindow);
        } catch (RemoteException e) {
        }
        // Dispose receiver would dispose client InputChannel, too. That could send out a socket
        // broken event, so we need to unregister the server InputChannel when removing window to
        // prevent server side receive the event and prompt error.
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }

        unregisterListeners();
        unscheduleTraversals();
    }

    /**
     * Notifies all callbacks that configuration and/or display has changed and updates internal
     * state.
     * @param mergedConfiguration New global and override config in {@link MergedConfiguration}
     *                            container.
     * @param force Flag indicating if we should force apply the config.
     * @param newDisplayId Id of new display if moved, {@link Display#INVALID_DISPLAY} if not
     *                     changed.
     */
    private void performConfigurationChange(MergedConfiguration mergedConfiguration, boolean force,
            int newDisplayId) {
        if (mergedConfiguration == null) {
            throw new IllegalArgumentException("No merged config provided.");
        }

        Configuration globalConfig = mergedConfiguration.getGlobalConfiguration();
        final Configuration overrideConfig = mergedConfiguration.getOverrideConfiguration();
        if (DEBUG_CONFIGURATION) Log.v(mTag,
                "Applying new config to window " + mWindowAttributes.getTitle()
                        + ", globalConfig: " + globalConfig
                        + ", overrideConfig: " + overrideConfig);

        final CompatibilityInfo ci = mDisplay.getDisplayAdjustments().getCompatibilityInfo();
        if (!ci.equals(CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO)) {
            globalConfig = new Configuration(globalConfig);
            ci.applyToConfiguration(mNoncompatDensity, globalConfig);
        }

        synchronized (sConfigCallbacks) {
            for (int i=sConfigCallbacks.size()-1; i>=0; i--) {
                sConfigCallbacks.get(i).onConfigurationChanged(globalConfig);
            }
        }

        mLastReportedMergedConfiguration.setConfiguration(globalConfig, overrideConfig);

        mForceNextConfigUpdate = force;
        if (mActivityConfigCallback != null) {
            // An activity callback is set - notify it about override configuration update.
            // This basically initiates a round trip to ActivityThread and back, which will ensure
            // that corresponding activity and resources are updated before updating inner state of
            // ViewRootImpl. Eventually it will call #updateConfiguration().
            mActivityConfigCallback.onConfigurationChanged(overrideConfig, newDisplayId);
        } else {
            // There is no activity callback - update the configuration right away.
            updateConfiguration(newDisplayId);
        }
        mForceNextConfigUpdate = false;
    }

    /**
     * Update display and views if last applied merged configuration changed.
     * @param newDisplayId Id of new display if moved, {@link Display#INVALID_DISPLAY} otherwise.
     */
    public void updateConfiguration(int newDisplayId) {
        if (mView == null) {
            return;
        }

        // At this point the resources have been updated to
        // have the most recent config, whatever that is.  Use
        // the one in them which may be newer.
        final Resources localResources = mView.getResources();
        final Configuration config = localResources.getConfiguration();

        // Handle move to display.
        if (newDisplayId != INVALID_DISPLAY) {
            onMovedToDisplay(newDisplayId, config);
        }

        // Handle configuration change.
        if (mForceNextConfigUpdate || mLastConfigurationFromResources.diff(config) != 0) {
            // Update the display with new DisplayAdjustments.
            updateInternalDisplay(mDisplay.getDisplayId(), localResources);

            final int lastLayoutDirection = mLastConfigurationFromResources.getLayoutDirection();
            final int currentLayoutDirection = config.getLayoutDirection();
            mLastConfigurationFromResources.setTo(config);
            if (lastLayoutDirection != currentLayoutDirection
                    && mViewLayoutDirectionInitial == View.LAYOUT_DIRECTION_INHERIT) {
                mView.setLayoutDirection(currentLayoutDirection);
            }
            mView.dispatchConfigurationChanged(config);

            // We could have gotten this {@link Configuration} update after we called
            // {@link #performTraversals} with an older {@link Configuration}. As a result, our
            // window frame may be stale. We must ensure the next pass of {@link #performTraversals}
            // catches this.
            mForceNextWindowRelayout = true;
            requestLayout();
        }

        updateForceDarkMode();
    }

    /**
     * Return true if child is an ancestor of parent, (or equal to the parent).
     */
    public static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) {
            return true;
        }

        final ViewParent theParent = child.getParent();
        return (theParent instanceof ViewGroup) && isViewDescendantOf((View) theParent, parent);
    }

    private static void forceLayout(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                forceLayout(group.getChildAt(i));
            }
        }
    }

    private static final int MSG_INVALIDATE = 1;
    private static final int MSG_INVALIDATE_RECT = 2;
    private static final int MSG_DIE = 3;
    private static final int MSG_RESIZED = 4;
    private static final int MSG_RESIZED_REPORT = 5;
    private static final int MSG_WINDOW_FOCUS_CHANGED = 6;
    private static final int MSG_DISPATCH_INPUT_EVENT = 7;
    private static final int MSG_DISPATCH_APP_VISIBILITY = 8;
    private static final int MSG_DISPATCH_GET_NEW_SURFACE = 9;
    private static final int MSG_DISPATCH_KEY_FROM_IME = 11;
    private static final int MSG_DISPATCH_KEY_FROM_AUTOFILL = 12;
    private static final int MSG_CHECK_FOCUS = 13;
    private static final int MSG_CLOSE_SYSTEM_DIALOGS = 14;
    private static final int MSG_DISPATCH_DRAG_EVENT = 15;
    private static final int MSG_DISPATCH_DRAG_LOCATION_EVENT = 16;
    private static final int MSG_DISPATCH_SYSTEM_UI_VISIBILITY = 17;
    private static final int MSG_UPDATE_CONFIGURATION = 18;
    private static final int MSG_PROCESS_INPUT_EVENTS = 19;
    private static final int MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST = 21;
    private static final int MSG_INVALIDATE_WORLD = 22;
    private static final int MSG_WINDOW_MOVED = 23;
    private static final int MSG_SYNTHESIZE_INPUT_EVENT = 24;
    private static final int MSG_DISPATCH_WINDOW_SHOWN = 25;
    private static final int MSG_REQUEST_KEYBOARD_SHORTCUTS = 26;
    private static final int MSG_UPDATE_POINTER_ICON = 27;
    private static final int MSG_POINTER_CAPTURE_CHANGED = 28;
    private static final int MSG_INSETS_CONTROL_CHANGED = 29;
    private static final int MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED = 30;
    private static final int MSG_SHOW_INSETS = 31;
    private static final int MSG_HIDE_INSETS = 32;
    private static final int MSG_REQUEST_SCROLL_CAPTURE = 33;
    private static final int MSG_WINDOW_TOUCH_MODE_CHANGED = 34;
    private static final int MSG_KEEP_CLEAR_RECTS_CHANGED = 35;


    final class ViewRootHandler extends Handler {
        @Override
        public String getMessageName(Message message) {
            switch (message.what) {
                case MSG_INVALIDATE:
                    return "MSG_INVALIDATE";
                case MSG_INVALIDATE_RECT:
                    return "MSG_INVALIDATE_RECT";
                case MSG_DIE:
                    return "MSG_DIE";
                case MSG_RESIZED:
                    return "MSG_RESIZED";
                case MSG_RESIZED_REPORT:
                    return "MSG_RESIZED_REPORT";
                case MSG_WINDOW_FOCUS_CHANGED:
                    return "MSG_WINDOW_FOCUS_CHANGED";
                case MSG_DISPATCH_INPUT_EVENT:
                    return "MSG_DISPATCH_INPUT_EVENT";
                case MSG_DISPATCH_APP_VISIBILITY:
                    return "MSG_DISPATCH_APP_VISIBILITY";
                case MSG_DISPATCH_GET_NEW_SURFACE:
                    return "MSG_DISPATCH_GET_NEW_SURFACE";
                case MSG_DISPATCH_KEY_FROM_IME:
                    return "MSG_DISPATCH_KEY_FROM_IME";
                case MSG_DISPATCH_KEY_FROM_AUTOFILL:
                    return "MSG_DISPATCH_KEY_FROM_AUTOFILL";
                case MSG_CHECK_FOCUS:
                    return "MSG_CHECK_FOCUS";
                case MSG_CLOSE_SYSTEM_DIALOGS:
                    return "MSG_CLOSE_SYSTEM_DIALOGS";
                case MSG_DISPATCH_DRAG_EVENT:
                    return "MSG_DISPATCH_DRAG_EVENT";
                case MSG_DISPATCH_DRAG_LOCATION_EVENT:
                    return "MSG_DISPATCH_DRAG_LOCATION_EVENT";
                case MSG_DISPATCH_SYSTEM_UI_VISIBILITY:
                    return "MSG_DISPATCH_SYSTEM_UI_VISIBILITY";
                case MSG_UPDATE_CONFIGURATION:
                    return "MSG_UPDATE_CONFIGURATION";
                case MSG_PROCESS_INPUT_EVENTS:
                    return "MSG_PROCESS_INPUT_EVENTS";
                case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST:
                    return "MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST";
                case MSG_WINDOW_MOVED:
                    return "MSG_WINDOW_MOVED";
                case MSG_SYNTHESIZE_INPUT_EVENT:
                    return "MSG_SYNTHESIZE_INPUT_EVENT";
                case MSG_DISPATCH_WINDOW_SHOWN:
                    return "MSG_DISPATCH_WINDOW_SHOWN";
                case MSG_UPDATE_POINTER_ICON:
                    return "MSG_UPDATE_POINTER_ICON";
                case MSG_POINTER_CAPTURE_CHANGED:
                    return "MSG_POINTER_CAPTURE_CHANGED";
                case MSG_INSETS_CONTROL_CHANGED:
                    return "MSG_INSETS_CONTROL_CHANGED";
                case MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED:
                    return "MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED";
                case MSG_SHOW_INSETS:
                    return "MSG_SHOW_INSETS";
                case MSG_HIDE_INSETS:
                    return "MSG_HIDE_INSETS";
                case MSG_WINDOW_TOUCH_MODE_CHANGED:
                    return "MSG_WINDOW_TOUCH_MODE_CHANGED";
                case MSG_KEEP_CLEAR_RECTS_CHANGED:
                    return "MSG_KEEP_CLEAR_RECTS_CHANGED";
            }
            return super.getMessageName(message);
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            if (msg.what == MSG_REQUEST_KEYBOARD_SHORTCUTS && msg.obj == null) {
                // Debugging for b/27963013
                throw new NullPointerException(
                        "Attempted to call MSG_REQUEST_KEYBOARD_SHORTCUTS with null receiver:");
            }
            return super.sendMessageAtTime(msg, uptimeMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, getMessageName(msg));
            }
            try {
                handleMessageImpl(msg);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
        }

        private void handleMessageImpl(Message msg) {
            switch (msg.what) {
                case MSG_INVALIDATE:
                    ((View) msg.obj).invalidate();
                    break;
                case MSG_INVALIDATE_RECT:
                    final View.AttachInfo.InvalidateInfo info =
                            (View.AttachInfo.InvalidateInfo) msg.obj;
                    info.target.invalidate(info.left, info.top, info.right, info.bottom);
                    info.recycle();
                    break;
                case MSG_PROCESS_INPUT_EVENTS:
                    mProcessInputEventsScheduled = false;
                    doProcessInputEvents();
                    break;
                case MSG_DISPATCH_APP_VISIBILITY:
                    handleAppVisibility(msg.arg1 != 0);
                    break;
                case MSG_DISPATCH_GET_NEW_SURFACE:
                    handleGetNewSurface();
                    break;
                case MSG_RESIZED:
                case MSG_RESIZED_REPORT: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    mInsetsController.onStateChanged((InsetsState) args.arg3);
                    handleResized(msg.what, args);
                    args.recycle();
                    break;
                }
                case MSG_INSETS_CONTROL_CHANGED: {
                    SomeArgs args = (SomeArgs) msg.obj;

                    // Deliver state change before control change, such that:
                    // a) When gaining control, controller can compare with server state to evaluate
                    // whether it needs to run animation.
                    // b) When loosing control, controller can restore server state by taking last
                    // dispatched state as truth.
                    mInsetsController.onStateChanged((InsetsState) args.arg1);
                    InsetsSourceControl[] controls = (InsetsSourceControl[]) args.arg2;
                    if (mAdded) {
                        mInsetsController.onControlsChanged(controls);
                    } else if (controls != null) {
                        for (InsetsSourceControl control : controls) {
                            if (control != null) {
                                control.release(SurfaceControl::release);
                            }
                        }
                    }
                    args.recycle();
                    break;
                }
                case MSG_SHOW_INSETS: {
                    if (mView == null) {
                        Log.e(TAG,
                                String.format("Calling showInsets(%d,%b) on window that no longer"
                                        + " has views.", msg.arg1, msg.arg2 == 1));
                    }
                    clearLowProfileModeIfNeeded(msg.arg1, msg.arg2 == 1);
                    mInsetsController.show(msg.arg1, msg.arg2 == 1);
                    break;
                }
                case MSG_HIDE_INSETS: {
                    mInsetsController.hide(msg.arg1, msg.arg2 == 1);
                    break;
                }
                case MSG_WINDOW_MOVED:
                    if (mAdded) {
                        final int w = mWinFrame.width();
                        final int h = mWinFrame.height();
                        final int l = msg.arg1;
                        final int t = msg.arg2;
                        mTmpFrames.frame.left = l;
                        mTmpFrames.frame.right = l + w;
                        mTmpFrames.frame.top = t;
                        mTmpFrames.frame.bottom = t + h;
                        setFrame(mTmpFrames.frame);
                        maybeHandleWindowMove(mWinFrame);
                    }
                    break;
                case MSG_WINDOW_FOCUS_CHANGED: {
                    handleWindowFocusChanged();
                } break;
                case MSG_WINDOW_TOUCH_MODE_CHANGED: {
                    handleWindowTouchModeChanged();
                } break;
                case MSG_DIE: {
                    doDie();
                } break;
                case MSG_DISPATCH_INPUT_EVENT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    InputEvent event = (InputEvent) args.arg1;
                    InputEventReceiver receiver = (InputEventReceiver) args.arg2;
                    enqueueInputEvent(event, receiver, 0, true);
                    args.recycle();
                } break;
                case MSG_SYNTHESIZE_INPUT_EVENT: {
                    InputEvent event = (InputEvent) msg.obj;
                    enqueueInputEvent(event, null, QueuedInputEvent.FLAG_UNHANDLED, true);
                } break;
                case MSG_DISPATCH_KEY_FROM_IME: {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Dispatching key " + msg.obj + " from IME to " + mView);
                    }
                    KeyEvent event = (KeyEvent) msg.obj;
                    if ((event.getFlags() & KeyEvent.FLAG_FROM_SYSTEM) != 0) {
                        // The IME is trying to say this event is from the
                        // system!  Bad bad bad!
                        //noinspection UnusedAssignment
                        event = KeyEvent.changeFlags(event,
                                event.getFlags() & ~KeyEvent.FLAG_FROM_SYSTEM);
                    }
                    enqueueInputEvent(event, null, QueuedInputEvent.FLAG_DELIVER_POST_IME, true);
                } break;
                case MSG_DISPATCH_KEY_FROM_AUTOFILL: {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "Dispatching key " + msg.obj + " from Autofill to " + mView);
                    }
                    KeyEvent event = (KeyEvent) msg.obj;
                    enqueueInputEvent(event, null, 0, true);
                } break;
                case MSG_CHECK_FOCUS: {
                    getImeFocusController().checkFocus(false, true);
                } break;
                case MSG_CLOSE_SYSTEM_DIALOGS: {
                    if (mView != null) {
                        mView.onCloseSystemDialogs((String) msg.obj);
                    }
                } break;
                case MSG_DISPATCH_DRAG_EVENT: {
                } // fall through
                case MSG_DISPATCH_DRAG_LOCATION_EVENT: {
                    DragEvent event = (DragEvent) msg.obj;
                    // only present when this app called startDrag()
                    event.mLocalState = mLocalDragState;
                    handleDragEvent(event);
                } break;
                case MSG_DISPATCH_SYSTEM_UI_VISIBILITY: {
                    handleDispatchSystemUiVisibilityChanged((SystemUiVisibilityInfo) msg.obj);
                } break;
                case MSG_UPDATE_CONFIGURATION: {
                    Configuration config = (Configuration) msg.obj;
                    if (config.isOtherSeqNewer(
                            mLastReportedMergedConfiguration.getMergedConfiguration())) {
                        // If we already have a newer merged config applied - use its global part.
                        config = mLastReportedMergedConfiguration.getGlobalConfiguration();
                    }

                    // Use the newer global config and last reported override config.
                    mPendingMergedConfiguration.setConfiguration(config,
                            mLastReportedMergedConfiguration.getOverrideConfiguration());

                    performConfigurationChange(new MergedConfiguration(mPendingMergedConfiguration),
                            false /* force */, INVALID_DISPLAY /* same display */);
                } break;
                case MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST: {
                    setAccessibilityFocus(null, null);
                } break;
                case MSG_INVALIDATE_WORLD: {
                    if (mView != null) {
                        invalidateWorld(mView);
                    }
                } break;
                case MSG_DISPATCH_WINDOW_SHOWN: {
                    handleDispatchWindowShown();
                } break;
                case MSG_REQUEST_KEYBOARD_SHORTCUTS: {
                    final IResultReceiver receiver = (IResultReceiver) msg.obj;
                    final int deviceId = msg.arg1;
                    handleRequestKeyboardShortcuts(receiver, deviceId);
                } break;
                case MSG_UPDATE_POINTER_ICON: {
                    MotionEvent event = (MotionEvent) msg.obj;
                    resetPointerIcon(event);
                } break;
                case MSG_POINTER_CAPTURE_CHANGED: {
                    final boolean hasCapture = msg.arg1 != 0;
                    handlePointerCaptureChanged(hasCapture);
                } break;
                case MSG_SYSTEM_GESTURE_EXCLUSION_CHANGED: {
                    systemGestureExclusionChanged();
                }   break;
                case MSG_KEEP_CLEAR_RECTS_CHANGED: {
                    keepClearRectsChanged();
                }   break;
                case MSG_REQUEST_SCROLL_CAPTURE:
                    handleScrollCaptureRequest((IScrollCaptureResponseListener) msg.obj);
                    break;
            }
        }
    }

    final ViewRootHandler mHandler = new ViewRootHandler();

    /**
     * Something in the current window tells us we need to change the touch mode.  For
     * example, we are not in touch mode, and the user touches the screen.
     *
     * If the touch mode has changed, tell the window manager, and handle it locally.
     *
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    boolean ensureTouchMode(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchMode(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);
        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        // tell the window manager
        try {
            mWindowSession.setInTouchMode(inTouchMode);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // handle the change
        return ensureTouchModeLocally(inTouchMode);
    }

    /**
     * Ensure that the touch mode for this window is set, and if it is changing,
     * take the appropriate action.
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    private boolean ensureTouchModeLocally(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchModeLocally(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);

        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        mAttachInfo.mInTouchMode = inTouchMode;
        mAttachInfo.mTreeObserver.dispatchOnTouchModeChanged(inTouchMode);

        return (inTouchMode) ? enterTouchMode() : leaveTouchMode();
    }

    private boolean enterTouchMode() {
        if (mView != null && mView.hasFocus()) {
            // note: not relying on mFocusedView here because this could
            // be when the window is first being added, and mFocused isn't
            // set yet.
            final View focused = mView.findFocus();
            if (focused != null && !focused.isFocusableInTouchMode()) {
                final ViewGroup ancestorToTakeFocus = findAncestorToTakeFocusInTouchMode(focused);
                if (ancestorToTakeFocus != null) {
                    // there is an ancestor that wants focus after its
                    // descendants that is focusable in touch mode.. give it
                    // focus
                    return ancestorToTakeFocus.requestFocus();
                } else {
                    // There's nothing to focus. Clear and propagate through the
                    // hierarchy, but don't attempt to place new focus.
                    focused.clearFocusInternal(null, true, false);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Find an ancestor of focused that wants focus after its descendants and is
     * focusable in touch mode.
     * @param focused The currently focused view.
     * @return An appropriate view, or null if no such view exists.
     */
    private static ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
        ViewParent parent = focused.getParent();
        while (parent instanceof ViewGroup) {
            final ViewGroup vgParent = (ViewGroup) parent;
            if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS
                    && vgParent.isFocusableInTouchMode()) {
                return vgParent;
            }
            if (vgParent.isRootNamespace()) {
                return null;
            } else {
                parent = vgParent.getParent();
            }
        }
        return null;
    }

    private boolean leaveTouchMode() {
        if (mView != null) {
            if (mView.hasFocus()) {
                View focusedView = mView.findFocus();
                if (!(focusedView instanceof ViewGroup)) {
                    // some view has focus, let it keep it
                    return false;
                } else if (((ViewGroup) focusedView).getDescendantFocusability() !=
                        ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    // some view group has focus, and doesn't prefer its children
                    // over itself for focus, so let them keep it.
                    return false;
                }
            }

            // find the best view to give focus to in this brave new non-touch-mode
            // world
            return mView.restoreDefaultFocus();
        }
        return false;
    }

    /**
     * Base class for implementing a stage in the chain of responsibility
     * for processing input events.
     * <p>
     * Events are delivered to the stage by the {@link #deliver} method.  The stage
     * then has the choice of finishing the event or forwarding it to the next stage.
     * </p>
     */
    abstract class InputStage {
        private final InputStage mNext;

        protected static final int FORWARD = 0;
        protected static final int FINISH_HANDLED = 1;
        protected static final int FINISH_NOT_HANDLED = 2;

        private String mTracePrefix;

        /**
         * Creates an input stage.
         * @param next The next stage to which events should be forwarded.
         */
        public InputStage(InputStage next) {
            mNext = next;
        }

        /**
         * Delivers an event to be processed.
         */
        public final void deliver(QueuedInputEvent q) {
            if ((q.mFlags & QueuedInputEvent.FLAG_FINISHED) != 0) {
                forward(q);
            } else if (shouldDropInputEvent(q)) {
                finish(q, false);
            } else {
                traceEvent(q, Trace.TRACE_TAG_VIEW);
                final int result;
                try {
                    result = onProcess(q);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
                apply(q, result);
            }
        }

        /**
         * Marks the input event as finished then forwards it to the next stage.
         */
        protected void finish(QueuedInputEvent q, boolean handled) {
            q.mFlags |= QueuedInputEvent.FLAG_FINISHED;
            if (handled) {
                q.mFlags |= QueuedInputEvent.FLAG_FINISHED_HANDLED;
            }
            forward(q);
        }

        /**
         * Forwards the event to the next stage.
         */
        protected void forward(QueuedInputEvent q) {
            onDeliverToNext(q);
        }

        /**
         * Applies a result code from {@link #onProcess} to the specified event.
         */
        protected void apply(QueuedInputEvent q, int result) {
            if (result == FORWARD) {
                forward(q);
            } else if (result == FINISH_HANDLED) {
                finish(q, true);
            } else if (result == FINISH_NOT_HANDLED) {
                finish(q, false);
            } else {
                throw new IllegalArgumentException("Invalid result: " + result);
            }
        }

        /**
         * Called when an event is ready to be processed.
         * @return A result code indicating how the event was handled.
         */
        protected int onProcess(QueuedInputEvent q) {
            return FORWARD;
        }

        /**
         * Called when an event is being delivered to the next stage.
         */
        protected void onDeliverToNext(QueuedInputEvent q) {
            if (DEBUG_INPUT_STAGES) {
                Log.v(mTag, "Done with " + getClass().getSimpleName() + ". " + q);
            }
            if (mNext != null) {
                mNext.deliver(q);
            } else {
                finishInputEvent(q);
            }
        }

        protected void onWindowFocusChanged(boolean hasWindowFocus) {
            if (mNext != null) {
                mNext.onWindowFocusChanged(hasWindowFocus);
            }
        }

        protected void onDetachedFromWindow() {
            if (mNext != null) {
                mNext.onDetachedFromWindow();
            }
        }

        protected boolean shouldDropInputEvent(QueuedInputEvent q) {
            if (mView == null || !mAdded) {
                Slog.w(mTag, "Dropping event due to root view being removed: " + q.mEvent);
                return true;
            }

            // Find a reason for dropping or canceling the event.
            final String reason;
            if (!mAttachInfo.mHasWindowFocus
                    && !q.mEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                    && !isAutofillUiShowing()) {
                // This is a non-pointer event and the window doesn't currently have input focus
                // This could be an event that came back from the previous stage
                // but the window has lost focus or stopped in the meantime.
                reason = "no window focus";
            } else if (mStopped) {
                reason = "window is stopped";
            } else if (mIsAmbientMode
                    && !q.mEvent.isFromSource(InputDevice.SOURCE_CLASS_BUTTON)) {
                reason = "non-button event in ambient mode";
            } else if (mPausedForTransition && !isBack(q.mEvent)) {
                reason = "paused for transition";
            } else {
                // Most common path: no reason to drop or cancel the event
                return false;
            }

            if (isTerminalInputEvent(q.mEvent)) {
                // Don't drop terminal input events, however mark them as canceled.
                q.mEvent.cancel();
                Slog.w(mTag, "Cancelling event (" + reason + "):" + q.mEvent);
                return false;
            }

            // Drop non-terminal input events.
            Slog.w(mTag, "Dropping event (" + reason + "):" + q.mEvent);
            return true;
        }

        void dump(String prefix, PrintWriter writer) {
            if (mNext != null) {
                mNext.dump(prefix, writer);
            }
        }

        boolean isBack(InputEvent event) {
            if (event instanceof KeyEvent) {
                return ((KeyEvent) event).getKeyCode() == KeyEvent.KEYCODE_BACK;
            } else {
                return false;
            }
        }

        private void traceEvent(QueuedInputEvent q, long traceTag) {
            if (!Trace.isTagEnabled(traceTag)) {
                return;
            }

            if (mTracePrefix == null) {
                mTracePrefix = getClass().getSimpleName();
            }
            Trace.traceBegin(traceTag, mTracePrefix + " id=0x"
                    + Integer.toHexString(q.mEvent.getId()));
        }
    }

    /**
     * Base class for implementing an input pipeline stage that supports
     * asynchronous and out-of-order processing of input events.
     * <p>
     * In addition to what a normal input stage can do, an asynchronous
     * input stage may also defer an input event that has been delivered to it
     * and finish or forward it later.
     * </p>
     */
    abstract class AsyncInputStage extends InputStage {
        private final String mTraceCounter;

        private QueuedInputEvent mQueueHead;
        private QueuedInputEvent mQueueTail;
        private int mQueueLength;

        protected static final int DEFER = 3;

        /**
         * Creates an asynchronous input stage.
         * @param next The next stage to which events should be forwarded.
         * @param traceCounter The name of a counter to record the size of
         * the queue of pending events.
         */
        public AsyncInputStage(InputStage next, String traceCounter) {
            super(next);
            mTraceCounter = traceCounter;
        }

        /**
         * Marks the event as deferred, which is to say that it will be handled
         * asynchronously.  The caller is responsible for calling {@link #forward}
         * or {@link #finish} later when it is done handling the event.
         */
        protected void defer(QueuedInputEvent q) {
            q.mFlags |= QueuedInputEvent.FLAG_DEFERRED;
            enqueue(q);
        }

        @Override
        protected void forward(QueuedInputEvent q) {
            // Clear the deferred flag.
            q.mFlags &= ~QueuedInputEvent.FLAG_DEFERRED;

            // Fast path if the queue is empty.
            QueuedInputEvent curr = mQueueHead;
            if (curr == null) {
                super.forward(q);
                return;
            }

            // Determine whether the event must be serialized behind any others
            // before it can be delivered to the next stage.  This is done because
            // deferred events might be handled out of order by the stage.
            final int deviceId = q.mEvent.getDeviceId();
            QueuedInputEvent prev = null;
            boolean blocked = false;
            while (curr != null && curr != q) {
                if (!blocked && deviceId == curr.mEvent.getDeviceId()) {
                    blocked = true;
                }
                prev = curr;
                curr = curr.mNext;
            }

            // If the event is blocked, then leave it in the queue to be delivered later.
            // Note that the event might not yet be in the queue if it was not previously
            // deferred so we will enqueue it if needed.
            if (blocked) {
                if (curr == null) {
                    enqueue(q);
                }
                return;
            }

            // The event is not blocked.  Deliver it immediately.
            if (curr != null) {
                curr = curr.mNext;
                dequeue(q, prev);
            }
            super.forward(q);

            // Dequeuing this event may have unblocked successors.  Deliver them.
            while (curr != null) {
                if (deviceId == curr.mEvent.getDeviceId()) {
                    if ((curr.mFlags & QueuedInputEvent.FLAG_DEFERRED) != 0) {
                        break;
                    }
                    QueuedInputEvent next = curr.mNext;
                    dequeue(curr, prev);
                    super.forward(curr);
                    curr = next;
                } else {
                    prev = curr;
                    curr = curr.mNext;
                }
            }
        }

        @Override
        protected void apply(QueuedInputEvent q, int result) {
            if (result == DEFER) {
                defer(q);
            } else {
                super.apply(q, result);
            }
        }

        private void enqueue(QueuedInputEvent q) {
            if (mQueueTail == null) {
                mQueueHead = q;
                mQueueTail = q;
            } else {
                mQueueTail.mNext = q;
                mQueueTail = q;
            }

            mQueueLength += 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mTraceCounter, mQueueLength);
        }

        private void dequeue(QueuedInputEvent q, QueuedInputEvent prev) {
            if (prev == null) {
                mQueueHead = q.mNext;
            } else {
                prev.mNext = q.mNext;
            }
            if (mQueueTail == q) {
                mQueueTail = prev;
            }
            q.mNext = null;

            mQueueLength -= 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mTraceCounter, mQueueLength);
        }

        @Override
        void dump(String prefix, PrintWriter writer) {
            writer.print(prefix);
            writer.print(getClass().getName());
            writer.print(": mQueueLength=");
            writer.println(mQueueLength);

            super.dump(prefix, writer);
        }
    }

    /**
     * Delivers pre-ime input events to a native activity.
     * Does not support pointer events.
     */
    final class NativePreImeInputStage extends AsyncInputStage
            implements InputQueue.FinishedInputEventCallback {
        public NativePreImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (mInputQueue != null && q.mEvent instanceof KeyEvent) {
                mInputQueue.sendInputEvent(q.mEvent, q, true, this);
                return DEFER;
            }
            return FORWARD;
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent)token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Delivers pre-ime input events to the view hierarchy.
     * Does not support pointer events.
     */
    final class ViewPreImeInputStage extends InputStage {
        public ViewPreImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                return processKeyEvent(q);
            }
            return FORWARD;
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent)q.mEvent;
            if (mView.dispatchKeyEventPreIme(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }
    }

    /**
     * Delivers input events to the ime.
     * Does not support pointer events.
     */
    final class ImeInputStage extends AsyncInputStage
            implements InputMethodManager.FinishedInputEventCallback {
        public ImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            final int result = mImeFocusController.onProcessImeInputStage(
                    q, q.mEvent, mWindowAttributes, this);
            switch (result) {
                case InputMethodManager.DISPATCH_IN_PROGRESS:
                    // callback will be invoked later
                    return DEFER;
                case InputMethodManager.DISPATCH_NOT_HANDLED:
                    // The IME could not handle it, so skip along to the next InputStage
                    return FORWARD;
                case InputMethodManager.DISPATCH_HANDLED:
                    return FINISH_HANDLED;
                default:
                    throw new IllegalStateException("Unexpected result=" + result);
            }
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent)token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Performs early processing of post-ime input events.
     */
    final class EarlyPostImeInputStage extends InputStage {
        public EarlyPostImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                return processKeyEvent(q);
            } else if (q.mEvent instanceof MotionEvent) {
                return processMotionEvent(q);
            }
            return FORWARD;
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent)q.mEvent;

            if (mAttachInfo.mTooltipHost != null) {
                mAttachInfo.mTooltipHost.handleTooltipKey(event);
            }

            // If the key's purpose is to exit touch mode then we consume it
            // and consider it handled.
            if (checkForLeavingTouchModeAndConsume(event)) {
                return FINISH_HANDLED;
            }

            // Make sure the fallback event policy sees all keys that will be
            // delivered to the view hierarchy.
            mFallbackEventHandler.preDispatchKeyEvent(event);
            return FORWARD;
        }

        private int processMotionEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent) q.mEvent;

            if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
                return processPointerEvent(q);
            }

            // If the motion event is from an absolute position device, exit touch mode
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_SCROLL) {
                if (event.isFromSource(InputDevice.SOURCE_CLASS_POSITION)) {
                    ensureTouchMode(false);
                }
            }
            return FORWARD;
        }

        private int processPointerEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent)q.mEvent;

            // Translate the pointer event for compatibility, if needed.
            if (mTranslator != null) {
                mTranslator.translateEventInScreenToAppWindow(event);
            }

            // Enter touch mode on down or scroll from any type of a device.
            final int action = event.getAction();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_SCROLL) {
                ensureTouchMode(true);
            }

            if (action == MotionEvent.ACTION_DOWN) {
                // Upon motion event within app window, close autofill ui.
                AutofillManager afm = getAutofillManager();
                if (afm != null) {
                    afm.requestHideFillUi();
                }
            }

            if (action == MotionEvent.ACTION_DOWN && mAttachInfo.mTooltipHost != null) {
                mAttachInfo.mTooltipHost.hideTooltip();
            }

            // Offset the scroll position.
            if (mCurScrollY != 0) {
                event.offsetLocation(0, mCurScrollY);
            }

            // Remember the touch position for possible drag-initiation.
            if (event.isTouchEvent()) {
                mLastTouchPoint.x = event.getRawX();
                mLastTouchPoint.y = event.getRawY();
                mLastTouchSource = event.getSource();
            }
            return FORWARD;
        }
    }

    /**
     * Delivers post-ime input events to a native activity.
     */
    final class NativePostImeInputStage extends AsyncInputStage
            implements InputQueue.FinishedInputEventCallback {
        public NativePostImeInputStage(InputStage next, String traceCounter) {
            super(next, traceCounter);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (mInputQueue != null) {
                mInputQueue.sendInputEvent(q.mEvent, q, false, this);
                return DEFER;
            }
            return FORWARD;
        }

        @Override
        public void onFinishedInputEvent(Object token, boolean handled) {
            QueuedInputEvent q = (QueuedInputEvent)token;
            if (handled) {
                finish(q, true);
                return;
            }
            forward(q);
        }
    }

    /**
     * Delivers post-ime input events to the view hierarchy.
     */
    final class ViewPostImeInputStage extends InputStage {
        public ViewPostImeInputStage(InputStage next) {
            super(next);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            if (q.mEvent instanceof KeyEvent) {
                return processKeyEvent(q);
            } else {
                final int source = q.mEvent.getSource();
                if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
                    return processPointerEvent(q);
                } else if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    return processTrackballEvent(q);
                } else {
                    return processGenericMotionEvent(q);
                }
            }
        }

        @Override
        protected void onDeliverToNext(QueuedInputEvent q) {
            if (mUnbufferedInputDispatch
                    && q.mEvent instanceof MotionEvent
                    && ((MotionEvent)q.mEvent).isTouchEvent()
                    && isTerminalInputEvent(q.mEvent)) {
                mUnbufferedInputDispatch = false;
                scheduleConsumeBatchedInput();
            }
            super.onDeliverToNext(q);
        }

        private boolean performFocusNavigation(KeyEvent event) {
            int direction = 0;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_LEFT;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_RIGHT;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_UP;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_DOWN;
                    }
                    break;
                case KeyEvent.KEYCODE_TAB:
                    if (event.hasNoModifiers()) {
                        direction = View.FOCUS_FORWARD;
                    } else if (event.hasModifiers(KeyEvent.META_SHIFT_ON)) {
                        direction = View.FOCUS_BACKWARD;
                    }
                    break;
            }
            if (direction != 0) {
                View focused = mView.findFocus();
                if (focused != null) {
                    View v = focused.focusSearch(direction);
                    if (v != null && v != focused) {
                        // do the math the get the interesting rect
                        // of previous focused into the coord system of
                        // newly focused view
                        focused.getFocusedRect(mTempRect);
                        if (mView instanceof ViewGroup) {
                            ((ViewGroup) mView).offsetDescendantRectToMyCoords(
                                    focused, mTempRect);
                            ((ViewGroup) mView).offsetRectIntoDescendantCoords(
                                    v, mTempRect);
                        }
                        if (v.requestFocus(direction, mTempRect)) {
                            boolean isFastScrolling = event.getRepeatCount() > 0;
                            playSoundEffect(
                                    SoundEffectConstants.getConstantForFocusDirection(direction,
                                            isFastScrolling));
                            return true;
                        }
                    }

                    // Give the focused view a last chance to handle the dpad key.
                    if (mView.dispatchUnhandledMove(focused, direction)) {
                        return true;
                    }
                } else {
                    if (mView.restoreDefaultFocus()) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean performKeyboardGroupNavigation(int direction) {
            final View focused = mView.findFocus();
            if (focused == null && mView.restoreDefaultFocus()) {
                return true;
            }
            View cluster = focused == null ? keyboardNavigationClusterSearch(null, direction)
                    : focused.keyboardNavigationClusterSearch(null, direction);

            // Since requestFocus only takes "real" focus directions (and therefore also
            // restoreFocusInCluster), convert forward/backward focus into FOCUS_DOWN.
            int realDirection = direction;
            if (direction == View.FOCUS_FORWARD || direction == View.FOCUS_BACKWARD) {
                realDirection = View.FOCUS_DOWN;
            }

            if (cluster != null && cluster.isRootNamespace()) {
                // the default cluster. Try to find a non-clustered view to focus.
                if (cluster.restoreFocusNotInCluster()) {
                    playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
                    return true;
                }
                // otherwise skip to next actual cluster
                cluster = keyboardNavigationClusterSearch(null, direction);
            }

            if (cluster != null && cluster.restoreFocusInCluster(realDirection)) {
                playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
                return true;
            }

            return false;
        }

        private int processKeyEvent(QueuedInputEvent q) {
            final KeyEvent event = (KeyEvent)q.mEvent;

            if (mUnhandledKeyManager.preViewDispatch(event)) {
                return FINISH_HANDLED;
            }

            // Deliver the key to the view hierarchy.
            if (mView.dispatchKeyEvent(event)) {
                return FINISH_HANDLED;
            }

            if (shouldDropInputEvent(q)) {
                return FINISH_NOT_HANDLED;
            }

            if (isBack(event)
                    && mContext != null
                    && WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(mContext)) {
                // Invoke the appropriate {@link OnBackInvokedCallback} if the new back
                // navigation should be used, and the key event is not handled by anything else.
                OnBackInvokedCallback topCallback =
                        getOnBackInvokedDispatcher().getTopCallback();
                if (topCallback != null) {
                    topCallback.onBackInvoked();
                    return FINISH_HANDLED;
                }
            }

            // This dispatch is for windows that don't have a Window.Callback. Otherwise,
            // the Window.Callback usually will have already called this (see
            // DecorView.superDispatchKeyEvent) leaving this call a no-op.
            if (mUnhandledKeyManager.dispatch(mView, event)) {
                return FINISH_HANDLED;
            }

            int groupNavigationDirection = 0;

            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_TAB) {
                if (KeyEvent.metaStateHasModifiers(event.getMetaState(), KeyEvent.META_META_ON)) {
                    groupNavigationDirection = View.FOCUS_FORWARD;
                } else if (KeyEvent.metaStateHasModifiers(event.getMetaState(),
                        KeyEvent.META_META_ON | KeyEvent.META_SHIFT_ON)) {
                    groupNavigationDirection = View.FOCUS_BACKWARD;
                }
            }

            // If a modifier is held, try to interpret the key as a shortcut.
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && !KeyEvent.metaStateHasNoModifiers(event.getMetaState())
                    && event.getRepeatCount() == 0
                    && !KeyEvent.isModifierKey(event.getKeyCode())
                    && groupNavigationDirection == 0) {
                if (mView.dispatchKeyShortcutEvent(event)) {
                    return FINISH_HANDLED;
                }
                if (shouldDropInputEvent(q)) {
                    return FINISH_NOT_HANDLED;
                }
            }

            // Apply the fallback event policy.
            if (mFallbackEventHandler.dispatchKeyEvent(event)) {
                return FINISH_HANDLED;
            }
            if (shouldDropInputEvent(q)) {
                return FINISH_NOT_HANDLED;
            }

            // Handle automatic focus changes.
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (groupNavigationDirection != 0) {
                    if (performKeyboardGroupNavigation(groupNavigationDirection)) {
                        return FINISH_HANDLED;
                    }
                } else {
                    if (performFocusNavigation(event)) {
                        return FINISH_HANDLED;
                    }
                }
            }
            return FORWARD;
        }

        private int processPointerEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent)q.mEvent;
            mHandwritingInitiator.onTouchEvent(event);

            mAttachInfo.mUnbufferedDispatchRequested = false;
            mAttachInfo.mHandlingPointerEvent = true;
            boolean handled = mView.dispatchPointerEvent(event);
            maybeUpdatePointerIcon(event);
            maybeUpdateTooltip(event);
            mAttachInfo.mHandlingPointerEvent = false;
            if (mAttachInfo.mUnbufferedDispatchRequested && !mUnbufferedInputDispatch) {
                mUnbufferedInputDispatch = true;
                if (mConsumeBatchedInputScheduled) {
                    scheduleConsumeBatchedInputImmediately();
                }
            }
            return handled ? FINISH_HANDLED : FORWARD;
        }

        private void maybeUpdatePointerIcon(MotionEvent event) {
            if (event.getPointerCount() == 1 && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                        || event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) {
                    // Other apps or the window manager may change the icon type outside of
                    // this app, therefore the icon type has to be reset on enter/exit event.
                    mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
                }

                if (event.getActionMasked() != MotionEvent.ACTION_HOVER_EXIT) {
                    if (!updatePointerIcon(event) &&
                            event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
                        mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
                    }
                }
            }
        }

        private int processTrackballEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent)q.mEvent;

            if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)) {
                if (!hasPointerCapture() || mView.dispatchCapturedPointerEvent(event)) {
                    return FINISH_HANDLED;
                }
            }

            if (mView.dispatchTrackballEvent(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }

        private int processGenericMotionEvent(QueuedInputEvent q) {
            final MotionEvent event = (MotionEvent)q.mEvent;

            if (event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) {
                if (hasPointerCapture() && mView.dispatchCapturedPointerEvent(event)) {
                    return FINISH_HANDLED;
                }
            }

            // Deliver the event to the view.
            if (mView.dispatchGenericMotionEvent(event)) {
                return FINISH_HANDLED;
            }
            return FORWARD;
        }
    }

    private void resetPointerIcon(MotionEvent event) {
        mPointerIconType = PointerIcon.TYPE_NOT_SPECIFIED;
        updatePointerIcon(event);
    }

    private boolean updatePointerIcon(MotionEvent event) {
        final int pointerIndex = 0;
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);
        if (mView == null) {
            // E.g. click outside a popup to dismiss it
            Slog.d(mTag, "updatePointerIcon called after view was removed");
            return false;
        }
        if (x < 0 || x >= mView.getWidth() || y < 0 || y >= mView.getHeight()) {
            // E.g. when moving window divider with mouse
            Slog.d(mTag, "updatePointerIcon called with position out of bounds");
            return false;
        }
        final PointerIcon pointerIcon = mView.onResolvePointerIcon(event, pointerIndex);
        final int pointerType = (pointerIcon != null) ?
                pointerIcon.getType() : PointerIcon.TYPE_DEFAULT;

        if (mPointerIconType != pointerType) {
            mPointerIconType = pointerType;
            mCustomPointerIcon = null;
            if (mPointerIconType != PointerIcon.TYPE_CUSTOM) {
                InputManager.getInstance().setPointerIconType(pointerType);
                return true;
            }
        }
        if (mPointerIconType == PointerIcon.TYPE_CUSTOM &&
                !pointerIcon.equals(mCustomPointerIcon)) {
            mCustomPointerIcon = pointerIcon;
            InputManager.getInstance().setCustomPointerIcon(mCustomPointerIcon);
        }
        return true;
    }

    private void maybeUpdateTooltip(MotionEvent event) {
        if (event.getPointerCount() != 1) {
            return;
        }
        final int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_HOVER_ENTER
                && action != MotionEvent.ACTION_HOVER_MOVE
                && action != MotionEvent.ACTION_HOVER_EXIT) {
            return;
        }
        AccessibilityManager manager = AccessibilityManager.getInstance(mContext);
        if (manager.isEnabled() && manager.isTouchExplorationEnabled()) {
            return;
        }
        if (mView == null) {
            Slog.d(mTag, "maybeUpdateTooltip called after view was removed");
            return;
        }
        mView.dispatchTooltipHoverEvent(event);
    }

    @Nullable
    private View getFocusedViewOrNull() {
        return mView != null ? mView.findFocus() : null;
    }

    /**
     * Performs synthesis of new input events from unhandled input events.
     */
    final class SyntheticInputStage extends InputStage {
        private final SyntheticTrackballHandler mTrackball = new SyntheticTrackballHandler();
        private final SyntheticJoystickHandler mJoystick = new SyntheticJoystickHandler();
        private final SyntheticTouchNavigationHandler mTouchNavigation =
                new SyntheticTouchNavigationHandler();
        private final SyntheticKeyboardHandler mKeyboard = new SyntheticKeyboardHandler();

        public SyntheticInputStage() {
            super(null);
        }

        @Override
        protected int onProcess(QueuedInputEvent q) {
            q.mFlags |= QueuedInputEvent.FLAG_RESYNTHESIZED;
            if (q.mEvent instanceof MotionEvent) {
                final MotionEvent event = (MotionEvent)q.mEvent;
                final int source = event.getSource();
                if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                    mTrackball.process(event);
                    return FINISH_HANDLED;
                } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                    mJoystick.process(event);
                    return FINISH_HANDLED;
                } else if ((source & InputDevice.SOURCE_TOUCH_NAVIGATION)
                        == InputDevice.SOURCE_TOUCH_NAVIGATION) {
                    mTouchNavigation.process(event);
                    return FINISH_HANDLED;
                }
            } else if ((q.mFlags & QueuedInputEvent.FLAG_UNHANDLED) != 0) {
                mKeyboard.process((KeyEvent)q.mEvent);
                return FINISH_HANDLED;
            }

            return FORWARD;
        }

        @Override
        protected void onDeliverToNext(QueuedInputEvent q) {
            if ((q.mFlags & QueuedInputEvent.FLAG_RESYNTHESIZED) == 0) {
                // Cancel related synthetic events if any prior stage has handled the event.
                if (q.mEvent instanceof MotionEvent) {
                    final MotionEvent event = (MotionEvent)q.mEvent;
                    final int source = event.getSource();
                    if ((source & InputDevice.SOURCE_CLASS_TRACKBALL) != 0) {
                        mTrackball.cancel();
                    } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
                        mJoystick.cancel();
                    } else if ((source & InputDevice.SOURCE_TOUCH_NAVIGATION)
                            == InputDevice.SOURCE_TOUCH_NAVIGATION) {
                        mTouchNavigation.cancel(event);
                    }
                }
            }
            super.onDeliverToNext(q);
        }

        @Override
        protected void onWindowFocusChanged(boolean hasWindowFocus) {
            if (!hasWindowFocus) {
                mJoystick.cancel();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            mJoystick.cancel();
        }
    }

    /**
     * Creates dpad events from unhandled trackball movements.
     */
    final class SyntheticTrackballHandler {
        private final TrackballAxis mX = new TrackballAxis();
        private final TrackballAxis mY = new TrackballAxis();
        private long mLastTime;

        public void process(MotionEvent event) {
            // Translate the trackball event into DPAD keys and try to deliver those.
            long curTime = SystemClock.uptimeMillis();
            if ((mLastTime + MAX_TRACKBALL_DELAY) < curTime) {
                // It has been too long since the last movement,
                // so restart at the beginning.
                mX.reset(0);
                mY.reset(0);
                mLastTime = curTime;
            }

            final int action = event.getAction();
            final int metaState = event.getMetaState();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
                case MotionEvent.ACTION_UP:
                    mX.reset(2);
                    mY.reset(2);
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    break;
            }

            if (DEBUG_TRACKBALL) Log.v(mTag, "TB X=" + mX.position + " step="
                    + mX.step + " dir=" + mX.dir + " acc=" + mX.acceleration
                    + " move=" + event.getX()
                    + " / Y=" + mY.position + " step="
                    + mY.step + " dir=" + mY.dir + " acc=" + mY.acceleration
                    + " move=" + event.getY());
            final float xOff = mX.collect(event.getX(), event.getEventTime(), "X");
            final float yOff = mY.collect(event.getY(), event.getEventTime(), "Y");

            // Generate DPAD events based on the trackball movement.
            // We pick the axis that has moved the most as the direction of
            // the DPAD.  When we generate DPAD events for one axis, then the
            // other axis is reset -- we don't want to perform DPAD jumps due
            // to slight movements in the trackball when making major movements
            // along the other axis.
            int keycode = 0;
            int movement = 0;
            float accel = 1;
            if (xOff > yOff) {
                movement = mX.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                            : KeyEvent.KEYCODE_DPAD_LEFT;
                    accel = mX.acceleration;
                    mY.reset(2);
                }
            } else if (yOff > 0) {
                movement = mY.generate();
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                            : KeyEvent.KEYCODE_DPAD_UP;
                    accel = mY.acceleration;
                    mX.reset(2);
                }
            }

            if (keycode != 0) {
                if (movement < 0) movement = -movement;
                int accelMovement = (int)(movement * accel);
                if (DEBUG_TRACKBALL) Log.v(mTag, "Move: movement=" + movement
                        + " accelMovement=" + accelMovement
                        + " accel=" + accel);
                if (accelMovement > movement) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    int repeatCount = accelMovement - movement;
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_MULTIPLE, keycode, repeatCount, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                while (movement > 0) {
                    if (DEBUG_TRACKBALL) Log.v(mTag, "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    curTime = SystemClock.uptimeMillis();
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                    enqueueInputEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, keycode, 0, metaState,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FALLBACK,
                            InputDevice.SOURCE_KEYBOARD));
                }
                mLastTime = curTime;
            }
        }

        public void cancel() {
            mLastTime = Integer.MIN_VALUE;

            // If we reach this, we consumed a trackball event.
            // Because we will not translate the trackball event into a key event,
            // touch mode will not exit, so we exit touch mode here.
            if (mView != null && mAdded) {
                ensureTouchMode(false);
            }
        }
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        /**
         * The maximum amount of acceleration we will apply.
         */
        static final float MAX_ACCELERATION = 20;

        /**
         * The maximum amount of time (in milliseconds) between events in order
         * for us to consider the user to be doing fast trackball movements,
         * and thus apply an acceleration.
         */
        static final long FAST_MOVE_TIME = 150;

        /**
         * Scaling factor to the time (in milliseconds) between events to how
         * much to multiple/divide the current acceleration.  When movement
         * is < FAST_MOVE_TIME this multiplies the acceleration; when >
         * FAST_MOVE_TIME it divides it.
         */
        static final float ACCEL_MOVE_SCALING_FACTOR = (1.0f/40);

        static final float FIRST_MOVEMENT_THRESHOLD = 0.5f;
        static final float SECOND_CUMULATIVE_MOVEMENT_THRESHOLD = 2.0f;
        static final float SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD = 1.0f;

        float position;
        float acceleration = 1;
        long lastMoveTime = 0;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
            lastMoveTime = 0;
            step = _step;
            dir = 0;
        }

        /**
         * Add trackball movement into the state.  If the direction of movement
         * has been reversed, the state is reset before adding the
         * movement (so that you don't have to compensate for any previously
         * collected movement before see the result of the movement in the
         * new direction).
         *
         * @return Returns the absolute value of the amount of movement
         * collected so far.
         */
        float collect(float off, long time, String axis) {
            long normTime;
            if (off > 0) {
                normTime = (long)(off * FAST_MOVE_TIME);
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = 1;
            } else if (off < 0) {
                normTime = (long)((-off) * FAST_MOVE_TIME);
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                    lastMoveTime = 0;
                }
                dir = -1;
            } else {
                normTime = 0;
            }

            // The number of milliseconds between each movement that is
            // considered "normal" and will not result in any acceleration
            // or deceleration, scaled by the offset we have here.
            if (normTime > 0) {
                long delta = time - lastMoveTime;
                lastMoveTime = time;
                float acc = acceleration;
                if (delta < normTime) {
                    // The user is scrolling rapidly, so increase acceleration.
                    float scale = (normTime-delta) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc *= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " accelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc < MAX_ACCELERATION ? acc : MAX_ACCELERATION;
                } else {
                    // The user is scrolling slowly, so decrease acceleration.
                    float scale = (delta-normTime) * ACCEL_MOVE_SCALING_FACTOR;
                    if (scale > 1) acc /= scale;
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " deccelerate: off="
                            + off + " normTime=" + normTime + " delta=" + delta
                            + " scale=" + scale + " acc=" + acc);
                    acceleration = acc > 1 ? acc : 1;
                }
            }
            position += off;
            return Math.abs(position);
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate() {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (Math.abs(position) < FIRST_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        step = 1;
                        break;
                    // If we have generated the first movement, then we need
                    // to wait for the second complete trackball motion before
                    // generating the second discrete movement.
                    case 1:
                        if (Math.abs(position) < SECOND_CUMULATIVE_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position -= SECOND_CUMULATIVE_MOVEMENT_THRESHOLD * dir;
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  This is a simple
                    // acceleration on top of what we already compute based
                    // on how quickly the wheel is being turned, to apply
                    // a longer increasing acceleration to continuous movement
                    // in one direction.
                    default:
                        if (Math.abs(position) < SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD) {
                            return movement;
                        }
                        movement += dir;
                        position -= dir * SUBSEQUENT_INCREMENTAL_MOVEMENT_THRESHOLD;
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < MAX_ACCELERATION ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    /**
     * Creates dpad events from unhandled joystick movements.
     */
    final class SyntheticJoystickHandler extends Handler {
        private final static int MSG_ENQUEUE_X_AXIS_KEY_REPEAT = 1;
        private final static int MSG_ENQUEUE_Y_AXIS_KEY_REPEAT = 2;

        private final JoystickAxesState mJoystickAxesState = new JoystickAxesState();
        private final SparseArray<KeyEvent> mDeviceKeyEvents = new SparseArray<>();

        public SyntheticJoystickHandler() {
            super(true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ENQUEUE_X_AXIS_KEY_REPEAT:
                case MSG_ENQUEUE_Y_AXIS_KEY_REPEAT: {
                    if (mAttachInfo.mHasWindowFocus) {
                        KeyEvent oldEvent = (KeyEvent) msg.obj;
                        KeyEvent e = KeyEvent.changeTimeRepeat(oldEvent,
                                SystemClock.uptimeMillis(), oldEvent.getRepeatCount() + 1);
                        enqueueInputEvent(e);
                        Message m = obtainMessage(msg.what, e);
                        m.setAsynchronous(true);
                        sendMessageDelayed(m, ViewConfiguration.getKeyRepeatDelay());
                    }
                } break;
            }
        }

        public void process(MotionEvent event) {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_CANCEL:
                    cancel();
                    break;
                case MotionEvent.ACTION_MOVE:
                    update(event);
                    break;
                default:
                    Log.w(mTag, "Unexpected action: " + event.getActionMasked());
            }
        }

        private void cancel() {
            removeMessages(MSG_ENQUEUE_X_AXIS_KEY_REPEAT);
            removeMessages(MSG_ENQUEUE_Y_AXIS_KEY_REPEAT);
            for (int i = 0; i < mDeviceKeyEvents.size(); i++) {
                final KeyEvent keyEvent = mDeviceKeyEvents.valueAt(i);
                if (keyEvent != null) {
                    enqueueInputEvent(KeyEvent.changeTimeRepeat(keyEvent,
                            SystemClock.uptimeMillis(), 0));
                }
            }
            mDeviceKeyEvents.clear();
            mJoystickAxesState.resetState();
        }

        private void update(MotionEvent event) {
            final int historySize = event.getHistorySize();
            for (int h = 0; h < historySize; h++) {
                final long time = event.getHistoricalEventTime(h);
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_X,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_X, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_Y,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_Y, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_X,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_X, 0, h));
                mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_Y,
                        event.getHistoricalAxisValue(MotionEvent.AXIS_HAT_Y, 0, h));
            }
            final long time = event.getEventTime();
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_X,
                    event.getAxisValue(MotionEvent.AXIS_X));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_Y,
                    event.getAxisValue(MotionEvent.AXIS_Y));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_X,
                    event.getAxisValue(MotionEvent.AXIS_HAT_X));
            mJoystickAxesState.updateStateForAxis(event, time, MotionEvent.AXIS_HAT_Y,
                    event.getAxisValue(MotionEvent.AXIS_HAT_Y));
        }

        final class JoystickAxesState {
            // State machine: from neutral state (no button press) can go into
            // button STATE_UP_OR_LEFT or STATE_DOWN_OR_RIGHT state, emitting an ACTION_DOWN event.
            // From STATE_UP_OR_LEFT or STATE_DOWN_OR_RIGHT state can go into neutral state,
            // emitting an ACTION_UP event.
            private static final int STATE_UP_OR_LEFT = -1;
            private static final int STATE_NEUTRAL = 0;
            private static final int STATE_DOWN_OR_RIGHT = 1;

            final int[] mAxisStatesHat = {STATE_NEUTRAL, STATE_NEUTRAL}; // {AXIS_HAT_X, AXIS_HAT_Y}
            final int[] mAxisStatesStick = {STATE_NEUTRAL, STATE_NEUTRAL}; // {AXIS_X, AXIS_Y}

            void resetState() {
                mAxisStatesHat[0] = STATE_NEUTRAL;
                mAxisStatesHat[1] = STATE_NEUTRAL;
                mAxisStatesStick[0] = STATE_NEUTRAL;
                mAxisStatesStick[1] = STATE_NEUTRAL;
            }

            void updateStateForAxis(MotionEvent event, long time, int axis, float value) {
                // Emit KeyEvent if necessary
                // axis can be AXIS_X, AXIS_Y, AXIS_HAT_X, AXIS_HAT_Y
                final int axisStateIndex;
                final int repeatMessage;
                if (isXAxis(axis)) {
                    axisStateIndex = 0;
                    repeatMessage = MSG_ENQUEUE_X_AXIS_KEY_REPEAT;
                } else if (isYAxis(axis)) {
                    axisStateIndex = 1;
                    repeatMessage = MSG_ENQUEUE_Y_AXIS_KEY_REPEAT;
                } else {
                    Log.e(mTag, "Unexpected axis " + axis + " in updateStateForAxis!");
                    return;
                }
                final int newState = joystickAxisValueToState(value);

                final int currentState;
                if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y) {
                    currentState = mAxisStatesStick[axisStateIndex];
                } else {
                    currentState = mAxisStatesHat[axisStateIndex];
                }

                if (currentState == newState) {
                    return;
                }

                final int metaState = event.getMetaState();
                final int deviceId = event.getDeviceId();
                final int source = event.getSource();

                if (currentState == STATE_DOWN_OR_RIGHT || currentState == STATE_UP_OR_LEFT) {
                    // send a button release event
                    final int keyCode = joystickAxisAndStateToKeycode(axis, currentState);
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        enqueueInputEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode,
                                0, metaState, deviceId, 0, KeyEvent.FLAG_FALLBACK, source));
                        // remove the corresponding pending UP event if focus lost/view detached
                        mDeviceKeyEvents.put(deviceId, null);
                    }
                    removeMessages(repeatMessage);
                }

                if (newState == STATE_DOWN_OR_RIGHT || newState == STATE_UP_OR_LEFT) {
                    // send a button down event
                    final int keyCode = joystickAxisAndStateToKeycode(axis, newState);
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        KeyEvent keyEvent = new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode,
                                0, metaState, deviceId, 0, KeyEvent.FLAG_FALLBACK, source);
                        enqueueInputEvent(keyEvent);
                        Message m = obtainMessage(repeatMessage, keyEvent);
                        m.setAsynchronous(true);
                        sendMessageDelayed(m, ViewConfiguration.getKeyRepeatTimeout());
                        // store the corresponding ACTION_UP event so that it can be sent
                        // if focus is lost or root view is removed
                        mDeviceKeyEvents.put(deviceId,
                                new KeyEvent(time, time, KeyEvent.ACTION_UP, keyCode,
                                        0, metaState, deviceId, 0,
                                        KeyEvent.FLAG_FALLBACK | KeyEvent.FLAG_CANCELED,
                                        source));
                    }
                }
                if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y) {
                    mAxisStatesStick[axisStateIndex] = newState;
                } else {
                    mAxisStatesHat[axisStateIndex] = newState;
                }
            }

            private boolean isXAxis(int axis) {
                return axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_HAT_X;
            }
            private boolean isYAxis(int axis) {
                return axis == MotionEvent.AXIS_Y || axis == MotionEvent.AXIS_HAT_Y;
            }

            private int joystickAxisAndStateToKeycode(int axis, int state) {
                if (isXAxis(axis) && state == STATE_UP_OR_LEFT) {
                    return KeyEvent.KEYCODE_DPAD_LEFT;
                }
                if (isXAxis(axis) && state == STATE_DOWN_OR_RIGHT) {
                    return KeyEvent.KEYCODE_DPAD_RIGHT;
                }
                if (isYAxis(axis) && state == STATE_UP_OR_LEFT) {
                    return KeyEvent.KEYCODE_DPAD_UP;
                }
                if (isYAxis(axis) && state == STATE_DOWN_OR_RIGHT) {
                    return KeyEvent.KEYCODE_DPAD_DOWN;
                }
                Log.e(mTag, "Unknown axis " + axis + " or direction " + state);
                return KeyEvent.KEYCODE_UNKNOWN; // should never happen
            }

            private int joystickAxisValueToState(float value) {
                if (value >= 0.5f) {
                    return STATE_DOWN_OR_RIGHT;
                } else if (value <= -0.5f) {
                    return STATE_UP_OR_LEFT;
                } else {
                    return STATE_NEUTRAL;
                }
            }
        }
    }

    /**
     * Creates dpad events from unhandled touch navigation movements.
     */
    final class SyntheticTouchNavigationHandler extends Handler {
        private static final String LOCAL_TAG = "SyntheticTouchNavigationHandler";
        private static final boolean LOCAL_DEBUG = false;

        // Assumed nominal width and height in millimeters of a touch navigation pad,
        // if no resolution information is available from the input system.
        private static final float DEFAULT_WIDTH_MILLIMETERS = 48;
        private static final float DEFAULT_HEIGHT_MILLIMETERS = 48;

        /* TODO: These constants should eventually be moved to ViewConfiguration. */

        // The nominal distance traveled to move by one unit.
        private static final int TICK_DISTANCE_MILLIMETERS = 12;

        // Minimum and maximum fling velocity in ticks per second.
        // The minimum velocity should be set such that we perform enough ticks per
        // second that the fling appears to be fluid.  For example, if we set the minimum
        // to 2 ticks per second, then there may be up to half a second delay between the next
        // to last and last ticks which is noticeably discrete and jerky.  This value should
        // probably not be set to anything less than about 4.
        // If fling accuracy is a problem then consider tuning the tick distance instead.
        private static final float MIN_FLING_VELOCITY_TICKS_PER_SECOND = 6f;
        private static final float MAX_FLING_VELOCITY_TICKS_PER_SECOND = 20f;

        // Fling velocity decay factor applied after each new key is emitted.
        // This parameter controls the deceleration and overall duration of the fling.
        // The fling stops automatically when its velocity drops below the minimum
        // fling velocity defined above.
        private static final float FLING_TICK_DECAY = 0.8f;

        /* The input device that we are tracking. */

        private int mCurrentDeviceId = -1;
        private int mCurrentSource;
        private boolean mCurrentDeviceSupported;

        /* Configuration for the current input device. */

        // The scaled tick distance.  A movement of this amount should generally translate
        // into a single dpad event in a given direction.
        private float mConfigTickDistance;

        // The minimum and maximum scaled fling velocity.
        private float mConfigMinFlingVelocity;
        private float mConfigMaxFlingVelocity;

        /* Tracking state. */

        // The velocity tracker for detecting flings.
        private VelocityTracker mVelocityTracker;

        // The active pointer id, or -1 if none.
        private int mActivePointerId = -1;

        // Location where tracking started.
        private float mStartX;
        private float mStartY;

        // Most recently observed position.
        private float mLastX;
        private float mLastY;

        // Accumulated movement delta since the last direction key was sent.
        private float mAccumulatedX;
        private float mAccumulatedY;

        // Set to true if any movement was delivered to the app.
        // Implies that tap slop was exceeded.
        private boolean mConsumedMovement;

        // The most recently sent key down event.
        // The keycode remains set until the direction changes or a fling ends
        // so that repeated key events may be generated as required.
        private long mPendingKeyDownTime;
        private int mPendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
        private int mPendingKeyRepeatCount;
        private int mPendingKeyMetaState;

        // The current fling velocity while a fling is in progress.
        private boolean mFlinging;
        private float mFlingVelocity;

        public SyntheticTouchNavigationHandler() {
            super(true);
        }

        public void process(MotionEvent event) {
            // Update the current device information.
            final long time = event.getEventTime();
            final int deviceId = event.getDeviceId();
            final int source = event.getSource();
            if (mCurrentDeviceId != deviceId || mCurrentSource != source) {
                finishKeys(time);
                finishTracking(time);
                mCurrentDeviceId = deviceId;
                mCurrentSource = source;
                mCurrentDeviceSupported = false;
                InputDevice device = event.getDevice();
                if (device != null) {
                    // In order to support an input device, we must know certain
                    // characteristics about it, such as its size and resolution.
                    InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X);
                    InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y);
                    if (xRange != null && yRange != null) {
                        mCurrentDeviceSupported = true;

                        // Infer the resolution if it not actually known.
                        float xRes = xRange.getResolution();
                        if (xRes <= 0) {
                            xRes = xRange.getRange() / DEFAULT_WIDTH_MILLIMETERS;
                        }
                        float yRes = yRange.getResolution();
                        if (yRes <= 0) {
                            yRes = yRange.getRange() / DEFAULT_HEIGHT_MILLIMETERS;
                        }
                        float nominalRes = (xRes + yRes) * 0.5f;

                        // Precompute all of the configuration thresholds we will need.
                        mConfigTickDistance = TICK_DISTANCE_MILLIMETERS * nominalRes;
                        mConfigMinFlingVelocity =
                                MIN_FLING_VELOCITY_TICKS_PER_SECOND * mConfigTickDistance;
                        mConfigMaxFlingVelocity =
                                MAX_FLING_VELOCITY_TICKS_PER_SECOND * mConfigTickDistance;

                        if (LOCAL_DEBUG) {
                            Log.d(LOCAL_TAG, "Configured device " + mCurrentDeviceId
                                    + " (" + Integer.toHexString(mCurrentSource) + "): "
                                    + ", mConfigTickDistance=" + mConfigTickDistance
                                    + ", mConfigMinFlingVelocity=" + mConfigMinFlingVelocity
                                    + ", mConfigMaxFlingVelocity=" + mConfigMaxFlingVelocity);
                        }
                    }
                }
            }
            if (!mCurrentDeviceSupported) {
                return;
            }

            // Handle the event.
            final int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    boolean caughtFling = mFlinging;
                    finishKeys(time);
                    finishTracking(time);
                    mActivePointerId = event.getPointerId(0);
                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(event);
                    mStartX = event.getX();
                    mStartY = event.getY();
                    mLastX = mStartX;
                    mLastY = mStartY;
                    mAccumulatedX = 0;
                    mAccumulatedY = 0;

                    // If we caught a fling, then pretend that the tap slop has already
                    // been exceeded to suppress taps whose only purpose is to stop the fling.
                    mConsumedMovement = caughtFling;
                    break;
                }

                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP: {
                    if (mActivePointerId < 0) {
                        break;
                    }
                    final int index = event.findPointerIndex(mActivePointerId);
                    if (index < 0) {
                        finishKeys(time);
                        finishTracking(time);
                        break;
                    }

                    mVelocityTracker.addMovement(event);
                    final float x = event.getX(index);
                    final float y = event.getY(index);
                    mAccumulatedX += x - mLastX;
                    mAccumulatedY += y - mLastY;
                    mLastX = x;
                    mLastY = y;

                    // Consume any accumulated movement so far.
                    final int metaState = event.getMetaState();
                    consumeAccumulatedMovement(time, metaState);

                    // Detect taps and flings.
                    if (action == MotionEvent.ACTION_UP) {
                        if (mConsumedMovement && mPendingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // It might be a fling.
                            mVelocityTracker.computeCurrentVelocity(1000, mConfigMaxFlingVelocity);
                            final float vx = mVelocityTracker.getXVelocity(mActivePointerId);
                            final float vy = mVelocityTracker.getYVelocity(mActivePointerId);
                            if (!startFling(time, vx, vy)) {
                                finishKeys(time);
                            }
                        }
                        finishTracking(time);
                    }
                    break;
                }

                case MotionEvent.ACTION_CANCEL: {
                    finishKeys(time);
                    finishTracking(time);
                    break;
                }
            }
        }

        public void cancel(MotionEvent event) {
            if (mCurrentDeviceId == event.getDeviceId()
                    && mCurrentSource == event.getSource()) {
                final long time = event.getEventTime();
                finishKeys(time);
                finishTracking(time);
            }
        }

        private void finishKeys(long time) {
            cancelFling();
            sendKeyUp(time);
        }

        private void finishTracking(long time) {
            if (mActivePointerId >= 0) {
                mActivePointerId = -1;
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
        }

        private void consumeAccumulatedMovement(long time, int metaState) {
            final float absX = Math.abs(mAccumulatedX);
            final float absY = Math.abs(mAccumulatedY);
            if (absX >= absY) {
                if (absX >= mConfigTickDistance) {
                    mAccumulatedX = consumeAccumulatedMovement(time, metaState, mAccumulatedX,
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
                    mAccumulatedY = 0;
                    mConsumedMovement = true;
                }
            } else {
                if (absY >= mConfigTickDistance) {
                    mAccumulatedY = consumeAccumulatedMovement(time, metaState, mAccumulatedY,
                            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
                    mAccumulatedX = 0;
                    mConsumedMovement = true;
                }
            }
        }

        private float consumeAccumulatedMovement(long time, int metaState,
                float accumulator, int negativeKeyCode, int positiveKeyCode) {
            while (accumulator <= -mConfigTickDistance) {
                sendKeyDownOrRepeat(time, negativeKeyCode, metaState);
                accumulator += mConfigTickDistance;
            }
            while (accumulator >= mConfigTickDistance) {
                sendKeyDownOrRepeat(time, positiveKeyCode, metaState);
                accumulator -= mConfigTickDistance;
            }
            return accumulator;
        }

        private void sendKeyDownOrRepeat(long time, int keyCode, int metaState) {
            if (mPendingKeyCode != keyCode) {
                sendKeyUp(time);
                mPendingKeyDownTime = time;
                mPendingKeyCode = keyCode;
                mPendingKeyRepeatCount = 0;
            } else {
                mPendingKeyRepeatCount += 1;
            }
            mPendingKeyMetaState = metaState;

            // Note: Normally we would pass FLAG_LONG_PRESS when the repeat count is 1
            // but it doesn't quite make sense when simulating the events in this way.
            if (LOCAL_DEBUG) {
                Log.d(LOCAL_TAG, "Sending key down: keyCode=" + mPendingKeyCode
                        + ", repeatCount=" + mPendingKeyRepeatCount
                        + ", metaState=" + Integer.toHexString(mPendingKeyMetaState));
            }
            enqueueInputEvent(new KeyEvent(mPendingKeyDownTime, time,
                    KeyEvent.ACTION_DOWN, mPendingKeyCode, mPendingKeyRepeatCount,
                    mPendingKeyMetaState, mCurrentDeviceId,
                    KeyEvent.FLAG_FALLBACK, mCurrentSource));
        }

        private void sendKeyUp(long time) {
            if (mPendingKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                if (LOCAL_DEBUG) {
                    Log.d(LOCAL_TAG, "Sending key up: keyCode=" + mPendingKeyCode
                            + ", metaState=" + Integer.toHexString(mPendingKeyMetaState));
                }
                enqueueInputEvent(new KeyEvent(mPendingKeyDownTime, time,
                        KeyEvent.ACTION_UP, mPendingKeyCode, 0, mPendingKeyMetaState,
                        mCurrentDeviceId, 0, KeyEvent.FLAG_FALLBACK,
                        mCurrentSource));
                mPendingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            }
        }

        private boolean startFling(long time, float vx, float vy) {
            if (LOCAL_DEBUG) {
                Log.d(LOCAL_TAG, "Considering fling: vx=" + vx + ", vy=" + vy
                        + ", min=" + mConfigMinFlingVelocity);
            }

            // Flings must be oriented in the same direction as the preceding movements.
            switch (mPendingKeyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (-vx >= mConfigMinFlingVelocity
                            && Math.abs(vy) < mConfigMinFlingVelocity) {
                        mFlingVelocity = -vx;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (vx >= mConfigMinFlingVelocity
                            && Math.abs(vy) < mConfigMinFlingVelocity) {
                        mFlingVelocity = vx;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_UP:
                    if (-vy >= mConfigMinFlingVelocity
                            && Math.abs(vx) < mConfigMinFlingVelocity) {
                        mFlingVelocity = -vy;
                        break;
                    }
                    return false;

                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (vy >= mConfigMinFlingVelocity
                            && Math.abs(vx) < mConfigMinFlingVelocity) {
                        mFlingVelocity = vy;
                        break;
                    }
                    return false;
            }

            // Post the first fling event.
            mFlinging = postFling(time);
            return mFlinging;
        }

        private boolean postFling(long time) {
            // The idea here is to estimate the time when the pointer would have
            // traveled one tick distance unit given the current fling velocity.
            // This effect creates continuity of motion.
            if (mFlingVelocity >= mConfigMinFlingVelocity) {
                long delay = (long)(mConfigTickDistance / mFlingVelocity * 1000);
                postAtTime(mFlingRunnable, time + delay);
                if (LOCAL_DEBUG) {
                    Log.d(LOCAL_TAG, "Posted fling: velocity="
                            + mFlingVelocity + ", delay=" + delay
                            + ", keyCode=" + mPendingKeyCode);
                }
                return true;
            }
            return false;
        }

        private void cancelFling() {
            if (mFlinging) {
                removeCallbacks(mFlingRunnable);
                mFlinging = false;
            }
        }

        private final Runnable mFlingRunnable = new Runnable() {
            @Override
            public void run() {
                final long time = SystemClock.uptimeMillis();
                sendKeyDownOrRepeat(time, mPendingKeyCode, mPendingKeyMetaState);
                mFlingVelocity *= FLING_TICK_DECAY;
                if (!postFling(time)) {
                    mFlinging = false;
                    finishKeys(time);
                }
            }
        };
    }

    final class SyntheticKeyboardHandler {
        public void process(KeyEvent event) {
            if ((event.getFlags() & KeyEvent.FLAG_FALLBACK) != 0) {
                return;
            }

            final KeyCharacterMap kcm = event.getKeyCharacterMap();
            final int keyCode = event.getKeyCode();
            final int metaState = event.getMetaState();

            // Check for fallback actions specified by the key character map.
            KeyCharacterMap.FallbackAction fallbackAction =
                    kcm.getFallbackAction(keyCode, metaState);
            if (fallbackAction != null) {
                final int flags = event.getFlags() | KeyEvent.FLAG_FALLBACK;
                KeyEvent fallbackEvent = KeyEvent.obtain(
                        event.getDownTime(), event.getEventTime(),
                        event.getAction(), fallbackAction.keyCode,
                        event.getRepeatCount(), fallbackAction.metaState,
                        event.getDeviceId(), event.getScanCode(),
                        flags, event.getSource(), null);
                fallbackAction.recycle();
                enqueueInputEvent(fallbackEvent);
            }
        }
    }

    /**
     * Returns true if the key is used for keyboard navigation.
     * @param keyEvent The key event.
     * @return True if the key is used for keyboard navigation.
     */
    private static boolean isNavigationKey(KeyEvent keyEvent) {
        switch (keyEvent.getKeyCode()) {
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

    /**
     * Returns true if the key is used for typing.
     * @param keyEvent The key event.
     * @return True if the key is used for typing.
     */
    private static boolean isTypingKey(KeyEvent keyEvent) {
        return keyEvent.getUnicodeChar() > 0;
    }

    /**
     * See if the key event means we should leave touch mode (and leave touch mode if so).
     * @param event The key event.
     * @return Whether this key event should be consumed (meaning the act of
     *   leaving touch mode alone is considered the event).
     */
    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        // Only relevant in touch mode.
        if (!mAttachInfo.mInTouchMode) {
            return false;
        }

        // Only consider leaving touch mode on DOWN or MULTIPLE actions, never on UP.
        final int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) {
            return false;
        }

        // Don't leave touch mode if the IME told us not to.
        if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0) {
            return false;
        }

        // If the key can be used for keyboard navigation then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // When a new focused view is selected, we consume the navigation key because
        // navigation doesn't make much sense unless a view already has focus so
        // the key's purpose is to set focus.
        if (event.hasNoModifiers() && isNavigationKey(event)) {
            return ensureTouchMode(false);
        }

        // If the key can be used for typing then leave touch mode
        // and select a focused view if needed (in ensureTouchMode).
        // Always allow the view to process the typing key.
        if (isTypingKey(event)) {
            ensureTouchMode(false);
            return false;
        }

        return false;
    }

    /* drag/drop */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    void setLocalDragState(Object obj) {
        mLocalDragState = obj;
    }

    private void handleDragEvent(DragEvent event) {
        // From the root, only drag start/end/location are dispatched.  entered/exited
        // are determined and dispatched by the viewgroup hierarchy, who then report
        // that back here for ultimate reporting back to the framework.
        if (mView != null && mAdded) {
            final int what = event.mAction;

            // Cache the drag description when the operation starts, then fill it in
            // on subsequent calls as a convenience
            if (what == DragEvent.ACTION_DRAG_STARTED) {
                mCurrentDragView = null;    // Start the current-recipient tracking
                mDragDescription = event.mClipDescription;
                if (mStartedDragViewForA11y != null) {
                    // Send a drag started a11y event
                    mStartedDragViewForA11y.sendWindowContentChangedAccessibilityEvent(
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_DRAG_STARTED);
                }
            } else {
                if (what == DragEvent.ACTION_DRAG_ENDED) {
                    mDragDescription = null;
                }
                event.mClipDescription = mDragDescription;
            }

            if (what == DragEvent.ACTION_DRAG_EXITED) {
                // A direct EXITED event means that the window manager knows we've just crossed
                // a window boundary, so the current drag target within this one must have
                // just been exited. Send the EXITED notification to the current drag view, if any.
                if (View.sCascadedDragDrop) {
                    mView.dispatchDragEnterExitInPreN(event);
                }
                setDragFocus(null, event);
            } else {
                // For events with a [screen] location, translate into window coordinates
                if ((what == DragEvent.ACTION_DRAG_LOCATION) || (what == DragEvent.ACTION_DROP)) {
                    mDragPoint.set(event.mX, event.mY);
                    if (mTranslator != null) {
                        mTranslator.translatePointInScreenToAppWindow(mDragPoint);
                    }

                    if (mCurScrollY != 0) {
                        mDragPoint.offset(0, mCurScrollY);
                    }

                    event.mX = mDragPoint.x;
                    event.mY = mDragPoint.y;
                }

                // Remember who the current drag target is pre-dispatch
                final View prevDragView = mCurrentDragView;

                if (what == DragEvent.ACTION_DROP && event.mClipData != null) {
                    event.mClipData.prepareToEnterProcess(
                            mView.getContext().getAttributionSource());
                }

                // Now dispatch the drag/drop event
                boolean result = mView.dispatchDragEvent(event);

                if (what == DragEvent.ACTION_DRAG_LOCATION && !event.mEventHandlerWasCalled) {
                    // If the LOCATION event wasn't delivered to any handler, no view now has a drag
                    // focus.
                    setDragFocus(null, event);
                }

                // If we changed apparent drag target, tell the OS about it
                if (prevDragView != mCurrentDragView) {
                    try {
                        if (prevDragView != null) {
                            mWindowSession.dragRecipientExited(mWindow);
                        }
                        if (mCurrentDragView != null) {
                            mWindowSession.dragRecipientEntered(mWindow);
                        }
                    } catch (RemoteException e) {
                        Slog.e(mTag, "Unable to note drag target change");
                    }
                }

                // Report the drop result when we're done
                if (what == DragEvent.ACTION_DROP) {
                    try {
                        Log.i(mTag, "Reporting drop result: " + result);
                        mWindowSession.reportDropResult(mWindow, result);
                    } catch (RemoteException e) {
                        Log.e(mTag, "Unable to report drop result");
                    }
                }

                // When the drag operation ends, reset drag-related state
                if (what == DragEvent.ACTION_DRAG_ENDED) {
                    if (mStartedDragViewForA11y != null) {
                        // If the drag failed, send a cancelled event from the source. Otherwise,
                        // the View that accepted the drop sends CONTENT_CHANGE_TYPE_DRAG_DROPPED
                        if (!event.getResult()) {
                            mStartedDragViewForA11y.sendWindowContentChangedAccessibilityEvent(
                                    AccessibilityEvent.CONTENT_CHANGE_TYPE_DRAG_CANCELLED);
                        }
                        mStartedDragViewForA11y.setAccessibilityDragStarted(false);
                    }
                    mStartedDragViewForA11y = null;
                    mCurrentDragView = null;
                    setLocalDragState(null);
                    mAttachInfo.mDragToken = null;
                    if (mAttachInfo.mDragSurface != null) {
                        mAttachInfo.mDragSurface.release();
                        mAttachInfo.mDragSurface = null;
                    }
                }
            }
        }
        event.recycle();
    }

    /**
     * Notify that the window title changed
     */
    public void onWindowTitleChanged() {
        mAttachInfo.mForceReportNewAttributes = true;
    }

    public void handleDispatchWindowShown() {
        mAttachInfo.mTreeObserver.dispatchOnWindowShown();
    }

    public void handleRequestKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        Bundle data = new Bundle();
        ArrayList<KeyboardShortcutGroup> list = new ArrayList<>();
        if (mView != null) {
            mView.requestKeyboardShortcuts(list, deviceId);
        }
        data.putParcelableArrayList(WindowManager.PARCEL_KEY_SHORTCUTS_ARRAY, list);
        try {
            receiver.send(0, data);
        } catch (RemoteException e) {
        }
    }

    @UnsupportedAppUsage
    public void getLastTouchPoint(Point outLocation) {
        outLocation.x = (int) mLastTouchPoint.x;
        outLocation.y = (int) mLastTouchPoint.y;
    }

    public int getLastTouchSource() {
        return mLastTouchSource;
    }

    public void setDragFocus(View newDragTarget, DragEvent event) {
        if (mCurrentDragView != newDragTarget && !View.sCascadedDragDrop) {
            // Send EXITED and ENTERED notifications to the old and new drag focus views.

            final float tx = event.mX;
            final float ty = event.mY;
            final int action = event.mAction;
            final ClipData td = event.mClipData;
            // Position should not be available for ACTION_DRAG_ENTERED and ACTION_DRAG_EXITED.
            event.mX = 0;
            event.mY = 0;
            event.mClipData = null;

            if (mCurrentDragView != null) {
                event.mAction = DragEvent.ACTION_DRAG_EXITED;
                mCurrentDragView.callDragEventHandler(event);
            }

            if (newDragTarget != null) {
                event.mAction = DragEvent.ACTION_DRAG_ENTERED;
                newDragTarget.callDragEventHandler(event);
            }

            event.mAction = action;
            event.mX = tx;
            event.mY = ty;
            event.mClipData = td;
        }

        mCurrentDragView = newDragTarget;
    }

    /** Sets the view that started drag and drop for the purpose of sending AccessibilityEvents */
    void setDragStartedViewForAccessibility(View view) {
        if (mStartedDragViewForA11y == null) {
            mStartedDragViewForA11y = view;
        }
    }

    private AudioManager getAudioManager() {
        if (mView == null) {
            throw new IllegalStateException("getAudioManager called when there is no mView");
        }
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mView.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return mAudioManager;
    }

    private @Nullable AutofillManager getAutofillManager() {
        if (mView instanceof ViewGroup) {
            ViewGroup decorView = (ViewGroup) mView;
            if (decorView.getChildCount() > 0) {
                // We cannot use decorView's Context for querying AutofillManager: DecorView's
                // context is based on Application Context, it would allocate a different
                // AutofillManager instance.
                return decorView.getChildAt(0).getContext()
                        .getSystemService(AutofillManager.class);
            }
        }
        return null;
    }

    private boolean isAutofillUiShowing() {
        AutofillManager afm = getAutofillManager();
        if (afm == null) {
            return false;
        }
        return afm.isAutofillUiShowing();
    }

    public AccessibilityInteractionController getAccessibilityInteractionController() {
        if (mView == null) {
            throw new IllegalStateException("getAccessibilityInteractionController"
                    + " called when there is no mView");
        }
        if (mAccessibilityInteractionController == null) {
            mAccessibilityInteractionController = new AccessibilityInteractionController(this);
        }
        return mAccessibilityInteractionController;
    }

    private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,
            boolean insetsPending) throws RemoteException {
        mRelayoutRequested = true;
        float appScale = mAttachInfo.mApplicationScale;
        boolean restore = false;
        if (params != null && mTranslator != null) {
            restore = true;
            params.backup();
            mTranslator.translateWindowLayout(params);
        }

        if (params != null) {
            if (DBG) Log.d(mTag, "WindowLayout in layoutWindow:" + params);

            if (mOrigWindowType != params.type) {
                // For compatibility with old apps, don't crash here.
                if (mTargetSdkVersion < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    Slog.w(mTag, "Window type can not be changed after "
                            + "the window is added; ignoring change of " + mView);
                    params.type = mOrigWindowType;
                }
            }
        }

        final int requestedWidth = (int) (mView.getMeasuredWidth() * appScale + 0.5f);
        final int requestedHeight = (int) (mView.getMeasuredHeight() * appScale + 0.5f);

        int relayoutResult = 0;
        WindowConfiguration winConfig = getConfiguration().windowConfiguration;
        if (LOCAL_LAYOUT) {
            if (mFirst || viewVisibility != mViewVisibility) {
                relayoutResult = mWindowSession.updateVisibility(mWindow, params, viewVisibility,
                        mPendingMergedConfiguration, mSurfaceControl, mTempInsets, mTempControls);
                if (mTranslator != null) {
                    mTranslator.translateInsetsStateInScreenToAppWindow(mTempInsets);
                    mTranslator.translateSourceControlsInScreenToAppWindow(mTempControls);
                }
                mInsetsController.onStateChanged(mTempInsets);
                mInsetsController.onControlsChanged(mTempControls);

                mPendingAlwaysConsumeSystemBars =
                        (relayoutResult & RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS) != 0;
            }
            final InsetsState state = mInsetsController.getState();
            final Rect displayCutoutSafe = mTempRect;
            state.getDisplayCutoutSafe(displayCutoutSafe);
            if (mWindowAttributes.type == TYPE_APPLICATION_STARTING) {
                // TODO(b/210378379): Remove the special logic.
                // Letting starting window use the window bounds from the pending config is for the
                // fixed rotation, because the config is not overridden before the starting window
                // is created.
                winConfig = mPendingMergedConfiguration.getMergedConfiguration()
                        .windowConfiguration;
            }
            mWindowLayout.computeFrames(mWindowAttributes, state, displayCutoutSafe,
                    winConfig.getBounds(), winConfig.getWindowingMode(), requestedWidth,
                    requestedHeight, mInsetsController.getRequestedVisibilities(),
                    getAttachedWindowFrame(), 1f /* compatScale */, mTmpFrames);

            mWindowSession.updateLayout(mWindow, params,
                    insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0, mTmpFrames,
                    requestedWidth, requestedHeight);

        } else {
            relayoutResult = mWindowSession.relayout(mWindow, params,
                    requestedWidth, requestedHeight, viewVisibility,
                    insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0,
                    mTmpFrames, mPendingMergedConfiguration, mSurfaceControl, mTempInsets,
                    mTempControls, mRelayoutBundle);
            mSyncSeqId = mRelayoutBundle.getInt("seqid");

            if (mTranslator != null) {
                mTranslator.translateRectInScreenToAppWindow(mTmpFrames.frame);
                mTranslator.translateRectInScreenToAppWindow(mTmpFrames.displayFrame);
                mTranslator.translateInsetsStateInScreenToAppWindow(mTempInsets);
                mTranslator.translateSourceControlsInScreenToAppWindow(mTempControls);
            }
            mInsetsController.onStateChanged(mTempInsets);
            mInsetsController.onControlsChanged(mTempControls);

            mPendingAlwaysConsumeSystemBars =
                    (relayoutResult & RELAYOUT_RES_CONSUME_ALWAYS_SYSTEM_BARS) != 0;
        }

        final int transformHint = SurfaceControl.rotationToBufferTransform(
                (mDisplayInstallOrientation + mDisplay.getRotation()) % 4);

        WindowLayout.computeSurfaceSize(mWindowAttributes, winConfig.getMaxBounds(), requestedWidth,
                requestedHeight, mTmpFrames.frame, mPendingDragResizing, mSurfaceSize);

        final boolean transformHintChanged = transformHint != mLastTransformHint;
        final boolean sizeChanged = !mLastSurfaceSize.equals(mSurfaceSize);
        final boolean surfaceControlChanged =
                (relayoutResult & RELAYOUT_RES_SURFACE_CHANGED) == RELAYOUT_RES_SURFACE_CHANGED;
        if (mAttachInfo.mThreadedRenderer != null &&
                (transformHintChanged || sizeChanged || surfaceControlChanged)) {
            if (mAttachInfo.mThreadedRenderer.pause()) {
                // Animations were running so we need to push a frame
                // to resume them
                mDirty.set(0, 0, mWidth, mHeight);
            }
        }

        mLastTransformHint = transformHint;
      
        mSurfaceControl.setTransformHint(transformHint);

        if (mAttachInfo.mContentCaptureManager != null) {
            MainContentCaptureSession mainSession = mAttachInfo.mContentCaptureManager
                    .getMainContentCaptureSession();
            mainSession.notifyWindowBoundsChanged(mainSession.getId(),
                    getConfiguration().windowConfiguration.getBounds());
        }

        if (mSurfaceControl.isValid()) {
            if (!useBLAST()) {
                mSurface.copyFrom(mSurfaceControl);
            } else {
                updateBlastSurfaceIfNeeded();
            }
            if (mAttachInfo.mThreadedRenderer != null) {
                mAttachInfo.mThreadedRenderer.setSurfaceControl(mSurfaceControl);
                mAttachInfo.mThreadedRenderer.setBlastBufferQueue(mBlastBufferQueue);
            }
            if (mPreviousTransformHint != transformHint) {
                mPreviousTransformHint = transformHint;
                dispatchTransformHintChanged(transformHint);
            }
        } else {
            if (mAttachInfo.mThreadedRenderer != null && mAttachInfo.mThreadedRenderer.pause()) {
                mDirty.set(0, 0, mWidth, mHeight);
            }
            destroySurface();
        }

        if (restore) {
            params.restore();
        }
        setFrame(mTmpFrames.frame);
        return relayoutResult;
    }

    private void updateOpacity(WindowManager.LayoutParams params, boolean dragResizing,
            boolean forceUpdate) {
        boolean opaque = false;

        if (!PixelFormat.formatHasAlpha(params.format)
                // Don't make surface with surfaceInsets opaque as they display a
                // translucent shadow.
                && params.surfaceInsets.left == 0
                && params.surfaceInsets.top == 0
                && params.surfaceInsets.right == 0
                && params.surfaceInsets.bottom == 0
                // Don't make surface opaque when resizing to reduce the amount of
                // artifacts shown in areas the app isn't drawing content to.
                && !dragResizing) {
            opaque = true;
        }

        if (!forceUpdate && mIsSurfaceOpaque == opaque) {
            return;
        }

        final ThreadedRenderer renderer = mAttachInfo.mThreadedRenderer;
        if (renderer != null && renderer.rendererOwnsSurfaceControlOpacity()) {
            opaque = renderer.setSurfaceControlOpaque(opaque);
        } else {
            mTransaction.setOpaque(mSurfaceControl, opaque).apply();
        }

        mIsSurfaceOpaque = opaque;
    }

    private void setFrame(Rect frame) {
        mWinFrame.set(frame);

        final WindowConfiguration winConfig = getConfiguration().windowConfiguration;
        mPendingBackDropFrame.set(mPendingDragResizing && !winConfig.useWindowFrameForBackdrop()
                ? winConfig.getMaxBounds()
                : frame);
        // Surface position is now inherited from parent, and BackdropFrameRenderer uses backdrop
        // frame to position content. Thus, we just keep the size of backdrop frame, and remove the
        // offset to avoid double offset from display origin.
        mPendingBackDropFrame.offsetTo(0, 0);

        mInsetsController.onFrameChanged(mOverrideInsetsFrame != null ?
                mOverrideInsetsFrame : frame);
    }

    /**
     * In the normal course of operations we compute insets relative to
     * the frame returned from relayout window. In the case of
     * SurfaceControlViewHost, this frame is in local coordinates
     * instead of global coordinates. We support this override
     * frame so we can allow SurfaceControlViewHost to set a frame
     * to be used to calculate insets, without disturbing the main
     * mFrame.
     */
    void setOverrideInsetsFrame(Rect frame) {
        mOverrideInsetsFrame = new Rect(frame);
        mInsetsController.onFrameChanged(mOverrideInsetsFrame);
    }

    /**
     * Gets the current display size in which the window is being laid out, accounting for screen
     * decorations around it.
     */
    void getDisplayFrame(Rect outFrame) {
        outFrame.set(mTmpFrames.displayFrame);
    }

    /**
     * Gets the current display size in which the window is being laid out, accounting for screen
     * decorations around it.
     */
    void getWindowVisibleDisplayFrame(Rect outFrame) {
        outFrame.set(mTmpFrames.displayFrame);
        // XXX This is really broken, and probably all needs to be done
        // in the window manager, and we need to know more about whether
        // we want the area behind or in front of the IME.
        final Rect insets = mAttachInfo.mVisibleInsets;
        outFrame.left += insets.left;
        outFrame.top += insets.top;
        outFrame.right -= insets.right;
        outFrame.bottom -= insets.bottom;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void playSoundEffect(@SoundEffectConstants.SoundEffect int effectId) {
        checkThread();

        try {
            final AudioManager audioManager = getAudioManager();

            if (mFastScrollSoundEffectsEnabled
                    && SoundEffectConstants.isNavigationRepeat(effectId)) {
                audioManager.playSoundEffect(
                        SoundEffectConstants.nextNavigationRepeatSoundEffectId());
                return;
            }

            switch (effectId) {
                case SoundEffectConstants.CLICK:
                    audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                    return;
                case SoundEffectConstants.NAVIGATION_DOWN:
                case SoundEffectConstants.NAVIGATION_REPEAT_DOWN:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN);
                    return;
                case SoundEffectConstants.NAVIGATION_LEFT:
                case SoundEffectConstants.NAVIGATION_REPEAT_LEFT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_LEFT);
                    return;
                case SoundEffectConstants.NAVIGATION_RIGHT:
                case SoundEffectConstants.NAVIGATION_REPEAT_RIGHT:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
                    return;
                case SoundEffectConstants.NAVIGATION_UP:
                case SoundEffectConstants.NAVIGATION_REPEAT_UP:
                    audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP);
                    return;
                default:
                    throw new IllegalArgumentException("unknown effect id " + effectId +
                            " not defined in " + SoundEffectConstants.class.getCanonicalName());
            }
        } catch (IllegalStateException e) {
            // Exception thrown by getAudioManager() when mView is null
            Log.e(mTag, "FATAL EXCEPTION when attempting to play sound effect: " + e);
            e.printStackTrace();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean performHapticFeedback(int effectId, boolean always) {
        try {
            return mWindowSession.performHapticFeedback(effectId, always);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public View keyboardNavigationClusterSearch(View currentCluster,
            @FocusDirection int direction) {
        checkThread();
        return FocusFinder.getInstance().findNextKeyboardNavigationCluster(
                mView, currentCluster, direction);
    }

    public void debug() {
        mView.debug();
    }

    /**
     * Export the state of {@link ViewRootImpl} and other relevant classes into a protocol buffer
     * output stream.
     *
     * @param proto Stream to write the state to
     * @param fieldId FieldId of ViewRootImpl as defined in the parent message
     */
    @GuardedBy("this")
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(VIEW, Objects.toString(mView));
        proto.write(DISPLAY_ID, mDisplay.getDisplayId());
        proto.write(APP_VISIBLE, mAppVisible);
        proto.write(HEIGHT, mHeight);
        proto.write(WIDTH, mWidth);
        proto.write(IS_ANIMATING, mIsAnimating);
        mVisRect.dumpDebug(proto, VISIBLE_RECT);
        proto.write(IS_DRAWING, mIsDrawing);
        proto.write(ADDED, mAdded);
        mWinFrame.dumpDebug(proto, WIN_FRAME);
        proto.write(LAST_WINDOW_INSETS, Objects.toString(mLastWindowInsets));
        proto.write(SOFT_INPUT_MODE, InputMethodDebug.softInputModeToString(mSoftInputMode));
        proto.write(SCROLL_Y, mScrollY);
        proto.write(CUR_SCROLL_Y, mCurScrollY);
        proto.write(REMOVED, mRemoved);
        mWindowAttributes.dumpDebug(proto, WINDOW_ATTRIBUTES);
        proto.end(token);
        mInsetsController.dumpDebug(proto, INSETS_CONTROLLER);
        mImeFocusController.dumpDebug(proto, IME_FOCUS_CONTROLLER);
    }

    /**
     * Dump information about this ViewRootImpl
     * @param prefix the prefix that will be prepended to each line of the produced output
     * @param writer the writer that will receive the resulting text
     */
    public void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        writer.println(prefix + "ViewRoot:");
        writer.println(innerPrefix + "mAdded=" + mAdded);
        writer.println(innerPrefix + "mRemoved=" + mRemoved);
        writer.println(innerPrefix + "mStopped=" + mStopped);
        writer.println(innerPrefix + "mPausedForTransition=" + mPausedForTransition);
        writer.println(innerPrefix + "mConsumeBatchedInputScheduled="
                + mConsumeBatchedInputScheduled);
        writer.println(innerPrefix + "mConsumeBatchedInputImmediatelyScheduled="
                + mConsumeBatchedInputImmediatelyScheduled);
        writer.println(innerPrefix + "mPendingInputEventCount=" + mPendingInputEventCount);
        writer.println(innerPrefix + "mProcessInputEventsScheduled="
                + mProcessInputEventsScheduled);
        writer.println(innerPrefix + "mTraversalScheduled=" + mTraversalScheduled);
        if (mTraversalScheduled) {
            writer.println(innerPrefix + " (barrier=" + mTraversalBarrier + ")");
        }
        writer.println(innerPrefix + "mIsAmbientMode="  + mIsAmbientMode);
        writer.println(innerPrefix + "mUnbufferedInputSource="
                + Integer.toHexString(mUnbufferedInputSource));
        if (mAttachInfo != null) {
            writer.print(innerPrefix + "mAttachInfo= ");
            mAttachInfo.dump(innerPrefix, writer);
        } else {
            writer.println(innerPrefix + "mAttachInfo=<null>");
        }

        mFirstInputStage.dump(innerPrefix, writer);

        if (mInputEventReceiver != null) {
            mInputEventReceiver.dump(innerPrefix, writer);
        }

        mChoreographer.dump(prefix, writer);

        mInsetsController.dump(prefix, writer);

        writer.println(prefix + "View Hierarchy:");
        dumpViewHierarchy(innerPrefix, writer, mView);
    }

    private void dumpViewHierarchy(String prefix, PrintWriter writer, View view) {
        writer.print(prefix);
        if (view == null) {
            writer.println("null");
            return;
        }
        writer.println(view.toString());
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup grp = (ViewGroup)view;
        final int N = grp.getChildCount();
        if (N <= 0) {
            return;
        }
        prefix = prefix + "  ";
        for (int i=0; i<N; i++) {
            dumpViewHierarchy(prefix, writer, grp.getChildAt(i));
        }
    }

    static final class GfxInfo {
        public int viewCount;
        public long renderNodeMemoryUsage;
        public long renderNodeMemoryAllocated;

        void add(GfxInfo other) {
            viewCount += other.viewCount;
            renderNodeMemoryUsage += other.renderNodeMemoryUsage;
            renderNodeMemoryAllocated += other.renderNodeMemoryAllocated;
        }
    }

    GfxInfo getGfxInfo() {
        GfxInfo info = new GfxInfo();
        if (mView != null) {
            appendGfxInfo(mView, info);
        }
        return info;
    }

    private static void computeRenderNodeUsage(RenderNode node, GfxInfo info) {
        if (node == null) return;
        info.renderNodeMemoryUsage += node.computeApproximateMemoryUsage();
        info.renderNodeMemoryAllocated += node.computeApproximateMemoryAllocated();
    }

    private static void appendGfxInfo(View view, GfxInfo info) {
        info.viewCount++;
        computeRenderNodeUsage(view.mRenderNode, info);
        computeRenderNodeUsage(view.mBackgroundRenderNode, info);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                appendGfxInfo(group.getChildAt(i), info);
            }
        }
    }

    /**
     * @param immediate True, do now if not in traversal. False, put on queue and do later.
     * @return True, request has been queued. False, request has been completed.
     */
    boolean die(boolean immediate) {
        // Make sure we do execute immediately if we are in the middle of a traversal or the damage
        // done by dispatchDetachedFromWindow will cause havoc on return.
        if (immediate && !mIsInTraversal) {
            doDie();
            return false;
        }

        if (!mIsDrawing) {
            destroyHardwareRenderer();
        } else {
            Log.e(mTag, "Attempting to destroy the window while drawing!\n" +
                    "  window=" + this + ", title=" + mWindowAttributes.getTitle());
        }
        mHandler.sendEmptyMessage(MSG_DIE);
        return true;
    }

    void doDie() {
        checkThread();
        if (LOCAL_LOGV) Log.v(mTag, "DIE in " + this + " of " + mSurface);
        synchronized (this) {
            if (mRemoved) {
                return;
            }
            mRemoved = true;
            mOnBackInvokedDispatcher.detachFromWindow();
            if (mAdded) {
                dispatchDetachedFromWindow();
            }

            if (mAdded && !mFirst) {
                destroyHardwareRenderer();

                if (mView != null) {
                    int viewVisibility = mView.getVisibility();
                    boolean viewVisibilityChanged = mViewVisibility != viewVisibility;
                    if (mWindowAttributesChanged || viewVisibilityChanged) {
                        // If layout params have been changed, first give them
                        // to the window manager to make sure it has the correct
                        // animation info.
                        try {
                            if ((relayoutWindow(mWindowAttributes, viewVisibility, false)
                                    & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                                mWindowSession.finishDrawing(
                                    mWindow, null /* postDrawTransaction */, Integer.MAX_VALUE);
                            }
                        } catch (RemoteException e) {
                        }
                    }

                    destroySurface();
                }
            }

            // If our window is removed, we might not get notified about losing control.
            // Invoking this can release the leashes as soon as possible instead of relying on GC.
            mInsetsController.onControlsChanged(null);

            mAdded = false;
        }
        WindowManagerGlobal.getInstance().doRemoveView(this);
    }

    public void requestUpdateConfiguration(Configuration config) {
        Message msg = mHandler.obtainMessage(MSG_UPDATE_CONFIGURATION, config);
        mHandler.sendMessage(msg);
    }

    public void loadSystemProperties() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Profiling
                mProfileRendering = SystemProperties.getBoolean(PROPERTY_PROFILE_RENDERING, false);
                profileRendering(mAttachInfo.mHasWindowFocus);

                // Hardware rendering
                if (mAttachInfo.mThreadedRenderer != null) {
                    if (mAttachInfo.mThreadedRenderer.loadSystemProperties()) {
                        invalidate();
                    }
                }

                // Layout debugging
                boolean layout = DisplayProperties.debug_layout().orElse(false);
                if (layout != mAttachInfo.mDebugLayout) {
                    mAttachInfo.mDebugLayout = layout;
                    if (!mHandler.hasMessages(MSG_INVALIDATE_WORLD)) {
                        mHandler.sendEmptyMessageDelayed(MSG_INVALIDATE_WORLD, 200);
                    }
                }
            }
        });
    }

    private void destroyHardwareRenderer() {
        ThreadedRenderer hardwareRenderer = mAttachInfo.mThreadedRenderer;

        if (hardwareRenderer != null) {
            if (mHardwareRendererObserver != null) {
                hardwareRenderer.removeObserver(mHardwareRendererObserver);
            }
            if (mView != null) {
                hardwareRenderer.destroyHardwareResources(mView);
            }
            hardwareRenderer.destroy();
            hardwareRenderer.setRequested(false);

            mAttachInfo.mThreadedRenderer = null;
            mAttachInfo.mHardwareAccelerated = false;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private void dispatchResized(ClientWindowFrames frames, boolean reportDraw,
            MergedConfiguration mergedConfiguration, InsetsState insetsState, boolean forceLayout,
            boolean alwaysConsumeSystemBars, int displayId, int syncSeqId, int resizeMode) {
        Message msg = mHandler.obtainMessage(reportDraw ? MSG_RESIZED_REPORT : MSG_RESIZED);
        SomeArgs args = SomeArgs.obtain();
        final boolean sameProcessCall = (Binder.getCallingPid() == android.os.Process.myPid());
        if (sameProcessCall) {
            insetsState = new InsetsState(insetsState, true /* copySource */);
        }
        if (mTranslator != null) {
            mTranslator.translateInsetsStateInScreenToAppWindow(insetsState);
        }
        if (insetsState.getSourceOrDefaultVisibility(ITYPE_IME)) {
            ImeTracing.getInstance().triggerClientDump("ViewRootImpl#dispatchResized",
                    getInsetsController().getHost().getInputMethodManager(), null /* icProto */);
        }
        args.arg1 = sameProcessCall ? new ClientWindowFrames(frames) : frames;
        args.arg2 = sameProcessCall && mergedConfiguration != null
                ? new MergedConfiguration(mergedConfiguration) : mergedConfiguration;
        args.arg3 = insetsState;
        args.argi1 = forceLayout ? 1 : 0;
        args.argi2 = alwaysConsumeSystemBars ? 1 : 0;
        args.argi3 = displayId;
        args.argi4 = syncSeqId;
        args.argi5 = resizeMode;

        msg.obj = args;
        mHandler.sendMessage(msg);
    }

    private void dispatchInsetsControlChanged(InsetsState insetsState,
            InsetsSourceControl[] activeControls) {
        if (Binder.getCallingPid() == android.os.Process.myPid()) {
            insetsState = new InsetsState(insetsState, true /* copySource */);
            if (activeControls != null) {
                for (int i = activeControls.length - 1; i >= 0; i--) {
                    activeControls[i] = new InsetsSourceControl(activeControls[i]);
                }
            }
        }
        if (mTranslator != null) {
            mTranslator.translateInsetsStateInScreenToAppWindow(insetsState);
            mTranslator.translateSourceControlsInScreenToAppWindow(activeControls);
        }
        if (insetsState != null && insetsState.getSourceOrDefaultVisibility(ITYPE_IME)) {
            ImeTracing.getInstance().triggerClientDump("ViewRootImpl#dispatchInsetsControlChanged",
                    getInsetsController().getHost().getInputMethodManager(), null /* icProto */);
        }
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = insetsState;
        args.arg2 = activeControls;
        mHandler.obtainMessage(MSG_INSETS_CONTROL_CHANGED, args).sendToTarget();
    }

    private void showInsets(@InsetsType int types, boolean fromIme) {
        mHandler.obtainMessage(MSG_SHOW_INSETS, types, fromIme ? 1 : 0).sendToTarget();
    }

    private void hideInsets(@InsetsType int types, boolean fromIme) {
        mHandler.obtainMessage(MSG_HIDE_INSETS, types, fromIme ? 1 : 0).sendToTarget();
    }

    public void dispatchMoved(int newX, int newY) {
        if (DEBUG_LAYOUT) Log.v(mTag, "Window moved " + this + ": newX=" + newX + " newY=" + newY);
        if (mTranslator != null) {
            PointF point = new PointF(newX, newY);
            mTranslator.translatePointInScreenToAppWindow(point);
            newX = (int) (point.x + 0.5);
            newY = (int) (point.y + 0.5);
        }
        Message msg = mHandler.obtainMessage(MSG_WINDOW_MOVED, newX, newY);
        mHandler.sendMessage(msg);
    }

    /**
     * Represents a pending input event that is waiting in a queue.
     *
     * Input events are processed in serial order by the timestamp specified by
     * {@link InputEvent#getEventTimeNano()}.  In general, the input dispatcher delivers
     * one input event to the application at a time and waits for the application
     * to finish handling it before delivering the next one.
     *
     * However, because the application or IME can synthesize and inject multiple
     * key events at a time without going through the input dispatcher, we end up
     * needing a queue on the application's side.
     */
    private static final class QueuedInputEvent {
        public static final int FLAG_DELIVER_POST_IME = 1 << 0;
        public static final int FLAG_DEFERRED = 1 << 1;
        public static final int FLAG_FINISHED = 1 << 2;
        public static final int FLAG_FINISHED_HANDLED = 1 << 3;
        public static final int FLAG_RESYNTHESIZED = 1 << 4;
        public static final int FLAG_UNHANDLED = 1 << 5;
        public static final int FLAG_MODIFIED_FOR_COMPATIBILITY = 1 << 6;

        public QueuedInputEvent mNext;

        public InputEvent mEvent;
        public InputEventReceiver mReceiver;
        public int mFlags;

        public boolean shouldSkipIme() {
            if ((mFlags & FLAG_DELIVER_POST_IME) != 0) {
                return true;
            }
            return mEvent instanceof MotionEvent
                    && (mEvent.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
                        || mEvent.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER));
        }

        public boolean shouldSendToSynthesizer() {
            if ((mFlags & FLAG_UNHANDLED) != 0) {
                return true;
            }

            return false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("QueuedInputEvent{flags=");
            boolean hasPrevious = false;
            hasPrevious = flagToString("DELIVER_POST_IME", FLAG_DELIVER_POST_IME, hasPrevious, sb);
            hasPrevious = flagToString("DEFERRED", FLAG_DEFERRED, hasPrevious, sb);
            hasPrevious = flagToString("FINISHED", FLAG_FINISHED, hasPrevious, sb);
            hasPrevious = flagToString("FINISHED_HANDLED", FLAG_FINISHED_HANDLED, hasPrevious, sb);
            hasPrevious = flagToString("RESYNTHESIZED", FLAG_RESYNTHESIZED, hasPrevious, sb);
            hasPrevious = flagToString("UNHANDLED", FLAG_UNHANDLED, hasPrevious, sb);
            if (!hasPrevious) {
                sb.append("0");
            }
            sb.append(", hasNextQueuedEvent=" + (mEvent != null ? "true" : "false"));
            sb.append(", hasInputEventReceiver=" + (mReceiver != null ? "true" : "false"));
            sb.append(", mEvent=" + mEvent + "}");
            return sb.toString();
        }

        private boolean flagToString(String name, int flag,
                boolean hasPrevious, StringBuilder sb) {
            if ((mFlags & flag) != 0) {
                if (hasPrevious) {
                    sb.append("|");
                }
                sb.append(name);
                return true;
            }
            return hasPrevious;
        }
    }

    private QueuedInputEvent obtainQueuedInputEvent(InputEvent event,
            InputEventReceiver receiver, int flags) {
        QueuedInputEvent q = mQueuedInputEventPool;
        if (q != null) {
            mQueuedInputEventPoolSize -= 1;
            mQueuedInputEventPool = q.mNext;
            q.mNext = null;
        } else {
            q = new QueuedInputEvent();
        }

        q.mEvent = event;
        q.mReceiver = receiver;
        q.mFlags = flags;
        return q;
    }

    private void recycleQueuedInputEvent(QueuedInputEvent q) {
        q.mEvent = null;
        q.mReceiver = null;

        if (mQueuedInputEventPoolSize < MAX_QUEUED_INPUT_EVENT_POOL_SIZE) {
            mQueuedInputEventPoolSize += 1;
            q.mNext = mQueuedInputEventPool;
            mQueuedInputEventPool = q;
        }
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    void enqueueInputEvent(InputEvent event) {
        enqueueInputEvent(event, null, 0, false);
    }

    @UnsupportedAppUsage
    void enqueueInputEvent(InputEvent event,
            InputEventReceiver receiver, int flags, boolean processImmediately) {
        QueuedInputEvent q = obtainQueuedInputEvent(event, receiver, flags);

        if (event instanceof MotionEvent) {
            MotionEvent me = (MotionEvent) event;
            if (me.getAction() == MotionEvent.ACTION_CANCEL) {
                EventLog.writeEvent(EventLogTags.VIEW_ENQUEUE_INPUT_EVENT, "Motion - Cancel",
                        getTitle().toString());
            }
        } else if (event instanceof KeyEvent) {
            KeyEvent ke = (KeyEvent) event;
            if (ke.isCanceled()) {
                EventLog.writeEvent(EventLogTags.VIEW_ENQUEUE_INPUT_EVENT, "Key - Cancel",
                        getTitle().toString());
            }
        }
        // Always enqueue the input event in order, regardless of its time stamp.
        // We do this because the application or the IME may inject key events
        // in response to touch events and we want to ensure that the injected keys
        // are processed in the order they were received and we cannot trust that
        // the time stamp of injected events are monotonic.
        QueuedInputEvent last = mPendingInputEventTail;
        if (last == null) {
            mPendingInputEventHead = q;
            mPendingInputEventTail = q;
        } else {
            last.mNext = q;
            mPendingInputEventTail = q;
        }
        mPendingInputEventCount += 1;
        Trace.traceCounter(Trace.TRACE_TAG_INPUT, mPendingInputEventQueueLengthCounterName,
                mPendingInputEventCount);

        if (processImmediately) {
            doProcessInputEvents();
        } else {
            scheduleProcessInputEvents();
        }
    }

    private void scheduleProcessInputEvents() {
        if (!mProcessInputEventsScheduled) {
            mProcessInputEventsScheduled = true;
            Message msg = mHandler.obtainMessage(MSG_PROCESS_INPUT_EVENTS);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    void doProcessInputEvents() {
        // Deliver all pending input events in the queue.
        while (mPendingInputEventHead != null) {
            QueuedInputEvent q = mPendingInputEventHead;
            mPendingInputEventHead = q.mNext;
            if (mPendingInputEventHead == null) {
                mPendingInputEventTail = null;
            }
            q.mNext = null;

            mPendingInputEventCount -= 1;
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, mPendingInputEventQueueLengthCounterName,
                    mPendingInputEventCount);

            mViewFrameInfo.setInputEvent(mInputEventAssigner.processEvent(q.mEvent));

            deliverInputEvent(q);
        }

        // We are done processing all input events that we can process right now
        // so we can clear the pending flag immediately.
        if (mProcessInputEventsScheduled) {
            mProcessInputEventsScheduled = false;
            mHandler.removeMessages(MSG_PROCESS_INPUT_EVENTS);
        }
    }

    private void deliverInputEvent(QueuedInputEvent q) {
        Trace.asyncTraceBegin(Trace.TRACE_TAG_VIEW, "deliverInputEvent",
                q.mEvent.getId());

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "deliverInputEvent src=0x"
                    + Integer.toHexString(q.mEvent.getSource()) + " eventTimeNano="
                    + q.mEvent.getEventTimeNano() + " id=0x"
                    + Integer.toHexString(q.mEvent.getId()));
        }
        try {
            if (mInputEventConsistencyVerifier != null) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "verifyEventConsistency");
                try {
                    mInputEventConsistencyVerifier.onInputEvent(q.mEvent, 0);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
            }

            InputStage stage;
            if (q.shouldSendToSynthesizer()) {
                stage = mSyntheticInputStage;
            } else {
                stage = q.shouldSkipIme() ? mFirstPostImeInputStage : mFirstInputStage;
            }

            if (q.mEvent instanceof KeyEvent) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "preDispatchToUnhandledKeyManager");
                try {
                    mUnhandledKeyManager.preDispatch((KeyEvent) q.mEvent);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
            }

            if (stage != null) {
                handleWindowFocusChanged();
                stage.deliver(q);
            } else {
                finishInputEvent(q);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    private void finishInputEvent(QueuedInputEvent q) {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_VIEW, "deliverInputEvent",
                q.mEvent.getId());

        if (q.mReceiver != null) {
            boolean handled = (q.mFlags & QueuedInputEvent.FLAG_FINISHED_HANDLED) != 0;
            boolean modified = (q.mFlags & QueuedInputEvent.FLAG_MODIFIED_FOR_COMPATIBILITY) != 0;
            if (modified) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "processInputEventBeforeFinish");
                InputEvent processedEvent;
                try {
                    processedEvent =
                            mInputCompatProcessor.processInputEventBeforeFinish(q.mEvent);
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_VIEW);
                }
                if (processedEvent != null) {
                    q.mReceiver.finishInputEvent(processedEvent, handled);
                }
            } else {
                q.mReceiver.finishInputEvent(q.mEvent, handled);
            }
        } else {
            q.mEvent.recycleIfNeededAfterDispatch();
        }

        recycleQueuedInputEvent(q);
    }

    static boolean isTerminalInputEvent(InputEvent event) {
        if (event instanceof KeyEvent) {
            final KeyEvent keyEvent = (KeyEvent)event;
            return keyEvent.getAction() == KeyEvent.ACTION_UP;
        } else {
            final MotionEvent motionEvent = (MotionEvent)event;
            final int action = motionEvent.getAction();
            return action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_HOVER_EXIT;
        }
    }

    void scheduleConsumeBatchedInput() {
        // If anything is currently scheduled to consume batched input then there's no point in
        // scheduling it again.
        if (!mConsumeBatchedInputScheduled && !mConsumeBatchedInputImmediatelyScheduled) {
            mConsumeBatchedInputScheduled = true;
            mChoreographer.postCallback(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void unscheduleConsumeBatchedInput() {
        if (mConsumeBatchedInputScheduled) {
            mConsumeBatchedInputScheduled = false;
            mChoreographer.removeCallbacks(Choreographer.CALLBACK_INPUT,
                    mConsumedBatchedInputRunnable, null);
        }
    }

    void scheduleConsumeBatchedInputImmediately() {
        if (!mConsumeBatchedInputImmediatelyScheduled) {
            unscheduleConsumeBatchedInput();
            mConsumeBatchedInputImmediatelyScheduled = true;
            mHandler.post(mConsumeBatchedInputImmediatelyRunnable);
        }
    }

    boolean doConsumeBatchedInput(long frameTimeNanos) {
        final boolean consumedBatches;
        if (mInputEventReceiver != null) {
            consumedBatches = mInputEventReceiver.consumeBatchedInputEvents(frameTimeNanos);
        } else {
            consumedBatches = false;
        }
        doProcessInputEvents();
        return consumedBatches;
    }

    final class TraversalRunnable implements Runnable {
        @Override
        public void run() {
            doTraversal();
        }
    }
    final TraversalRunnable mTraversalRunnable = new TraversalRunnable();

    final class WindowInputEventReceiver extends InputEventReceiver {
        public WindowInputEventReceiver(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEvent(InputEvent event) {
            Trace.traceBegin(Trace.TRACE_TAG_VIEW, "processInputEventForCompatibility");
            List<InputEvent> processedEvents;
            try {
                processedEvents =
                    mInputCompatProcessor.processInputEventForCompatibility(event);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            if (processedEvents != null) {
                if (processedEvents.isEmpty()) {
                    // InputEvent consumed by mInputCompatProcessor
                    finishInputEvent(event, true);
                } else {
                    for (int i = 0; i < processedEvents.size(); i++) {
                        enqueueInputEvent(
                                processedEvents.get(i), this,
                                QueuedInputEvent.FLAG_MODIFIED_FOR_COMPATIBILITY, true);
                    }
                }
            } else {
                enqueueInputEvent(event, this, 0, true);
            }
        }

        @Override
        public void onBatchedInputEventPending(int source) {
            final boolean unbuffered = mUnbufferedInputDispatch
                    || (source & mUnbufferedInputSource) != SOURCE_CLASS_NONE;
            if (unbuffered) {
                if (mConsumeBatchedInputScheduled) {
                    unscheduleConsumeBatchedInput();
                }
                // Consume event immediately if unbuffered input dispatch has been requested.
                consumeBatchedInputEvents(-1);
                return;
            }
            scheduleConsumeBatchedInput();
        }

        @Override
        public void onFocusEvent(boolean hasFocus) {
            windowFocusChanged(hasFocus);
        }

        @Override
        public void onTouchModeChanged(boolean inTouchMode) {
            touchModeChanged(inTouchMode);
        }

        @Override
        public void onPointerCaptureEvent(boolean pointerCaptureEnabled) {
            dispatchPointerCaptureChanged(pointerCaptureEnabled);
        }

        @Override
        public void onDragEvent(boolean isExiting, float x, float y) {
            // force DRAG_EXITED_EVENT if appropriate
            DragEvent event = DragEvent.obtain(
                    isExiting ? DragEvent.ACTION_DRAG_EXITED : DragEvent.ACTION_DRAG_LOCATION,
                    x, y, 0 /* offsetX */, 0 /* offsetY */, null/* localState */,
                    null/* description */, null /* data */, null /* dragSurface */,
                    null /* dragAndDropPermissions */, false /* result */);
            dispatchDragEvent(event);
        }

        @Override
        public void dispose() {
            unscheduleConsumeBatchedInput();
            super.dispose();
        }
    }
    private WindowInputEventReceiver mInputEventReceiver;

    final class InputMetricsListener
            implements HardwareRendererObserver.OnFrameMetricsAvailableListener {
        public long[] data = new long[FrameMetrics.Index.FRAME_STATS_COUNT];

        @Override
        public void onFrameMetricsAvailable(int dropCountSinceLastInvocation) {
            final int inputEventId = (int) data[FrameMetrics.Index.INPUT_EVENT_ID];
            if (inputEventId == INVALID_INPUT_EVENT_ID) {
                return;
            }
            final long presentTime = data[FrameMetrics.Index.DISPLAY_PRESENT_TIME];
            if (presentTime <= 0) {
                // Present time is not available for this frame. If the present time is not
                // available, we cannot compute end-to-end input latency metrics.
                return;
            }
            final long gpuCompletedTime = data[FrameMetrics.Index.GPU_COMPLETED];
            if (mInputEventReceiver == null) {
                return;
            }
            if (gpuCompletedTime >= presentTime) {
                final double discrepancyMs = (gpuCompletedTime - presentTime) * 1E-6;
                final long vsyncId = data[FrameMetrics.Index.FRAME_TIMELINE_VSYNC_ID];
                Log.w(TAG, "Not reporting timeline because gpuCompletedTime is " + discrepancyMs
                        + "ms ahead of presentTime. FRAME_TIMELINE_VSYNC_ID=" + vsyncId
                        + ", INPUT_EVENT_ID=" + inputEventId);
                // TODO(b/186664409): figure out why this sometimes happens
                return;
            }
            mInputEventReceiver.reportTimeline(inputEventId, gpuCompletedTime, presentTime);
        }
    }
    HardwareRendererObserver mHardwareRendererObserver;

    final class ConsumeBatchedInputRunnable implements Runnable {
        @Override
        public void run() {
            mConsumeBatchedInputScheduled = false;
            if (doConsumeBatchedInput(mChoreographer.getFrameTimeNanos())) {
                // If we consumed a batch here, we want to go ahead and schedule the
                // consumption of batched input events on the next frame. Otherwise, we would
                // wait until we have more input events pending and might get starved by other
                // things occurring in the process.
                scheduleConsumeBatchedInput();
            }
        }
    }
    final ConsumeBatchedInputRunnable mConsumedBatchedInputRunnable =
            new ConsumeBatchedInputRunnable();
    boolean mConsumeBatchedInputScheduled;

    final class ConsumeBatchedInputImmediatelyRunnable implements Runnable {
        @Override
        public void run() {
            mConsumeBatchedInputImmediatelyScheduled = false;
            doConsumeBatchedInput(-1);
        }
    }
    final ConsumeBatchedInputImmediatelyRunnable mConsumeBatchedInputImmediatelyRunnable =
            new ConsumeBatchedInputImmediatelyRunnable();
    boolean mConsumeBatchedInputImmediatelyScheduled;

    final class InvalidateOnAnimationRunnable implements Runnable {
        private boolean mPosted;
        private final ArrayList<View> mViews = new ArrayList<View>();
        private final ArrayList<AttachInfo.InvalidateInfo> mViewRects =
                new ArrayList<AttachInfo.InvalidateInfo>();
        private View[] mTempViews;
        private AttachInfo.InvalidateInfo[] mTempViewRects;

        public void addView(View view) {
            synchronized (this) {
                mViews.add(view);
                postIfNeededLocked();
            }
        }

        public void addViewRect(AttachInfo.InvalidateInfo info) {
            synchronized (this) {
                mViewRects.add(info);
                postIfNeededLocked();
            }
        }

        public void removeView(View view) {
            synchronized (this) {
                mViews.remove(view);

                for (int i = mViewRects.size(); i-- > 0; ) {
                    AttachInfo.InvalidateInfo info = mViewRects.get(i);
                    if (info.target == view) {
                        mViewRects.remove(i);
                        info.recycle();
                    }
                }

                if (mPosted && mViews.isEmpty() && mViewRects.isEmpty()) {
                    mChoreographer.removeCallbacks(Choreographer.CALLBACK_ANIMATION, this, null);
                    mPosted = false;
                }
            }
        }

        @Override
        public void run() {
            final int viewCount;
            final int viewRectCount;
            synchronized (this) {
                mPosted = false;

                viewCount = mViews.size();
                if (viewCount != 0) {
                    mTempViews = mViews.toArray(mTempViews != null
                            ? mTempViews : new View[viewCount]);
                    mViews.clear();
                }

                viewRectCount = mViewRects.size();
                if (viewRectCount != 0) {
                    mTempViewRects = mViewRects.toArray(mTempViewRects != null
                            ? mTempViewRects : new AttachInfo.InvalidateInfo[viewRectCount]);
                    mViewRects.clear();
                }
            }

            for (int i = 0; i < viewCount; i++) {
                mTempViews[i].invalidate();
                mTempViews[i] = null;
            }

            for (int i = 0; i < viewRectCount; i++) {
                final View.AttachInfo.InvalidateInfo info = mTempViewRects[i];
                info.target.invalidate(info.left, info.top, info.right, info.bottom);
                info.recycle();
            }
        }

        private void postIfNeededLocked() {
            if (!mPosted) {
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
                mPosted = true;
            }
        }
    }
    final InvalidateOnAnimationRunnable mInvalidateOnAnimationRunnable =
            new InvalidateOnAnimationRunnable();

    public void dispatchInvalidateDelayed(View view, long delayMilliseconds) {
        Message msg = mHandler.obtainMessage(MSG_INVALIDATE, view);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateRectDelayed(AttachInfo.InvalidateInfo info,
            long delayMilliseconds) {
        final Message msg = mHandler.obtainMessage(MSG_INVALIDATE_RECT, info);
        mHandler.sendMessageDelayed(msg, delayMilliseconds);
    }

    public void dispatchInvalidateOnAnimation(View view) {
        mInvalidateOnAnimationRunnable.addView(view);
    }

    public void dispatchInvalidateRectOnAnimation(AttachInfo.InvalidateInfo info) {
        mInvalidateOnAnimationRunnable.addViewRect(info);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void cancelInvalidate(View view) {
        mHandler.removeMessages(MSG_INVALIDATE, view);
        // fixme: might leak the AttachInfo.InvalidateInfo objects instead of returning
        // them to the pool
        mHandler.removeMessages(MSG_INVALIDATE_RECT, view);
        mInvalidateOnAnimationRunnable.removeView(view);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void dispatchInputEvent(InputEvent event) {
        dispatchInputEvent(event, null);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void dispatchInputEvent(InputEvent event, InputEventReceiver receiver) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = event;
        args.arg2 = receiver;
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_INPUT_EVENT, args);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void synthesizeInputEvent(InputEvent event) {
        Message msg = mHandler.obtainMessage(MSG_SYNTHESIZE_INPUT_EVENT, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    @UnsupportedAppUsage
    public void dispatchKeyFromIme(KeyEvent event) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_KEY_FROM_IME, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    public void dispatchKeyFromAutofill(KeyEvent event) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_KEY_FROM_AUTOFILL, event);
        msg.setAsynchronous(true);
        mHandler.sendMessage(msg);
    }

    /**
     * Reinject unhandled {@link InputEvent}s in order to synthesize fallbacks events.
     *
     * Note that it is the responsibility of the caller of this API to recycle the InputEvent it
     * passes in.
     */
    @UnsupportedAppUsage
    public void dispatchUnhandledInputEvent(InputEvent event) {
        if (event instanceof MotionEvent) {
            event = MotionEvent.obtain((MotionEvent) event);
        }
        synthesizeInputEvent(event);
    }

    public void dispatchAppVisibility(boolean visible) {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_APP_VISIBILITY);
        msg.arg1 = visible ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    public void dispatchGetNewSurface() {
        Message msg = mHandler.obtainMessage(MSG_DISPATCH_GET_NEW_SURFACE);
        mHandler.sendMessage(msg);
    }

    /**
     * Notifies this {@link ViewRootImpl} object that window focus has changed.
     */
    public void windowFocusChanged(boolean hasFocus) {
        synchronized (this) {
            mWindowFocusChanged = true;
            mUpcomingWindowFocus = hasFocus;
        }
        Message msg = Message.obtain();
        msg.what = MSG_WINDOW_FOCUS_CHANGED;
        mHandler.sendMessage(msg);
    }

    /**
     * Notifies this {@link ViewRootImpl} object that touch mode state has changed.
     */
    public void touchModeChanged(boolean inTouchMode) {
        synchronized (this) {
            mUpcomingInTouchMode = inTouchMode;
        }
        Message msg = Message.obtain();
        msg.what = MSG_WINDOW_TOUCH_MODE_CHANGED;
        mHandler.sendMessage(msg);
    }

    public void dispatchWindowShown() {
        mHandler.sendEmptyMessage(MSG_DISPATCH_WINDOW_SHOWN);
    }

    public void dispatchCloseSystemDialogs(String reason) {
        Message msg = Message.obtain();
        msg.what = MSG_CLOSE_SYSTEM_DIALOGS;
        msg.obj = reason;
        mHandler.sendMessage(msg);
    }

    public void dispatchDragEvent(DragEvent event) {
        final int what;
        if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
            what = MSG_DISPATCH_DRAG_LOCATION_EVENT;
            mHandler.removeMessages(what);
        } else {
            what = MSG_DISPATCH_DRAG_EVENT;
        }
        Message msg = mHandler.obtainMessage(what, event);
        mHandler.sendMessage(msg);
    }

    public void updatePointerIcon(float x, float y) {
        final int what = MSG_UPDATE_POINTER_ICON;
        mHandler.removeMessages(what);
        final long now = SystemClock.uptimeMillis();
        final MotionEvent event = MotionEvent.obtain(
                0, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        Message msg = mHandler.obtainMessage(what, event);
        mHandler.sendMessage(msg);
    }

    public void dispatchCheckFocus() {
        if (!mHandler.hasMessages(MSG_CHECK_FOCUS)) {
            // This will result in a call to checkFocus() below.
            mHandler.sendEmptyMessage(MSG_CHECK_FOCUS);
        }
    }

    public void dispatchRequestKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
        mHandler.obtainMessage(
                MSG_REQUEST_KEYBOARD_SHORTCUTS, deviceId, 0, receiver).sendToTarget();
    }

    private void dispatchPointerCaptureChanged(boolean on) {
        final int what = MSG_POINTER_CAPTURE_CHANGED;
        mHandler.removeMessages(what);
        Message msg = mHandler.obtainMessage(what);
        msg.arg1 = on ? 1 : 0;
        mHandler.sendMessage(msg);
    }

    /**
     * Post a callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     * This event is send at most once every
     * {@link ViewConfiguration#getSendRecurringAccessibilityEventsInterval()}.
     */
    private void postSendWindowContentChangedCallback(View source, int changeType) {
        if (mSendWindowContentChangedAccessibilityEvent == null) {
            mSendWindowContentChangedAccessibilityEvent =
                new SendWindowContentChangedAccessibilityEvent();
        }
        mSendWindowContentChangedAccessibilityEvent.runOrPost(source, changeType);
    }

    /**
     * Remove a posted callback to send a
     * {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED} event.
     */
    private void removeSendWindowContentChangedCallback() {
        if (mSendWindowContentChangedAccessibilityEvent != null) {
            mHandler.removeCallbacks(mSendWindowContentChangedAccessibilityEvent);
        }
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        return false;
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return false;
    }

    @Override
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
        return null;
    }

    @Override
    public ActionMode startActionModeForChild(
            View originalView, ActionMode.Callback callback, int type) {
        return null;
    }

    @Override
    public void createContextMenu(ContextMenu menu) {
    }

    @Override
    public void childDrawableStateChanged(View child) {
    }

    @Override
    public boolean requestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (mView == null || mStopped || mPausedForTransition) {
            return false;
        }

        // Immediately flush pending content changed event (if any) to preserve event order
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && mSendWindowContentChangedAccessibilityEvent != null
                && mSendWindowContentChangedAccessibilityEvent.mSource != null) {
            mSendWindowContentChangedAccessibilityEvent.removeCallbacksAndRun();
        }

        // Intercept accessibility focus events fired by virtual nodes to keep
        // track of accessibility focus position in such nodes.
        final int eventType = event.getEventType();
        final View source = getSourceForAccessibilityEvent(event);
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                if (source != null) {
                    AccessibilityNodeProvider provider = source.getAccessibilityNodeProvider();
                    if (provider != null) {
                        final int virtualNodeId = AccessibilityNodeInfo.getVirtualDescendantId(
                                event.getSourceNodeId());
                        final AccessibilityNodeInfo node;
                        node = provider.createAccessibilityNodeInfo(virtualNodeId);
                        setAccessibilityFocus(source, node);
                    }
                }
            } break;
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                if (source != null && source.getAccessibilityNodeProvider() != null) {
                    setAccessibilityFocus(null, null);
                }
            } break;


            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                handleWindowContentChangedEvent(event);
            } break;
        }
        mAccessibilityManager.sendAccessibilityEvent(event);
        return true;
    }

    private View getSourceForAccessibilityEvent(AccessibilityEvent event) {
        final long sourceNodeId = event.getSourceNodeId();
        final int accessibilityViewId = AccessibilityNodeInfo.getAccessibilityViewId(
                sourceNodeId);
        return AccessibilityNodeIdManager.getInstance().findView(accessibilityViewId);
    }

    /**
     * Updates the focused virtual view, when necessary, in response to a
     * content changed event.
     * <p>
     * This is necessary to get updated bounds after a position change.
     *
     * @param event an accessibility event of type
     *              {@link AccessibilityEvent#TYPE_WINDOW_CONTENT_CHANGED}
     */
    private void handleWindowContentChangedEvent(AccessibilityEvent event) {
        final View focusedHost = mAccessibilityFocusedHost;
        if (focusedHost == null || mAccessibilityFocusedVirtualView == null) {
            // No virtual view focused, nothing to do here.
            return;
        }

        final AccessibilityNodeProvider provider = focusedHost.getAccessibilityNodeProvider();
        if (provider == null) {
            // Error state: virtual view with no provider. Clear focus.
            mAccessibilityFocusedHost = null;
            mAccessibilityFocusedVirtualView = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);
            return;
        }

        // We only care about change types that may affect the bounds of the
        // focused virtual view.
        final int changes = event.getContentChangeTypes();
        if ((changes & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) == 0
                && changes != AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) {
            return;
        }

        final long eventSourceNodeId = event.getSourceNodeId();
        final int changedViewId = AccessibilityNodeInfo.getAccessibilityViewId(eventSourceNodeId);

        // Search up the tree for subtree containment.
        boolean hostInSubtree = false;
        View root = mAccessibilityFocusedHost;
        while (root != null && !hostInSubtree) {
            if (changedViewId == root.getAccessibilityViewId()) {
                hostInSubtree = true;
            } else {
                final ViewParent parent = root.getParent();
                if (parent instanceof View) {
                    root = (View) parent;
                } else {
                    root = null;
                }
            }
        }

        // We care only about changes in subtrees containing the host view.
        if (!hostInSubtree) {
            return;
        }

        final long focusedSourceNodeId = mAccessibilityFocusedVirtualView.getSourceNodeId();
        int focusedChildId = AccessibilityNodeInfo.getVirtualDescendantId(focusedSourceNodeId);

        // Refresh the node for the focused virtual view.
        final Rect oldBounds = mTempRect;
        mAccessibilityFocusedVirtualView.getBoundsInScreen(oldBounds);
        mAccessibilityFocusedVirtualView = provider.createAccessibilityNodeInfo(focusedChildId);
        if (mAccessibilityFocusedVirtualView == null) {
            // Error state: The node no longer exists. Clear focus.
            mAccessibilityFocusedHost = null;
            focusedHost.clearAccessibilityFocusNoCallbacks(0);

            // This will probably fail, but try to keep the provider's internal
            // state consistent by clearing focus.
            provider.performAction(focusedChildId,
                    AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS.getId(), null);
            invalidateRectOnScreen(oldBounds);
        } else {
            // The node was refreshed, invalidate bounds if necessary.
            final Rect newBounds = mAccessibilityFocusedVirtualView.getBoundsInScreen();
            if (!oldBounds.equals(newBounds)) {
                oldBounds.union(newBounds);
                invalidateRectOnScreen(oldBounds);
            }
        }
    }

    @Override
    public void notifySubtreeAccessibilityStateChanged(View child, View source, int changeType) {
        postSendWindowContentChangedCallback(Preconditions.checkNotNull(source), changeType);
    }

    @Override
    public boolean canResolveLayoutDirection() {
        return true;
    }

    @Override
    public boolean isLayoutDirectionResolved() {
        return true;
    }

    @Override
    public int getLayoutDirection() {
        return View.LAYOUT_DIRECTION_RESOLVED_DEFAULT;
    }

    @Override
    public boolean canResolveTextDirection() {
        return true;
    }

    @Override
    public boolean isTextDirectionResolved() {
        return true;
    }

    @Override
    public int getTextDirection() {
        return View.TEXT_DIRECTION_RESOLVED_DEFAULT;
    }

    @Override
    public boolean canResolveTextAlignment() {
        return true;
    }

    @Override
    public boolean isTextAlignmentResolved() {
        return true;
    }

    @Override
    public int getTextAlignment() {
        return View.TEXT_ALIGNMENT_RESOLVED_DEFAULT;
    }

    private View getCommonPredecessor(View first, View second) {
        if (mTempHashSet == null) {
            mTempHashSet = new HashSet<View>();
        }
        HashSet<View> seen = mTempHashSet;
        seen.clear();
        View firstCurrent = first;
        while (firstCurrent != null) {
            seen.add(firstCurrent);
            ViewParent firstCurrentParent = firstCurrent.mParent;
            if (firstCurrentParent instanceof View) {
                firstCurrent = (View) firstCurrentParent;
            } else {
                firstCurrent = null;
            }
        }
        View secondCurrent = second;
        while (secondCurrent != null) {
            if (seen.contains(secondCurrent)) {
                seen.clear();
                return secondCurrent;
            }
            ViewParent secondCurrentParent = secondCurrent.mParent;
            if (secondCurrentParent instanceof View) {
                secondCurrent = (View) secondCurrentParent;
            } else {
                secondCurrent = null;
            }
        }
        seen.clear();
        return null;
    }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views.");
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // ViewAncestor never intercepts touch event, so this can be a no-op
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (rectangle == null) {
            return scrollToRectOrFocus(null, immediate);
        }
        rectangle.offset(child.getLeft() - child.getScrollX(),
                child.getTop() - child.getScrollY());
        final boolean scrolled = scrollToRectOrFocus(rectangle, immediate);
        mTempRect.set(rectangle);
        mTempRect.offset(0, -mCurScrollY);
        mTempRect.offset(mAttachInfo.mWindowLeft, mAttachInfo.mWindowTop);
        try {
            mWindowSession.onRectangleOnScreenRequested(mWindow, mTempRect);
        } catch (RemoteException re) {
            /* ignore */
        }
        return scrolled;
    }

    @Override
    public void childHasTransientStateChanged(View child, boolean hasTransientState) {
        // Do nothing.
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return false;
    }

    @Override
    public void onStopNestedScroll(View target) {
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
            int dxUnconsumed, int dyUnconsumed) {
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return false;
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public boolean onNestedPrePerformAccessibilityAction(View target, int action, Bundle args) {
        return false;
    }

    /**
     * Adds a scroll capture callback to this window.
     *
     * @param callback the callback to add
     */
    public void addScrollCaptureCallback(ScrollCaptureCallback callback) {
        if (mRootScrollCaptureCallbacks == null) {
            mRootScrollCaptureCallbacks = new HashSet<>();
        }
        mRootScrollCaptureCallbacks.add(callback);
    }

    /**
     * Removes a scroll capture callback from this window.
     *
     * @param callback the callback to remove
     */
    public void removeScrollCaptureCallback(ScrollCaptureCallback callback) {
        if (mRootScrollCaptureCallbacks != null) {
            mRootScrollCaptureCallbacks.remove(callback);
            if (mRootScrollCaptureCallbacks.isEmpty()) {
                mRootScrollCaptureCallbacks = null;
            }
        }
    }

    /**
     * Dispatches a scroll capture request to the view hierarchy on the ui thread.
     *
     * @param listener for the response
     */
    public void dispatchScrollCaptureRequest(@NonNull IScrollCaptureResponseListener listener) {
        mHandler.obtainMessage(MSG_REQUEST_SCROLL_CAPTURE, listener).sendToTarget();
    }

    /**
     * Collect and include any ScrollCaptureCallback instances registered with the window.
     *
     * @see #addScrollCaptureCallback(ScrollCaptureCallback)
     * @param results an object to collect the results of the search
     */
    private void collectRootScrollCaptureTargets(ScrollCaptureSearchResults results) {
        if (mRootScrollCaptureCallbacks == null) {
            return;
        }
        for (ScrollCaptureCallback cb : mRootScrollCaptureCallbacks) {
            // Add to the list for consideration
            Point offset = new Point(mView.getLeft(), mView.getTop());
            Rect rect = new Rect(0, 0, mView.getWidth(), mView.getHeight());
            results.addTarget(new ScrollCaptureTarget(mView, rect, offset, cb));
        }
    }

    /**
     * Update the timeout for scroll capture requests. Only affects this view root.
     * The default value is {@link #SCROLL_CAPTURE_REQUEST_TIMEOUT_MILLIS}.
     *
     * @param timeMillis the new timeout in milliseconds
     */
    public void setScrollCaptureRequestTimeout(int timeMillis) {
        mScrollCaptureRequestTimeout = timeMillis;
    }

    /**
     * Get the current timeout for scroll capture requests.
     *
     * @return the timeout in milliseconds
     */
    public long getScrollCaptureRequestTimeout() {
        return mScrollCaptureRequestTimeout;
    }

    /**
     * Handles an inbound request for scroll capture from the system. A search will be
     * dispatched through the view tree to locate scrolling content.
     * <p>
     * A call to
     * {@link IScrollCaptureResponseListener#onScrollCaptureResponse} will follow.
     *
     * @param listener to receive responses
     * @see ScrollCaptureSearchResults
     */
    public void handleScrollCaptureRequest(@NonNull IScrollCaptureResponseListener listener) {
        ScrollCaptureSearchResults results =
                new ScrollCaptureSearchResults(mContext.getMainExecutor());

        // Window (root) level callbacks
        collectRootScrollCaptureTargets(results);

        // Search through View-tree
        View rootView = getView();
        if (rootView != null) {
            Point point = new Point();
            Rect rect = new Rect(0, 0, rootView.getWidth(), rootView.getHeight());
            getChildVisibleRect(rootView, rect, point);
            rootView.dispatchScrollCaptureSearch(rect, point, results::addTarget);
        }
        Runnable onComplete = () -> dispatchScrollCaptureSearchResponse(listener, results);
        results.setOnCompleteListener(onComplete);
        if (!results.isComplete()) {
            mHandler.postDelayed(results::finish, getScrollCaptureRequestTimeout());
        }
    }

    /** Called by {@link #handleScrollCaptureRequest} when a result is returned */
    private void dispatchScrollCaptureSearchResponse(
            @NonNull IScrollCaptureResponseListener listener,
            @NonNull ScrollCaptureSearchResults results) {

        ScrollCaptureTarget selectedTarget = results.getTopResult();

        ScrollCaptureResponse.Builder response = new ScrollCaptureResponse.Builder();
        response.setWindowTitle(getTitle().toString());
        response.setPackageName(mContext.getPackageName());

        StringWriter writer =  new StringWriter();
        IndentingPrintWriter pw = new IndentingPrintWriter(writer);
        results.dump(pw);
        pw.flush();
        response.addMessage(writer.toString());

        if (selectedTarget == null) {
            response.setDescription("No scrollable targets found in window");
            try {
                listener.onScrollCaptureResponse(response.build());
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to send scroll capture search result", e);
            }
            return;
        }

        response.setDescription("Connected");

        // Compute area covered by scrolling content within window
        Rect boundsInWindow = new Rect();
        View containingView = selectedTarget.getContainingView();
        containingView.getLocationInWindow(mAttachInfo.mTmpLocation);
        boundsInWindow.set(selectedTarget.getScrollBounds());
        boundsInWindow.offset(mAttachInfo.mTmpLocation[0], mAttachInfo.mTmpLocation[1]);
        response.setBoundsInWindow(boundsInWindow);

        // Compute the area on screen covered by the window
        Rect boundsOnScreen = new Rect();
        mView.getLocationOnScreen(mAttachInfo.mTmpLocation);
        boundsOnScreen.set(0, 0, mView.getWidth(), mView.getHeight());
        boundsOnScreen.offset(mAttachInfo.mTmpLocation[0], mAttachInfo.mTmpLocation[1]);
        response.setWindowBounds(boundsOnScreen);

        // Create a connection and return it to the caller
        ScrollCaptureConnection connection = new ScrollCaptureConnection(
                mView.getContext().getMainExecutor(), selectedTarget);
        response.setConnection(connection);

        try {
            listener.onScrollCaptureResponse(response.build());
        } catch (RemoteException e) {
            if (DEBUG_SCROLL_CAPTURE) {
                Log.w(TAG, "Failed to send scroll capture search response.", e);
            }
            connection.close();
        }
    }

    private void reportNextDraw() {
        if (DEBUG_BLAST) {
            Log.d(mTag, "reportNextDraw " + Debug.getCallers(5));
        }
        mReportNextDraw = true;
    }

    /**
     * Force the window to report its next draw.
     * <p>
     * This method is only supposed to be used to speed up the interaction from SystemUI and window
     * manager when waiting for the first frame to be drawn when turning on the screen. DO NOT USE
     * unless you fully understand this interaction.
     *
     * @param syncBuffer If true, the transaction that contains the buffer from the draw should be
     *                   sent to system to be synced. If false, VRI will not try to sync the buffer,
     *                   but only report back that a buffer was drawn.
     * @hide
     */
    public void setReportNextDraw(boolean syncBuffer) {
        mSyncBuffer = syncBuffer;
        reportNextDraw();
        invalidate();
    }

    void changeCanvasOpacity(boolean opaque) {
        Log.d(mTag, "changeCanvasOpacity: opaque=" + opaque);
        opaque = opaque & ((mView.mPrivateFlags & View.PFLAG_REQUEST_TRANSPARENT_REGIONS) == 0);
        if (mAttachInfo.mThreadedRenderer != null) {
            mAttachInfo.mThreadedRenderer.setOpaque(opaque);
        }
    }

    /**
     * Dispatches a KeyEvent to all registered key fallback handlers.
     *
     * @param event
     * @return {@code true} if the event was handled, {@code false} otherwise.
     */
    public boolean dispatchUnhandledKeyEvent(KeyEvent event) {
        return mUnhandledKeyManager.dispatch(mView, event);
    }

    class TakenSurfaceHolder extends BaseSurfaceHolder {
        @Override
        public boolean onAllowLockCanvas() {
            return mDrawingAllowed;
        }

        @Override
        public void onRelayoutContainer() {
            // Not currently interesting -- from changing between fixed and layout size.
        }

        @Override
        public void setFormat(int format) {
            ((RootViewSurfaceTaker)mView).setSurfaceFormat(format);
        }

        @Override
        public void setType(int type) {
            ((RootViewSurfaceTaker)mView).setSurfaceType(type);
        }

        @Override
        public void onUpdateSurface() {
            // We take care of format and type changes on our own.
            throw new IllegalStateException("Shouldn't be here");
        }

        @Override
        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void setFixedSize(int width, int height) {
            throw new UnsupportedOperationException(
                    "Currently only support sizing from layout");
        }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
            ((RootViewSurfaceTaker)mView).setSurfaceKeepScreenOn(screenOn);
        }
    }

    static class W extends IWindow.Stub {
        private final WeakReference<ViewRootImpl> mViewAncestor;
        private final IWindowSession mWindowSession;

        W(ViewRootImpl viewAncestor) {
            mViewAncestor = new WeakReference<ViewRootImpl>(viewAncestor);
            mWindowSession = viewAncestor.mWindowSession;
        }

        @Override
        public void resized(ClientWindowFrames frames, boolean reportDraw,
                MergedConfiguration mergedConfiguration, InsetsState insetsState,
                boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId, int syncSeqId,
                int resizeMode) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchResized(frames, reportDraw, mergedConfiguration, insetsState,
                        forceLayout, alwaysConsumeSystemBars, displayId, syncSeqId, resizeMode);
            }
        }

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchInsetsControlChanged(insetsState, activeControls);
            }
        }

        @Override
        public void showInsets(@InsetsType int types, boolean fromIme) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (fromIme) {
                ImeTracing.getInstance().triggerClientDump("ViewRootImpl.W#showInsets",
                        viewAncestor.getInsetsController().getHost().getInputMethodManager(),
                        null /* icProto */);
            }
            if (viewAncestor != null) {
                viewAncestor.showInsets(types, fromIme);
            }
        }

        @Override
        public void hideInsets(@InsetsType int types, boolean fromIme) {

            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (fromIme) {
                ImeTracing.getInstance().triggerClientDump("ViewRootImpl.W#hideInsets",
                        viewAncestor.getInsetsController().getHost().getInputMethodManager(),
                        null /* icProto */);
            }
            if (viewAncestor != null) {
                viewAncestor.hideInsets(types, fromIme);
            }
        }

        @Override
        public void moved(int newX, int newY) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchMoved(newX, newY);
            }
        }

        @Override
        public void dispatchAppVisibility(boolean visible) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchAppVisibility(visible);
            }
        }

        @Override
        public void dispatchGetNewSurface() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchGetNewSurface();
            }
        }

        private static int checkCallingPermission(String permission) {
            try {
                return ActivityManager.getService().checkPermission(
                        permission, Binder.getCallingPid(), Binder.getCallingUid());
            } catch (RemoteException e) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        @Override
        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                final View view = viewAncestor.mView;
                if (view != null) {
                    if (checkCallingPermission(Manifest.permission.DUMP) !=
                            PackageManager.PERMISSION_GRANTED) {
                        throw new SecurityException("Insufficient permissions to invoke"
                                + " executeCommand() from pid=" + Binder.getCallingPid()
                                + ", uid=" + Binder.getCallingUid());
                    }

                    OutputStream clientStream = null;
                    try {
                        clientStream = new ParcelFileDescriptor.AutoCloseOutputStream(out);
                        ViewDebug.dispatchCommand(view, command, parameters, clientStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        if (clientStream != null) {
                            try {
                                clientStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }

        @Override
        public void closeSystemDialogs(String reason) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchCloseSystemDialogs(reason);
            }
        }

        @Override
        public void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep,
                float zoom, boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperOffsetsComplete(asBinder());
                } catch (RemoteException e) {
                }
            }
        }

        @Override
        public void dispatchWallpaperCommand(String action, int x, int y,
                int z, Bundle extras, boolean sync) {
            if (sync) {
                try {
                    mWindowSession.wallpaperCommandComplete(asBinder(), null);
                } catch (RemoteException e) {
                }
            }
        }

        /* Drag/drop */
        @Override
        public void dispatchDragEvent(DragEvent event) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchDragEvent(event);
            }
        }

        @Override
        public void updatePointerIcon(float x, float y) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.updatePointerIcon(x, y);
            }
        }

        @Override
        public void dispatchWindowShown() {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchWindowShown();
            }
        }

        @Override
        public void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId) {
            ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchRequestKeyboardShortcuts(receiver, deviceId);
            }
        }

        @Override
        public void requestScrollCapture(IScrollCaptureResponseListener listener) {
            final ViewRootImpl viewAncestor = mViewAncestor.get();
            if (viewAncestor != null) {
                viewAncestor.dispatchScrollCaptureRequest(listener);
            }
        }
    }

    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        @UnsupportedAppUsage
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }

    static HandlerActionQueue getRunQueue() {
        HandlerActionQueue rq = sRunQueues.get();
        if (rq != null) {
            return rq;
        }
        rq = new HandlerActionQueue();
        sRunQueues.set(rq);
        return rq;
    }

    /**
     * Start a drag resizing which will inform all listeners that a window resize is taking place.
     */
    private void startDragResizing(Rect initialBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets, int resizeMode) {
        if (!mDragResizing) {
            mDragResizing = true;
            if (mUseMTRenderer) {
                for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                    mWindowCallbacks.get(i).onWindowDragResizeStart(
                            initialBounds, fullscreen, systemInsets, stableInsets, resizeMode);
                }
            }
            mFullRedrawNeeded = true;
        }
    }

    /**
     * End a drag resize which will inform all listeners that a window resize has ended.
     */
    private void endDragResizing() {
        if (mDragResizing) {
            mDragResizing = false;
            if (mUseMTRenderer) {
                for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                    mWindowCallbacks.get(i).onWindowDragResizeEnd();
                }
            }
            mFullRedrawNeeded = true;
        }
    }

    private boolean updateContentDrawBounds() {
        boolean updated = false;
        if (mUseMTRenderer) {
            for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
                updated |=
                        mWindowCallbacks.get(i).onContentDrawn(mWindowAttributes.surfaceInsets.left,
                                mWindowAttributes.surfaceInsets.top, mWidth, mHeight);
            }
        }
        return updated | (mDragResizing && mReportNextDraw);
    }

    private void requestDrawWindow() {
        if (!mUseMTRenderer) {
            return;
        }
        // Only wait if it will report next draw.
        if (mReportNextDraw) {
            mWindowDrawCountDown = new CountDownLatch(mWindowCallbacks.size());
        }
        for (int i = mWindowCallbacks.size() - 1; i >= 0; i--) {
            mWindowCallbacks.get(i).onRequestDraw(mReportNextDraw);
        }
    }

    /**
     * Tells this instance that its corresponding activity has just relaunched. In this case, we
     * need to force a relayout of the window to make sure we get the correct bounds from window
     * manager.
     */
    public void reportActivityRelaunched() {
        mActivityRelaunched = true;
    }

    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    /**
     * @return Returns a token used to identify the windows input channel.
     */
    public IBinder getInputToken() {
        if (mInputEventReceiver == null) {
            return null;
        }
        return mInputEventReceiver.getToken();
    }

    @NonNull
    public IBinder getWindowToken() {
        return mAttachInfo.mWindowToken;
    }

    /**
     * Class for managing the accessibility interaction connection
     * based on the global accessibility state.
     */
    final class AccessibilityInteractionConnectionManager
            implements AccessibilityStateChangeListener {
        @Override
        public void onAccessibilityStateChanged(boolean enabled) {
            if (enabled) {
                ensureConnection();
                if (mAttachInfo.mHasWindowFocus && (mView != null)) {
                    mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
                    View focusedView = mView.findFocus();
                    if (focusedView != null && focusedView != mView) {
                        focusedView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                    }
                }
                if (mAttachInfo.mLeashedParentToken != null) {
                    mAccessibilityManager.associateEmbeddedHierarchy(
                            mAttachInfo.mLeashedParentToken, mLeashToken);
                }
            } else {
                ensureNoConnection();
                mHandler.obtainMessage(MSG_CLEAR_ACCESSIBILITY_FOCUS_HOST).sendToTarget();
            }
        }

        public void ensureConnection() {
            final boolean registered = mAttachInfo.mAccessibilityWindowId
                    != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            if (!registered) {
                mAttachInfo.mAccessibilityWindowId =
                        mAccessibilityManager.addAccessibilityInteractionConnection(mWindow,
                                mLeashToken,
                                mContext.getPackageName(),
                                new AccessibilityInteractionConnection(ViewRootImpl.this));
            }
        }

        public void ensureNoConnection() {
            final boolean registered = mAttachInfo.mAccessibilityWindowId
                    != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            if (registered) {
                mAttachInfo.mAccessibilityWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                mAccessibilityManager.removeAccessibilityInteractionConnection(mWindow);
            }
        }
    }

    final class HighContrastTextManager implements HighTextContrastChangeListener {
        HighContrastTextManager() {
            ThreadedRenderer.setHighContrastText(mAccessibilityManager.isHighTextContrastEnabled());
        }
        @Override
        public void onHighTextContrastStateChanged(boolean enabled) {
            ThreadedRenderer.setHighContrastText(enabled);

            // Destroy Displaylists so they can be recreated with high contrast recordings
            destroyHardwareResources();

            // Schedule redraw, which will rerecord + redraw all text
            invalidate();
        }
    }

    /**
     * This class is an interface this ViewAncestor provides to the
     * AccessibilityManagerService to the latter can interact with
     * the view hierarchy in this ViewAncestor.
     */
    static final class AccessibilityInteractionConnection
            extends IAccessibilityInteractionConnection.Stub {
        private final WeakReference<ViewRootImpl> mViewRootImpl;

        AccessibilityInteractionConnection(ViewRootImpl viewRootImpl) {
            mViewRootImpl = new WeakReference<ViewRootImpl>(viewRootImpl);
        }

        @Override
        public void findAccessibilityNodeInfoByAccessibilityId(long accessibilityNodeId,
                Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec, Bundle args) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfoByAccessibilityIdClientThread(accessibilityNodeId,
                            interactiveRegion, interactionId, callback, flags, interrogatingPid,
                            interrogatingTid, spec, args);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void performAccessibilityAction(long accessibilityNodeId, int action,
                Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .performAccessibilityActionClientThread(accessibilityNodeId, action, arguments,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setPerformAccessibilityActionResult(false, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfosByViewId(long accessibilityNodeId,
                String viewId, Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfosByViewIdClientThread(accessibilityNodeId,
                            viewId, interactiveRegion, interactionId, callback, flags,
                            interrogatingPid, interrogatingTid, spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findAccessibilityNodeInfosByText(long accessibilityNodeId, String text,
                Region interactiveRegion, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findAccessibilityNodeInfosByTextClientThread(accessibilityNodeId, text,
                            interactiveRegion, interactionId, callback, flags, interrogatingPid,
                            interrogatingTid, spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfosResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void findFocus(long accessibilityNodeId, int focusType, Region interactiveRegion,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .findFocusClientThread(accessibilityNodeId, focusType, interactiveRegion,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid,
                            spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void focusSearch(long accessibilityNodeId, int direction, Region interactiveRegion,
                int interactionId, IAccessibilityInteractionConnectionCallback callback, int flags,
                int interrogatingPid, long interrogatingTid, MagnificationSpec spec) {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                    .focusSearchClientThread(accessibilityNodeId, direction, interactiveRegion,
                            interactionId, callback, flags, interrogatingPid, interrogatingTid,
                            spec);
            } else {
                // We cannot make the call and notify the caller so it does not wait.
                try {
                    callback.setFindAccessibilityNodeInfoResult(null, interactionId);
                } catch (RemoteException re) {
                    /* best effort - ignore */
                }
            }
        }

        @Override
        public void clearAccessibilityFocus() {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .clearAccessibilityFocusClientThread();
            }
        }

        @Override
        public void notifyOutsideTouch() {
            ViewRootImpl viewRootImpl = mViewRootImpl.get();
            if (viewRootImpl != null && viewRootImpl.mView != null) {
                viewRootImpl.getAccessibilityInteractionController()
                        .notifyOutsideTouchClientThread();
            }
        }
    }

    /**
     * Gets an accessibility embedded connection interface for this ViewRootImpl.
     * @hide
     */
    public IAccessibilityEmbeddedConnection getAccessibilityEmbeddedConnection() {
        if (mAccessibilityEmbeddedConnection == null) {
            mAccessibilityEmbeddedConnection = new AccessibilityEmbeddedConnection(
                    ViewRootImpl.this);
        }
        return mAccessibilityEmbeddedConnection;
    }

    private class SendWindowContentChangedAccessibilityEvent implements Runnable {
        private int mChangeTypes = 0;

        public View mSource;
        public long mLastEventTimeMillis;
        /**
         * Override for {@link AccessibilityEvent#originStackTrace} to provide the stack trace
         * of the original {@link #runOrPost} call instead of one for sending the delayed event
         * from a looper.
         */
        public StackTraceElement[] mOrigin;

        @Override
        public void run() {
            // Protect against re-entrant code and attempt to do the right thing in the case that
            // we're multithreaded.
            View source = mSource;
            mSource = null;
            if (source == null) {
                Log.e(TAG, "Accessibility content change has no source");
                return;
            }
            // The accessibility may be turned off while we were waiting so check again.
            if (AccessibilityManager.getInstance(mContext).isEnabled()) {
                mLastEventTimeMillis = SystemClock.uptimeMillis();
                AccessibilityEvent event = AccessibilityEvent.obtain();
                event.setEventType(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                event.setContentChangeTypes(mChangeTypes);
                if (AccessibilityEvent.DEBUG_ORIGIN) event.originStackTrace = mOrigin;
                source.sendAccessibilityEventUnchecked(event);
            } else {
                mLastEventTimeMillis = 0;
            }
            // In any case reset to initial state.
            source.resetSubtreeAccessibilityStateChanged();
            mChangeTypes = 0;
            if (AccessibilityEvent.DEBUG_ORIGIN) mOrigin = null;
        }

        public void runOrPost(View source, int changeType) {
            if (mHandler.getLooper() != Looper.myLooper()) {
                CalledFromWrongThreadException e = new CalledFromWrongThreadException("Only the "
                        + "original thread that created a view hierarchy can touch its views.");
                // TODO: Throw the exception
                Log.e(TAG, "Accessibility content change on non-UI thread. Future Android "
                        + "versions will throw an exception.", e);
                // Attempt to recover. This code does not eliminate the thread safety issue, but
                // it should force any issues to happen near the above log.
                mHandler.removeCallbacks(this);
                if (mSource != null) {
                    // Dispatch whatever was pending. It's still possible that the runnable started
                    // just before we removed the callbacks, and bad things will happen, but at
                    // least they should happen very close to the logged error.
                    run();
                }
            }
            if (mSource != null) {
                // If there is no common predecessor, then mSource points to
                // a removed view, hence in this case always prefer the source.
                View predecessor = getCommonPredecessor(mSource, source);
                if (predecessor != null) {
                    predecessor = predecessor.getSelfOrParentImportantForA11y();
                }
                mSource = (predecessor != null) ? predecessor : source;
                mChangeTypes |= changeType;
                return;
            }
            mSource = source;
            mChangeTypes = changeType;
            if (AccessibilityEvent.DEBUG_ORIGIN) {
                mOrigin = Thread.currentThread().getStackTrace();
            }
            final long timeSinceLastMillis = SystemClock.uptimeMillis() - mLastEventTimeMillis;
            final long minEventIntevalMillis =
                    ViewConfiguration.getSendRecurringAccessibilityEventsInterval();
            if (timeSinceLastMillis >= minEventIntevalMillis) {
                removeCallbacksAndRun();
            } else {
                mHandler.postDelayed(this, minEventIntevalMillis - timeSinceLastMillis);
            }
        }

        public void removeCallbacksAndRun() {
            mHandler.removeCallbacks(this);
            run();
        }
    }

    private static class UnhandledKeyManager {
        // This is used to ensure that unhandled events are only dispatched once. We attempt
        // to dispatch more than once in order to achieve a certain order. Specifically, if we
        // are in an Activity or Dialog (and have a Window.Callback), the unhandled events should
        // be dispatched after the view hierarchy, but before the Callback. However, if we aren't
        // in an activity, we still want unhandled keys to be dispatched.
        private boolean mDispatched = true;

        // Keeps track of which Views have unhandled key focus for which keys. This doesn't
        // include modifiers.
        private final SparseArray<WeakReference<View>> mCapturedKeys = new SparseArray<>();

        // The current receiver. This value is transient and used between the pre-dispatch and
        // pre-view phase to ensure that other input-stages don't interfere with tracking.
        private WeakReference<View> mCurrentReceiver = null;

        boolean dispatch(View root, KeyEvent event) {
            if (mDispatched) {
                return false;
            }
            View consumer;
            try {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "UnhandledKeyEvent dispatch");
                mDispatched = true;

                consumer = root.dispatchUnhandledKeyEvent(event);

                // If an unhandled listener handles one, then keep track of it so that the
                // consuming view is first to receive its repeats and release as well.
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keycode = event.getKeyCode();
                    if (consumer != null && !KeyEvent.isModifierKey(keycode)) {
                        mCapturedKeys.put(keycode, new WeakReference<>(consumer));
                    }
                }
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_VIEW);
            }
            return consumer != null;
        }

        /**
         * Called before the event gets dispatched to anything
         */
        void preDispatch(KeyEvent event) {
            // Always clean-up 'up' events since it's possible for earlier dispatch stages to
            // consume them without consuming the corresponding 'down' event.
            mCurrentReceiver = null;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                int idx = mCapturedKeys.indexOfKey(event.getKeyCode());
                if (idx >= 0) {
                    mCurrentReceiver = mCapturedKeys.valueAt(idx);
                    mCapturedKeys.removeAt(idx);
                }
            }
        }

        /**
         * Called before the event gets dispatched to the view hierarchy
         * @return {@code true} if an unhandled handler has focus and consumed the event
         */
        boolean preViewDispatch(KeyEvent event) {
            mDispatched = false;
            if (mCurrentReceiver == null) {
                mCurrentReceiver = mCapturedKeys.get(event.getKeyCode());
            }
            if (mCurrentReceiver != null) {
                View target = mCurrentReceiver.get();
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    mCurrentReceiver = null;
                }
                if (target != null && target.isAttachedToWindow()) {
                    target.onUnhandledKeyEvent(event);
                }
                // consume anyways so that we don't feed uncaptured key events to other views
                return true;
            }
            return false;
        }
    }

    /**
     * @hide
     */
    public void setDisplayDecoration(boolean displayDecoration) {
        if (displayDecoration == mDisplayDecorationCached) return;

        mDisplayDecorationCached = displayDecoration;

        if (mSurfaceControl.isValid()) {
            updateDisplayDecoration();
        }
    }

    private void updateDisplayDecoration() {
        mTransaction.setDisplayDecoration(mSurfaceControl, mDisplayDecorationCached).apply();
    }

    /**
     * Sends a list of blur regions to SurfaceFlinger, tagged with a frame.
     *
     * @param regionCopy List of regions
     * @param frameNumber Frame where it should be applied (or current when using BLAST)
     */
    public void dispatchBlurRegions(float[][] regionCopy, long frameNumber) {
        final SurfaceControl surfaceControl = getSurfaceControl();
        if (!surfaceControl.isValid()) {
            return;
        }

        SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        transaction.setBlurRegions(surfaceControl, regionCopy);

        if (useBLAST() && mBlastBufferQueue != null) {
            mBlastBufferQueue.mergeWithNextTransaction(transaction, frameNumber);
        }
    }

    /**
     * Creates a background blur drawable for the backing {@link Surface}.
     */
    public BackgroundBlurDrawable createBackgroundBlurDrawable() {
        return mBlurRegionAggregator.createBackgroundBlurDrawable(mContext);
    }

    @Override
    public void onDescendantUnbufferedRequested() {
        mUnbufferedInputSource = mView.mUnbufferedInputSource;
    }

    /**
     * Force disabling use of the BLAST adapter regardless of the system
     * flag. Needs to be called before addView.
     */
    void forceDisableBLAST() {
        mForceDisableBLAST = true;
    }

    boolean useBLAST() {
        return mUseBLASTAdapter && !mForceDisableBLAST;
    }

    int getSurfaceSequenceId() {
        return mSurfaceSequenceId;
    }

    /**
     * Merges the transaction passed in with the next transaction in BLASTBufferQueue. This ensures
     * you can add transactions to the upcoming frame.
     */
    public void mergeWithNextTransaction(Transaction t, long frameNumber) {
        if (mBlastBufferQueue != null) {
            mBlastBufferQueue.mergeWithNextTransaction(t, frameNumber);
        } else {
            t.apply();
        }
    }

    @Override
    @Nullable public SurfaceControl.Transaction buildReparentTransaction(
        @NonNull SurfaceControl child) {
        if (mSurfaceControl.isValid()) {
            return new SurfaceControl.Transaction().reparent(child, mSurfaceControl);
        }
        return null;
    }

    @Override
    public boolean applyTransactionOnDraw(@NonNull SurfaceControl.Transaction t) {
        if (mRemoved || !isHardwareEnabled()) {
            t.apply();
        } else {
            mHasPendingTransactions = true;
            registerRtFrameCallback(frame -> {
                mergeWithNextTransaction(t, frame);
            });
        }
        return true;
    }

    @Override
    public @SurfaceControl.BufferTransform int getBufferTransformHint() {
        return mSurfaceControl.getTransformHint();
    }

    @Override
    public void addOnBufferTransformHintChangedListener(
            OnBufferTransformHintChangedListener listener) {
        Objects.requireNonNull(listener);
        if (mTransformHintListeners.contains(listener)) {
            throw new IllegalArgumentException(
                    "attempt to call addOnBufferTransformHintChangedListener() "
                            + "with a previously registered listener");
        }
        mTransformHintListeners.add(listener);
    }

    @Override
    public void removeOnBufferTransformHintChangedListener(
            OnBufferTransformHintChangedListener listener) {
        Objects.requireNonNull(listener);
        mTransformHintListeners.remove(listener);
    }

    private void dispatchTransformHintChanged(@SurfaceControl.BufferTransform int hint) {
        if (mTransformHintListeners.isEmpty()) {
            return;
        }
        ArrayList<OnBufferTransformHintChangedListener> listeners =
                (ArrayList<OnBufferTransformHintChangedListener>) mTransformHintListeners.clone();
        for (int i = 0; i < listeners.size(); i++) {
            OnBufferTransformHintChangedListener listener = listeners.get(i);
            listener.onBufferTransformHintChanged(hint);
        }
    }

    /**
     * Shows or hides a Camera app compat toggle for stretched issues with the requested state
     * for the corresponding activity.
     *
     * @param showControl Whether the control should be shown or hidden.
     * @param transformationApplied Whether the treatment is already applied.
     * @param callback The callback executed when the user clicks on a control.
    */
    public void requestCompatCameraControl(boolean showControl, boolean transformationApplied,
                ICompatCameraControlCallback callback) {
        mActivityConfigCallback.requestCompatCameraControl(
                showControl, transformationApplied, callback);
    }

    boolean wasRelayoutRequested() {
        return mRelayoutRequested;
    }

    void forceWmRelayout() {
       mForceNextWindowRelayout = true;
       scheduleTraversals();
    }

    /**
     * Returns the {@link OnBackInvokedDispatcher} on the decor view if one exists, or the
     * fallback {@link OnBackInvokedDispatcher} instance.
     */
    @NonNull
    public WindowOnBackInvokedDispatcher getOnBackInvokedDispatcher() {
        return mOnBackInvokedDispatcher;
    }

    @NonNull
    @Override
    public OnBackInvokedDispatcher findOnBackInvokedDispatcherForChild(
            @NonNull View child, @NonNull View requester) {
        return getOnBackInvokedDispatcher();
    }

    /**
     * When this ViewRootImpl is added to the window manager, transfers the first
     * {@link OnBackInvokedCallback} to be called to the server.
     */
    private void registerBackCallbackOnWindow() {
        if (OnBackInvokedDispatcher.DEBUG) {
            Log.d(OnBackInvokedDispatcher.TAG, TextUtils.formatSimple(
                    "ViewRootImpl.registerBackCallbackOnWindow. Dispatcher:%s Package:%s "
                            + "IWindow:%s Session:%s",
                    mOnBackInvokedDispatcher, mBasePackageName, mWindow, mWindowSession));
        }
        mOnBackInvokedDispatcher.attachToWindow(mWindowSession, mWindow);
    }

    private void sendBackKeyEvent(int action) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent ev = new KeyEvent(when, when, action,
                KeyEvent.KEYCODE_BACK, 0 /* repeat */, 0 /* metaState */,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);

        ev.setDisplayId(mContext.getDisplay().getDisplayId());
        if (mView != null) {
            mView.dispatchKeyEvent(ev);
        }
    }

    private void registerCompatOnBackInvokedCallback() {
        mCompatOnBackInvokedCallback = () -> {
                sendBackKeyEvent(KeyEvent.ACTION_DOWN);
                sendBackKeyEvent(KeyEvent.ACTION_UP);
        };
        mOnBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCompatOnBackInvokedCallback);
    }

    private void unregisterCompatOnBackInvokedCallback() {
        if (mCompatOnBackInvokedCallback != null) {
            mOnBackInvokedDispatcher.unregisterOnBackInvokedCallback(mCompatOnBackInvokedCallback);
            mCompatOnBackInvokedCallback = null;
        }
    }

    @Override
    public void setTouchableRegion(Region r) {
        if (r != null) {
            mTouchableRegion = new Region(r);
        } else {
            mTouchableRegion = null;
        }
        mLastGivenInsets.reset();
        requestLayout();
    }

    IWindowSession getWindowSession() {
        return mWindowSession;
    }

    private void registerCallbacksForSync(boolean syncBuffer,
            final SurfaceSyncer.SyncBufferCallback syncBufferCallback) {
        if (!isHardwareEnabled()) {
            return;
        }

        if (DEBUG_BLAST) {
            Log.d(mTag, "registerCallbacksForSync syncBuffer=" + syncBuffer);
        }
        mAttachInfo.mThreadedRenderer.registerRtFrameCallback(new FrameDrawingCallback() {
            @Override
            public void onFrameDraw(long frame) {
            }

            @Override
            public HardwareRenderer.FrameCommitCallback onFrameDraw(int syncResult, long frame) {
                if (DEBUG_BLAST) {
                    Log.d(mTag,
                            "Received frameDrawingCallback syncResult=" + syncResult + " frameNum="
                                    + frame + ".");
                }

                // If the syncResults are SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                // SYNC_CONTEXT_IS_STOPPED it means nothing will draw. There's no need to set up
                // any blast sync or commit callback, and the code should directly call
                // pendingDrawFinished.
                if ((syncResult
                        & (SYNC_LOST_SURFACE_REWARD_IF_FOUND | SYNC_CONTEXT_IS_STOPPED)) != 0) {
                    syncBufferCallback.onBufferReady(
                            mBlastBufferQueue.gatherPendingTransactions(frame));
                    return null;
                }

                if (DEBUG_BLAST) {
                    Log.d(mTag, "Setting up sync and frameCommitCallback");
                }

                if (syncBuffer) {
                    mBlastBufferQueue.syncNextTransaction(syncBufferCallback::onBufferReady);
                }

                return didProduceBuffer -> {
                    if (DEBUG_BLAST) {
                        Log.d(mTag, "Received frameCommittedCallback"
                                + " lastAttemptedDrawFrameNum=" + frame
                                + " didProduceBuffer=" + didProduceBuffer);
                    }

                    // If frame wasn't drawn, clear out the next transaction so it doesn't affect
                    // the next draw attempt. The next transaction and transaction complete callback
                    // were only set for the current draw attempt.
                    if (!didProduceBuffer) {
                        mBlastBufferQueue.syncNextTransaction(null);

                        // Gather the transactions that were sent to mergeWithNextTransaction
                        // since the frame didn't draw on this vsync. It's possible the frame will
                        // draw later, but it's better to not be sync than to block on a frame that
                        // may never come.
                        syncBufferCallback.onBufferReady(
                                mBlastBufferQueue.gatherPendingTransactions(frame));
                        return;
                    }

                    // If we didn't request to sync a buffer, then we won't get the
                    // syncNextTransaction callback. Instead, just report back to the Syncer so it
                    // knows that this sync request is complete.
                    if (!syncBuffer) {
                        syncBufferCallback.onBufferReady(null);
                    }
                };
            }
        });
    }

    public final SurfaceSyncer.SyncTarget mSyncTarget = new SurfaceSyncer.SyncTarget() {
        @Override
        public void onReadyToSync(SurfaceSyncer.SyncBufferCallback syncBufferCallback) {
            readyToSync(syncBufferCallback);
        }

        @Override
        public void onSyncComplete() {
            mHandler.postAtFrontOfQueue(() -> {
                if (--mNumSyncsInProgress == 0 && mAttachInfo.mThreadedRenderer != null) {
                    HardwareRenderer.setRtAnimationsEnabled(true);
                }
            });
        }
    };

    private void readyToSync(SurfaceSyncer.SyncBufferCallback syncBufferCallback) {
        mNumSyncsInProgress++;
        if (mAttachInfo.mThreadedRenderer != null) {
            HardwareRenderer.setRtAnimationsEnabled(false);
        }

        if (mSyncBufferCallback != null) {
            Log.d(mTag, "Already set sync for the next draw.");
            mSyncBufferCallback.onBufferReady(null);
        }
        if (DEBUG_BLAST) {
            Log.d(mTag, "Setting syncFrameCallback");
        }
        mSyncBufferCallback = syncBufferCallback;
        if (!mIsInTraversal && !mTraversalScheduled) {
            scheduleTraversals();
        }
    }
}
