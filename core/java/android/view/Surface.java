/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.res.CompatibilityInfo.Translator;
import android.graphics.*;
import android.os.Parcelable;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;

/**
 * Handle on to a raw buffer that is being managed by the screen compositor.
 */
public class Surface implements Parcelable {
    private static final String LOG_TAG = "Surface";
    private static final boolean DEBUG_RELEASE = false;
    
    /* flags used in constructor (keep in sync with ISurfaceComposer.h) */

    /** Surface is created hidden */
    public static final int HIDDEN              = 0x00000004;

    /** The surface is to be used by hardware accelerators or DMA engines 
     * @deprecated this is ignored, this value is set automatically when needed.
     */
    @Deprecated
    public static final int HARDWARE            = 0x00000010;

    /** Implies "HARDWARE", the surface is to be used by the GPU
     * additionally the backbuffer is never preserved for these
     * surfaces. 
     * @deprecated this is ignored, this value is set automatically when needed.
     */
    @Deprecated
    public static final int GPU                 = 0x00000028;

    /** The surface contains secure content, special measures will
     * be taken to disallow the surface's content to be copied from
     * another process. In particular, screenshots and VNC servers will
     * be disabled, but other measures can take place, for instance the
     * surface might not be hardware accelerated. */
    public static final int SECURE              = 0x00000080;
    
    /** Creates a surface where color components are interpreted as 
     *  "non pre-multiplied" by their alpha channel. Of course this flag is
     *  meaningless for surfaces without an alpha channel. By default
     *  surfaces are pre-multiplied, which means that each color component is
     *  already multiplied by its alpha value. In this case the blending
     *  equation used is:
     *  
     *    DEST = SRC + DEST * (1-SRC_ALPHA)
     *    
     *  By contrast, non pre-multiplied surfaces use the following equation:
     *  
     *    DEST = SRC * SRC_ALPHA * DEST * (1-SRC_ALPHA)
     *    
     *  pre-multiplied surfaces must always be used if transparent pixels are
     *  composited on top of each-other into the surface. A pre-multiplied
     *  surface can never lower the value of the alpha component of a given
     *  pixel.
     *  
     *  In some rare situations, a non pre-multiplied surface is preferable.
     *  
     */
    public static final int NON_PREMULTIPLIED   = 0x00000100;
    
    /**
     * Creates a surface without a rendering buffer. Instead, the content
     * of the surface must be pushed by an external entity. This is type
     * of surface can be used for efficient camera preview or movie
     * play back.
     */
    public static final int PUSH_BUFFERS        = 0x00000200;
    
    /** Creates a normal surface. This is the default */
    public static final int FX_SURFACE_NORMAL   = 0x00000000;
    
    /** Creates a Blur surface. Everything behind this surface is blurred
     * by some amount. The quality and refresh speed of the blur effect
     * is not settable or guaranteed.
     * It is an error to lock a Blur surface, since it doesn't have
     * a backing store.
     */
    public static final int FX_SURFACE_BLUR     = 0x00010000;
    
    /** Creates a Dim surface. Everything behind this surface is dimmed
     * by the amount specified in setAlpha(). 
     * It is an error to lock a Dim surface, since it doesn't have
     * a backing store.
     */
    public static final int FX_SURFACE_DIM     = 0x00020000;

    /** Mask used for FX values above */
    public static final int FX_SURFACE_MASK     = 0x000F0000;

    /* flags used with setFlags() (keep in sync with ISurfaceComposer.h) */
    
    /** Hide the surface. Equivalent to calling hide() */
    public static final int SURFACE_HIDDEN    = 0x01;
    
    /** Freeze the surface. Equivalent to calling freeze() */ 
    public static final int SURFACE_FROZEN     = 0x02;

    /**
     * @deprecated use {@link #SURFACE_FROZEN} instead.
     */
    @Deprecated
    public static final int SURACE_FROZEN     = 0x02;

    /** Enable dithering when compositing this surface */
    public static final int SURFACE_DITHER    = 0x04;

    public static final int SURFACE_BLUR_FREEZE= 0x10;

    /* orientations for setOrientation() */
    public static final int ROTATION_0       = 0;
    public static final int ROTATION_90      = 1;
    public static final int ROTATION_180     = 2;
    public static final int ROTATION_270     = 3;
    
