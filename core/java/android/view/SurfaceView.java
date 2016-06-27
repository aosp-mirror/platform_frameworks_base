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

import com.android.internal.view.BaseIWindow;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.CompatibilityInfo.Translator;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Provides a dedicated drawing surface embedded inside of a view hierarchy.
 * You can control the format of this surface and, if you like, its size; the
 * SurfaceView takes care of placing the surface at the correct location on the
 * screen
 *
 * <p>The surface is Z ordered so that it is behind the window holding its
 * SurfaceView; the SurfaceView punches a hole in its window to allow its
 * surface to be displayed. The view hierarchy will take care of correctly
 * compositing with the Surface any siblings of the SurfaceView that would
 * normally appear on top of it. This can be used to place overlays such as
 * buttons on top of the Surface, though note however that it can have an
 * impact on performance since a full alpha-blended composite will be performed
 * each time the Surface changes.
 *
 * <p> The transparent region that makes the surface visible is based on the
 * layout positions in the view hierarchy. If the post-layout transform
 * properties are used to draw a sibling view on top of the SurfaceView, the
 * view may not be properly composited with the surface.
 *
 * <p>Access to the underlying surface is provided via the SurfaceHolder interface,
 * which can be retrieved by calling {@link #getHolder}.
 *
 * <p>The Surface will be created for you while the SurfaceView's window is
 * visible; you should implement {@link SurfaceHolder.Callback#surfaceCreated}
 * and {@link SurfaceHolder.Callback#surfaceDestroyed} to discover when the
 * Surface is created and destroyed as the window is shown and hidden.
 *
 * <p>One of the purposes of this class is to provide a surface in which a
 * secondary thread can render into the screen. If you are going to use it
 * this way, you need to be aware of some threading semantics:
 *
 * <ul>
 * <li> All SurfaceView and
 * {@link SurfaceHolder.Callback SurfaceHolder.Callback} methods will be called
 * from the thread running the SurfaceView's window (typically the main thread
 * of the application). They thus need to correctly synchronize with any
 * state that is also touched by the drawing thread.
 * <li> You must ensure that the drawing thread only touches the underlying
 * Surface while it is valid -- between
 * {@link SurfaceHolder.Callback#surfaceCreated SurfaceHolder.Callback.surfaceCreated()}
 * and
 * {@link SurfaceHolder.Callback#surfaceDestroyed SurfaceHolder.Callback.surfaceDestroyed()}.
 * </ul>
 *
 * <p class="note"><strong>Note:</strong> Starting in platform version
 * {@link android.os.Build.VERSION_CODES#N}, SurfaceView's window position is
 * updated synchronously with other View rendering. This means that translating
 * and scaling a SurfaceView on screen will not cause rendering artifacts. Such
 * artifacts may occur on previous versions of the platform when its window is
 * positioned asynchronously.</p>
 */
public class SurfaceView extends View {
    static private final String TAG = "SurfaceView";
    static private final boolean DEBUG = false;

    final ArrayList<SurfaceHolder.Callback> mCallbacks
            = new ArrayList<SurfaceHolder.Callback>();

    final int[] mLocation = new int[2];

    final ReentrantLock mSurfaceLock = new ReentrantLock();
    final Surface mSurface = new Surface();       // Current surface in use
    final Surface mNewSurface = new Surface();    // New surface we are switching to
    boolean mDrawingStopped = true;

    final WindowManager.LayoutParams mLayout
            = new WindowManager.LayoutParams();
    IWindowSession mSession;
    MyWindow mWindow;
    final Rect mVisibleInsets = new Rect();
    final Rect mWinFrame = new Rect();
    final Rect mOverscanInsets = new Rect();
    final Rect mContentInsets = new Rect();
    final Rect mStableInsets = new Rect();
    final Rect mOutsets = new Rect();
    final Rect mBackdropFrame = new Rect();
    final Configuration mConfiguration = new Configuration();

    static final int KEEP_SCREEN_ON_MSG = 1;
    static final int GET_NEW_SURFACE_MSG = 2;
    static final int UPDATE_WINDOW_MSG = 3;

    int mWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;

    boolean mIsCreating = false;
    private volatile boolean mRtHandlingPositionUpdates = false;

    final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case KEEP_SCREEN_ON_MSG: {
                    setKeepScreenOn(msg.arg1 != 0);
                } break;
                case GET_NEW_SURFACE_MSG: {
                    handleGetNewSurface();
                } break;
                case UPDATE_WINDOW_MSG: {
                    updateWindow(false, false);
                } break;
            }
        }
    };

    private final ViewTreeObserver.OnScrollChangedListener mScrollChangedListener
            = new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        updateWindow(false, false);
                    }
            };

    private final ViewTreeObserver.OnPreDrawListener mDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    // reposition ourselves where the surface is
                    mHaveFrame = getWidth() > 0 && getHeight() > 0;
                    updateWindow(false, false);
                    return true;
                }
            };

    boolean mRequestedVisible = false;
    boolean mWindowVisibility = false;
    boolean mViewVisibility = false;
    int mRequestedWidth = -1;
    int mRequestedHeight = -1;
    /* Set SurfaceView's format to 565 by default to maintain backward
     * compatibility with applications assuming this format.
     */
    int mRequestedFormat = PixelFormat.RGB_565;

    boolean mHaveFrame = false;
    boolean mSurfaceCreated = false;
    long mLastLockTime = 0;

    boolean mVisible = false;
    int mWindowSpaceLeft = -1;
    int mWindowSpaceTop = -1;
    int mWindowSpaceWidth = -1;
    int mWindowSpaceHeight = -1;
    int mFormat = -1;
    final Rect mSurfaceFrame = new Rect();
    int mLastSurfaceWidth = -1, mLastSurfaceHeight = -1;
    boolean mUpdateWindowNeeded;
    boolean mReportDrawNeeded;
    private Translator mTranslator;
    private int mWindowInsetLeft;
    private int mWindowInsetTop;

    private boolean mGlobalListenersAdded;

    public SurfaceView(Context context) {
        super(context);
        init();
    }

    public SurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        setWillNotDraw(true);
    }

    /**
     * Return the SurfaceHolder providing access and control over this
     * SurfaceView's underlying surface.
     *
     * @return SurfaceHolder The holder of the surface.
     */
    public SurfaceHolder getHolder() {
        return mSurfaceHolder;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mParent.requestTransparentRegion(this);
        mSession = getWindowSession();
        mLayout.token = getWindowToken();
        mLayout.setTitle("SurfaceView - " + getViewRootImpl().getTitle());
        mViewVisibility = getVisibility() == VISIBLE;

        if (!mGlobalListenersAdded) {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.addOnScrollChangedListener(mScrollChangedListener);
            observer.addOnPreDrawListener(mDrawListener);
            mGlobalListenersAdded = true;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisibility = visibility == VISIBLE;
        mRequestedVisible = mWindowVisibility && mViewVisibility;
        updateWindow(false, false);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        mViewVisibility = visibility == VISIBLE;
        boolean newRequestedVisible = mWindowVisibility && mViewVisibility;
        if (newRequestedVisible != mRequestedVisible) {
            // our base class (View) invalidates the layout only when
            // we go from/to the GONE state. However, SurfaceView needs
            // to request a re-layout when the visibility changes at all.
            // This is needed because the transparent region is computed
            // as part of the layout phase, and it changes (obviously) when
            // the visibility changes.
            requestLayout();
        }
        mRequestedVisible = newRequestedVisible;
        updateWindow(false, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mGlobalListenersAdded) {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.removeOnScrollChangedListener(mScrollChangedListener);
            observer.removeOnPreDrawListener(mDrawListener);
            mGlobalListenersAdded = false;
        }

        mRequestedVisible = false;
        updateWindow(false, false);
        mHaveFrame = false;
        if (mWindow != null) {
            try {
                mSession.remove(mWindow);
            } catch (RemoteException ex) {
                // Not much we can do here...
            }
            mWindow = null;
        }
        mSession = null;
        mLayout.token = null;

        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = mRequestedWidth >= 0
                ? resolveSizeAndState(mRequestedWidth, widthMeasureSpec, 0)
                : getDefaultSize(0, widthMeasureSpec);
        int height = mRequestedHeight >= 0
                ? resolveSizeAndState(mRequestedHeight, heightMeasureSpec, 0)
                : getDefaultSize(0, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    /** @hide */
    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        boolean result = super.setFrame(left, top, right, bottom);
        updateWindow(false, false);
        return result;
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        if (mWindowType == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
            return super.gatherTransparentRegion(region);
        }

        boolean opaque = true;
        if ((mPrivateFlags & PFLAG_SKIP_DRAW) == 0) {
            // this view draws, remove it from the transparent region
            opaque = super.gatherTransparentRegion(region);
        } else if (region != null) {
            int w = getWidth();
            int h = getHeight();
            if (w>0 && h>0) {
                getLocationInWindow(mLocation);
                // otherwise, punch a hole in the whole hierarchy
                int l = mLocation[0];
                int t = mLocation[1];
                region.op(l, t, l+w, t+h, Region.Op.UNION);
            }
        }
        if (PixelFormat.formatHasAlpha(mRequestedFormat)) {
            opaque = false;
        }
        return opaque;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mWindowType != WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
            // draw() is not called when SKIP_DRAW is set
            if ((mPrivateFlags & PFLAG_SKIP_DRAW) == 0) {
                // punch a whole in the view-hierarchy below us
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }
        }
        super.draw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mWindowType != WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
            // if SKIP_DRAW is cleared, draw() has already punched a hole
            if ((mPrivateFlags & PFLAG_SKIP_DRAW) == PFLAG_SKIP_DRAW) {
                // punch a whole in the view-hierarchy below us
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }
        }
        super.dispatchDraw(canvas);
    }

    /**
     * Control whether the surface view's surface is placed on top of another
     * regular surface view in the window (but still behind the window itself).
     * This is typically used to place overlays on top of an underlying media
     * surface view.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderOnTop}.
     */
    public void setZOrderMediaOverlay(boolean isMediaOverlay) {
        mWindowType = isMediaOverlay
                ? WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
    }

    /**
     * Control whether the surface view's surface is placed on top of its
     * window.  Normally it is placed behind the window, to allow it to
     * (for the most part) appear to composite with the views in the
     * hierarchy.  By setting this, you cause it to be placed above the
     * window.  This means that none of the contents of the window this
     * SurfaceView is in will be visible on top of its surface.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderMediaOverlay}.
     */
    public void setZOrderOnTop(boolean onTop) {
        if (onTop) {
            mWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            // ensures the surface is placed below the IME
            mLayout.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        } else {
            mWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
            mLayout.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
    }

    /**
     * Control whether the surface view's content should be treated as secure,
     * preventing it from appearing in screenshots or from being viewed on
     * non-secure displays.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager.
     *
     * <p>See {@link android.view.Display#FLAG_SECURE} for details.
     *
     * @param isSecure True if the surface view is secure.
     */
    public void setSecure(boolean isSecure) {
        if (isSecure) {
            mLayout.flags |= WindowManager.LayoutParams.FLAG_SECURE;
        } else {
            mLayout.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
        }
    }

    /**
     * Hack to allow special layering of windows.  The type is one of the
     * types in WindowManager.LayoutParams.  This is a hack so:
     * @hide
     */
    public void setWindowType(int type) {
        mWindowType = type;
    }

    /** @hide */
    protected void updateWindow(boolean force, boolean redrawNeeded) {
        if (!mHaveFrame) {
            return;
        }
        ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot != null) {
            mTranslator = viewRoot.mTranslator;
        }

        if (mTranslator != null) {
            mSurface.setCompatibilityTranslator(mTranslator);
        }

        int myWidth = mRequestedWidth;
        if (myWidth <= 0) myWidth = getWidth();
        int myHeight = mRequestedHeight;
        if (myHeight <= 0) myHeight = getHeight();

        final boolean creating = mWindow == null;
        final boolean formatChanged = mFormat != mRequestedFormat;
        final boolean sizeChanged = mWindowSpaceWidth != myWidth || mWindowSpaceHeight != myHeight;
        final boolean visibleChanged = mVisible != mRequestedVisible;
        final boolean layoutSizeChanged = getWidth() != mLayout.width
                || getHeight() != mLayout.height;

        if (force || creating || formatChanged || sizeChanged || visibleChanged
            || mUpdateWindowNeeded || mReportDrawNeeded || redrawNeeded) {
            getLocationInWindow(mLocation);

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                    + "Changes: creating=" + creating
                    + " format=" + formatChanged + " size=" + sizeChanged
                    + " visible=" + visibleChanged
                    + " left=" + (mWindowSpaceLeft != mLocation[0])
                    + " top=" + (mWindowSpaceTop != mLocation[1]));

            try {
                final boolean visible = mVisible = mRequestedVisible;
                mWindowSpaceLeft = mLocation[0];
                mWindowSpaceTop = mLocation[1];
                mWindowSpaceWidth = myWidth;
                mWindowSpaceHeight = myHeight;
                mFormat = mRequestedFormat;

                // Scaling/Translate window's layout here because mLayout is not used elsewhere.

                // Places the window relative
                mLayout.x = mWindowSpaceLeft;
                mLayout.y = mWindowSpaceTop;
                mLayout.width = getWidth();
                mLayout.height = getHeight();
                if (mTranslator != null) {
                    mTranslator.translateLayoutParamsInAppWindowToScreen(mLayout);
                }

                mLayout.format = mRequestedFormat;
                mLayout.flags |=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                              | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                              | WindowManager.LayoutParams.FLAG_SCALED
                              | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                              ;
                if (!creating && !force && !mUpdateWindowNeeded && !sizeChanged) {
                    mLayout.privateFlags |=
                            WindowManager.LayoutParams.PRIVATE_FLAG_PRESERVE_GEOMETRY;
                } else {
                    mLayout.privateFlags &=
                            ~WindowManager.LayoutParams.PRIVATE_FLAG_PRESERVE_GEOMETRY;
                }

                if (!getContext().getResources().getCompatibilityInfo().supportsScreen()) {
                    mLayout.privateFlags |=
                            WindowManager.LayoutParams.PRIVATE_FLAG_COMPATIBLE_WINDOW;
                }
                mLayout.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION
                    | WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME;

                if (mWindow == null) {
                    Display display = getDisplay();
                    mWindow = new MyWindow(this);
                    mLayout.type = mWindowType;
                    mLayout.gravity = Gravity.START|Gravity.TOP;
                    mSession.addToDisplayWithoutInputChannel(mWindow, mWindow.mSeq, mLayout,
                            mVisible ? VISIBLE : GONE, display.getDisplayId(), mContentInsets,
                            mStableInsets);
                }

                boolean realSizeChanged;
                boolean reportDrawNeeded;

                int relayoutResult;

                mSurfaceLock.lock();
                try {
                    mUpdateWindowNeeded = false;
                    reportDrawNeeded = mReportDrawNeeded;
                    mReportDrawNeeded = false;
                    mDrawingStopped = !visible;

                    if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                            + "Cur surface: " + mSurface);

                    relayoutResult = mSession.relayout(
                        mWindow, mWindow.mSeq, mLayout, mWindowSpaceWidth, mWindowSpaceHeight,
                            visible ? VISIBLE : GONE,
                            WindowManagerGlobal.RELAYOUT_DEFER_SURFACE_DESTROY,
                            mWinFrame, mOverscanInsets, mContentInsets,
                            mVisibleInsets, mStableInsets, mOutsets, mBackdropFrame,
                            mConfiguration, mNewSurface);
                    if ((relayoutResult & WindowManagerGlobal.RELAYOUT_RES_FIRST_TIME) != 0) {
                        reportDrawNeeded = true;
                    }

                    if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                            + "New surface: " + mNewSurface
                            + ", vis=" + visible + ", frame=" + mWinFrame);

                    mSurfaceFrame.left = 0;
                    mSurfaceFrame.top = 0;
                    if (mTranslator == null) {
                        mSurfaceFrame.right = mWinFrame.width();
                        mSurfaceFrame.bottom = mWinFrame.height();
                    } else {
                        float appInvertedScale = mTranslator.applicationInvertedScale;
                        mSurfaceFrame.right = (int) (mWinFrame.width() * appInvertedScale + 0.5f);
                        mSurfaceFrame.bottom = (int) (mWinFrame.height() * appInvertedScale + 0.5f);
                    }

                    final int surfaceWidth = mSurfaceFrame.right;
                    final int surfaceHeight = mSurfaceFrame.bottom;
                    realSizeChanged = mLastSurfaceWidth != surfaceWidth
                            || mLastSurfaceHeight != surfaceHeight;
                    mLastSurfaceWidth = surfaceWidth;
                    mLastSurfaceHeight = surfaceHeight;
                } finally {
                    mSurfaceLock.unlock();
                }

                try {
                    redrawNeeded |= creating | reportDrawNeeded;

                    SurfaceHolder.Callback callbacks[] = null;

                    final boolean surfaceChanged = (relayoutResult
                            & WindowManagerGlobal.RELAYOUT_RES_SURFACE_CHANGED) != 0;
                    if (mSurfaceCreated && (surfaceChanged || (!visible && visibleChanged))) {
                        mSurfaceCreated = false;
                        if (mSurface.isValid()) {
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "visibleChanged -- surfaceDestroyed");
                            callbacks = getSurfaceCallbacks();
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceDestroyed(mSurfaceHolder);
                            }
                        }
                    }

                    mSurface.transferFrom(mNewSurface);
                    if (visible && mSurface.isValid()) {
                        if (!mSurfaceCreated && (surfaceChanged || visibleChanged)) {
                            mSurfaceCreated = true;
                            mIsCreating = true;
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "visibleChanged -- surfaceCreated");
                            if (callbacks == null) {
                                callbacks = getSurfaceCallbacks();
                            }
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        if (creating || formatChanged || sizeChanged
                                || visibleChanged || realSizeChanged) {
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "surfaceChanged -- format=" + mFormat
                                    + " w=" + myWidth + " h=" + myHeight);
                            if (callbacks == null) {
                                callbacks = getSurfaceCallbacks();
                            }
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, mFormat, myWidth, myHeight);
                            }
                        }
                        if (redrawNeeded) {
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "surfaceRedrawNeeded");
                            if (callbacks == null) {
                                callbacks = getSurfaceCallbacks();
                            }
                            for (SurfaceHolder.Callback c : callbacks) {
                                if (c instanceof SurfaceHolder.Callback2) {
                                    ((SurfaceHolder.Callback2)c).surfaceRedrawNeeded(
                                            mSurfaceHolder);
                                }
                            }
                        }
                    }
                } finally {
                    mIsCreating = false;
                    if (redrawNeeded) {
                        if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                + "finishedDrawing");
                        mSession.finishDrawing(mWindow);
                    }
                    mSession.performDeferredDestroy(mWindow);
                }
            } catch (RemoteException ex) {
                Log.e(TAG, "Exception from relayout", ex);
            }
            if (DEBUG) Log.v(
                TAG, "Layout: x=" + mLayout.x + " y=" + mLayout.y +
                " w=" + mLayout.width + " h=" + mLayout.height +
                ", frame=" + mSurfaceFrame);
        } else {
            // Calculate the window position in case RT loses the window
            // and we need to fallback to a UI-thread driven position update
            getLocationInWindow(mLocation);
            final boolean positionChanged = mWindowSpaceLeft != mLocation[0]
                    || mWindowSpaceTop != mLocation[1];
            if (positionChanged || layoutSizeChanged) { // Only the position has changed
                mWindowSpaceLeft = mLocation[0];
                mWindowSpaceTop = mLocation[1];
                // For our size changed check, we keep mLayout.width and mLayout.height
                // in view local space.
                mLocation[0] = mLayout.width = getWidth();
                mLocation[1] = mLayout.height = getHeight();

                transformFromViewToWindowSpace(mLocation);

                mWinFrame.set(mWindowSpaceLeft, mWindowSpaceTop,
                        mLocation[0], mLocation[1]);

                if (mTranslator != null) {
                    mTranslator.translateRectInAppWindowToScreen(mWinFrame);
                }

                if (!isHardwareAccelerated() || !mRtHandlingPositionUpdates) {
                    try {
                        if (DEBUG) Log.d(TAG, String.format("%d updateWindowPosition UI, " +
                                "postion = [%d, %d, %d, %d]", System.identityHashCode(this),
                                mWinFrame.left, mWinFrame.top,
                                mWinFrame.right, mWinFrame.bottom));
                        mSession.repositionChild(mWindow, mWinFrame.left, mWinFrame.top,
                                mWinFrame.right, mWinFrame.bottom, -1, mWinFrame);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Exception from relayout", ex);
                    }
                }
            }
        }
    }

    private Rect mRTLastReportedPosition = new Rect();

    /**
     * Called by native on RenderThread to update the window position
     * @hide
     */
    public final void updateWindowPositionRT(long frameNumber,
            int left, int top, int right, int bottom) {
        IWindowSession session = mSession;
        MyWindow window = mWindow;
        if (session == null || window == null) {
            // Guess we got detached, that sucks
            return;
        }
        // TODO: This is teensy bit racey in that a brand new SurfaceView moving on
        // its 2nd frame if RenderThread is running slowly could potentially see
        // this as false, enter the branch, get pre-empted, then this comes along
        // and reports a new position, then the UI thread resumes and reports
        // its position. This could therefore be de-sync'd in that interval, but
        // the synchronization would violate the rule that RT must never block
        // on the UI thread which would open up potential deadlocks. The risk of
        // a single-frame desync is therefore preferable for now.
        mRtHandlingPositionUpdates = true;
        if (mRTLastReportedPosition.left == left
                && mRTLastReportedPosition.top == top
                && mRTLastReportedPosition.right == right
                && mRTLastReportedPosition.bottom == bottom) {
            return;
        }
        try {
            if (DEBUG) {
                Log.d(TAG, String.format("%d updateWindowPosition RT, frameNr = %d, " +
                        "postion = [%d, %d, %d, %d]", System.identityHashCode(this),
                        frameNumber, left, top, right, bottom));
            }
            // Just using mRTLastReportedPosition as a dummy rect here
            session.repositionChild(window, left, top, right, bottom,
                    frameNumber,
                    mRTLastReportedPosition);
            // Now overwrite mRTLastReportedPosition with our values
            mRTLastReportedPosition.set(left, top, right, bottom);
        } catch (RemoteException ex) {
            Log.e(TAG, "Exception from repositionChild", ex);
        }
    }

    /**
     * Called by native on RenderThread to notify that the window is no longer in the
     * draw tree
     * @hide
     */
    public final void windowPositionLostRT(long frameNumber) {
        if (DEBUG) {
            Log.d(TAG, String.format("%d windowPositionLostRT RT, frameNr = %d",
                    System.identityHashCode(this), frameNumber));
        }
        IWindowSession session = mSession;
        MyWindow window = mWindow;
        if (session == null || window == null) {
            // We got detached prior to receiving this, abort
            return;
        }
        if (mRtHandlingPositionUpdates) {
            mRtHandlingPositionUpdates = false;
            // This callback will happen while the UI thread is blocked, so we can
            // safely access other member variables at this time.
            // So do what the UI thread would have done if RT wasn't handling position
            // updates.
            if (!mWinFrame.isEmpty() && !mWinFrame.equals(mRTLastReportedPosition)) {
                try {
                    if (DEBUG) Log.d(TAG, String.format("%d updateWindowPosition, " +
                            "postion = [%d, %d, %d, %d]", System.identityHashCode(this),
                            mWinFrame.left, mWinFrame.top,
                            mWinFrame.right, mWinFrame.bottom));
                    session.repositionChild(window, mWinFrame.left, mWinFrame.top,
                            mWinFrame.right, mWinFrame.bottom, frameNumber, mWinFrame);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Exception from relayout", ex);
                }
            }
            mRTLastReportedPosition.setEmpty();
        }
    }

    private SurfaceHolder.Callback[] getSurfaceCallbacks() {
        SurfaceHolder.Callback callbacks[];
        synchronized (mCallbacks) {
            callbacks = new SurfaceHolder.Callback[mCallbacks.size()];
            mCallbacks.toArray(callbacks);
        }
        return callbacks;
    }

    void handleGetNewSurface() {
        updateWindow(false, false);
    }

    /**
     * Check to see if the surface has fixed size dimensions or if the surface's
     * dimensions are dimensions are dependent on its current layout.
     *
     * @return true if the surface has dimensions that are fixed in size
     * @hide
     */
    public boolean isFixedSize() {
        return (mRequestedWidth != -1 || mRequestedHeight != -1);
    }

    private static class MyWindow extends BaseIWindow {
        private final WeakReference<SurfaceView> mSurfaceView;

        public MyWindow(SurfaceView surfaceView) {
            mSurfaceView = new WeakReference<SurfaceView>(surfaceView);
        }

        @Override
        public void resized(Rect frame, Rect overscanInsets, Rect contentInsets,
                Rect visibleInsets, Rect stableInsets, Rect outsets, boolean reportDraw,
                Configuration newConfig, Rect backDropRect, boolean forceLayout,
                boolean alwaysConsumeNavBar) {
            SurfaceView surfaceView = mSurfaceView.get();
            if (surfaceView != null) {
                if (DEBUG) Log.v(TAG, surfaceView + " got resized: w=" + frame.width()
                        + " h=" + frame.height() + ", cur w=" + mCurWidth + " h=" + mCurHeight);
                surfaceView.mSurfaceLock.lock();
                try {
                    if (reportDraw) {
                        surfaceView.mUpdateWindowNeeded = true;
                        surfaceView.mReportDrawNeeded = true;
                        surfaceView.mHandler.sendEmptyMessage(UPDATE_WINDOW_MSG);
                    } else if (surfaceView.mWinFrame.width() != frame.width()
                            || surfaceView.mWinFrame.height() != frame.height()
                            || forceLayout) {
                        surfaceView.mUpdateWindowNeeded = true;
                        surfaceView.mHandler.sendEmptyMessage(UPDATE_WINDOW_MSG);
                    }
                } finally {
                    surfaceView.mSurfaceLock.unlock();
                }
            }
        }

        @Override
        public void dispatchAppVisibility(boolean visible) {
            // The point of SurfaceView is to let the app control the surface.
        }

        @Override
        public void dispatchGetNewSurface() {
            SurfaceView surfaceView = mSurfaceView.get();
            if (surfaceView != null) {
                Message msg = surfaceView.mHandler.obtainMessage(GET_NEW_SURFACE_MSG);
                surfaceView.mHandler.sendMessage(msg);
            }
        }

        @Override
        public void windowFocusChanged(boolean hasFocus, boolean touchEnabled) {
            Log.w("SurfaceView", "Unexpected focus in surface: focus=" + hasFocus + ", touchEnabled=" + touchEnabled);
        }

        @Override
        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
        }

        int mCurWidth = -1;
        int mCurHeight = -1;
    }

    private final SurfaceHolder mSurfaceHolder = new SurfaceHolder() {

        private static final String LOG_TAG = "SurfaceHolder";

        @Override
        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void addCallback(Callback callback) {
            synchronized (mCallbacks) {
                // This is a linear search, but in practice we'll
                // have only a couple callbacks, so it doesn't matter.
                if (mCallbacks.contains(callback) == false) {
                    mCallbacks.add(callback);
                }
            }
        }

        @Override
        public void removeCallback(Callback callback) {
            synchronized (mCallbacks) {
                mCallbacks.remove(callback);
            }
        }

        @Override
        public void setFixedSize(int width, int height) {
            if (mRequestedWidth != width || mRequestedHeight != height) {
                mRequestedWidth = width;
                mRequestedHeight = height;
                requestLayout();
            }
        }

        @Override
        public void setSizeFromLayout() {
            if (mRequestedWidth != -1 || mRequestedHeight != -1) {
                mRequestedWidth = mRequestedHeight = -1;
                requestLayout();
            }
        }

        @Override
        public void setFormat(int format) {

            // for backward compatibility reason, OPAQUE always
            // means 565 for SurfaceView
            if (format == PixelFormat.OPAQUE)
                format = PixelFormat.RGB_565;

            mRequestedFormat = format;
            if (mWindow != null) {
                updateWindow(false, false);
            }
        }

        /**
         * @deprecated setType is now ignored.
         */
        @Override
        @Deprecated
        public void setType(int type) { }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
            Message msg = mHandler.obtainMessage(KEEP_SCREEN_ON_MSG);
            msg.arg1 = screenOn ? 1 : 0;
            mHandler.sendMessage(msg);
        }

        /**
         * Gets a {@link Canvas} for drawing into the SurfaceView's Surface
         *
         * After drawing into the provided {@link Canvas}, the caller must
         * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
         *
         * The caller must redraw the entire surface.
         * @return A canvas for drawing into the surface.
         */
        @Override
        public Canvas lockCanvas() {
            return internalLockCanvas(null);
        }

        /**
         * Gets a {@link Canvas} for drawing into the SurfaceView's Surface
         *
         * After drawing into the provided {@link Canvas}, the caller must
         * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
         *
         * @param inOutDirty A rectangle that represents the dirty region that the caller wants
         * to redraw.  This function may choose to expand the dirty rectangle if for example
         * the surface has been resized or if the previous contents of the surface were
         * not available.  The caller must redraw the entire dirty region as represented
         * by the contents of the inOutDirty rectangle upon return from this function.
         * The caller may also pass <code>null</code> instead, in the case where the
         * entire surface should be redrawn.
         * @return A canvas for drawing into the surface.
         */
        @Override
        public Canvas lockCanvas(Rect inOutDirty) {
            return internalLockCanvas(inOutDirty);
        }

        private final Canvas internalLockCanvas(Rect dirty) {
            mSurfaceLock.lock();

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " " + "Locking canvas... stopped="
                    + mDrawingStopped + ", win=" + mWindow);

            Canvas c = null;
            if (!mDrawingStopped && mWindow != null) {
                try {
                    c = mSurface.lockCanvas(dirty);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Exception locking surface", e);
                }
            }

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " " + "Returned canvas: " + c);
            if (c != null) {
                mLastLockTime = SystemClock.uptimeMillis();
                return c;
            }

            // If the Surface is not ready to be drawn, then return null,
            // but throttle calls to this function so it isn't called more
            // than every 100ms.
            long now = SystemClock.uptimeMillis();
            long nextTime = mLastLockTime + 100;
            if (nextTime > now) {
                try {
                    Thread.sleep(nextTime-now);
                } catch (InterruptedException e) {
                }
                now = SystemClock.uptimeMillis();
            }
            mLastLockTime = now;
            mSurfaceLock.unlock();

            return null;
        }

        /**
         * Posts the new contents of the {@link Canvas} to the surface and
         * releases the {@link Canvas}.
         *
         * @param canvas The canvas previously obtained from {@link #lockCanvas}.
         */
        @Override
        public void unlockCanvasAndPost(Canvas canvas) {
            mSurface.unlockCanvasAndPost(canvas);
            mSurfaceLock.unlock();
        }

        @Override
        public Surface getSurface() {
            return mSurface;
        }

        @Override
        public Rect getSurfaceFrame() {
            return mSurfaceFrame;
        }
    };
}
