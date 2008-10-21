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

import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.*;
import android.os.Process;
import android.util.AndroidRuntimeException;
import android.util.Config;
import android.util.Log;
import android.util.EventLog;
import android.view.View.MeasureSpec;
import android.content.pm.PackageManager;
import android.content.Context;
import android.app.ActivityManagerNative;
import android.Manifest;
import android.media.AudioManager;

import java.lang.ref.WeakReference;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;

import javax.microedition.khronos.egl.*;
import javax.microedition.khronos.opengles.*;
import static javax.microedition.khronos.opengles.GL10.*;

/**
 * The top of a view hierarchy, implementing the needed protocol between View
 * and the WindowManager.  This is for the most part an internal implementation
 * detail of {@link WindowManagerImpl}.
 *
 * {@hide}
 */
@SuppressWarnings({"EmptyCatchBlock"})
final class ViewRoot extends Handler implements ViewParent, View.AttachInfo.SoundEffectPlayer {
    private static final String TAG = "ViewRoot";
    private static final boolean DBG = false;
    @SuppressWarnings({"ConstantConditionalExpression"})
    private static final boolean LOCAL_LOGV = false ? Config.LOGD : Config.LOGV;
    /** @noinspection PointlessBooleanExpression*/
    private static final boolean DEBUG_DRAW = false || LOCAL_LOGV;
    private static final boolean DEBUG_ORIENTATION = false || LOCAL_LOGV;
    private static final boolean DEBUG_TRACKBALL = LOCAL_LOGV;
    private static final boolean WATCH_POINTER = false;

    static final boolean PROFILE_DRAWING = false;
    private static final boolean PROFILE_LAYOUT = false;
    // profiles real fps (times between draws) and displays the result
    private static final boolean SHOW_FPS = false;
    // used by SHOW_FPS
    private static int sDrawTime;

    /**
     * Maximum time we allow the user to roll the trackball enough to generate
     * a key event, before resetting the counters.
     */
    static final int MAX_TRACKBALL_DELAY = 250;

    private static long sInstanceCount = 0;

    private static IWindowSession sWindowSession;

    private static final Object mStaticInit = new Object();
    private static boolean mInitialized = false;

    static final ThreadLocal<Handler> sUiThreads = new ThreadLocal<Handler>();
    static final RunQueue sRunQueue = new RunQueue();

    private long mLastTrackballTime = 0;
    private final TrackballAxis mTrackballAxisX = new TrackballAxis();
    private final TrackballAxis mTrackballAxisY = new TrackballAxis();

    private final Thread mThread;

    private final WindowLeaked mLocation;

    private final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

    final W mWindow;

    private View mView;
    private View mFocusedView;
    private int mViewVisibility;
    private boolean mAppVisible = true;

    private final Region mTransparentRegion;
    private final Region mPreviousTransparentRegion;

    private int mWidth;
    private int mHeight;
    private Rect mDirty; // will be a graphics.Region soon

    private final View.AttachInfo mAttachInfo;

    private final Rect mTempRect; // used in the transaction to not thrash the heap.

    private boolean mTraversalScheduled;
    private boolean mWillDrawSoon;
    private boolean mLayoutRequested;
    private boolean mFirst;
    private boolean mReportNextDraw;
    private boolean mFullRedrawNeeded;
    private boolean mNewSurfaceNeeded;

    private boolean mWindowAttributesChanged = false;

    // These can be accessed by any thread, must be protected with a lock.
    private Surface mSurface;

    private boolean mAdded;
    private boolean mAddedTouchMode;

    /*package*/ int mAddNesting;

    // These are accessed by multiple threads.
    private final Rect mWinFrame; // frame given by window manager.

    private final Rect mCoveredInsets = new Rect();
    private final Rect mNewCoveredInsets = new Rect();

    private EGL10 mEgl;
    private EGLDisplay mEglDisplay;
    private EGLContext mEglContext;
    private EGLSurface mEglSurface;
    private GL11 mGL;
    private Canvas mGlCanvas;
    private boolean mUseGL;
    private boolean mGlWanted;

    /**
     * see {@link #playSoundEffect(int)}
     */
    private AudioManager mAudioManager;



    public ViewRoot() {
        super();

        ++sInstanceCount;

        // Initialize the statics when this class is first instantiated. This is
        // done here instead of in the static block because Zygote does not
        // allow the spawning of threads.
        synchronized (mStaticInit) {
            if (!mInitialized) {
                try {
                    sWindowSession = IWindowManager.Stub.asInterface(
                            ServiceManager.getService("window"))
                            .openSession(new Binder());
                    mInitialized = true;
                } catch (RemoteException e) {
                }
            }
        }

        mThread = Thread.currentThread();
        mLocation = new WindowLeaked(null);
        mLocation.fillInStackTrace();
        mWidth = -1;
        mHeight = -1;
        mDirty = new Rect();
        mTempRect = new Rect();
        mWinFrame = new Rect();
        mWindow = new W(this);
        mViewVisibility = View.GONE;
        mTransparentRegion = new Region();
        mPreviousTransparentRegion = new Region();
        mFirst = true; // true for the first time the view is added
        mSurface = new Surface();
        mAdded = false;

        Handler handler = sUiThreads.get();
        if (handler == null) {
            handler = new RootHandler();
            sUiThreads.set(handler);
        }
        mAttachInfo = new View.AttachInfo(handler, this);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        --sInstanceCount;
    }

