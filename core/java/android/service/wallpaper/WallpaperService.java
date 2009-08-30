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

import com.android.internal.os.HandlerCaller;
import com.android.internal.view.BaseIWindow;
import com.android.internal.view.BaseSurfaceHolder;

import android.app.Service;
import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.IWindowSession;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRoot;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

/**
 * A wallpaper service is responsible for showing a live wallpaper behind
 * applications that would like to sit on top of it.
 * @hide Live Wallpaper
 */
public abstract class WallpaperService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    public static final String SERVICE_INTERFACE =
        "android.service.wallpaper.WallpaperService";

    static final String TAG = "WallpaperService";
    static final boolean DEBUG = false;
    
    private static final int DO_ATTACH = 10;
    private static final int DO_DETACH = 20;
    private static final int DO_SET_DESIRED_SIZE = 30;
    
    private static final int MSG_UPDATE_SURFACE = 10000;
    private static final int MSG_VISIBILITY_CHANGED = 10010;
    private static final int MSG_WALLPAPER_OFFSETS = 10020;
    private static final int MSG_WINDOW_RESIZED = 10030;
    private static final int MSG_TOUCH_EVENT = 10040;
    
    /**
     * The actual implementation of a wallpaper.  A wallpaper service may
     * have multiple instances running (for example as a real wallpaper
     * and as a preview), each of which is represented by its own Engine
     * instance.  You must implement {@link WallpaperService#onCreateEngine()}
     * to return your concrete Engine implementation.
     */
    public class Engine {
        IWallpaperEngineWrapper mIWallpaperEngine;
        
        // Copies from mIWallpaperEngine.
        HandlerCaller mCaller;
        IWallpaperConnection mConnection;
        IBinder mWindowToken;
        
        boolean mInitializing = true;
        boolean mVisible;
        boolean mDestroyed;
        
        // Current window state.
        boolean mCreated;
        boolean mIsCreating;
        boolean mDrawingAllowed;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        int mCurWidth;
        int mCurHeight;
        int mWindowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        int mCurWindowFlags = mWindowFlags;
        boolean mDestroyReportNeeded;
        final Rect mVisibleInsets = new Rect();
        final Rect mWinFrame = new Rect();
        final Rect mContentInsets = new Rect();
        
        final WindowManager.LayoutParams mLayout
                = new WindowManager.LayoutParams();
        IWindowSession mSession;

        final Object mLock = new Object();
        boolean mOffsetMessageEnqueued;
        float mPendingXOffset;
        float mPendingYOffset;
        MotionEvent mPendingMove;
        
        final BaseSurfaceHolder mSurfaceHolder = new BaseSurfaceHolder() {

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
                throw new UnsupportedOperationException(
                        "Wallpapers currently only support sizing from layout");
            }
            
            public void setKeepScreenOn(boolean screenOn) {
                throw new UnsupportedOperationException(
                        "Wallpapers do not support keep screen on");
            }
            
        };
        
        final BaseIWindow mWindow = new BaseIWindow() {
            @Override
            public boolean onDispatchPointer(MotionEvent event, long eventTime,
                    boolean callWhenDone) {
                synchronized (mLock) {
                    if (event.getAction() == MotionEvent.ACTION_MOVE) {
                        if (mPendingMove != null) {
                            mCaller.removeMessages(MSG_TOUCH_EVENT, mPendingMove);
                            mPendingMove.recycle();
                        }
                        mPendingMove = event;
                    } else {
                        mPendingMove = null;
                    }
                    Message msg = mCaller.obtainMessageO(MSG_TOUCH_EVENT,
                            event);
                    mCaller.sendMessage(msg);
                }
                return false;
            }
            
            @Override
            public void resized(int w, int h, Rect coveredInsets,
                    Rect visibleInsets, boolean reportDraw) {
                Message msg = mCaller.obtainMessageI(MSG_WINDOW_RESIZED,
                        reportDraw ? 1 : 0);
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
            public void dispatchWallpaperOffsets(float x, float y) {
                synchronized (mLock) {
                    mPendingXOffset = x;
                    mPendingYOffset = y;
                    if (!mOffsetMessageEnqueued) {
                        mOffsetMessageEnqueued = true;
                        Message msg = mCaller.obtainMessage(MSG_WALLPAPER_OFFSETS);
                        mCaller.sendMessage(msg);
                    }
                }
            }
            
        };
        
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
            return mVisible;
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
                updateSurface(false, false);
            }
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
         * Called as the user performs touch-screen interaction with the
         * window that is currently showing this wallpaper.  Note that the
         * events you receive here are driven by the actual application the
         * user is interacting with, so if it is slow you will get viewer
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
                int xPixelOffset, int yPixelOffset) {
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

        void updateSurface(boolean forceRelayout, boolean forceReport) {
            if (mDestroyed) {
                Log.w(TAG, "Ignoring updateSurface: destroyed");
            }
            
            int myWidth = mSurfaceHolder.getRequestedWidth();
            if (myWidth <= 0) myWidth = ViewGroup.LayoutParams.FILL_PARENT;
            int myHeight = mSurfaceHolder.getRequestedHeight();
            if (myHeight <= 0) myHeight = ViewGroup.LayoutParams.FILL_PARENT;
            
            final boolean creating = !mCreated;
            final boolean formatChanged = mFormat != mSurfaceHolder.getRequestedFormat();
            boolean sizeChanged = mWidth != myWidth || mHeight != myHeight;
            final boolean typeChanged = mType != mSurfaceHolder.getRequestedType();
            final boolean flagsChanged = mCurWindowFlags != mWindowFlags;
            if (forceRelayout || creating || formatChanged || sizeChanged
                    || typeChanged || flagsChanged) {

                if (DEBUG) Log.v(TAG, "Changes: creating=" + creating
                        + " format=" + formatChanged + " size=" + sizeChanged);

                try {
                    mWidth = myWidth;
                    mHeight = myHeight;
                    mFormat = mSurfaceHolder.getRequestedFormat();
                    mType = mSurfaceHolder.getRequestedType();

                    mLayout.x = 0;
                    mLayout.y = 0;
                    mLayout.width = myWidth;
                    mLayout.height = myHeight;
                    
                    mLayout.format = mFormat;
                    
                    mCurWindowFlags = mWindowFlags;
                    mLayout.flags = mWindowFlags
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            ;

                    mLayout.memoryType = mType;
                    mLayout.token = mWindowToken;

                    if (!mCreated) {
                        mLayout.type = mIWallpaperEngine.mWindowType;
                        mLayout.gravity = Gravity.LEFT|Gravity.TOP;
                        mLayout.windowAnimations =
                                com.android.internal.R.style.Animation_Wallpaper;
                        mSession.add(mWindow, mLayout, View.VISIBLE, mContentInsets);
                    }
                    
                    mSurfaceHolder.mSurfaceLock.lock();
                    mDrawingAllowed = true;

                    final int relayoutResult = mSession.relayout(
                        mWindow, mLayout, mWidth, mHeight,
                            View.VISIBLE, false, mWinFrame, mContentInsets,
                            mVisibleInsets, mSurfaceHolder.mSurface);

                    if (DEBUG) Log.v(TAG, "New surface: " + mSurfaceHolder.mSurface
                            + ", frame=" + mWinFrame);
                    
                    int w = mWinFrame.width();
                    if (mCurWidth != w) {
                        sizeChanged = true;
                        mCurWidth = w;
                    }
                    int h = mWinFrame.height();
                    if (mCurHeight != h) {
                        sizeChanged = true;
                        mCurHeight = h;
                    }
                    
                    mSurfaceHolder.mSurfaceLock.unlock();

                    try {
                        mDestroyReportNeeded = true;

                        SurfaceHolder.Callback callbacks[] = null;
                        synchronized (mSurfaceHolder.mCallbacks) {
                            final int N = mSurfaceHolder.mCallbacks.size();
                            if (N > 0) {
                                callbacks = new SurfaceHolder.Callback[N];
                                mSurfaceHolder.mCallbacks.toArray(callbacks);
                            }
                        }

                        if (!mCreated) {
                            mIsCreating = true;
                            if (DEBUG) Log.v(TAG, "onSurfaceCreated("
                                    + mSurfaceHolder + "): " + this);
                            onSurfaceCreated(mSurfaceHolder);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceCreated(mSurfaceHolder);
                                }
                            }
                        }
                        if (forceReport || creating || formatChanged || sizeChanged) {
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
                            onSurfaceChanged(mSurfaceHolder, mFormat,
                                    mCurWidth, mCurHeight);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceChanged(mSurfaceHolder, mFormat,
                                            mCurWidth, mCurHeight);
                                }
                            }
                        }
                    } finally {
                        mIsCreating = false;
                        mCreated = true;
                        if (creating || (relayoutResult&WindowManagerImpl.RELAYOUT_FIRST_TIME) != 0) {
                            mSession.finishDrawing(mWindow);
                        }
                    }
                } catch (RemoteException ex) {
                }
                if (DEBUG) Log.v(
                    TAG, "Layout: x=" + mLayout.x + " y=" + mLayout.y +
                    " w=" + mLayout.width + " h=" + mLayout.height);
            }
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
            mSession = ViewRoot.getWindowSession(getMainLooper());
            mWindow.setSession(mSession);
            
            if (DEBUG) Log.v(TAG, "onCreate(): " + this);
            onCreate(mSurfaceHolder);
            
            mInitializing = false;
            updateSurface(false, false);
        }
        
        void doDesiredSizeChanged(int desiredWidth, int desiredHeight) {
            if (!mDestroyed) {
                if (DEBUG) Log.v(TAG, "onDesiredSizeChanged("
                        + desiredWidth + "," + desiredHeight + "): " + this);
                onDesiredSizeChanged(desiredWidth, desiredHeight);
            }
        }
        
        void doVisibilityChanged(boolean visible) {
            if (!mDestroyed) {
                mVisible = visible;
                if (DEBUG) Log.v(TAG, "onVisibilityChanged(" + visible
                        + "): " + this);
                onVisibilityChanged(visible);
            }
        }
        
        void doOffsetsChanged() {
            if (mDestroyed) {
                return;
            }
            
            float xOffset;
            float yOffset;
            synchronized (mLock) {
                xOffset = mPendingXOffset;
                yOffset = mPendingYOffset;
                mOffsetMessageEnqueued = false;
            }
            if (DEBUG) Log.v(TAG, "Offsets change in " + this
                    + ": " + xOffset + "," + yOffset);
            final int availw = mIWallpaperEngine.mReqWidth-mCurWidth;
            final int xPixels = availw > 0 ? -(int)(availw*xOffset+.5f) : 0;
            final int availh = mIWallpaperEngine.mReqHeight-mCurHeight;
            final int yPixels = availh > 0 ? -(int)(availh*yOffset+.5f) : 0;
            onOffsetsChanged(xOffset, yOffset, xPixels, yPixels);
        }
        
        void detach() {
            mDestroyed = true;
            
            if (mVisible) {
                mVisible = false;
                if (DEBUG) Log.v(TAG, "onVisibilityChanged(false): " + this);
                onVisibilityChanged(false);
            }
            
            if (mDestroyReportNeeded) {
                mDestroyReportNeeded = false;
                SurfaceHolder.Callback callbacks[];
                synchronized (mSurfaceHolder.mCallbacks) {
                    callbacks = new SurfaceHolder.Callback[
                            mSurfaceHolder.mCallbacks.size()];
                    mSurfaceHolder.mCallbacks.toArray(callbacks);
                }
                for (SurfaceHolder.Callback c : callbacks) {
                    c.surfaceDestroyed(mSurfaceHolder);
                }
                if (DEBUG) Log.v(TAG, "onSurfaceDestroyed("
                        + mSurfaceHolder + "): " + this);
                onSurfaceDestroyed(mSurfaceHolder);
            }
            
            if (DEBUG) Log.v(TAG, "onDestroy(): " + this);
            onDestroy();
            
            if (mCreated) {
                try {
                    mSession.remove(mWindow);
                } catch (RemoteException e) {
                }
                mSurfaceHolder.mSurface.clear();
                mCreated = false;
            }
        }
    }
    
    class IWallpaperEngineWrapper extends IWallpaperEngine.Stub
            implements HandlerCaller.Callback {
        private final HandlerCaller mCaller;

        final IWallpaperConnection mConnection;
        final IBinder mWindowToken;
        final int mWindowType;
        final boolean mIsPreview;
        int mReqWidth;
        int mReqHeight;
        
        Engine mEngine;
        
        IWallpaperEngineWrapper(WallpaperService context,
                IWallpaperConnection conn, IBinder windowToken,
                int windowType, boolean isPreview, int reqWidth, int reqHeight) {
            mCaller = new HandlerCaller(context, this);
            mConnection = conn;
            mWindowToken = windowToken;
            mWindowType = windowType;
            mIsPreview = isPreview;
            mReqWidth = reqWidth;
            mReqHeight = reqHeight;
            
            Message msg = mCaller.obtainMessage(DO_ATTACH);
            mCaller.sendMessage(msg);
        }
        
        public void setDesiredSize(int width, int height) {
            Message msg = mCaller.obtainMessageII(DO_SET_DESIRED_SIZE, width, height);
            mCaller.sendMessage(msg);
        }
        
        public void setVisibility(boolean visible) {
            Message msg = mCaller.obtainMessageI(MSG_VISIBILITY_CHANGED,
                    visible ? 1 : 0);
            mCaller.sendMessage(msg);
        }

        public void destroy() {
            Message msg = mCaller.obtainMessage(DO_DETACH);
            mCaller.sendMessage(msg);
        }

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ATTACH: {
                    try {
                        mConnection.attachEngine(this);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Wallpaper host disappeared", e);
                        return;
                    }
                    Engine engine = onCreateEngine();
                    mEngine = engine;
                    engine.attach(this);
                    return;
                }
                case DO_DETACH: {
                    mEngine.detach();
                    return;
                }
                case DO_SET_DESIRED_SIZE: {
                    mEngine.doDesiredSizeChanged(message.arg1, message.arg2);
                    return;
                }
                case MSG_UPDATE_SURFACE:
                    mEngine.updateSurface(true, false);
                    break;
                case MSG_VISIBILITY_CHANGED:
                    if (DEBUG) Log.v(TAG, "Visibility change in " + mEngine
                            + ": " + message.arg1);
                    mEngine.doVisibilityChanged(message.arg1 != 0);
                    break;
                case MSG_WALLPAPER_OFFSETS: {
                    mEngine.doOffsetsChanged();
                } break;
                case MSG_WINDOW_RESIZED: {
                    final boolean reportDraw = message.arg1 != 0;
                    mEngine.updateSurface(true, false);
                    if (reportDraw) {
                        try {
                            mEngine.mSession.finishDrawing(mEngine.mWindow);
                        } catch (RemoteException e) {
                        }
                    }
                } break;
                case MSG_TOUCH_EVENT: {
                    MotionEvent ev = (MotionEvent)message.obj;
                    synchronized (mEngine.mLock) {
                        if (mEngine.mPendingMove == ev) {
                            mEngine.mPendingMove = null;
                        }
                    }
                    mEngine.onTouchEvent(ev);
                    ev.recycle();
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

        public IWallpaperServiceWrapper(WallpaperService context) {
            mTarget = context;
        }

        public void attach(IWallpaperConnection conn, IBinder windowToken,
                int windowType, boolean isPreview, int reqWidth, int reqHeight) {
            new IWallpaperEngineWrapper(mTarget, conn, windowToken,
                    windowType, isPreview, reqWidth, reqHeight);
        }
    }
    
    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IWallpaperServiceWrapper(this);
    }
    
    public abstract Engine onCreateEngine();
}