    /** 
     * Disable the orientation animation 
     * {@hide} 
     */
    public static final int FLAGS_ORIENTATION_ANIMATION_DISABLE = 0x000000001;

    @SuppressWarnings("unused")
    private int mSurface;
    @SuppressWarnings("unused")
    private int mSurfaceControl;
    @SuppressWarnings("unused")
    private int mSaveCount;
    @SuppressWarnings("unused")
    private Canvas mCanvas;
    private String mName;

    // The display metrics used to provide the pseudo canvas size for applications
    // running in compatibility mode. This is set to null for non compatibility mode.
    private DisplayMetrics mCompatibleDisplayMetrics;

    // A matrix to scale the matrix set by application. This is set to null for
    // non compatibility mode.
    private Matrix mCompatibleMatrix;

    @SuppressWarnings("unused")
    private Exception mCreationStack;

    /**
     * Exception thrown when a surface couldn't be created or resized
     */
    public static class OutOfResourcesException extends Exception {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    native private static void nativeClassInit();
    static { nativeClassInit(); }

    
    /**
     * create a surface
     * {@hide}
     */
    public Surface(SurfaceSession s,
            int pid, int display, int w, int h, int format, int flags)
        throws OutOfResourcesException {
        if (DEBUG_RELEASE) {
            mCreationStack = new Exception();
        }
        mCanvas = new CompatibleCanvas();
        init(s,pid,null,display,w,h,format,flags);
    }

    /**
     * create a surface with a name
     * {@hide}
     */
    public Surface(SurfaceSession s,
            int pid, String name, int display, int w, int h, int format, int flags)
        throws OutOfResourcesException {
        if (DEBUG_RELEASE) {
            mCreationStack = new Exception();
        }
        mCanvas = new CompatibleCanvas();
        init(s,pid,name,display,w,h,format,flags);
        mName = name;
    }

    /**
     * Create an empty surface, which will later be filled in by
     * readFromParcel().
     * {@hide}
     */
    public Surface() {
        if (DEBUG_RELEASE) {
            mCreationStack = new Exception();
        }
        mCanvas = new CompatibleCanvas();
    }

    /**
     * A Canvas class that can handle the compatibility mode. This does two things differently.
     * <ul>
     *  <li> Returns the width and height of the target metrics, rather than native.
     *  For example, the canvas returns 320x480 even if an app is running in WVGA high density.
     *  <li> Scales the matrix in setMatrix by the application scale, except if the matrix looks
     *  like obtained from getMatrix. This is a hack to handle the case that an application
     *  uses getMatrix to keep the original matrix, set matrix of its own, then set the original
     *  matrix back. There is no perfect solution that works for all cases, and there are a lot of
     *  cases that this model dose not work, but we hope this works for many apps.
     * </ul>
     */
    private class CompatibleCanvas extends Canvas {
        // A temp matrix to remember what an application obtained via {@link getMatrix}
        private Matrix mOrigMatrix = null;

        @Override
        public int getWidth() {
            return mCompatibleDisplayMetrics == null ?
                    super.getWidth() : mCompatibleDisplayMetrics.widthPixels;
        }

        @Override
        public int getHeight() {
            return mCompatibleDisplayMetrics == null ?
                    super.getHeight() : mCompatibleDisplayMetrics.heightPixels;
        }

        @Override
        public void setMatrix(Matrix matrix) {
            if (mCompatibleMatrix == null || mOrigMatrix == null || mOrigMatrix.equals(matrix)) {
                // don't scale the matrix if it's not compatibility mode, or
                // the matrix was obtained from getMatrix.
                super.setMatrix(matrix);
            } else {
                Matrix m = new Matrix(mCompatibleMatrix);
                m.preConcat(matrix);
                super.setMatrix(m);
            }
        }

        @Override
        public void getMatrix(Matrix m) {
            super.getMatrix(m);
            if (mOrigMatrix == null) {
                mOrigMatrix = new Matrix(); 
            }
            mOrigMatrix.set(m);
        }
    };

    /**
     * Sets the display metrics used to provide canva's width/height in compatibility mode.
     */
    void setCompatibleDisplayMetrics(DisplayMetrics metrics, Translator translator) {
        mCompatibleDisplayMetrics = metrics;
        if (translator != null) {
            float appScale = translator.applicationScale;
            mCompatibleMatrix = new Matrix();
            mCompatibleMatrix.setScale(appScale, appScale);
        }
    }
    