    public static long getInstanceCount() {
        return sInstanceCount;
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
        if (mInitialized) {
            try {
                return sWindowSession.getInTouchMode();
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    private void initializeGL() {
        initializeGLInner();
        int err = mEgl.eglGetError();
        if (err != EGL10.EGL_SUCCESS) {
            // give-up on using GL
            destroyGL();
            mGlWanted = false;
        }
    }

    private void initializeGLInner() {
        final EGL10 egl = (EGL10) EGLContext.getEGL();
        mEgl = egl;

        /*
         * Get to the default display.
         */
        final EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
        mEglDisplay = eglDisplay;

        /*
         * We can now initialize EGL for that display
         */
        int[] version = new int[2];
        egl.eglInitialize(eglDisplay, version);

        /*
         * Specify a configuration for our opengl session
         * and grab the first configuration that matches is
         */
        final int[] configSpec = {
                EGL10.EGL_RED_SIZE,      5,
                EGL10.EGL_GREEN_SIZE,    6,
                EGL10.EGL_BLUE_SIZE,     5,
                EGL10.EGL_DEPTH_SIZE,    0,
                EGL10.EGL_NONE
        };
        final EGLConfig[] configs = new EGLConfig[1];
        final int[] num_config = new int[1];
        egl.eglChooseConfig(eglDisplay, configSpec, configs, 1, num_config);
        final EGLConfig config = configs[0];

        /*
         * Create an OpenGL ES context. This must be done only once, an
         * OpenGL context is a somewhat heavy object.
         */
        final EGLContext context = egl.eglCreateContext(eglDisplay, config,
                EGL10.EGL_NO_CONTEXT, null);
        mEglContext = context;

        /*
         * Create an EGL surface we can render into.
         */
        final EGLSurface surface = egl.eglCreateWindowSurface(eglDisplay, config, mHolder, null);
        mEglSurface = surface;

        /*
         * Before we can issue GL commands, we need to make sure
         * the context is current and bound to a surface.
         */
        egl.eglMakeCurrent(eglDisplay, surface, surface, context);

        /*
         * Get to the appropriate GL interface.
         * This is simply done by casting the GL context to either
         * GL10 or GL11.
         */
        final GL11 gl = (GL11) context.getGL();
        mGL = gl;
        mGlCanvas = new Canvas(gl);
        mUseGL = true;
    }

    private void destroyGL() {
        // inform skia that the context is gone
        nativeAbandonGlCaches();

        mEgl.eglMakeCurrent(mEglDisplay, EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
        mEgl.eglDestroyContext(mEglDisplay, mEglContext);
        mEgl.eglDestroySurface(mEglDisplay, mEglSurface);
        mEgl.eglTerminate(mEglDisplay);
        mEglContext = null;
        mEglSurface = null;
        mEglDisplay = null;
        mEgl = null;
        mGlCanvas = null;
        mGL = null;
        mUseGL = false;
    }

    private void checkEglErrors() {
        if (mUseGL) {
            int err = mEgl.eglGetError();
            if (err != EGL10.EGL_SUCCESS) {
                // something bad has happened revert to
                // normal rendering.
                destroyGL();
                if (err != EGL11.EGL_CONTEXT_LOST) {
                    // we'll try again if it was context lost
                    mGlWanted = false;
                }
            }
        }
    }

    /**
     * We have one child
     */
    public void setView(View view, WindowManager.LayoutParams attrs,
            View panelParentView) {
        synchronized (this) {
            if (mView == null) {
                mWindowAttributes.copyFrom(attrs);
                mWindowAttributesChanged = true;
                mView = view;
                if (panelParentView != null) {
                    mAttachInfo.mPanelParentWindowToken
                            = panelParentView.getApplicationWindowToken();
                }
                mAdded = true;
                int res; /* = WindowManagerImpl.ADD_OKAY; */
                
                // Schedule the first layout -before- adding to the window
                // manager, to make sure we do the relayout before receiving
                // any other events from the system.
                requestLayout();
                
                try {
                    res = sWindowSession.add(mWindow, attrs,
                            getHostVisibility(), mCoveredInsets);
                } catch (RemoteException e) {
                    mAdded = false;
                    mView = null;
                    unscheduleTraversals();
                    throw new RuntimeException("Adding window failed", e);
                }
                if (Config.LOGV) Log.v("ViewRoot", "Added window " + mWindow);
                if (res < WindowManagerImpl.ADD_OKAY) {
                    mView = null;
                    mAdded = false;
                    unscheduleTraversals();
                    switch (res) {
                        case WindowManagerImpl.ADD_BAD_APP_TOKEN:
                        case WindowManagerImpl.ADD_BAD_SUBWINDOW_TOKEN:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not valid; is your activity running?");
                        case WindowManagerImpl.ADD_NOT_APP_TOKEN:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- token " + attrs.token
                                + " is not for an application");
                        case WindowManagerImpl.ADD_APP_EXITING:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- app for token " + attrs.token
                                + " is exiting");
                        case WindowManagerImpl.ADD_DUPLICATE_ADD:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window -- window " + mWindow
                                + " has already been added");
                        case WindowManagerImpl.ADD_STARTING_NOT_NEEDED:
                            // Silently ignore -- we would have just removed it
                            // right away, anyway.
                            return;
                        case WindowManagerImpl.ADD_MULTIPLE_SINGLETON:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- another window of this type already exists");
                        case WindowManagerImpl.ADD_PERMISSION_DENIED:
                            throw new WindowManagerImpl.BadTokenException(
                                "Unable to add window " + mWindow +
                                " -- permission denied for this window type");
                    }
                    throw new RuntimeException(
                        "Unable to add window -- unknown error code " + res);
                }
                view.assignParent(this);
                mAddedTouchMode = (res&WindowManagerImpl.ADD_FLAG_IN_TOUCH_MODE) != 0;
                mAppVisible = (res&WindowManagerImpl.ADD_FLAG_APP_VISIBLE) != 0;
            }
        }
    }

    public View getView() {
        return mView;
    }

    final WindowLeaked getLocation() {
        return mLocation;
    }

    public void setLayoutParams(WindowManager.LayoutParams attrs) {
        synchronized (this) {
            mWindowAttributes.copyFrom(attrs);
            mWindowAttributesChanged = true;
            scheduleTraversals();
        }
    }

    void handleAppVisibility(boolean visible) {
        if (mAppVisible != visible) {
            mAppVisible = visible;
            scheduleTraversals();
        }
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        mFullRedrawNeeded = true;
        scheduleTraversals();
    }

    /**
     * {@inheritDoc}
     */
    public void requestLayout() {
        checkThread();
        mLayoutRequested = true;
        scheduleTraversals();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isLayoutRequested() {
        return mLayoutRequested;
    }

    public void invalidateChild(View child, Rect dirty) {
        checkThread();
        if (LOCAL_LOGV) Log.v(TAG, "Invalidate child: " + dirty);
        mDirty.union(dirty);
        if (!mWillDrawSoon) {
            scheduleTraversals();
        }
    }

    public ViewParent getParent() {
        return null;
    }

    public ViewParent invalidateChildInParent(final int[] location, final Rect dirty) {
        invalidateChild(null, dirty);
        return null;
    }

     public boolean getChildVisibleRect(View child, Rect r, android.graphics.Point offset) {
        if (child != mView) {
            throw new RuntimeException("child is not mine, honest!");
        }
        return r.intersect(0, 0, mWidth, mHeight);
    }

    public void bringChildToFront(View child) {
    }

    public void scheduleTraversals() {
        if (!mTraversalScheduled) {
            mTraversalScheduled = true;
            sendEmptyMessage(DO_TRAVERSAL);
        }
    }

    public void unscheduleTraversals() {
        if (mTraversalScheduled) {
            mTraversalScheduled = false;
            removeMessages(DO_TRAVERSAL);
        }
    }

    int getHostVisibility() {
        return mAppVisible ? mView.getVisibility() : View.GONE;
    }
    
    private void performTraversals() {
        // cache mView since it is used so much below...
        final View host = mView;

        if (DBG) {
            System.out.println("======================================");
            System.out.println("performTraversals");
            host.debug();
        }

        if (host == null || !mAdded)
            return;

        mTraversalScheduled = false;
        mWillDrawSoon = true;
        boolean windowResizesToFitContent = false;
        boolean fullRedrawNeeded = mFullRedrawNeeded;
        boolean newSurface = false;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) host.getLayoutParams();

        int desiredWindowWidth;
        int desiredWindowHeight;
        int childWidthMeasureSpec;
        int childHeightMeasureSpec;

        final View.AttachInfo attachInfo = mAttachInfo;

        final int viewVisibility = getHostVisibility();
        boolean viewVisibilityChanged = mViewVisibility != viewVisibility
                || mNewSurfaceNeeded;

        WindowManager.LayoutParams params = null;
        if (mWindowAttributesChanged) {
            mWindowAttributesChanged = false;
            params = mWindowAttributes;
        }

        if (mFirst) {
            fullRedrawNeeded = true;
            mLayoutRequested = true;

            Display d = new Display(0);
            desiredWindowWidth = d.getWidth();
            desiredWindowHeight = d.getHeight();

            // For the very first time, tell the view hierarchy that it
            // is attached to the window.  Note that at this point the surface
            // object is not initialized to its backing store, but soon it
            // will be (assuming the window is visible).
            attachInfo.mWindowToken = mWindow.asBinder();
            attachInfo.mSurface = mSurface;
            attachInfo.mSession = sWindowSession;
            attachInfo.mHasWindowFocus = false;
            attachInfo.mWindowVisibility = viewVisibility;
            attachInfo.mRecomputeGlobalAttributes = false;
            attachInfo.mKeepScreenOn = false;
            viewVisibilityChanged = false;
            host.dispatchAttachedToWindow(attachInfo, 0);
            sRunQueue.executeActions(attachInfo.mHandler);
            //Log.i(TAG, "Screen on initialized: " + attachInfo.mKeepScreenOn);
        } else {
            desiredWindowWidth = mWinFrame.width();
            desiredWindowHeight = mWinFrame.height();
            if (desiredWindowWidth != mWidth || desiredWindowHeight != mHeight) {
                if (DEBUG_ORIENTATION) Log.v("ViewRoot",
                        "View " + host + " resized to: " + mWinFrame);
                fullRedrawNeeded = true;
                mLayoutRequested = true;
                windowResizesToFitContent = true;
            }
        }

        if (viewVisibilityChanged) {
            attachInfo.mWindowVisibility = viewVisibility;
            host.dispatchWindowVisibilityChanged(viewVisibility);
            if (viewVisibility != View.VISIBLE || mNewSurfaceNeeded) {
                if (mUseGL) {
                    destroyGL();
                }
            }
        }

        if (mLayoutRequested) {
            if (mFirst) {
                host.fitSystemWindows(mCoveredInsets);
                // make sure touch mode code executes by setting cached value
                // to opposite of the added touch mode.
                mAttachInfo.mInTouchMode = !mAddedTouchMode;
                ensureTouchModeLocally(mAddedTouchMode);
            } else {
                if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                    windowResizesToFitContent = true;

                    Display d = new Display(0);
                    desiredWindowWidth = d.getWidth();
                    desiredWindowHeight = d.getHeight();
                }
            }

            childWidthMeasureSpec = getRootMeasureSpec(desiredWindowWidth, lp.width);
            childHeightMeasureSpec = getRootMeasureSpec(desiredWindowHeight, lp.height);

            // Ask host how big it wants to be
            if (DEBUG_ORIENTATION) Log.v("ViewRoot",
                    "Measuring " + host + " in display " + desiredWindowWidth
                    + "x" + desiredWindowHeight + "...");
            host.measure(childWidthMeasureSpec, childHeightMeasureSpec);

            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after measure");
                host.debug();
            }
        }

