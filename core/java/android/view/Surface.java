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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import dalvik.system.CloseGuard;

/**
 * Handle onto a raw buffer that is being managed by the screen compositor.
 */
public class Surface implements Parcelable {
    private static final String TAG = "Surface";

    private static native int nativeCreateFromSurfaceTexture(SurfaceTexture surfaceTexture)
            throws OutOfResourcesException;

    private native Canvas nativeLockCanvas(int nativeObject, Rect dirty);
    private native void nativeUnlockCanvasAndPost(int nativeObject, Canvas canvas);

    private static native void nativeRelease(int nativeObject);
    private static native boolean nativeIsValid(int nativeObject);
    private static native boolean nativeIsConsumerRunningBehind(int nativeObject);
    private static native int nativeCopyFrom(int nativeObject, int surfaceControlNativeObject);
    private static native int nativeReadFromParcel(int nativeObject, Parcel source);
    private static native void nativeWriteToParcel(int nativeObject, Parcel dest);

    public static final Parcelable.Creator<Surface> CREATOR =
            new Parcelable.Creator<Surface>() {
        public Surface createFromParcel(Parcel source) {
            try {
                Surface s = new Surface();
                s.readFromParcel(source);
                return s;
            } catch (Exception e) {
                Log.e(TAG, "Exception creating surface from parcel", e);
                return null;
            }
        }
        public Surface[] newArray(int size) {
            return new Surface[size];
        }
    };

    private final CloseGuard mCloseGuard = CloseGuard.get();
    private String mName;

    // Note: These fields are accessed by native code.
    // The mSurfaceControl will only be present for Surfaces used by the window
    // server or system processes. When this class is parceled we defer to the
    // mSurfaceControl to do the parceling. Otherwise we parcel the
    // mNativeSurface.
    int mNativeObject; // package scope only for SurfaceControl access

    // protects the native state
    private final Object mNativeObjectLock = new Object();

    private int mGenerationId; // incremented each time mNativeSurface changes
    @SuppressWarnings("UnusedDeclaration")
    private final Canvas mCanvas = new CompatibleCanvas();

    // A matrix to scale the matrix set by application. This is set to null for
    // non compatibility mode.
    private Matrix mCompatibleMatrix;

    /**
     * Rotation constant: 0 degree rotation (natural orientation)
     */
    public static final int ROTATION_0 = 0;

    /**
     * Rotation constant: 90 degree rotation.
     */
    public static final int ROTATION_90 = 1;

    /**
     * Rotation constant: 180 degree rotation.
     */
    public static final int ROTATION_180 = 2;

    /**
     * Rotation constant: 270 degree rotation.
     */
    public static final int ROTATION_270 = 3;

    /**
     * Create an empty surface, which will later be filled in by readFromParcel().
     * @hide
     */
    public Surface() {
    }

    /**
     * Create Surface from a {@link SurfaceTexture}.
     *
     * Images drawn to the Surface will be made available to the {@link
     * SurfaceTexture}, which can attach them to an OpenGL ES texture via {@link
     * SurfaceTexture#updateTexImage}.
     *
     * @param surfaceTexture The {@link SurfaceTexture} that is updated by this
     * Surface.
     */
    public Surface(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null) {
            throw new IllegalArgumentException("surfaceTexture must not be null");
        }

        mName = surfaceTexture.toString();
        try {
            mNativeObject = nativeCreateFromSurfaceTexture(surfaceTexture);
        } catch (OutOfResourcesException ex) {
            // We can't throw OutOfResourcesException because it would be an API change.
            throw new RuntimeException(ex);
        }

