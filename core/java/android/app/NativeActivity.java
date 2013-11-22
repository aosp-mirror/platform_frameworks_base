/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.AttributeSet;
import android.view.InputQueue;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import java.io.File;

/**
 * Convenience for implementing an activity that will be implemented
 * purely in native code.  That is, a game (or game-like thing).  There
 * is no need to derive from this class; you can simply declare it in your
 * manifest, and use the NDK APIs from there.
 *
 * <p>A typical manifest would look like:
 *
 * {@sample development/ndk/platforms/android-9/samples/native-activity/AndroidManifest.xml
 *      manifest}
 *
 * <p>A very simple example of native code that is run by NativeActivity
 * follows.  This reads input events from the user and uses OpenGLES to
 * draw into the native activity's window.
 *
 * {@sample development/ndk/platforms/android-9/samples/native-activity/jni/main.c all}
 */
public class NativeActivity extends Activity implements SurfaceHolder.Callback2,
        InputQueue.Callback, OnGlobalLayoutListener {
    /**
     * Optional meta-that can be in the manifest for this component, specifying
     * the name of the native shared library to load.  If not specified,
     * "main" is used.
     */
    public static final String META_DATA_LIB_NAME = "android.app.lib_name";
    
    /**
     * Optional meta-that can be in the manifest for this component, specifying
     * the name of the main entry point for this native activity in the
     * {@link #META_DATA_LIB_NAME} native code.  If not specified,
     * "ANativeActivity_onCreate" is used.
     */
    public static final String META_DATA_FUNC_NAME = "android.app.func_name";
    
    private static final String KEY_NATIVE_SAVED_STATE = "android:native_state";

    private NativeContentView mNativeContentView;
    private InputMethodManager mIMM;

    private int mNativeHandle;
    
    private InputQueue mCurInputQueue;
    private SurfaceHolder mCurSurfaceHolder;
    
    final int[] mLocation = new int[2];
    int mLastContentX;
    int mLastContentY;
    int mLastContentWidth;
    int mLastContentHeight;

    private boolean mDispatchingUnhandledKey;

    private boolean mDestroyed;
    
    private native int loadNativeCode(String path, String funcname, MessageQueue queue,
            String internalDataPath, String obbPath, String externalDataPath, int sdkVersion,
            AssetManager assetMgr, byte[] savedState);
    private native void unloadNativeCode(int handle);
    
    private native void onStartNative(int handle);
    private native void onResumeNative(int handle);
    private native byte[] onSaveInstanceStateNative(int handle);
    private native void onPauseNative(int handle);
    private native void onStopNative(int handle);
    private native void onConfigurationChangedNative(int handle);
    private native void onLowMemoryNative(int handle);
    private native void onWindowFocusChangedNative(int handle, boolean focused);
    private native void onSurfaceCreatedNative(int handle, Surface surface);
    private native void onSurfaceChangedNative(int handle, Surface surface,
            int format, int width, int height);
    private native void onSurfaceRedrawNeededNative(int handle, Surface surface);
    private native void onSurfaceDestroyedNative(int handle);
    private native void onInputQueueCreatedNative(int handle, int queuePtr);
    private native void onInputQueueDestroyedNative(int handle, int queuePtr);
    private native void onContentRectChangedNative(int handle, int x, int y, int w, int h);

    static class NativeContentView extends View {
        NativeActivity mActivity;

        public NativeContentView(Context context) {
            super(context);
        }

        public NativeContentView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String libname = "main";
        String funcname = "ANativeActivity_onCreate";
        ActivityInfo ai;
        
        mIMM = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);

        getWindow().takeSurface(this);
        getWindow().takeInputQueue(this);
        getWindow().setFormat(PixelFormat.RGB_565);
        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        mNativeContentView = new NativeContentView(this);
        mNativeContentView.mActivity = this;
        setContentView(mNativeContentView);
        mNativeContentView.requestFocus();
        mNativeContentView.getViewTreeObserver().addOnGlobalLayoutListener(this);
        
        try {
            ai = getPackageManager().getActivityInfo(
                    getIntent().getComponent(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) {
                String ln = ai.metaData.getString(META_DATA_LIB_NAME);
                if (ln != null) libname = ln;
                ln = ai.metaData.getString(META_DATA_FUNC_NAME);
                if (ln != null) funcname = ln;
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Error getting activity info", e);
        }
        
        String path = null;
        
        File libraryFile = new File(ai.applicationInfo.nativeLibraryDir,
                System.mapLibraryName(libname));
        if (libraryFile.exists()) {
            path = libraryFile.getPath();
        }
        
        if (path == null) {
            throw new IllegalArgumentException("Unable to find native library: " + libname);
        }
        
        byte[] nativeSavedState = savedInstanceState != null
                ? savedInstanceState.getByteArray(KEY_NATIVE_SAVED_STATE) : null;

        mNativeHandle = loadNativeCode(path, funcname, Looper.myQueue(),
                getAbsolutePath(getFilesDir()), getAbsolutePath(getObbDir()),
                getAbsolutePath(getExternalFilesDir(null)),
                Build.VERSION.SDK_INT, getAssets(), nativeSavedState);

        if (mNativeHandle == 0) {
            throw new IllegalArgumentException("Unable to load native library: " + path);
        }
        super.onCreate(savedInstanceState);
    }

    private static String getAbsolutePath(File file) {
        return (file != null) ? file.getAbsolutePath() : null;
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mCurSurfaceHolder != null) {
            onSurfaceDestroyedNative(mNativeHandle);
            mCurSurfaceHolder = null;
        }
        if (mCurInputQueue != null) {
            onInputQueueDestroyedNative(mNativeHandle, mCurInputQueue.getNativePtr());
            mCurInputQueue = null;
        }
        unloadNativeCode(mNativeHandle);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        onPauseNative(mNativeHandle);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onResumeNative(mNativeHandle);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        byte[] state = onSaveInstanceStateNative(mNativeHandle);
        if (state != null) {
            outState.putByteArray(KEY_NATIVE_SAVED_STATE, state);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        onStartNative(mNativeHandle);
    }

    @Override
    protected void onStop() {
        super.onStop();
        onStopNative(mNativeHandle);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!mDestroyed) {
            onConfigurationChangedNative(mNativeHandle);
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (!mDestroyed) {
            onLowMemoryNative(mNativeHandle);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!mDestroyed) {
            onWindowFocusChangedNative(mNativeHandle, hasFocus);
        }
    }
    
    public void surfaceCreated(SurfaceHolder holder) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceCreatedNative(mNativeHandle, holder.getSurface());
        }
    }
    
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceChangedNative(mNativeHandle, holder.getSurface(), format, width, height);
        }
    }
    
    public void surfaceRedrawNeeded(SurfaceHolder holder) {
        if (!mDestroyed) {
            mCurSurfaceHolder = holder;
            onSurfaceRedrawNeededNative(mNativeHandle, holder.getSurface());
        }
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        mCurSurfaceHolder = null;
        if (!mDestroyed) {
            onSurfaceDestroyedNative(mNativeHandle);
        }
    }
    
    public void onInputQueueCreated(InputQueue queue) {
        if (!mDestroyed) {
            mCurInputQueue = queue;
            onInputQueueCreatedNative(mNativeHandle, queue.getNativePtr());
        }
    }
    
    public void onInputQueueDestroyed(InputQueue queue) {
        if (!mDestroyed) {
            onInputQueueDestroyedNative(mNativeHandle, queue.getNativePtr());
            mCurInputQueue = null;
        }
    }
    
    public void onGlobalLayout() {
        mNativeContentView.getLocationInWindow(mLocation);
        int w = mNativeContentView.getWidth();
        int h = mNativeContentView.getHeight();
        if (mLocation[0] != mLastContentX || mLocation[1] != mLastContentY
                || w != mLastContentWidth || h != mLastContentHeight) {
            mLastContentX = mLocation[0];
            mLastContentY = mLocation[1];
            mLastContentWidth = w;
            mLastContentHeight = h;
            if (!mDestroyed) {
                onContentRectChangedNative(mNativeHandle, mLastContentX,
                        mLastContentY, mLastContentWidth, mLastContentHeight);
            }
        }
    }

    void setWindowFlags(int flags, int mask) {
        getWindow().setFlags(flags, mask);
    }
    
    void setWindowFormat(int format) {
        getWindow().setFormat(format);
    }

    void showIme(int mode) {
        mIMM.showSoftInput(mNativeContentView, mode);
    }

    void hideIme(int mode) {
        mIMM.hideSoftInputFromWindow(mNativeContentView.getWindowToken(), mode);
    }
}