        if (attachInfo.mRecomputeGlobalAttributes) {
            //Log.i(TAG, "Computing screen on!");
            attachInfo.mRecomputeGlobalAttributes = false;
            boolean oldVal = attachInfo.mKeepScreenOn;
            attachInfo.mKeepScreenOn = false;
            host.dispatchCollectViewAttributes(0);
            if (attachInfo.mKeepScreenOn != oldVal) {
                params = mWindowAttributes;
                //Log.i(TAG, "Keep screen on changed: " + attachInfo.mKeepScreenOn);
            }
        }

        if (params != null && (host.mPrivateFlags & View.REQUEST_TRANSPARENT_REGIONS) != 0) {
            if (!PixelFormat.formatHasAlpha(params.format)) {
                params.format = PixelFormat.TRANSLUCENT;
            }
        }

        boolean windowShouldResize = mLayoutRequested && windowResizesToFitContent
            && (mWidth != host.mMeasuredWidth || mHeight != host.mMeasuredHeight);

        int relayoutResult = 0;
        if (mFirst || windowShouldResize || viewVisibilityChanged || params != null) {

            if (viewVisibility == View.VISIBLE) {
                if (mWindowAttributes.memoryType == WindowManager.LayoutParams.MEMORY_TYPE_GPU) {
                    if (params == null) {
                        params = mWindowAttributes;
                    }
                    mGlWanted = true;
                }
            }

            final Rect frame = mWinFrame;
            boolean initialized = false;
            boolean coveredInsetsChanged = false;
            try {
                boolean hadSurface = mSurface.isValid();
                int fl = 0;
                if (params != null) {
                    fl = params.flags;
                    if (attachInfo.mKeepScreenOn) {
                        params.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
                    }
                }
                relayoutResult = sWindowSession.relayout(
                    mWindow, params, host.mMeasuredWidth, host.mMeasuredHeight,
                    viewVisibility, frame, mNewCoveredInsets, mSurface);
                if (params != null) {
                    params.flags = fl;
                }

                coveredInsetsChanged = !mNewCoveredInsets.equals(mCoveredInsets);
                if (coveredInsetsChanged) {
                    mCoveredInsets.set(mNewCoveredInsets);
                    host.fitSystemWindows(mCoveredInsets);
                }

                if (!hadSurface && mSurface.isValid()) {
                    // If we are creating a new surface, then we need to
                    // completely redraw it.  Also, when we get to the
                    // point of drawing it we will hold off and schedule
                    // a new traversal instead.  This is so we can tell the
                    // window manager about all of the windows being displayed
                    // before actually drawing them, so it can display then
                    // all at once.
                    newSurface = true;
                    fullRedrawNeeded = true;

                    if (mGlWanted && !mUseGL) {
                        initializeGL();
                        initialized = mGlCanvas != null;
                    }
                }
            } catch (RemoteException e) {
            }
            if (DEBUG_ORIENTATION) Log.v(
                    "ViewRoot", "Relayout returned: frame=" + mWinFrame + ", surface=" + mSurface);

            attachInfo.mWindowLeft = frame.left;
            attachInfo.mWindowTop = frame.top;

            // !!FIXME!! This next section handles the case where we did not get the
            // window size we asked for. We should avoid this by getting a maximum size from
            // the window session beforehand.
            mWidth = frame.width();
            mHeight = frame.height();

            if (initialized) {
                mGlCanvas.setViewport(mWidth, mHeight);
            }

            boolean focusChangedDueToTouchMode = ensureTouchModeLocally(
                    (relayoutResult&WindowManagerImpl.RELAYOUT_IN_TOUCH_MODE) != 0);
            if (focusChangedDueToTouchMode || mWidth != host.mMeasuredWidth
                    || mHeight != host.mMeasuredHeight || coveredInsetsChanged) {
                childWidthMeasureSpec = getRootMeasureSpec(mWidth, lp.width);
                childHeightMeasureSpec = getRootMeasureSpec(mHeight, lp.height);

                 // Ask host how big it wants to be
                host.measure(childWidthMeasureSpec, childHeightMeasureSpec);

                // Implementation of weights from WindowManager.LayoutParams
                // We just grow the dimensions as needed and re-measure if
                // needs be
                int width = host.mMeasuredWidth;
                int height = host.mMeasuredHeight;
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
                    host.measure(childWidthMeasureSpec, childHeightMeasureSpec);
                }

                mLayoutRequested = true;
            }
        }

        boolean triggerGlobalLayoutListener = mLayoutRequested
                || attachInfo.mRecomputeGlobalAttributes;
        if (mLayoutRequested) {
            mLayoutRequested = false;
            if (DEBUG_ORIENTATION) Log.v(
                "ViewRoot", "Setting frame " + host + " to (" +
                host.mMeasuredWidth + ", " + host.mMeasuredHeight + ")");
            long startTime;
            if (PROFILE_LAYOUT) {
                startTime = SystemClock.elapsedRealtime();
            }

            host.layout(0, 0, host.mMeasuredWidth, host.mMeasuredHeight);

            if (PROFILE_LAYOUT) {
                EventLog.writeEvent(60001, SystemClock.elapsedRealtime() - startTime);
            }

            // By this point all views have been sized and positionned
            // We can compute the transparent area

            if ((host.mPrivateFlags & View.REQUEST_TRANSPARENT_REGIONS) != 0) {
                // start out transparent
                // TODO: AVOID THAT CALL BY CACHING THE RESULT?
                host.getLocationInWindow(host.mLocation);
                mTransparentRegion.set(host.mLocation[0], host.mLocation[1],
                        host.mLocation[0] + host.mRight - host.mLeft,
                        host.mLocation[1] + host.mBottom - host.mTop);

                host.gatherTransparentRegion(mTransparentRegion);
                if (!mTransparentRegion.equals(mPreviousTransparentRegion)) {
                    mPreviousTransparentRegion.set(mTransparentRegion);
                    // reconfigure window manager
                    try {
                        sWindowSession.setTransparentRegion(mWindow, mTransparentRegion);
                    } catch (RemoteException e) {
                    }
                }
            }


            if (DBG) {
                System.out.println("======================================");
                System.out.println("performTraversals -- after setFrame");
                host.debug();
            }
        }

        if (triggerGlobalLayoutListener) {
            attachInfo.mRecomputeGlobalAttributes = false;
            attachInfo.mTreeObserver.dispatchOnGlobalLayout();
        }

        if (mFirst) {
            // handle first focus request
            if (mView != null && !mView.hasFocus()) {
                mView.requestFocus(View.FOCUS_FORWARD);
                mFocusedView = mView.findFocus();
            }
        }

        mFirst = false;
        mWillDrawSoon = false;
        mNewSurfaceNeeded = false;
        mViewVisibility = viewVisibility;

        boolean cancelDraw = attachInfo.mTreeObserver.dispatchOnPreDraw();

        if (!cancelDraw && !newSurface) {
            mFullRedrawNeeded = false;
            draw(fullRedrawNeeded);

            if ((relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0
                    || mReportNextDraw) {
                if (LOCAL_LOGV) {
                    Log.v("ViewRoot", "FINISHED DRAWING: " + mWindowAttributes.getTitle());
                }
                mReportNextDraw = false;
                try {
                    sWindowSession.finishDrawing(mWindow);
                } catch (RemoteException e) {
                }
            }
        } else {
            // We were supposed to report when we are done drawing. Since we canceled the
            // draw, rememeber it here.
            if ((relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                mReportNextDraw = true;
            }
            if (fullRedrawNeeded) {
                mFullRedrawNeeded = true;
            }
            // Try again
            scheduleTraversals();
        }
    }

    public void requestTransparentRegion(View child) {
        // the test below should not fail unless someone is messing with us
        checkThread();
        if (mView == child) {
            mView.mPrivateFlags |= View.REQUEST_TRANSPARENT_REGIONS;
            // Need to make sure we re-evaluate the window attributes next
            // time around, to ensure the window has the correct format.
            mWindowAttributesChanged = true;
        }
    }

    /**
     * Figures out the measure spec for the root view in a window based on it's
     * layout params.
     *
     * @param windowSize
     *            The available width or height of the window
     *
     * @param rootDimension
     *            The layout params for one dimension (width or height) of the
     *            window.
     *
     * @return The measure spec to use to measure the root view.
     */
    private int getRootMeasureSpec(int windowSize, int rootDimension) {
        int measureSpec;
        switch (rootDimension) {

        case ViewGroup.LayoutParams.FILL_PARENT:
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

    private void draw(boolean fullRedrawNeeded) {
        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) {
            return;
        }

        Rect dirty = mDirty;
        if (mUseGL) {
            if (!dirty.isEmpty()) {
                Canvas canvas = mGlCanvas;
                if (mGL!=null && canvas != null) {
                    mGL.glDisable(GL_SCISSOR_TEST);
                    mGL.glClearColor(0, 0, 0, 0);
                    mGL.glClear(GL_COLOR_BUFFER_BIT);
                    mGL.glEnable(GL_SCISSOR_TEST);

                    mAttachInfo.mDrawingTime = SystemClock.uptimeMillis();
                    mView.draw(canvas);

                    mEgl.eglSwapBuffers(mEglDisplay, mEglSurface);
                    checkEglErrors();

                    if (SHOW_FPS) {
                        int now = (int)SystemClock.elapsedRealtime();
                        if (sDrawTime != 0) {
                            nativeShowFPS(canvas, now - sDrawTime);
                        }
                        sDrawTime = now;
                    }
                }
            }
            return;
        }


        if (fullRedrawNeeded)
            dirty.union(0, 0, mWidth, mHeight);

        if (DEBUG_ORIENTATION || DEBUG_DRAW) {
            Log.v("ViewRoot", "Draw " + mView + "/"
                    + mWindowAttributes.getTitle()
                    + ": dirty={" + dirty.left + "," + dirty.top
                    + "," + dirty.right + "," + dirty.bottom + "} surface="
                    + surface + " surface.isValid()=" + surface.isValid());
        }

        if (!dirty.isEmpty()) {
            Canvas canvas;
            try {
                canvas = surface.lockCanvas(dirty);
            } catch (Surface.OutOfResourcesException e) {
                Log.e("ViewRoot", "OutOfResourcesException locking surface", e);
                // TODO: we should ask the window manager to do something!
                // for now we just do nothing
                return;
            }

            long startTime;

            try {
                if (DEBUG_ORIENTATION || DEBUG_DRAW) {
                    Log.v("ViewRoot", "Surface " + surface + " drawing to bitmap w="
                            + canvas.getWidth() + ", h=" + canvas.getHeight());
                    //canvas.drawARGB(255, 255, 0, 0);
                }

                if (PROFILE_DRAWING) {
                    startTime = SystemClock.elapsedRealtime();
                }

                // If this bitmap's format includes an alpha channel, we
                // need to clear it before drawing so that the child will
                // properly re-composite its drawing on a transparent
                // background. This automatically respects the clip/dirty region
                if (!canvas.isOpaque()) {
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                }

                dirty.setEmpty();
                mAttachInfo.mDrawingTime = SystemClock.uptimeMillis();
                mView.draw(canvas);

                if (SHOW_FPS) {
                    int now = (int)SystemClock.elapsedRealtime();
                    if (sDrawTime != 0) {
                        nativeShowFPS(canvas, now - sDrawTime);
                    }
                    sDrawTime = now;
                }

            } finally {
                surface.unlockCanvasAndPost(canvas);
            }

            if (PROFILE_DRAWING) {
                EventLog.writeEvent(60000, SystemClock.elapsedRealtime() - startTime);
            }

            if (LOCAL_LOGV) {
                Log.v("ViewRoot", "Surface " + surface + " unlockCanvasAndPost");
            }
        }
    }

    public void requestChildFocus(View child, View focused) {
        checkThread();
        if (mFocusedView != focused) {
            mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(mFocusedView, focused);
        }
        mFocusedView = focused;
    }

    public void clearChildFocus(View child) {
        checkThread();

        View oldFocus = mFocusedView;

        mFocusedView = null;
        if (mView != null && !mView.hasFocus()) {
            // If a view gets the focus, the listener will be invoked from requestChildFocus()
            if (!mView.requestFocus(View.FOCUS_FORWARD)) {
                mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(oldFocus, null);
            }
        } else if (oldFocus != null) {
            mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(oldFocus, null);
        }
    }


    public void focusableViewAvailable(View v) {
        checkThread();

        if (mView != null && !mView.hasFocus()) {
            v.requestFocus();
        } else {
            // the one case where will transfer focus away from the current one
            // is if the current view is a view group that prefers to give focus
            // to its children first AND the view is a descendant of it.
            mFocusedView = mView.findFocus();
            boolean descendantsHaveDibsOnFocus =
                    (mFocusedView instanceof ViewGroup) &&
                        (((ViewGroup) mFocusedView).getDescendantFocusability() ==
                                ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (descendantsHaveDibsOnFocus && isViewDescendantOf(v, mFocusedView)) {
                // If a view gets the focus, the listener will be invoked from requestChildFocus()
                v.requestFocus();
            }
        }
    }

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
        if (Config.LOGV) Log.v("ViewRoot", "Detaching in " + this + " of " + mSurface);
        if (mView != null) {
            mView.dispatchDetachedFromWindow();
        }
        mView = null;
        if (mUseGL) {
            destroyGL();
        }
    }
    
    /**
     * Return true if child is an ancestor of parent, (or equal to the parent).
     */
    private static boolean isViewDescendantOf(View child, View parent) {
        if (child == parent) {
            return true;
        }

        final ViewParent theParent = child.getParent();
        return (theParent instanceof ViewGroup) && isViewDescendantOf((View) theParent, parent);
    }


    private final static int DO_TRAVERSAL = 1000;
    private final static int DIE = 1001;
    private final static int RESIZED = 1002;
    private final static int RESIZED_REPORT = 1003;
    private final static int WINDOW_FOCUS_CHANGED = 1004;
    private final static int DISPATCH_KEY = 1005;
    private final static int DISPATCH_POINTER = 1006;
    private final static int DISPATCH_TRACKBALL = 1007;
    private final static int DISPATCH_APP_VISIBILITY = 1008;
    private final static int DISPATCH_GET_NEW_SURFACE = 1009;

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
        case DO_TRAVERSAL:
            if (mProfile) {
                Debug.startMethodTracing("ViewRoot");
            }

            performTraversals();

            if (mProfile) {
                Debug.stopMethodTracing();
                mProfile = false;
            }
            break;
        case DISPATCH_KEY:
            if (LOCAL_LOGV) Log.v(
                "ViewRoot", "Dispatching key "
                + msg.obj + " to " + mView);
            deliverKeyEvent((KeyEvent)msg.obj, true);
            break;
        case DISPATCH_POINTER:
            MotionEvent event = (MotionEvent)msg.obj;

            boolean didFinish;
            if (event == null) {
                try {
                    event = sWindowSession.getPendingPointerMove(mWindow);
                } catch (RemoteException e) {
                }
                didFinish = true;
            } else {
                didFinish = false;
            }

            try {
                boolean handled;
                if (mView != null && mAdded && event != null) {

                    // enter touch mode on the down
                    boolean isDown = event.getAction() == MotionEvent.ACTION_DOWN;
                    if (isDown) {
                        ensureTouchMode(true);
                    }

                    handled = mView.dispatchTouchEvent(event);
                    if (!handled && isDown) {
                        int edgeSlop = ViewConfiguration.getEdgeSlop();

                        final int edgeFlags = event.getEdgeFlags();
                        int direction = View.FOCUS_UP;
                        int x = (int)event.getX();
                        int y = (int)event.getY();
                        final int[] deltas = new int[2];

                        if ((edgeFlags & MotionEvent.EDGE_TOP) != 0) {
                            direction = View.FOCUS_DOWN;
                            if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                                deltas[0] = edgeSlop;
                                x += edgeSlop;
                            } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                                deltas[0] = -edgeSlop;
                                x -= edgeSlop;
                            }
                        } else if ((edgeFlags & MotionEvent.EDGE_BOTTOM) != 0) {
                            direction = View.FOCUS_UP;
                            if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                                deltas[0] = edgeSlop;
                                x += edgeSlop;
                            } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                                deltas[0] = -edgeSlop;
                                x -= edgeSlop;
                            }
                        } else if ((edgeFlags & MotionEvent.EDGE_LEFT) != 0) {
                            direction = View.FOCUS_RIGHT;
                        } else if ((edgeFlags & MotionEvent.EDGE_RIGHT) != 0) {
                            direction = View.FOCUS_LEFT;
                        }

                        if (edgeFlags != 0 && mView instanceof ViewGroup) {
                            View nearest = FocusFinder.getInstance().findNearestTouchable(
                                    ((ViewGroup) mView), x, y, direction, deltas);
                            if (nearest != null) {
                                event.offsetLocation(deltas[0], deltas[1]);
                                event.setEdgeFlags(0);
                                mView.dispatchTouchEvent(event);
                            }
                        }
                    }
                }
            } finally {
                if (!didFinish) {
                    try {
                        sWindowSession.finishKey(mWindow);
                    } catch (RemoteException e) {
                    }
                }
                if (event != null) {
                    event.recycle();
                }
                if (LOCAL_LOGV || WATCH_POINTER) Log.i(TAG, "Done dispatching!");
                // Let the exception fall through -- the looper will catch
                // it and take care of the bad app for us.
            }
            break;
        case DISPATCH_TRACKBALL:
            deliverTrackballEvent((MotionEvent)msg.obj);
            break;
        case DISPATCH_APP_VISIBILITY:
            handleAppVisibility(msg.arg1 != 0);
            break;
        case DISPATCH_GET_NEW_SURFACE:
            handleGetNewSurface();
            break;
        case RESIZED:
            if (mWinFrame.width() == msg.arg1 && mWinFrame.height() == msg.arg2) {
                break;
            }
            // fall through...
        case RESIZED_REPORT:
            if (mAdded) {
                mWinFrame.left = 0;
                mWinFrame.right = msg.arg1;
                mWinFrame.top = 0;
                mWinFrame.bottom = msg.arg2;
                if (msg.what == RESIZED_REPORT) {
                    mReportNextDraw = true;
                }
                requestLayout();
            }
            break;
        case WINDOW_FOCUS_CHANGED: {
            if (mAdded) {
                boolean hasWindowFocus = msg.arg1 != 0;
                mAttachInfo.mHasWindowFocus = hasWindowFocus;
                if (hasWindowFocus) {
                    boolean inTouchMode = msg.arg2 != 0;
                    ensureTouchModeLocally(inTouchMode);

                    if (mGlWanted) {
                        checkEglErrors();
                        // we lost the gl context, so recreate it.
                        if (mGlWanted && !mUseGL) {
                            initializeGL();
                            if (mGlCanvas != null) {
                                mGlCanvas.setViewport(mWidth, mHeight);
                            }
                        }
                    }
                }
                if (mView != null) {
                    mView.dispatchWindowFocusChanged(hasWindowFocus);
                }
            }
        } break;
        case DIE:
            dispatchDetachedFromWindow();
            break;
        }
    }

    /**
     * Something in the current window tells us we need to change the touch mode.  For
     * example, we are not in touch mode, and the user touches the screen.
     *
     * If the touch mode has changed, tell the window manager, and handle it locally.
     *
     * @param inTouchMode Whether we want to be in touch mode.
     * @return True if the touch mode changed and focus changed was changed as a result
     */
    boolean ensureTouchMode(boolean inTouchMode) {
        if (DBG) Log.d("touchmode", "ensureTouchMode(" + inTouchMode + "), current "
                + "touch mode is " + mAttachInfo.mInTouchMode);
        if (mAttachInfo.mInTouchMode == inTouchMode) return false;

        // tell the window manager
        try {
            sWindowSession.setInTouchMode(inTouchMode);
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
        if (mView != null) {
            if (mView.hasFocus()) {
                // note: not relying on mFocusedView here because this could
                // be when the window is first being added, and mFocused isn't
                // set yet.
                final View focused = mView.findFocus();
                if (focused != null && !focused.isFocusableInTouchMode()) {

                    final ViewGroup ancestorToTakeFocus =
                            findAncestorToTakeFocusInTouchMode(focused);
                    if (ancestorToTakeFocus != null) {
                        // there is an ancestor that wants focus after its descendants that
                        // is focusable in touch mode.. give it focus
                        return ancestorToTakeFocus.requestFocus();
                    } else {
                        // nothing appropriate to have focus in touch mode, clear it out
                        mView.unFocus();
                        mAttachInfo.mTreeObserver.dispatchOnGlobalFocusChange(focused, null);
                        mFocusedView = null;
                        return true;
                    }
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
    private ViewGroup findAncestorToTakeFocusInTouchMode(View focused) {
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
                // i learned the hard way to not trust mFocusedView :)
                mFocusedView = mView.findFocus();
                if (!(mFocusedView instanceof ViewGroup)) {
                    // some view has focus, let it keep it
                    return false;
                } else if (((ViewGroup)mFocusedView).getDescendantFocusability() !=
                        ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                    // some view group has focus, and doesn't prefer its children
                    // over itself for focus, so let them keep it.
                    return false;
                }
            }

            // find the best view to give focus to in this brave new non-touch-mode
            // world
            final View focused = focusSearch(null, View.FOCUS_DOWN);
            if (focused != null) {
                return focused.requestFocus(View.FOCUS_DOWN);
            }
        }
        return false;
    }


    private void deliverTrackballEvent(MotionEvent event) {
        boolean didFinish;
        if (event == null) {
            try {
                event = sWindowSession.getPendingTrackballMove(mWindow);
            } catch (RemoteException e) {
            }
            didFinish = true;
        } else {
            didFinish = false;
        }

        //Log.i("foo", "Motion event:" + event);

        boolean handled = false;
        try {
            if (event == null) {
                handled = true;
            } else if (mView != null && mAdded) {
                handled = mView.dispatchTrackballEvent(event);
                if (!handled) {
                    // we could do something here, like changing the focus
                    // or someting?
                }
            }
        } finally {
            if (handled) {
                if (!didFinish) {
                    try {
                        sWindowSession.finishKey(mWindow);
                    } catch (RemoteException e) {
                    }
                }
                if (event != null) {
                    event.recycle();
                }
                //noinspection ReturnInsideFinallyBlock
                return;
            }
            // Let the exception fall through -- the looper will catch
            // it and take care of the bad app for us.
        }

        final TrackballAxis x = mTrackballAxisX;
        final TrackballAxis y = mTrackballAxisY;

        long curTime = SystemClock.uptimeMillis();
        if ((mLastTrackballTime+MAX_TRACKBALL_DELAY) < curTime) {
            // It has been too long since the last movement,
            // so restart at the beginning.
            x.reset(0);
            y.reset(0);
            mLastTrackballTime = curTime;
        }

        try {
            final int action = event.getAction();
            final int metastate = event.getMetaState();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    x.reset(2);
                    y.reset(2);
                    deliverKeyEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                            0, metastate), false);
                    break;
                case MotionEvent.ACTION_UP:
                    x.reset(2);
                    y.reset(2);
                    deliverKeyEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER,
                            0, metastate), false);
                    break;
            }

            if (DEBUG_TRACKBALL) Log.v(TAG, "TB X=" + x.position + " step="
                    + x.step + " dir=" + x.dir + " acc=" + x.acceleration
                    + " move=" + event.getX()
                    + " / Y=" + y.position + " step="
                    + y.step + " dir=" + y.dir + " acc=" + y.acceleration
                    + " move=" + event.getY());
            final float xOff = x.collect(event.getX(), "X");
            final float yOff = y.collect(event.getY(), "Y");

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
                movement = x.generate((2/event.getXPrecision()));
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                            : KeyEvent.KEYCODE_DPAD_LEFT;
                    accel = x.acceleration;
                    y.reset(2);
                }
            } else if (yOff > 0) {
                movement = y.generate((2/event.getYPrecision()));
                if (movement != 0) {
                    keycode = movement > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                            : KeyEvent.KEYCODE_DPAD_UP;
                    accel = y.acceleration;
                    x.reset(2);
                }
            }

            if (keycode != 0) {
                if (movement < 0) movement = -movement;
                int accelMovement = (int)(movement * accel);
                //Log.i(TAG, "Move: movement=" + movement
                //        + " accelMovement=" + accelMovement
                //        + " accel=" + accel);
                if (accelMovement > movement) {
                    if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    deliverKeyEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_MULTIPLE, keycode,
                            accelMovement-movement, metastate), false);
                }
                while (movement > 0) {
                    if (DEBUG_TRACKBALL) Log.v("foo", "Delivering fake DPAD: "
                            + keycode);
                    movement--;
                    curTime = SystemClock.uptimeMillis();
                    deliverKeyEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_DOWN, keycode, 0, event.getMetaState()), false);
                    deliverKeyEvent(new KeyEvent(curTime, curTime,
                            KeyEvent.ACTION_UP, keycode, 0, metastate), false);
                }
                mLastTrackballTime = curTime;
            }
        } finally {
            if (!didFinish) {
                try {
                    sWindowSession.finishKey(mWindow);
                } catch (RemoteException e) {
                }
                if (event != null) {
                    event.recycle();
                }
            }
            // Let the exception fall through -- the looper will catch
            // it and take care of the bad app for us.
        }
    }

    /**
     * @param keyCode The key code
     * @return True if the key is directional.
     */
    static boolean isDirectional(int keyCode) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_DPAD_LEFT:
        case KeyEvent.KEYCODE_DPAD_RIGHT:
        case KeyEvent.KEYCODE_DPAD_UP:
        case KeyEvent.KEYCODE_DPAD_DOWN:
            return true;
        }
        return false;
    }

    /**
     * Returns true if this key is a keyboard key.
     * @param keyEvent The key event.
     * @return whether this key is a keyboard key.
     */
    private static boolean isKeyboardKey(KeyEvent keyEvent) {
      final int convertedKey = keyEvent.getUnicodeChar();
        return convertedKey > 0;
    }



    /**
     * See if the key event means we should leave touch mode (and leave touch
     * mode if so).
     * @param event The key event.
     * @return Whether this key event should be consumed (meaning the act of
     *   leaving touch mode alone is considered the event).
     */
    private boolean checkForLeavingTouchModeAndConsume(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }

        // only relevant if we are in touch mode
        if (!mAttachInfo.mInTouchMode) {
            return false;
        }

        // if something like an edit text has focus and the user is typing,
        // leave touch mode
        //
        // note: the condition of not being a keyboard key is kind of a hacky
        // approximation of whether we think the focused view will want the
        // key; if we knew for sure whether the focused view would consume
        // the event, that would be better.
        if (isKeyboardKey(event) && mView != null && mView.hasFocus()) {
            mFocusedView = mView.findFocus();
            if ((mFocusedView instanceof ViewGroup)
                    && ((ViewGroup) mFocusedView).getDescendantFocusability() ==
                    ViewGroup.FOCUS_AFTER_DESCENDANTS) {
                // something has focus, but is holding it weakly as a container
                return false;
            }
            if (ensureTouchMode(false)) {
                throw new IllegalStateException("should not have changed focus "
                        + "when leaving touch mode while a view has focus.");
            }
            return false;
        }

        if (isDirectional(event.getKeyCode())) {
            // no view has focus, so we leave touch mode (and find something
            // to give focus to).  the event is consumed if we were able to
            // find something to give focus to.
            return ensureTouchMode(false);
        }
        return false;
    }


    private void deliverKeyEvent(KeyEvent event, boolean sendDone) {
        try {
            if (mView != null && mAdded) {
                final int action = event.getAction();
                boolean isDown = (action == KeyEvent.ACTION_DOWN);

                if (checkForLeavingTouchModeAndConsume(event)) {
                    return;
                }

                boolean keyHandled = mView.dispatchKeyEvent(event);

                if ((!keyHandled && isDown) || (action == KeyEvent.ACTION_MULTIPLE)) {
                    int direction = 0;
                    switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        direction = View.FOCUS_LEFT;
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        direction = View.FOCUS_RIGHT;
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        direction = View.FOCUS_UP;
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        direction = View.FOCUS_DOWN;
                        break;
                    }

                    if (direction != 0) {

                        View focused = mView != null ? mView.findFocus() : null;
                        if (focused != null) {
                            View v = focused.focusSearch(direction);
                            boolean focusPassed = false;
                            if (v != null && v != focused) {
                                // do the math the get the interesting rect
                                // of previous focused into the coord system of
                                // newly focused view
                                focused.getFocusedRect(mTempRect);
                                ((ViewGroup) mView).offsetDescendantRectToMyCoords(focused, mTempRect);
                                ((ViewGroup) mView).offsetRectIntoDescendantCoords(v, mTempRect);
                                focusPassed = v.requestFocus(direction, mTempRect);
                            }

                            if (!focusPassed) {
                                mView.dispatchUnhandledMove(focused, direction);
                            } else {
                                playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
                            }
                        }
                    }
                }
            }

        } finally {
            if (sendDone) {
                if (LOCAL_LOGV) Log.v(
                    "ViewRoot", "Telling window manager key is finished");
                try {
                    sWindowSession.finishKey(mWindow);
                } catch (RemoteException e) {
                }
            }
            // Let the exception fall through -- the looper will catch
            // it and take care of the bad app for us.
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

    /**
     * {@inheritDoc}
     */
    public void playSoundEffect(int effectId) {
        checkThread();

        final AudioManager audioManager = getAudioManager();

        switch (effectId) {
            case SoundEffectConstants.CLICK:
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
                return;
            case SoundEffectConstants.NAVIGATION_DOWN:
                audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_DOWN);
                return;
            case SoundEffectConstants.NAVIGATION_LEFT:
                audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_LEFT);
                return;
            case SoundEffectConstants.NAVIGATION_RIGHT:
                audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
                return;
            case SoundEffectConstants.NAVIGATION_UP:
                audioManager.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_UP);
                return;
            default:
                throw new IllegalArgumentException("unknown effect id " + effectId +
                        " not defined in " + SoundEffectConstants.class.getCanonicalName());
        }
    }

    /**
     * {@inheritDoc}
     */
    public View focusSearch(View focused, int direction) {
        checkThread();
        if (!(mView instanceof ViewGroup)) {
            return null;
        }
        return FocusFinder.getInstance().findNextFocus((ViewGroup) mView, focused, direction);
    }

    public void debug() {
        mView.debug();
    }

    public void die(boolean immediate) {
        checkThread();
        if (Config.LOGV) Log.v("ViewRoot", "DIE in " + this + " of " + mSurface);
        synchronized (this) {
            if (mAdded && !mFirst) {
                int viewVisibility = mView.getVisibility();
                boolean viewVisibilityChanged = mViewVisibility != viewVisibility;
                if (mWindowAttributesChanged || viewVisibilityChanged) {
                    // If layout params have been changed, first give them
                    // to the window manager to make sure it has the correct
                    // animation info.
                    try {
                        if ((sWindowSession.relayout(
                            mWindow, mWindowAttributes,
                            mView.mMeasuredWidth, mView.mMeasuredHeight,
                            viewVisibility, mWinFrame, mCoveredInsets, mSurface)
                            &WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                            sWindowSession.finishDrawing(mWindow);
                        }
                    } catch (RemoteException e) {
                    }
                }

                mSurface = null;
            }
            if (mAdded) {
                mAdded = false;
                try {
                    sWindowSession.remove(mWindow);
                } catch (RemoteException e) {
                }
                if (immediate) {
                    dispatchDetachedFromWindow();
                } else if (mView != null) {
                    sendEmptyMessage(DIE);
                }
            }
        }
    }

    public void dispatchResized(int w, int h, boolean reportDraw) {
        if (DEBUG_DRAW) Log.v(TAG, "Resized " + this + ": w=" + w
                + " h=" + h + " reportDraw=" + reportDraw);
        Message msg = obtainMessage(reportDraw ? RESIZED_REPORT : RESIZED);
        msg.arg1 = w;
        msg.arg2 = h;
        sendMessage(msg);
    }

    public void dispatchKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            //noinspection ConstantConditions
            if (false && event.getKeyCode() == KeyEvent.KEYCODE_CAMERA) {
                if (Config.LOGD) Log.d("keydisp",
                        "===================================================");
                if (Config.LOGD) Log.d("keydisp", "Focused view Hierarchy is:");
                debug();

                if (Config.LOGD) Log.d("keydisp",
                        "===================================================");
            }
        }

        Message msg = obtainMessage(DISPATCH_KEY);
        msg.obj = event;

        if (LOCAL_LOGV) Log.v(
            "ViewRoot", "sending key " + event + " to " + mView);

        sendMessageAtTime(msg, event.getEventTime());
    }

    public void dispatchPointer(MotionEvent event, long eventTime) {
        Message msg = obtainMessage(DISPATCH_POINTER);
        msg.obj = event;
        sendMessageAtTime(msg, eventTime);
    }

    public void dispatchTrackball(MotionEvent event, long eventTime) {
        Message msg = obtainMessage(DISPATCH_TRACKBALL);
        msg.obj = event;
        sendMessageAtTime(msg, eventTime);
    }

    public void dispatchAppVisibility(boolean visible) {
        Message msg = obtainMessage(DISPATCH_APP_VISIBILITY);
        msg.arg1 = visible ? 1 : 0;
        sendMessage(msg);
    }

    public void dispatchGetNewSurface() {
        Message msg = obtainMessage(DISPATCH_GET_NEW_SURFACE);
        sendMessage(msg);
    }

    public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
        Message msg = Message.obtain();
        msg.what = WINDOW_FOCUS_CHANGED;
        msg.arg1 = hasFocus ? 1 : 0;
        msg.arg2 = inTouchMode ? 1 : 0;
        sendMessage(msg);
    }

    public boolean showContextMenuForChild(View originalView) {
        return false;
    }

    public void createContextMenu(ContextMenu menu) {
    }

    public void childDrawableStateChanged(View child) {
    }

    protected Rect getWindowFrame() {
        return mWinFrame;
    }

    void checkThread() {
        if (mThread != Thread.currentThread()) {
            throw new CalledFromWrongThreadException(
                    "Only the original thread that created a view hierarchy can touch its views.");
        }
    }

    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // ViewRoot never intercepts touch event, so this can be a no-op
    }

    static class W extends IWindow.Stub {
        private WeakReference<ViewRoot> mViewRoot;

        public W(ViewRoot viewRoot) {
            mViewRoot = new WeakReference<ViewRoot>(viewRoot);
        }

        public void resized(int w, int h, boolean reportDraw) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchResized(w, h, reportDraw);
            }
        }

        public void dispatchKey(KeyEvent event) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchKey(event);
            }
        }

        public void dispatchPointer(MotionEvent event, long eventTime) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchPointer(event, eventTime);
            }
        }

        public void dispatchTrackball(MotionEvent event, long eventTime) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchTrackball(event, eventTime);
            }
        }

        public void dispatchAppVisibility(boolean visible) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchAppVisibility(visible);
            }
        }

        public void dispatchGetNewSurface() {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.dispatchGetNewSurface();
            }
        }

        public void windowFocusChanged(boolean hasFocus, boolean inTouchMode) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                viewRoot.windowFocusChanged(hasFocus, inTouchMode);
            }
        }

        private static int checkCallingPermission(String permission) {
            if (!Process.supportsProcesses()) {
                return PackageManager.PERMISSION_GRANTED;
            }

            try {
                return ActivityManagerNative.getDefault().checkPermission(
                        permission, Binder.getCallingPid(), Binder.getCallingUid());
            } catch (RemoteException e) {
                return PackageManager.PERMISSION_DENIED;
            }
        }

        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
            final ViewRoot viewRoot = mViewRoot.get();
            if (viewRoot != null) {
                final View view = viewRoot.mView;
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
    }

    /**
     * Maintains state information for a single trackball axis, generating
     * discrete (DPAD) movements based on raw trackball motion.
     */
    static final class TrackballAxis {
        float position;
        float absPosition;
        float acceleration = 1;
        int step;
        int dir;
        int nonAccelMovement;

        void reset(int _step) {
            position = 0;
            acceleration = 1;
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
        float collect(float off, String axis) {
            if (off > 0) {
                if (dir < 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to positive!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                }
                dir = 1;
            } else if (off < 0) {
                if (dir > 0) {
                    if (DEBUG_TRACKBALL) Log.v(TAG, axis + " reversed to negative!");
                    position = 0;
                    step = 0;
                    acceleration = 1;
                }
                dir = -1;
            }
            position += off;
            return (absPosition = Math.abs(position));
        }

        /**
         * Generate the number of discrete movement events appropriate for
         * the currently collected trackball movement.
         *
         * @param precision The minimum movement required to generate the
         * first discrete movement.
         *
         * @return Returns the number of discrete movements, either positive
         * or negative, or 0 if there is not enough trackball movement yet
         * for a discrete movement.
         */
        int generate(float precision) {
            int movement = 0;
            nonAccelMovement = 0;
            do {
                final int dir = position >= 0 ? 1 : -1;
                switch (step) {
                    // If we are going to execute the first step, then we want
                    // to do this as soon as possible instead of waiting for
                    // a full movement, in order to make things look responsive.
                    case 0:
                        if (absPosition < precision) {
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
                        if (absPosition < 2) {
                            return movement;
                        }
                        movement += dir;
                        nonAccelMovement += dir;
                        position += dir > 0 ? -2 : 2;
                        absPosition = Math.abs(position);
                        step = 2;
                        break;
                    // After the first two, we generate discrete movements
                    // consistently with the trackball, applying an acceleration
                    // if the trackball is moving quickly.  The acceleration is
                    // currently very simple, just reducing the amount of
                    // trackball motion required as more discrete movements are
                    // generated.  This should probably be changed to take time
                    // more into account, so that quick trackball movements will
                    // have increased acceleration.
                    default:
                        if (absPosition < 1) {
                            return movement;
                        }
                        movement += dir;
                        position += dir >= 0 ? -1 : 1;
                        absPosition = Math.abs(position);
                        float acc = acceleration;
                        acc *= 1.1f;
                        acceleration = acc < 20 ? acc : acceleration;
                        break;
                }
            } while (true);
        }
    }

    public static final class CalledFromWrongThreadException extends AndroidRuntimeException {
        public CalledFromWrongThreadException(String msg) {
            super(msg);
        }
    }

    private static final class RootHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case View.AttachInfo.INVALIDATE_MSG:
                    ((View) msg.obj).invalidate();
                    break;
                case View.AttachInfo.INVALIDATE_RECT_MSG:
                    int left = msg.arg1 >>> 16;
                    int top = msg.arg1 & 0xFFFF;
                    int right = msg.arg2 >>> 16;
                    int bottom = msg.arg2 & 0xFFFF;
                    ((View) msg.obj).invalidate(left, top, right, bottom);
                    break;
            }
        }
    }

    private SurfaceHolder mHolder = new SurfaceHolder() {
        // we only need a SurfaceHolder for opengl. it would be nice
        // to implement everything else though, especially the callback
        // support (opengl doesn't make use of it right now, but eventually
        // will).
        public Surface getSurface() {
            return mSurface;
        }

        public boolean isCreating() {
            return false;
        }

        public void addCallback(Callback callback) {
        }

        public void removeCallback(Callback callback) {
        }

        public void setFixedSize(int width, int height) {
        }

        public void setSizeFromLayout() {
        }

        public void setFormat(int format) {
        }

        public void setType(int type) {
        }

        public void setKeepScreenOn(boolean screenOn) {
        }

        public Canvas lockCanvas() {
            return null;
        }

        public Canvas lockCanvas(Rect dirty) {
            return null;
        }

        public void unlockCanvasAndPost(Canvas canvas) {
        }
        public Rect getSurfaceFrame() {
            return null;
        }
    };

    /**
     * @hide
     */
    static final class RunQueue {
        private final ArrayList<HandlerAction> mActions = new ArrayList<HandlerAction>();

        void post(Runnable action) {
            postDelayed(action, 0);
        }

        void postDelayed(Runnable action, long delayMillis) {
            HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;
            handlerAction.delay = delayMillis;

            synchronized (mActions) {
                mActions.add(handlerAction);
            }
        }

        void removeCallbacks(Runnable action) {
            final HandlerAction handlerAction = new HandlerAction();
            handlerAction.action = action;

            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;
                final int count = actions.size();

                while (actions.remove(handlerAction)) {
                    // Keep going
                }
            }
        }

        void executeActions(Handler handler) {
            synchronized (mActions) {
                final ArrayList<HandlerAction> actions = mActions;
                final int count = actions.size();

                for (int i = 0; i < count; i++) {
                    final HandlerAction handlerAction = actions.get(i);
                    handler.postDelayed(handlerAction.action, handlerAction.delay);
                }

                mActions.clear();
            }
        }

        private static class HandlerAction {
            Runnable action;
            long delay;

            @Override
            public boolean equals(Object o) {
                return action.equals(o);
            }
        }
    }

    private static native void nativeShowFPS(Canvas canvas, int durationMillis);

    // inform skia to just abandon its texture cache IDs
    // doesn't call glDeleteTextures
    private static native void nativeAbandonGlCaches();
}