        mCloseGuard.open("release");
    }

    /* called from android_view_Surface_createFromIGraphicBufferProducer() */
    private Surface(int nativeObject) {
        mNativeObject = nativeObject;
        mCloseGuard.open("release");
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            release();
        } finally {
            super.finalize();
        }
    }

    /**
     * Release the local reference to the server-side surface.
     * Always call release() when you're done with a Surface.
     * This will make the surface invalid.
     */
    public void release() {
        synchronized (mNativeObjectLock) {
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
                mNativeObject = 0;
                mGenerationId++;
            }
            mCloseGuard.close();
        }
    }

    /**
     * Free all server-side state associated with this surface and
     * release this object's reference.  This method can only be
     * called from the process that created the service.
     * @hide
     */
    public void destroy() {
        release();
    }

    /**
     * Returns true if this object holds a valid surface.
     *
     * @return True if it holds a physical surface, so lockCanvas() will succeed.
     * Otherwise returns false.
     */
    public boolean isValid() {
        synchronized (mNativeObjectLock) {
            if (mNativeObject == 0) return false;
            return nativeIsValid(mNativeObject);
        }
    }

    /**
     * Gets the generation number of this surface, incremented each time
     * the native surface contained within this object changes.
     *
     * @return The current generation number.
     * @hide
     */
    public int getGenerationId() {
        return mGenerationId;
    }

    /**
     * Returns true if the consumer of this Surface is running behind the producer.
     *
     * @return True if the consumer is more than one buffer ahead of the producer.
     * @hide
     */
    public boolean isConsumerRunningBehind() {
        synchronized (mNativeObjectLock) {
            checkNotReleasedLocked();
            return nativeIsConsumerRunningBehind(mNativeObject);
        }
    }

    /**
     * Gets a {@link Canvas} for drawing into this surface.
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
    public Canvas lockCanvas(Rect inOutDirty)
            throws OutOfResourcesException, IllegalArgumentException {
        synchronized (mNativeObjectLock) {
            checkNotReleasedLocked();
            return nativeLockCanvas(mNativeObject, inOutDirty);
        }
    }

    /**
     * Posts the new contents of the {@link Canvas} to the surface and
     * releases the {@link Canvas}.
     *
     * @param canvas The canvas previously obtained from {@link #lockCanvas}.
     */
    public void unlockCanvasAndPost(Canvas canvas) {
        synchronized (mNativeObjectLock) {
            checkNotReleasedLocked();
            nativeUnlockCanvasAndPost(mNativeObject, canvas);
        }
    }

    /** 
     * @deprecated This API has been removed and is not supported.  Do not use.
     */
    @Deprecated
    public void unlockCanvas(Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets the translator used to scale canvas's width/height in compatibility
     * mode.
     */
    void setCompatibilityTranslator(Translator translator) {
        if (translator != null) {
            float appScale = translator.applicationScale;
            mCompatibleMatrix = new Matrix();
            mCompatibleMatrix.setScale(appScale, appScale);
        }
    }


    /**
     * Copy another surface to this one.  This surface now holds a reference
     * to the same data as the original surface, and is -not- the owner.
     * This is for use by the window manager when returning a window surface
     * back from a client, converting it from the representation being managed
     * by the window manager to the representation the client uses to draw
     * in to it.
     * @hide
     */
    public void copyFrom(SurfaceControl other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        if (other.mNativeObject == 0) {
            throw new NullPointerException(
                    "SurfaceControl native object is null. Are you using a released SurfaceControl?");
        }
        synchronized (mNativeObjectLock) {
            mNativeObject = nativeCopyFrom(mNativeObject, other.mNativeObject);
            if (mNativeObject == 0) {
                // nativeCopyFrom released our reference
                mCloseGuard.close();
            }
            mGenerationId++;
        }
    }

    /**
     * This is intended to be used by {@link SurfaceView.updateWindow} only.
     * @param other access is not thread safe
     * @hide
     * @deprecated
     */
    @Deprecated
    public void transferFrom(Surface other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        if (other != this) {
            synchronized (mNativeObjectLock) {
                if (mNativeObject != 0) {
                    // release our reference to our native object
                    nativeRelease(mNativeObject);
                }
                // transfer the reference from other to us
                if (other.mNativeObject != 0 && mNativeObject == 0) {
                    mCloseGuard.open("release");
                }
                mNativeObject = other.mNativeObject;
                mGenerationId++;
            }
            other.mNativeObject = 0;
            other.mGenerationId++;
            other.mCloseGuard.close();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void readFromParcel(Parcel source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        synchronized (mNativeObjectLock) {
            mName = source.readString();
            int nativeObject = nativeReadFromParcel(mNativeObject, source);
            if (nativeObject !=0 && mNativeObject == 0) {
                mCloseGuard.open("release");
            }
            mNativeObject = nativeObject;
            mGenerationId++;
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (dest == null) {
            throw new IllegalArgumentException("dest must not be null");
        }
        synchronized (mNativeObjectLock) {
            dest.writeString(mName);
            nativeWriteToParcel(mNativeObject, dest);
        }
        if ((flags & Parcelable.PARCELABLE_WRITE_RETURN_VALUE) != 0) {
            release();
        }
    }

    @Override
    public String toString() {
        return "Surface(name=" + mName + ")";
    }

    /**
     * Exception thrown when a surface couldn't be created or resized.
     */
    public static class OutOfResourcesException extends Exception {
        public OutOfResourcesException() {
        }
        public OutOfResourcesException(String name) {
            super(name);
        }
    }

    /**
     * Returns a human readable representation of a rotation.
     *
     * @param rotation The rotation.
     * @return The rotation symbolic name.
     *
     * @hide
     */
    public static String rotationToString(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0: {
                return "ROTATION_0";
            }
            case Surface.ROTATION_90: {
                return "ROATATION_90";
            }
            case Surface.ROTATION_180: {
                return "ROATATION_180";
            }
            case Surface.ROTATION_270: {
                return "ROATATION_270";
            }
            default: {
                throw new IllegalArgumentException("Invalid rotation: " + rotation);
            }
        }
    }

    /**
     * A Canvas class that can handle the compatibility mode.
     * This does two things differently.
     * <ul>
     * <li>Returns the width and height of the target metrics, rather than
     * native. For example, the canvas returns 320x480 even if an app is running
     * in WVGA high density.
     * <li>Scales the matrix in setMatrix by the application scale, except if
     * the matrix looks like obtained from getMatrix. This is a hack to handle
     * the case that an application uses getMatrix to keep the original matrix,
     * set matrix of its own, then set the original matrix back. There is no
     * perfect solution that works for all cases, and there are a lot of cases
     * that this model does not work, but we hope this works for many apps.
     * </ul>
     */
    private final class CompatibleCanvas extends Canvas {
        // A temp matrix to remember what an application obtained via {@link getMatrix}
        private Matrix mOrigMatrix = null;

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

        @SuppressWarnings("deprecation")
        @Override
        public void getMatrix(Matrix m) {
            super.getMatrix(m);
            if (mOrigMatrix == null) {
                mOrigMatrix = new Matrix(); 
            }
            mOrigMatrix.set(m);
        }
    }

    private void checkNotReleasedLocked() {
        if (mNativeObject == 0) throw new NullPointerException(
                "mNativeObject is null. Have you called release() already?");
    }
}
