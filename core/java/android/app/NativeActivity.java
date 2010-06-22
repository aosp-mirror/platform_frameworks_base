package android.app;

import dalvik.system.PathClassLoader;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.InputChannel;
import android.view.InputConsumer;
import android.view.SurfaceHolder;

import java.io.File;

/**
 * Convenience for implementing an activity that will be implemented
 * purely in native code.  That is, a game (or game-like thing).
 */
public class NativeActivity extends Activity implements SurfaceHolder.Callback,
        InputConsumer.Callback {
    public static final String META_DATA_LIB_NAME = "android.app.lib_name";
    
    private int mNativeHandle;
    
    private native int loadNativeCode(String path);
    private native void unloadNativeCode(int handle);
    
    private native void onStartNative(int handle);
    private native void onResumeNative(int handle);
    private native void onSaveInstanceStateNative(int handle);
    private native void onPauseNative(int handle);
    private native void onStopNative(int handle);
    private native void onLowMemoryNative(int handle);
    private native void onWindowFocusChangedNative(int handle, boolean focused);
    private native void onSurfaceCreatedNative(int handle, SurfaceHolder holder);
    private native void onSurfaceChangedNative(int handle, SurfaceHolder holder,
            int format, int width, int height);
    private native void onSurfaceDestroyedNative(int handle, SurfaceHolder holder);
    private native void onInputChannelCreatedNative(int handle, InputChannel channel);
    private native void onInputChannelDestroyedNative(int handle, InputChannel channel);
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        String libname = "main";
        ActivityInfo ai;
        
        getWindow().takeSurface(this);
        getWindow().takeInputChannel(this);
        
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
        
        mNativeHandle = loadNativeCode(path);
        if (mNativeHandle == 0) {
            throw new IllegalArgumentException("Unable to load native library: " + path);
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onDestroy() {
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
        onLowMemoryNative(mNativeHandle);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        onWindowFocusChangedNative(mNativeHandle, hasFocus);
    }
    
    public void surfaceCreated(SurfaceHolder holder) {
        onSurfaceCreatedNative(mNativeHandle, holder);
    }
    
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        onSurfaceChangedNative(mNativeHandle, holder, format, width, height);
    }
    
    public void surfaceDestroyed(SurfaceHolder holder) {
        onSurfaceDestroyedNative(mNativeHandle, holder);
    }
    
    public void onInputConsumerCreated(InputConsumer consumer) {
        onInputChannelCreatedNative(mNativeHandle, consumer.getInputChannel());
    }
    
    public void onInputConsumerDestroyed(InputConsumer consumer) {
        onInputChannelDestroyedNative(mNativeHandle, consumer.getInputChannel());
    }
}
