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

import android.util.DisplayMetrics;
import com.android.internal.view.BaseIWindow;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
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
import android.util.Config;
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
 * surface to be displayed.  The view hierarchy will take care of correctly
 * compositing with the Surface any siblings of the SurfaceView that would
 * normally appear on top of it.  This can be used to place overlays such as
 * buttons on top of the Surface, though note however that it can have an
 * impact on performance since a full alpha-blended composite will be performed
 * each time the Surface changes.
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
 * secondary thread can render in to the screen.  If you are going to use it
 * this way, you need to be aware of some threading semantics:
 * 
 * <ul>
 * <li> All SurfaceView and
 * {@link SurfaceHolder.Callback SurfaceHolder.Callback} methods will be called
 * from the thread running the SurfaceView's window (typically the main thread
 * of the application).  They thus need to correctly synchronize with any
 * state that is also touched by the drawing thread.
 * <li> You must ensure that the drawing thread only touches the underlying
 * Surface while it is valid -- between
 * {@link SurfaceHolder.Callback#surfaceCreated SurfaceHolder.Callback.surfaceCreated()}
 * and
 * {@link SurfaceHolder.Callback#surfaceDestroyed SurfaceHolder.Callback.surfaceDestroyed()}.
 * </ul>
 */
public class SurfaceView extends View {
    static private final String TAG = "SurfaceView";
    static private final boolean DEBUG = false;
    static private final boolean localLOGV = DEBUG ? true : Config.LOGV;

    final ArrayList<SurfaceHolder.Callback> mCallbacks
            = new ArrayList<SurfaceHolder.Callback>();

    final int[] mLocation = new int[2];
    
    final ReentrantLock mSurfaceLock = new ReentrantLock();
    final Surface mSurface = new Surface();
    boolean mDrawingStopped = true;

    final WindowManager.LayoutParams mLayout
            = new WindowManager.LayoutParams();
    IWindowSession mSession;
    MyWindow mWindow;
    final Rect mVisibleInsets = new Rect();
    final Rect mWinFrame = new Rect();
    final Rect mContentInsets = new Rect();
    final Configuration mConfiguration = new Configuration();
    
    static final int KEEP_SCREEN_ON_MSG = 1;
    static final int GET_NEW_SURFACE_MSG = 2;
    static final int UPDATE_WINDOW_MSG = 3;
    
    int mWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
    
    boolean mIsCreating = false;

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
                    updateWindow(false);
                } break;
            }
        }
    };
    
    final ViewTreeObserver.OnScrollChangedListener mScrollChangedListener
            = new ViewTreeObserver.OnScrollChangedListener() {
                    public void onScrollChanged() {
                        updateWindow(false);
                    }
            };
            
    boolean mRequestedVisible = false;
    boolean mWindowVisibility = false;
    boolean mViewVisibility = false;
    int mRequestedWidth = -1;
    int mRequestedHeight = -1;
    int mRequestedFormat = PixelFormat.OPAQUE;
    int mRequestedType = -1;

    boolean mHaveFrame = false;
    boolean mDestroyReportNeeded = false;
    boolean mNewSurfaceNeeded = false;
    long mLastLockTime = 0;
    
    boolean mVisible = false;
    int mLeft = -1;
    int mTop = -1;
    int mWidth = -1;
    int mHeight = -1;
    int mFormat = -1;
    int mType = -1;
    final Rect mSurfaceFrame = new Rect();
    int mLastSurfaceWidth = -1, mLastSurfaceHeight = -1;
    boolean mUpdateWindowNeeded;
    boolean mReportDrawNeeded;
    private Translator mTranslator;
    
    public SurfaceView(Context context) {
        super(context);
        setWillNotDraw(true);
    }
    
    public SurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(true);
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
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
        mLayout.setTitle("SurfaceView");
        mViewVisibility = getVisibility() == VISIBLE;
        getViewTreeObserver().addOnScrollChangedListener(mScrollChangedListener);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisibility = visibility == VISIBLE;
        mRequestedVisible = mWindowVisibility && mViewVisibility;
        updateWindow(false);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        mViewVisibility = visibility == VISIBLE;
        mRequestedVisible = mWindowVisibility && mViewVisibility;
        updateWindow(false);
    }

    /**
     * This method is not intended for general use. It was created
     * temporarily to improve performance of 3D layers in Launcher
     * and should be removed and fixed properly.
     * 
     * Do not call this method. Ever.
     * 
     * @hide
     */
    protected void showSurface() {
        if (mSession != null) {
            updateWindow(true);
        }
    }

    /**
     * This method is not intended for general use. It was created
     * temporarily to improve performance of 3D layers in Launcher
     * and should be removed and fixed properly.
     * 
     * Do not call this method. Ever.
     * 
     * @hide
     */
    protected void hideSurface() {
        if (mSession != null && mWindow != null) {
            mSurfaceLock.lock();
            try {
                DisplayMetrics metrics = getResources().getDisplayMetrics();
                mLayout.x = metrics.widthPixels * 3;
                mSession.relayout(mWindow, mLayout, mWidth, mHeight, VISIBLE, false,
                        mWinFrame, mContentInsets, mVisibleInsets, mConfiguration, mSurface);
            } catch (RemoteException e) {
                // Ignore
            } finally {
                mSurfaceLock.unlock();
            }
        }
    }
    
    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(mScrollChangedListener);
        mRequestedVisible = false;
        updateWindow(false);
        mHaveFrame = false;
        if (mWindow != null) {
            try {
                mSession.remove(mWindow);
            } catch (RemoteException ex) {
            }
            mWindow = null;
        }
        mSession = null;
        mLayout.token = null;

        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(mRequestedWidth, widthMeasureSpec);
        int height = getDefaultSize(mRequestedHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateWindow(false);
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        if (mWindowType == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL) {
            return super.gatherTransparentRegion(region);
        }
        
        boolean opaque = true;
        if ((mPrivateFlags & SKIP_DRAW) == 0) {
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
            if ((mPrivateFlags & SKIP_DRAW) == 0) {
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
            if ((mPrivateFlags & SKIP_DRAW) == SKIP_DRAW) {
                // punch a whole in the view-hierarchy below us
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
            }
        }
        // reposition ourselves where the surface is 
        mHaveFrame = true;
        updateWindow(false);
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
     * Hack to allow special layering of windows.  The type is one of the
     * types in WindowManager.LayoutParams.  This is a hack so:
     * @hide
     */
    public void setWindowType(int type) {
        mWindowType = type;
    }

    private void updateWindow(boolean force) {
        if (!mHaveFrame) {
            return;
        }
        ViewRoot viewRoot = (ViewRoot) getRootView().getParent();
        if (viewRoot != null) {
            mTranslator = viewRoot.mTranslator;
        }

        Resources res = getContext().getResources();
        if (mTranslator != null || !res.getCompatibilityInfo().supportsScreen()) {
            mSurface.setCompatibleDisplayMetrics(res.getDisplayMetrics(), mTranslator);
        }
        
        int myWidth = mRequestedWidth;
        if (myWidth <= 0) myWidth = getWidth();
        int myHeight = mRequestedHeight;
        if (myHeight <= 0) myHeight = getHeight();

        getLocationInWindow(mLocation);
        final boolean creating = mWindow == null;
        final boolean formatChanged = mFormat != mRequestedFormat;
        final boolean sizeChanged = mWidth != myWidth || mHeight != myHeight;
        final boolean visibleChanged = mVisible != mRequestedVisible
                || mNewSurfaceNeeded;
        final boolean typeChanged = mType != mRequestedType;
        if (force || creating || formatChanged || sizeChanged || visibleChanged
            || typeChanged || mLeft != mLocation[0] || mTop != mLocation[1]
            || mUpdateWindowNeeded || mReportDrawNeeded) {

            if (localLOGV) Log.i(TAG, "Changes: creating=" + creating
                    + " format=" + formatChanged + " size=" + sizeChanged
                    + " visible=" + visibleChanged
                    + " left=" + (mLeft != mLocation[0])
                    + " top=" + (mTop != mLocation[1]));

            try {
                final boolean visible = mVisible = mRequestedVisible;
                mLeft = mLocation[0];
                mTop = mLocation[1];
                mWidth = myWidth;
                mHeight = myHeight;
                mFormat = mRequestedFormat;
                mType = mRequestedType;

                // Scaling/Translate window's layout here because mLayout is not used elsewhere.
                
                // Places the window relative
                mLayout.x = mLeft;
                mLayout.y = mTop;
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
                if (!getContext().getResources().getCompatibilityInfo().supportsScreen()) {
                    mLayout.flags |= WindowManager.LayoutParams.FLAG_COMPATIBLE_WINDOW;
                }

                mLayout.memoryType = mRequestedType;

                if (mWindow == null) {
                    mWindow = new MyWindow(this);
                    mLayout.type = mWindowType;
                    mLayout.gravity = Gravity.LEFT|Gravity.TOP;
                    mSession.add(mWindow, mLayout,
                            mVisible ? VISIBLE : GONE, mContentInsets);
                }
                
                if (visibleChanged && (!visible || mNewSurfaceNeeded)) {
                    reportSurfaceDestroyed();
                }

                mNewSurfaceNeeded = false;
                
                boolean realSizeChanged;
                boolean reportDrawNeeded;
                
                mSurfaceLock.lock();
                try {
                    mUpdateWindowNeeded = false;
                    reportDrawNeeded = mReportDrawNeeded;
                    mReportDrawNeeded = false;
                    mDrawingStopped = !visible;
    
                    final int relayoutResult = mSession.relayout(
                        mWindow, mLayout, mWidth, mHeight,
                            visible ? VISIBLE : GONE, false, mWinFrame, mContentInsets,
                            mVisibleInsets, mConfiguration, mSurface);
                    if ((relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                        mReportDrawNeeded = true;
                    }
                    
                    if (localLOGV) Log.i(TAG, "New surface: " + mSurface
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
                    if (visible) {
                        mDestroyReportNeeded = true;

                        SurfaceHolder.Callback callbacks[];
                        synchronized (mCallbacks) {
                            callbacks = new SurfaceHolder.Callback[mCallbacks.size()];
                            mCallbacks.toArray(callbacks);
                        }

                        if (visibleChanged) {
                            mIsCreating = true;
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        if (creating || formatChanged || sizeChanged
                                || visibleChanged || realSizeChanged) {
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, mFormat, myWidth, myHeight);
                            }
                        }
                    } else {
                        mSurface.release();
                    }
                } finally {
                    mIsCreating = false;
                    if (creating || reportDrawNeeded) {
                        mSession.finishDrawing(mWindow);
                    }
                }
            } catch (RemoteException ex) {
            }
            if (localLOGV) Log.v(
                TAG, "Layout: x=" + mLayout.x + " y=" + mLayout.y +
                " w=" + mLayout.width + " h=" + mLayout.height +
                ", frame=" + mSurfaceFrame);
        }
    }

    private void reportSurfaceDestroyed() {
        if (mDestroyReportNeeded) {
            mDestroyReportNeeded = false;
            SurfaceHolder.Callback callbacks[];
            synchronized (mCallbacks) {
                callbacks = new SurfaceHolder.Callback[mCallbacks.size()];
                mCallbacks.toArray(callbacks);
            }            
            for (SurfaceHolder.Callback c : callbacks) {
                c.surfaceDestroyed(mSurfaceHolder);
            }
        }
        super.onDetachedFromWindow();
    }

    void handleGetNewSurface() {
        mNewSurfaceNeeded = true;
        updateWindow(false);
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

        public void resized(int w, int h, Rect coveredInsets,
                Rect visibleInsets, boolean reportDraw, Configuration newConfig) {
            SurfaceView surfaceView = mSurfaceView.get();
            if (surfaceView != null) {
                if (localLOGV) Log.v(
                        "SurfaceView", surfaceView + " got resized: w=" +
                                w + " h=" + h + ", cur w=" + mCurWidth + " h=" + mCurHeight);
                surfaceView.mSurfaceLock.lock();
                try {
                    if (reportDraw) {
                        surfaceView.mUpdateWindowNeeded = true;
                        surfaceView.mReportDrawNeeded = true;
                        surfaceView.mHandler.sendEmptyMessage(UPDATE_WINDOW_MSG);
                    } else if (surfaceView.mWinFrame.width() != w
                            || surfaceView.mWinFrame.height() != h) {
                        surfaceView.mUpdateWindowNeeded = true;
                        surfaceView.mHandler.sendEmptyMessage(UPDATE_WINDOW_MSG);
                    }
                } finally {
                    surfaceView.mSurfaceLock.unlock();
                }
            }
        }

        public void dispatchKey(KeyEvent event) {
            SurfaceView surfaceView = mSurfaceView.get();
            if (surfaceView != null) {
                //Log.w("SurfaceView", "Unexpected key event in surface: " + event);
                if (surfaceView.mSession != null && surfaceView.mSurface != null) {
                    try {
                        surfaceView.mSession.finishKey(surfaceView.mWindow);
                    } catch (RemoteException ex) {
                    }
                }
            }
        }

        public void dispatchPointer(MotionEvent event, long eventTime,
                boolean callWhenDone) {
            Log.w("SurfaceView", "Unexpected pointer event in surface: " + event);
            //if (mSession != null && mSurface != null) {
            //    try {
            //        //mSession.finishKey(mWindow);
            //    } catch (RemoteException ex) {
            //    }
            //}
        }

        public void dispatchTrackball(MotionEvent event, long eventTime,
                boolean callWhenDone) {
            Log.w("SurfaceView", "Unexpected trackball event in surface: " + event);
            //if (mSession != null && mSurface != null) {
            //    try {
            //        //mSession.finishKey(mWindow);
            //    } catch (RemoteException ex) {
            //    }
            //}
        }

        public void dispatchAppVisibility(boolean visible) {
            // The point of SurfaceView is to let the app control the surface.
        }

        public void dispatchGetNewSurface() {
            SurfaceView surfaceView = mSurfaceView.get();
            if (surfaceView != null) {
                Message msg = surfaceView.mHandler.obtainMessage(GET_NEW_SURFACE_MSG);
                surfaceView.mHandler.sendMessage(msg);
            }
        }

        public void windowFocusChanged(boolean hasFocus, boolean touchEnabled) {
            Log.w("SurfaceView", "Unexpected focus in surface: focus=" + hasFocus + ", touchEnabled=" + touchEnabled);
        }

        public void executeCommand(String command, String parameters, ParcelFileDescriptor out) {
        }

        int mCurWidth = -1;
        int mCurHeight = -1;
    }

    private SurfaceHolder mSurfaceHolder = new SurfaceHolder() {
        
        private static final String LOG_TAG = "SurfaceHolder";
        private int mSaveCount;
        
        public boolean isCreating() {
            return mIsCreating;
        }

        public void addCallback(Callback callback) {
            synchronized (mCallbacks) {
                // This is a linear search, but in practice we'll 
                // have only a couple callbacks, so it doesn't matter.
                if (mCallbacks.contains(callback) == false) {      
                    mCallbacks.add(callback);
                }
            }
        }

        public void removeCallback(Callback callback) {
            synchronized (mCallbacks) {
                mCallbacks.remove(callback);
            }
        }
        
        public void setFixedSize(int width, int height) {
            if (mRequestedWidth != width || mRequestedHeight != height) {
                mRequestedWidth = width;
                mRequestedHeight = height;
                requestLayout();
            }
        }

        public void setSizeFromLayout() {
            if (mRequestedWidth != -1 || mRequestedHeight != -1) {
                mRequestedWidth = mRequestedHeight = -1;
                requestLayout();
            }
        }

        public void setFormat(int format) {
            mRequestedFormat = format;
            if (mWindow != null) {
                updateWindow(false);
            }
        }

        public void setType(int type) {
            switch (type) {
            case SURFACE_TYPE_HARDWARE:
            case SURFACE_TYPE_GPU:
                // these are deprecated, treat as "NORMAL"
                type = SURFACE_TYPE_NORMAL;
                break;
            }
            switch (type) {
            case SURFACE_TYPE_NORMAL:
            case SURFACE_TYPE_PUSH_BUFFERS:
                mRequestedType = type;
                if (mWindow != null) {
                    updateWindow(false);
                }
                break;
            }
        }

        public void setKeepScreenOn(boolean screenOn) {
            Message msg = mHandler.obtainMessage(KEEP_SCREEN_ON_MSG);
            msg.arg1 = screenOn ? 1 : 0;
            mHandler.sendMessage(msg);
        }
        
        public Canvas lockCanvas() {
            return internalLockCanvas(null);
        }

        public Canvas lockCanvas(Rect dirty) {
            return internalLockCanvas(dirty);
        }

        private final Canvas internalLockCanvas(Rect dirty) {
            if (mType == SURFACE_TYPE_PUSH_BUFFERS) {
                throw new BadSurfaceTypeException(
                        "Surface type is SURFACE_TYPE_PUSH_BUFFERS");
            }
            mSurfaceLock.lock();

            if (localLOGV) Log.i(TAG, "Locking canvas... stopped="
                    + mDrawingStopped + ", win=" + mWindow);

            Canvas c = null;
            if (!mDrawingStopped && mWindow != null) {
                Rect frame = dirty != null ? dirty : mSurfaceFrame;
                try {
                    c = mSurface.lockCanvas(frame);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Exception locking surface", e);
                }
            }

            if (localLOGV) Log.i(TAG, "Returned canvas: " + c);
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

        public void unlockCanvasAndPost(Canvas canvas) {
            mSurface.unlockCanvasAndPost(canvas);
            mSurfaceLock.unlock();
        }

        public Surface getSurface() {
            return mSurface;
        }

        public Rect getSurfaceFrame() {
            return mSurfaceFrame;
        }
    };
}
