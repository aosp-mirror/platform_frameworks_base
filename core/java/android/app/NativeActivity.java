package android.app;

import dalvik.system.PathClassLoader;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.AttributeSet;
import android.view.InputChannel;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.inputmethod.InputMethodManager;

import java.io.File;

/**
 * Convenience for implementing an activity that will be implemented
 * purely in native code.  That is, a game (or game-like thing).
 */
public class NativeActivity extends Activity implements SurfaceHolder.Callback2,
        InputQueue.Callback, OnGlobalLayoutListener {
    public static final String META_DATA_LIB_NAME = "android.app.lib_name";
    
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
    
    private native int loadNativeCode(String path, MessageQueue queue,
            String internalDataPath, String externalDataPath, int sdkVersion,
            AssetManager assetMgr);
    private native void unloadNativeCode(int handle);
    
    private native void onStartNative(int handle);
    private native void onResumeNative(int handle);
    private native void onSaveInstanceStateNative(int handle);
    private native void onPauseNative(int handle);
    private native void onStopNative(int handle);
    private native void onLowMemoryNative(int handle);
    private native void onWindowFocusChangedNative(int handle, boolean focused);
    private native void onSurfaceCreatedNative(int handle, Surface surface);
    private native void onSurfaceChangedNative(int handle, Surface surface,
            int format, int width, int height);
    private native void onSurfaceRedrawNeededNative(int handle, Surface surface);
    private native void onSurfaceDestroyedNative(int handle);
    private native void onInputChannelCreatedNative(int handle, InputChannel channel);
    private native void onInputChannelDestroyedNative(int handle, InputChannel channel);
    private native void onContentRectChangedNative(int handle, int x, int y, int w, int h);
    private native void dispatchKeyEventNative(int handle, KeyEvent event);

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
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Error getting activity info", e);
        }
        
        String path = null;
        
        if ((ai.applicationInfo.flags&ApplicationInfo.FLAG_HAS_CODE) == 0) {
            // If the application does not have (Java) code, then no ClassLoader
            // has been set up for it.  We will need to do our own search for
            // the native code.
            path = ai.applicationInfo.dataDir + "/lib/" + System.mapLibraryName(libname);
            if (!(new File(path)).exists()) {
                path = null;
            }
        }
        
        if (path == null) {
            path = ((PathClassLoader)getClassLoader()).findLibrary(libname);
        }
        
        if (path == null) {
            throw new IllegalArgumentException("Unable to find native library: " + libname);
        }
        
        mNativeHandle = loadNativeCode(path, Looper.myQueue(),
                 getFilesDir().toString(),
                 Environment.getExternalStorageAppFilesDirectory(ai.packageName).toString(),
                 Build.VERSION.SDK_INT, getAssets());
        
        if (mNativeHandle == 0) {
            throw new IllegalArgumentException("Unable to load native library: " + path);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mCurSurfaceHolder != null) {
            onSurfaceDestroyedNative(mNativeHandle);
            mCurSurfaceHolder = null;
        }
        if (mCurInputQueue != null) {
            onInputChannelDestroyedNative(mNativeHandle, mCurInputQueue.getInputChannel());
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
        onSaveInstanceStateNative(mNativeHandle);
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
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mDispatchingUnhandledKey) {
            return super.dispatchKeyEvent(event);
        } else {
            // Key events from the IME do not go through the input channel;
            // we need to intercept them here to hand to the application.
            dispatchKeyEventNative(mNativeHandle, event);
            return true;
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
            onInputChannelCreatedNative(mNativeHandle, queue.getInputChannel());
        }
    }
    
    public void onInputQueueDestroyed(InputQueue queue) {
        mCurInputQueue = null;
        if (!mDestroyed) {
            onInputChannelDestroyedNative(mNativeHandle, queue.getInputChannel());
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

    void dispatchUnhandledKeyEvent(KeyEvent event) {
        try {
            mDispatchingUnhandledKey = true;
            View decor = getWindow().getDecorView();
            if (decor != null) {
                decor.dispatchKeyEvent(event);
            }
        } finally {
            mDispatchingUnhandledKey = false;
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
