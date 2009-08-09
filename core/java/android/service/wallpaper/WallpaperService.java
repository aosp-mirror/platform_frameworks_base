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
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.IWindowSession;
import android.view.SurfaceHolder;
import android.view.View;
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
    static final boolean DEBUG = true;
    
    private static final int DO_ATTACH = 10;
    private static final int DO_DETACH = 20;
    
    private static final int MSG_UPDATE_SURFACE = 10000;
    
    /**
     * The actual implementation of a wallpaper.  A wallpaper service may
     * have multiple instances running (for example as a real wallpaper
     * and as a preview), each of which is represented by its own Engine
     * instance.
     */
    public class Engine {
        IWallpaperEngineWrapper mIWallpaperEngine;
        
        // Copies from mIWallpaperEngine.
        HandlerCaller mCaller;
        IWallpaperConnection mConnection;
        IBinder mWindowToken;
        
        boolean mInitializing = true;
        
        // Current window state.
        boolean mCreated;
        boolean mIsCreating;
        boolean mDrawingAllowed;
        int mWidth;
        int mHeight;
        int mFormat;
        int mType;
        boolean mDestroyReportNeeded;
        final Rect mVisibleInsets = new Rect();
        final Rect mWinFrame = new Rect();
        final Rect mContentInsets = new Rect();
        
        final WindowManager.LayoutParams mLayout
                = new WindowManager.LayoutParams();
        IWindowSession mSession;

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

            public void setKeepScreenOn(boolean screenOn) {
                // Ignore.
            }
            
        };
        
        final BaseIWindow mWindow = new BaseIWindow() {
            
        };
        
        public void onAttach(SurfaceHolder surfaceHolder) {
        }
        
        public void onDetach() {
        }
        
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        public void onSurfaceCreated(SurfaceHolder holder) {
        }

        public void onSurfaceDestroyed(SurfaceHolder holder) {
        }

        void updateSurface(boolean force) {
            int myWidth = mSurfaceHolder.getRequestedWidth();
            if (myWidth <= 0) myWidth = mIWallpaperEngine.mReqWidth;
            int myHeight = mSurfaceHolder.getRequestedHeight();
            if (myHeight <= 0) myHeight = mIWallpaperEngine.mReqHeight;
            
            final boolean creating = !mCreated;
            final boolean formatChanged = mFormat != mSurfaceHolder.getRequestedFormat();
            final boolean sizeChanged = mWidth != myWidth || mHeight != myHeight;
            final boolean typeChanged = mType != mSurfaceHolder.getRequestedType();
            if (force || creating || formatChanged || sizeChanged || typeChanged) {

                if (DEBUG) Log.i(TAG, "Changes: creating=" + creating
                        + " format=" + formatChanged + " size=" + sizeChanged);

                try {
                    mWidth = myWidth;
                    mHeight = myHeight;
                    mFormat = mSurfaceHolder.getRequestedFormat();
                    mType = mSurfaceHolder.getRequestedType();

                    // Scaling/Translate window's layout here because mLayout is not used elsewhere.
                    
                    // Places the window relative
                    mLayout.x = 0;
                    mLayout.y = 0;
                    mLayout.width = myWidth;
                    mLayout.height = myHeight;
                    
                    mLayout.format = mFormat;
                    mLayout.flags |=WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                  | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                  | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                  | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                  ;

                    mLayout.memoryType = mType;
                    mLayout.token = mWindowToken;

                    if (!mCreated) {
                        mLayout.type = WindowManager.LayoutParams.TYPE_WALLPAPER;
                        mLayout.gravity = Gravity.LEFT|Gravity.TOP;
                        mSession.add(mWindow, mLayout, View.VISIBLE, mContentInsets);
                    }
                    
                    mSurfaceHolder.mSurfaceLock.lock();
                    mDrawingAllowed = true;

                    final int relayoutResult = mSession.relayout(
                        mWindow, mLayout, mWidth, mHeight,
                            View.VISIBLE, false, mWinFrame, mContentInsets,
                            mVisibleInsets, mSurfaceHolder.mSurface);

                    if (DEBUG) Log.i(TAG, "New surface: " + mSurfaceHolder.mSurface
                            + ", frame=" + mWinFrame);
                    
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
                            onSurfaceCreated(mSurfaceHolder);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceCreated(mSurfaceHolder);
                                }
                            }
                        }
                        if (creating || formatChanged || sizeChanged) {
                            onSurfaceChanged(mSurfaceHolder, mFormat, mWidth, mHeight);
                            if (callbacks != null) {
                                for (SurfaceHolder.Callback c : callbacks) {
                                    c.surfaceChanged(mSurfaceHolder, mFormat, mWidth, mHeight);
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
            mIWallpaperEngine = wrapper;
            mCaller = wrapper.mCaller;
            mConnection = wrapper.mConnection;
            mWindowToken = wrapper.mWindowToken;
            mSurfaceHolder.setSizeFromLayout();
            mInitializing = true;
            mSession = ViewRoot.getWindowSession(getMainLooper());
            mWindow.setSession(mSession);
            
            onAttach(mSurfaceHolder);
            
            mInitializing = false;
            updateSurface(false);
        }
        
        void detach() {
            onDetach();
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
            }
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
        int mReqWidth;
        int mReqHeight;
        
        Engine mEngine;
        
        IWallpaperEngineWrapper(WallpaperService context,
                IWallpaperConnection conn, IBinder windowToken,
                int reqWidth, int reqHeight) {
            mCaller = new HandlerCaller(context, this);
            mConnection = conn;
            mWindowToken = windowToken;
            mReqWidth = reqWidth;
            mReqHeight = reqHeight;
            
            try {
                conn.attachEngine(this);
            } catch (RemoteException e) {
                destroy();
            }
            
            Message msg = mCaller.obtainMessage(DO_ATTACH);
            mCaller.sendMessage(msg);
        }
        
        public void destroy() {
            Message msg = mCaller.obtainMessage(DO_DETACH);
            mCaller.sendMessage(msg);
        }

        public void executeMessage(Message message) {
            switch (message.what) {
                case DO_ATTACH: {
                    Engine engine = onCreateEngine();
                    mEngine = engine;
                    engine.attach(this);
                    return;
                }
                case DO_DETACH: {
                    mEngine.detach();
                    return;
                }
                case MSG_UPDATE_SURFACE:
                    mEngine.updateSurface(false);
                    break;
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

        public void attach(IWallpaperConnection conn,
                IBinder windowToken, int reqWidth, int reqHeight) {
            new IWallpaperEngineWrapper(
                    mTarget, conn, windowToken, reqWidth, reqHeight);
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