    /**
     * Copy another surface to this one.  This surface now holds a reference
     * to the same data as the original surface, and is -not- the owner.
     * {@hide}
     */
    public native   void copyFrom(Surface o);
    
    /**
     * Does this object hold a valid surface?  Returns true if it holds
     * a physical surface, so lockCanvas() will succeed.  Otherwise
     * returns false.
     */
    public native   boolean isValid();
    
    /** Free all server-side state associated with this surface and
     * release this object's reference. {@hide} */
    public native void destroy();
    
    /** Release the local reference to the server-side surface. @hide */
    public native void release();
    
    /** draw into a surface */
    public Canvas lockCanvas(Rect dirty) throws OutOfResourcesException, IllegalArgumentException
    {
        /* the dirty rectangle may be expanded to the surface's size, if
         * for instance it has been resized or if the bits were lost, since
         * the last call.
         */
        return lockCanvasNative(dirty);
    }

    private native Canvas lockCanvasNative(Rect dirty);

    /** unlock the surface and asks a page flip */
    public native   void unlockCanvasAndPost(Canvas canvas);

    /** 
     * unlock the surface. the screen won't be updated until
     * post() or postAll() is called
     */
    public native   void unlockCanvas(Canvas canvas);
    
    /** start/end a transaction {@hide} */
    public static native   void openTransaction();
    /** {@hide} */
    public static native   void closeTransaction();

    /**
     * Freezes the specified display, No updating of the screen will occur
     * until unfreezeDisplay() is called. Everything else works as usual though,
     * in particular transactions.
     * @param display
     * {@hide}
     */
    public static native   void freezeDisplay(int display);

    /**
     * resume updating the specified display.
     * @param display
     * {@hide}
     */
    public static native   void unfreezeDisplay(int display);

    /**
     * set the orientation of the given display.
     * @param display
     * @param orientation
     * @param flags
     * {@hide}
     */
    public static native   void setOrientation(int display, int orientation, int flags);

    /**
     * set the orientation of the given display.
     * @param display
     * @param orientation
     */
    public static void setOrientation(int display, int orientation) {
        setOrientation(display, orientation, 0);
    }
    
    /**
     * set surface parameters.
     * needs to be inside open/closeTransaction block
     */
    public native   void setLayer(int zorder);
    public native   void setPosition(int x, int y);
    public native   void setSize(int w, int h);

    public native   void hide();
    public native   void show();
    public native   void setTransparentRegionHint(Region region);
    public native   void setAlpha(float alpha);
    public native   void setMatrix(float dsdx, float dtdx,
                                   float dsdy, float dtdy);

    public native   void freeze();
    public native   void unfreeze();

    public native   void setFreezeTint(int tint);

    public native   void setFlags(int flags, int mask);

    @Override
    public String toString() {
        return "Surface(name=" + mName + ", identity=" + getIdentity() + ")";
    }

    private Surface(Parcel source) throws OutOfResourcesException {
        init(source);
    }
    
    public int describeContents() {
        return 0;
    }

    public native   void readFromParcel(Parcel source);
    public native   void writeToParcel(Parcel dest, int flags);
    
    public static final Parcelable.Creator<Surface> CREATOR
            = new Parcelable.Creator<Surface>()
    {
        public Surface createFromParcel(Parcel source) {
            try {
                return new Surface(source);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception creating surface from parcel", e);
            }
            return null;
        }

        public Surface[] newArray(int size) {
            return new Surface[size];
        }
    };

    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        if (mSurface != 0 || mSurfaceControl != 0) {
            if (DEBUG_RELEASE) {
                Log.w(LOG_TAG, "Surface.finalize() has work. You should have called release() (" 
                        + mSurface + ", " + mSurfaceControl + ")", mCreationStack);
            } else {
                Log.w(LOG_TAG, "Surface.finalize() has work. You should have called release() (" 
                        + mSurface + ", " + mSurfaceControl + ")");
            }
        }
        release();
    }
    
    private native void init(SurfaceSession s,
            int pid, String name, int display, int w, int h, int format, int flags)
            throws OutOfResourcesException;

    private native void init(Parcel source);

    private native int getIdentity();
}
