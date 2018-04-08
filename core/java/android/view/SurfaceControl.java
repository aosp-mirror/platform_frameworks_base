/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.graphics.Matrix.MSCALE_X;
import static android.graphics.Matrix.MSCALE_Y;
import static android.graphics.Matrix.MSKEW_X;
import static android.graphics.Matrix.MSKEW_Y;
import static android.graphics.Matrix.MTRANS_X;
import static android.graphics.Matrix.MTRANS_Y;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.SurfaceControlProto.HASH_CODE;
import static android.view.SurfaceControlProto.NAME;

import android.annotation.Size;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.Surface.OutOfResourcesException;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

import libcore.util.NativeAllocationRegistry;

import java.io.Closeable;

/**
 * SurfaceControl
 *  @hide
 */
public class SurfaceControl implements Parcelable {
    private static final String TAG = "SurfaceControl";

    private static native long nativeCreate(SurfaceSession session, String name,
            int w, int h, int format, int flags, long parentObject, int windowType, int ownerUid)
            throws OutOfResourcesException;
    private static native long nativeReadFromParcel(Parcel in);
    private static native void nativeWriteToParcel(long nativeObject, Parcel out);
    private static native void nativeRelease(long nativeObject);
    private static native void nativeDestroy(long nativeObject);
    private static native void nativeDisconnect(long nativeObject);

    private static native Bitmap nativeScreenshot(IBinder displayToken,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform, int rotation);
    private static native GraphicBuffer nativeScreenshotToBuffer(IBinder displayToken,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform, int rotation);
    private static native void nativeScreenshot(IBinder displayToken, Surface consumer,
            Rect sourceCrop, int width, int height, int minLayer, int maxLayer,
            boolean allLayers, boolean useIdentityTransform);
    private static native GraphicBuffer nativeCaptureLayers(IBinder layerHandleToken,
            Rect sourceCrop, float frameScale);

    private static native long nativeCreateTransaction();
    private static native long nativeGetNativeTransactionFinalizer();
    private static native void nativeApplyTransaction(long transactionObj, boolean sync);
    private static native void nativeMergeTransaction(long transactionObj,
            long otherTransactionObj);
    private static native void nativeSetAnimationTransaction(long transactionObj);
    private static native void nativeSetEarlyWakeup(long transactionObj);

    private static native void nativeSetLayer(long transactionObj, long nativeObject, int zorder);
    private static native void nativeSetRelativeLayer(long transactionObj, long nativeObject,
            IBinder relativeTo, int zorder);
    private static native void nativeSetPosition(long transactionObj, long nativeObject,
            float x, float y);
    private static native void nativeSetGeometryAppliesWithResize(long transactionObj,
            long nativeObject);
    private static native void nativeSetSize(long transactionObj, long nativeObject, int w, int h);
    private static native void nativeSetTransparentRegionHint(long transactionObj,
            long nativeObject, Region region);
    private static native void nativeSetAlpha(long transactionObj, long nativeObject, float alpha);
    private static native void nativeSetMatrix(long transactionObj, long nativeObject,
            float dsdx, float dtdx,
            float dtdy, float dsdy);
    private static native void nativeSetColor(long transactionObj, long nativeObject, float[] color);
    private static native void nativeSetFlags(long transactionObj, long nativeObject,
            int flags, int mask);
    private static native void nativeSetWindowCrop(long transactionObj, long nativeObject,
            int l, int t, int r, int b);
    private static native void nativeSetFinalCrop(long transactionObj, long nativeObject,
            int l, int t, int r, int b);
    private static native void nativeSetLayerStack(long transactionObj, long nativeObject,
            int layerStack);

    private static native boolean nativeClearContentFrameStats(long nativeObject);
    private static native boolean nativeGetContentFrameStats(long nativeObject, WindowContentFrameStats outStats);
    private static native boolean nativeClearAnimationFrameStats();
    private static native boolean nativeGetAnimationFrameStats(WindowAnimationFrameStats outStats);

    private static native IBinder nativeGetBuiltInDisplay(int physicalDisplayId);
    private static native IBinder nativeCreateDisplay(String name, boolean secure);
    private static native void nativeDestroyDisplay(IBinder displayToken);
    private static native void nativeSetDisplaySurface(long transactionObj,
            IBinder displayToken, long nativeSurfaceObject);
    private static native void nativeSetDisplayLayerStack(long transactionObj,
            IBinder displayToken, int layerStack);
    private static native void nativeSetDisplayProjection(long transactionObj,
            IBinder displayToken, int orientation,
            int l, int t, int r, int b,
            int L, int T, int R, int B);
    private static native void nativeSetDisplaySize(long transactionObj, IBinder displayToken,
            int width, int height);
    private static native SurfaceControl.PhysicalDisplayInfo[] nativeGetDisplayConfigs(
            IBinder displayToken);
    private static native int nativeGetActiveConfig(IBinder displayToken);
    private static native boolean nativeSetActiveConfig(IBinder displayToken, int id);
    private static native int[] nativeGetDisplayColorModes(IBinder displayToken);
    private static native int nativeGetActiveColorMode(IBinder displayToken);
    private static native boolean nativeSetActiveColorMode(IBinder displayToken,
            int colorMode);
    private static native void nativeSetDisplayPowerMode(
            IBinder displayToken, int mode);
    private static native void nativeDeferTransactionUntil(long transactionObj, long nativeObject,
            IBinder handle, long frame);
    private static native void nativeDeferTransactionUntilSurface(long transactionObj,
            long nativeObject,
            long surfaceObject, long frame);
    private static native void nativeReparentChildren(long transactionObj, long nativeObject,
            IBinder handle);
    private static native void nativeReparent(long transactionObj, long nativeObject,
            IBinder parentHandle);
    private static native void nativeSeverChildren(long transactionObj, long nativeObject);
    private static native void nativeSetOverrideScalingMode(long transactionObj, long nativeObject,
            int scalingMode);
    private static native void nativeDestroy(long transactionObj, long nativeObject);
    private static native IBinder nativeGetHandle(long nativeObject);
    private static native boolean nativeGetTransformToDisplayInverse(long nativeObject);

    private static native Display.HdrCapabilities nativeGetHdrCapabilities(IBinder displayToken);


    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final String mName;
    long mNativeObject; // package visibility only for Surface.java access

    // TODO: Move this to native.
    private final Object mSizeLock = new Object();
    @GuardedBy("mSizeLock")
    private int mWidth;
    @GuardedBy("mSizeLock")
    private int mHeight;

    static Transaction sGlobalTransaction;
    static long sTransactionNestCount = 0;

    /* flags used in constructor (keep in sync with ISurfaceComposerClient.h) */

    /**
     * Surface creation flag: Surface is created hidden
     */
    public static final int HIDDEN = 0x00000004;

    /**
     * Surface creation flag: The surface contains secure content, special
     * measures will be taken to disallow the surface's content to be copied
     * from another process. In particular, screenshots and VNC servers will
     * be disabled, but other measures can take place, for instance the
     * surface might not be hardware accelerated.
     *
     */
    public static final int SECURE = 0x00000080;

    /**
     * Surface creation flag: Creates a surface where color components are interpreted
     * as "non pre-multiplied" by their alpha channel. Of course this flag is
     * meaningless for surfaces without an alpha channel. By default
     * surfaces are pre-multiplied, which means that each color component is
     * already multiplied by its alpha value. In this case the blending
     * equation used is:
     * <p>
     *    <code>DEST = SRC + DEST * (1-SRC_ALPHA)</code>
     * <p>
     * By contrast, non pre-multiplied surfaces use the following equation:
     * <p>
     *    <code>DEST = SRC * SRC_ALPHA * DEST * (1-SRC_ALPHA)</code>
     * <p>
     * pre-multiplied surfaces must always be used if transparent pixels are
     * composited on top of each-other into the surface. A pre-multiplied
     * surface can never lower the value of the alpha component of a given
     * pixel.
     * <p>
     * In some rare situations, a non pre-multiplied surface is preferable.
     *
     */
    public static final int NON_PREMULTIPLIED = 0x00000100;

    /**
     * Surface creation flag: Indicates that the surface must be considered opaque,
     * even if its pixel format contains an alpha channel. This can be useful if an
     * application needs full RGBA 8888 support for instance but will
     * still draw every pixel opaque.
     * <p>
     * This flag is ignored if setAlpha() is used to make the surface non-opaque.
     * Combined effects are (assuming a buffer format with an alpha channel):
     * <ul>
     * <li>OPAQUE + alpha(1.0) == opaque composition
     * <li>OPAQUE + alpha(0.x) == blended composition
     * <li>!OPAQUE + alpha(1.0) == blended composition
     * <li>!OPAQUE + alpha(0.x) == blended composition
     * </ul>
     * If the underlying buffer lacks an alpha channel, the OPAQUE flag is effectively
     * set automatically.
     */
    public static final int OPAQUE = 0x00000400;

    /**
     * Surface creation flag: Application requires a hardware-protected path to an
     * external display sink. If a hardware-protected path is not available,
     * then this surface will not be displayed on the external sink.
     *
     */
    public static final int PROTECTED_APP = 0x00000800;

    // 0x1000 is reserved for an independent DRM protected flag in framework

    /**
     * Surface creation flag: Window represents a cursor glyph.
     */
    public static final int CURSOR_WINDOW = 0x00002000;

    /**
     * Surface creation flag: Creates a normal surface.
     * This is the default.
     *
     */
    public static final int FX_SURFACE_NORMAL   = 0x00000000;

    /**
     * Surface creation flag: Creates a Dim surface.
     * Everything behind this surface is dimmed by the amount specified
     * in {@link #setAlpha}.  It is an error to lock a Dim surface, since it
     * doesn't have a backing store.
     *
     */
    public static final int FX_SURFACE_DIM = 0x00020000;

    /**
     * Mask used for FX values above.
     *
     */
    public static final int FX_SURFACE_MASK = 0x000F0000;

    /* flags used with setFlags() (keep in sync with ISurfaceComposer.h) */

    /**
     * Surface flag: Hide the surface.
     * Equivalent to calling hide().
     * Updates the value set during Surface creation (see {@link #HIDDEN}).
     */
    private static final int SURFACE_HIDDEN = 0x01;

    /**
     * Surface flag: composite without blending when possible.
     * Updates the value set during Surface creation (see {@link #OPAQUE}).
     */
    private static final int SURFACE_OPAQUE = 0x02;


    /* built-in physical display ids (keep in sync with ISurfaceComposer.h)
     * these are different from the logical display ids used elsewhere in the framework */

    /**
     * Built-in physical display id: Main display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay(int)}.
     */
    public static final int BUILT_IN_DISPLAY_ID_MAIN = 0;

    /**
     * Built-in physical display id: Attached HDMI display.
     * Use only with {@link SurfaceControl#getBuiltInDisplay(int)}.
     */
    public static final int BUILT_IN_DISPLAY_ID_HDMI = 1;

    /* Display power modes * /

    /**
     * Display power mode off: used while blanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_OFF = 0;

    /**
     * Display power mode doze: used while putting the screen into low power mode.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_DOZE = 1;

    /**
     * Display power mode normal: used while unblanking the screen.
     * Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_NORMAL = 2;

    /**
     * Display power mode doze: used while putting the screen into a suspended
     * low power mode.  Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_DOZE_SUSPEND = 3;

    /**
     * Display power mode on: used while putting the screen into a suspended
     * full power mode.  Use only with {@link SurfaceControl#setDisplayPowerMode}.
     */
    public static final int POWER_MODE_ON_SUSPEND = 4;

    /**
     * A value for windowType used to indicate that the window should be omitted from screenshots
     * and display mirroring. A temporary workaround until we express such things with
     * the hierarchy.
     * TODO: b/64227542
     * @hide
     */
    public static final int WINDOW_TYPE_DONT_SCREENSHOT = 441731;

    /**
     * Builder class for {@link SurfaceControl} objects.
     */
    public static class Builder {
        private SurfaceSession mSession;
        private int mFlags = HIDDEN;
        private int mWidth;
        private int mHeight;
        private int mFormat = PixelFormat.OPAQUE;
        private String mName;
        private SurfaceControl mParent;
        private int mWindowType = -1;
        private int mOwnerUid = -1;

        /**
         * Begin building a SurfaceControl with a given {@link SurfaceSession}.
         *
         * @param session The {@link SurfaceSession} with which to eventually construct the surface.
         */
        public Builder(SurfaceSession session) {
            mSession = session;
        }

        /**
         * Construct a new {@link SurfaceControl} with the set parameters.
         */
        public SurfaceControl build() {
            if (mWidth <= 0 || mHeight <= 0) {
                throw new IllegalArgumentException(
                        "width and height must be set");
            }
            return new SurfaceControl(mSession, mName, mWidth, mHeight, mFormat,
                    mFlags, mParent, mWindowType, mOwnerUid);
        }

        /**
         * Set a debugging-name for the SurfaceControl.
         *
         * @param name A name to identify the Surface in debugging.
         */
        public Builder setName(String name) {
            mName = name;
            return this;
        }

        /**
         * Set the initial size of the controlled surface's buffers in pixels.
         *
         * @param width The buffer width in pixels.
         * @param height The buffer height in pixels.
         */
        public Builder setSize(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "width and height must be positive");
            }
            mWidth = width;
            mHeight = height;
            return this;
        }

        /**
         * Set the pixel format of the controlled surface's buffers, using constants from
         * {@link android.graphics.PixelFormat}.
         */
        public Builder setFormat(@PixelFormat.Format int format) {
            mFormat = format;
            return this;
        }

        /**
         * Specify if the app requires a hardware-protected path to
         * an external display sync. If protected content is enabled, but
         * such a path is not available, then the controlled Surface will
         * not be displayed.
         *
         * @param protectedContent Whether to require a protected sink.
         */
        public Builder setProtected(boolean protectedContent) {
            if (protectedContent) {
                mFlags |= PROTECTED_APP;
            } else {
                mFlags &= ~PROTECTED_APP;
            }
            return this;
        }

        /**
         * Specify whether the Surface contains secure content. If true, the system
         * will prevent the surfaces content from being copied by another process. In
         * particular screenshots and VNC servers will be disabled. This is however
         * not a complete prevention of readback as {@link #setProtected}.
         */
        public Builder setSecure(boolean secure) {
            if (secure) {
                mFlags |= SECURE;
            } else {
                mFlags &= ~SECURE;
            }
            return this;
        }

        /**
         * Indicates whether the surface must be considered opaque,
         * even if its pixel format is set to translucent. This can be useful if an
         * application needs full RGBA 8888 support for instance but will
         * still draw every pixel opaque.
         * <p>
         * This flag only determines whether opacity will be sampled from the alpha channel.
         * Plane-alpha from calls to setAlpha() can still result in blended composition
         * regardless of the opaque setting.
         *
         * Combined effects are (assuming a buffer format with an alpha channel):
         * <ul>
         * <li>OPAQUE + alpha(1.0) == opaque composition
         * <li>OPAQUE + alpha(0.x) == blended composition
         * <li>OPAQUE + alpha(0.0) == no composition
         * <li>!OPAQUE + alpha(1.0) == blended composition
         * <li>!OPAQUE + alpha(0.x) == blended composition
         * <li>!OPAQUE + alpha(0.0) == no composition
         * </ul>
         * If the underlying buffer lacks an alpha channel, it is as if setOpaque(true)
         * were set automatically.
         * @param opaque Whether the Surface is OPAQUE.
         */
        public Builder setOpaque(boolean opaque) {
            if (opaque) {
                mFlags |= OPAQUE;
            } else {
                mFlags &= ~OPAQUE;
            }
            return this;
        }

        /**
         * Set a parent surface for our new SurfaceControl.
         *
         * Child surfaces are constrained to the onscreen region of their parent.
         * Furthermore they stack relatively in Z order, and inherit the transformation
         * of the parent.
         *
         * @param parent The parent control.
         */
        public Builder setParent(SurfaceControl parent) {
            mParent = parent;
            return this;
        }

        /**
         * Set surface metadata.
         *
         * Currently these are window-types as per {@link WindowManager.LayoutParams} and
         * owner UIDs. Child surfaces inherit their parents
         * metadata so only the WindowManager needs to set this on root Surfaces.
         *
         * @param windowType A window-type
         * @param ownerUid UID of the window owner.
         */
        public Builder setMetadata(int windowType, int ownerUid) {
            if (UserHandle.getAppId(Process.myUid()) != Process.SYSTEM_UID) {
                throw new UnsupportedOperationException(
                        "It only makes sense to set Surface metadata from the WindowManager");
            }
            mWindowType = windowType;
            mOwnerUid = ownerUid;
            return this;
        }

        /**
         * Indicate whether a 'ColorLayer' is to be constructed.
         *
         * Color layers will not have an associated BufferQueue and will instead always render a
         * solid color (that is, solid before plane alpha). Currently that color is black.
         *
         * @param isColorLayer Whether to create a color layer.
         */
        public Builder setColorLayer(boolean isColorLayer) {
            if (isColorLayer) {
                mFlags |= FX_SURFACE_DIM;
            } else {
                mFlags &= ~FX_SURFACE_DIM;
            }
            return this;
        }

        /**
         * Set 'Surface creation flags' such as {@link HIDDEN}, {@link SECURE}.
         *
         * TODO: Finish conversion to individual builder methods?
         * @param flags The combined flags
         */
        public Builder setFlags(int flags) {
            mFlags = flags;
            return this;
        }
    }

    /**
     * Create a surface with a name.
     * <p>
     * The surface creation flags specify what kind of surface to create and
     * certain options such as whether the surface can be assumed to be opaque
     * and whether it should be initially hidden.  Surfaces should always be
     * created with the {@link #HIDDEN} flag set to ensure that they are not
     * made visible prematurely before all of the surface's properties have been
     * configured.
     * <p>
     * Good practice is to first create the surface with the {@link #HIDDEN} flag
     * specified, open a transaction, set the surface layer, layer stack, alpha,
     * and position, call {@link #show} if appropriate, and close the transaction.
     *
     * @param session The surface session, must not be null.
     * @param name The surface name, must not be null.
     * @param w The surface initial width.
     * @param h The surface initial height.
     * @param flags The surface creation flags.  Should always include {@link #HIDDEN}
     * in the creation flags.
     * @param windowType The type of the window as specified in WindowManager.java.
     * @param ownerUid A unique per-app ID.
     *
     * @throws throws OutOfResourcesException If the SurfaceControl cannot be created.
     */
    private SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
            SurfaceControl parent, int windowType, int ownerUid)
                    throws OutOfResourcesException, IllegalArgumentException {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }

        if ((flags & SurfaceControl.HIDDEN) == 0) {
            Log.w(TAG, "Surfaces should always be created with the HIDDEN flag set "
                    + "to ensure that they are not made visible prematurely before "
                    + "all of the surface's properties have been configured.  "
                    + "Set the other properties and make the surface visible within "
                    + "a transaction.  New surface name: " + name,
                    new Throwable());
        }

        mName = name;
        mWidth = w;
        mHeight = h;
        mNativeObject = nativeCreate(session, name, w, h, format, flags,
            parent != null ? parent.mNativeObject : 0, windowType, ownerUid);
        if (mNativeObject == 0) {
            throw new OutOfResourcesException(
                    "Couldn't allocate SurfaceControl native object");
        }

        mCloseGuard.open("release");
    }

    // This is a transfer constructor, useful for transferring a live SurfaceControl native
    // object to another Java wrapper which could have some different behavior, e.g.
    // event logging.
    public SurfaceControl(SurfaceControl other) {
        mName = other.mName;
        mWidth = other.mWidth;
        mHeight = other.mHeight;
        mNativeObject = other.mNativeObject;
        other.mCloseGuard.close();
        other.mNativeObject = 0;
        mCloseGuard.open("release");
    }

    private SurfaceControl(Parcel in) {
        mName = in.readString();
        mWidth = in.readInt();
        mHeight = in.readInt();
        mNativeObject = nativeReadFromParcel(in);
        if (mNativeObject == 0) {
            throw new IllegalArgumentException("Couldn't read SurfaceControl from parcel=" + in);
        }
        mCloseGuard.open("release");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        nativeWriteToParcel(mNativeObject, dest);
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at {@link
     * android.view.SurfaceControlProto}.
     *
     * @param proto Stream to write the SurfaceControl object to.
     * @param fieldId Field Id of the SurfaceControl as defined in the parent message.
     * @hide
     */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(NAME, mName);
        proto.end(token);
    }

    public static final Creator<SurfaceControl> CREATOR
            = new Creator<SurfaceControl>() {
        public SurfaceControl createFromParcel(Parcel in) {
            return new SurfaceControl(in);
        }

        public SurfaceControl[] newArray(int size) {
            return new SurfaceControl[size];
        }
    };

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            if (mNativeObject != 0) {
                nativeRelease(mNativeObject);
            }
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
        if (mNativeObject != 0) {
            nativeRelease(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
    }

    /**
     * Free all server-side state associated with this surface and
     * release this object's reference.  This method can only be
     * called from the process that created the service.
     */
    public void destroy() {
        if (mNativeObject != 0) {
            nativeDestroy(mNativeObject);
            mNativeObject = 0;
        }
        mCloseGuard.close();
    }

    /**
     * Disconnect any client still connected to the surface.
     */
    public void disconnect() {
        if (mNativeObject != 0) {
            nativeDisconnect(mNativeObject);
        }
    }

    private void checkNotReleased() {
        if (mNativeObject == 0) throw new NullPointerException(
                "mNativeObject is null. Have you called release() already?");
    }

    /*
     * set surface parameters.
     * needs to be inside open/closeTransaction block
     */

    /** start a transaction */
    public static void openTransaction() {
        synchronized (SurfaceControl.class) {
            if (sGlobalTransaction == null) {
                sGlobalTransaction = new Transaction();
            }
            synchronized(SurfaceControl.class) {
                sTransactionNestCount++;
            }
        }
    }

    private static void closeTransaction(boolean sync) {
        synchronized(SurfaceControl.class) {
            if (sTransactionNestCount == 0) {
                Log.e(TAG, "Call to SurfaceControl.closeTransaction without matching openTransaction");
            } else if (--sTransactionNestCount > 0) {
                return;
            }
            sGlobalTransaction.apply(sync);
        }
    }

    /**
     * Merge the supplied transaction in to the deprecated "global" transaction.
     * This clears the supplied transaction in an identical fashion to {@link Transaction#merge}.
     * <p>
     * This is a utility for interop with legacy-code and will go away with the Global Transaction.
     */
    @Deprecated
    public static void mergeToGlobalTransaction(Transaction t) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.merge(t);
        }
    }

    /** end a transaction */
    public static void closeTransaction() {
        closeTransaction(false);
    }

    public static void closeTransactionSync() {
        closeTransaction(true);
    }

    public void deferTransactionUntil(IBinder handle, long frame) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.deferTransactionUntil(this, handle, frame);
        }
    }

    public void deferTransactionUntil(Surface barrier, long frame) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.deferTransactionUntilSurface(this, barrier, frame);
        }
    }

    public void reparentChildren(IBinder newParentHandle) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.reparentChildren(this, newParentHandle);
        }
    }

    public void reparent(IBinder newParentHandle) {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.reparent(this, newParentHandle);
        }
    }

    public void detachChildren() {
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.detachChildren(this);
        }
    }

    public void setOverrideScalingMode(int scalingMode) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setOverrideScalingMode(this, scalingMode);
        }
    }

    public IBinder getHandle() {
        return nativeGetHandle(mNativeObject);
    }

    public static void setAnimationTransaction() {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setAnimationTransaction();
        }
    }

    public void setLayer(int zorder) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setLayer(this, zorder);
        }
    }

    public void setRelativeLayer(SurfaceControl relativeTo, int zorder) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setRelativeLayer(this, relativeTo, zorder);
        }
    }

    public void setPosition(float x, float y) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setPosition(this, x, y);
        }
    }

    public void setGeometryAppliesWithResize() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setGeometryAppliesWithResize(this);
        }
    }

    public void setSize(int w, int h) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setSize(this, w, h);
        }
    }

    public void hide() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.hide(this);
        }
    }

    public void show() {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.show(this);
        }
    }

    public void setTransparentRegionHint(Region region) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setTransparentRegionHint(this, region);
        }
    }

    public boolean clearContentFrameStats() {
        checkNotReleased();
        return nativeClearContentFrameStats(mNativeObject);
    }

    public boolean getContentFrameStats(WindowContentFrameStats outStats) {
        checkNotReleased();
        return nativeGetContentFrameStats(mNativeObject, outStats);
    }

    public static boolean clearAnimationFrameStats() {
        return nativeClearAnimationFrameStats();
    }

    public static boolean getAnimationFrameStats(WindowAnimationFrameStats outStats) {
        return nativeGetAnimationFrameStats(outStats);
    }

    public void setAlpha(float alpha) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setAlpha(this, alpha);
        }
    }

    public void setColor(@Size(3) float[] color) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setColor(this, color);
        }
    }

    public void setMatrix(float dsdx, float dtdx, float dtdy, float dsdy) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setMatrix(this, dsdx, dtdx, dtdy, dsdy);
        }
    }

    /**
     * Sets the transform and position of a {@link SurfaceControl} from a 3x3 transformation matrix.
     *
     * @param matrix The matrix to apply.
     * @param float9 An array of 9 floats to be used to extract the values from the matrix.
     */
    public void setMatrix(Matrix matrix, float[] float9) {
        checkNotReleased();
        matrix.getValues(float9);
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setMatrix(this, float9[MSCALE_X], float9[MSKEW_Y],
                    float9[MSKEW_X], float9[MSCALE_Y]);
            sGlobalTransaction.setPosition(this, float9[MTRANS_X], float9[MTRANS_Y]);
        }
    }

    public void setWindowCrop(Rect crop) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setWindowCrop(this, crop);
        }
    }

    public void setFinalCrop(Rect crop) {
        checkNotReleased();
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setFinalCrop(this, crop);
        }
    }

    public void setLayerStack(int layerStack) {
        checkNotReleased();
        synchronized(SurfaceControl.class) {
            sGlobalTransaction.setLayerStack(this, layerStack);
        }
    }

    public void setOpaque(boolean isOpaque) {
        checkNotReleased();

        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setOpaque(this, isOpaque);
        }
    }

    public void setSecure(boolean isSecure) {
        checkNotReleased();

        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setSecure(this, isSecure);
        }
    }

    public int getWidth() {
        synchronized (mSizeLock) {
            return mWidth;
        }
    }

    public int getHeight() {
        synchronized (mSizeLock) {
            return mHeight;
        }
    }

    @Override
    public String toString() {
        return "Surface(name=" + mName + ")/@0x" +
                Integer.toHexString(System.identityHashCode(this));
    }

    /*
     * set display parameters.
     * needs to be inside open/closeTransaction block
     */

    /**
     * Describes the properties of a physical display known to surface flinger.
     */
    public static final class PhysicalDisplayInfo {
        public int width;
        public int height;
        public float refreshRate;
        public float density;
        public float xDpi;
        public float yDpi;
        public boolean secure;
        public long appVsyncOffsetNanos;
        public long presentationDeadlineNanos;

        public PhysicalDisplayInfo() {
        }

        public PhysicalDisplayInfo(PhysicalDisplayInfo other) {
            copyFrom(other);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PhysicalDisplayInfo && equals((PhysicalDisplayInfo)o);
        }

        public boolean equals(PhysicalDisplayInfo other) {
            return other != null
                    && width == other.width
                    && height == other.height
                    && refreshRate == other.refreshRate
                    && density == other.density
                    && xDpi == other.xDpi
                    && yDpi == other.yDpi
                    && secure == other.secure
                    && appVsyncOffsetNanos == other.appVsyncOffsetNanos
                    && presentationDeadlineNanos == other.presentationDeadlineNanos;
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        public void copyFrom(PhysicalDisplayInfo other) {
            width = other.width;
            height = other.height;
            refreshRate = other.refreshRate;
            density = other.density;
            xDpi = other.xDpi;
            yDpi = other.yDpi;
            secure = other.secure;
            appVsyncOffsetNanos = other.appVsyncOffsetNanos;
            presentationDeadlineNanos = other.presentationDeadlineNanos;
        }

        // For debugging purposes
        @Override
        public String toString() {
            return "PhysicalDisplayInfo{" + width + " x " + height + ", " + refreshRate + " fps, "
                    + "density " + density + ", " + xDpi + " x " + yDpi + " dpi, secure " + secure
                    + ", appVsyncOffset " + appVsyncOffsetNanos
                    + ", bufferDeadline " + presentationDeadlineNanos + "}";
        }
    }

    public static void setDisplayPowerMode(IBinder displayToken, int mode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeSetDisplayPowerMode(displayToken, mode);
    }

    public static SurfaceControl.PhysicalDisplayInfo[] getDisplayConfigs(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayConfigs(displayToken);
    }

    public static int getActiveConfig(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveConfig(displayToken);
    }

    public static boolean setActiveConfig(IBinder displayToken, int id) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveConfig(displayToken, id);
    }

    public static int[] getDisplayColorModes(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetDisplayColorModes(displayToken);
    }

    public static int getActiveColorMode(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetActiveColorMode(displayToken);
    }

    public static boolean setActiveColorMode(IBinder displayToken, int colorMode) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeSetActiveColorMode(displayToken, colorMode);
    }

    public static void setDisplayProjection(IBinder displayToken,
            int orientation, Rect layerStackRect, Rect displayRect) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplayProjection(displayToken, orientation,
                    layerStackRect, displayRect);
        }
    }

    public static void setDisplayLayerStack(IBinder displayToken, int layerStack) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplayLayerStack(displayToken, layerStack);
        }
    }

    public static void setDisplaySurface(IBinder displayToken, Surface surface) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplaySurface(displayToken, surface);
        }
    }

    public static void setDisplaySize(IBinder displayToken, int width, int height) {
        synchronized (SurfaceControl.class) {
            sGlobalTransaction.setDisplaySize(displayToken, width, height);
        }
    }

    public static Display.HdrCapabilities getHdrCapabilities(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        return nativeGetHdrCapabilities(displayToken);
    }

    public static IBinder createDisplay(String name, boolean secure) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        return nativeCreateDisplay(name, secure);
    }

    public static void destroyDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        nativeDestroyDisplay(displayToken);
    }

    public static IBinder getBuiltInDisplay(int builtInDisplayId) {
        return nativeGetBuiltInDisplay(builtInDisplayId);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param minLayer The lowest (bottom-most Z order) surface layer to
     * include in the screenshot.
     * @param maxLayer The highest (top-most Z order) surface layer to
     * include in the screenshot.
     * @param useIdentityTransform Replace whatever transformation (rotation,
     * scaling, translation) the surface layers are currently using with the
     * identity transformation while taking the screenshot.
     */
    public static void screenshot(IBinder display, Surface consumer,
            int width, int height, int minLayer, int maxLayer,
            boolean useIdentityTransform) {
        screenshot(display, consumer, new Rect(), width, height, minLayer, maxLayer,
                false, useIdentityTransform);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     */
    public static void screenshot(IBinder display, Surface consumer,
            int width, int height) {
        screenshot(display, consumer, new Rect(), width, height, 0, 0, true, false);
    }

    /**
     * Copy the current screen contents into the provided {@link Surface}
     *
     * @param display The display to take the screenshot of.
     * @param consumer The {@link Surface} to take the screenshot into.
     */
    public static void screenshot(IBinder display, Surface consumer) {
        screenshot(display, consumer, new Rect(), 0, 0, 0, 0, true, false);
    }

    /**
     * Copy the current screen contents into a hardware bitmap and return it.
     * Note: If you want to modify the Bitmap in software, you will need to copy the Bitmap into
     * a software Bitmap using {@link Bitmap#copy(Bitmap.Config, boolean)}
     *
     * CAVEAT: Versions of screenshot that return a {@link Bitmap} can
     * be extremely slow; avoid use unless absolutely necessary; prefer
     * the versions that use a {@link Surface} instead, such as
     * {@link SurfaceControl#screenshot(IBinder, Surface)}.
     *
     * @param sourceCrop The portion of the screen to capture into the Bitmap;
     * caller may pass in 'new Rect()' if no cropping is desired.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param minLayer The lowest (bottom-most Z order) surface layer to
     * include in the screenshot.
     * @param maxLayer The highest (top-most Z order) surface layer to
     * include in the screenshot.
     * @param useIdentityTransform Replace whatever transformation (rotation,
     * scaling, translation) the surface layers are currently using with the
     * identity transformation while taking the screenshot.
     * @param rotation Apply a custom clockwise rotation to the screenshot, i.e.
     * Surface.ROTATION_0,90,180,270. Surfaceflinger will always take
     * screenshots in its native portrait orientation by default, so this is
     * useful for returning screenshots that are independent of device
     * orientation.
     * @return Returns a hardware Bitmap containing the screen contents, or null
     * if an error occurs. Make sure to call Bitmap.recycle() as soon as
     * possible, once its content is not needed anymore.
     */
    public static Bitmap screenshot(Rect sourceCrop, int width, int height,
            int minLayer, int maxLayer, boolean useIdentityTransform,
            int rotation) {
        // TODO: should take the display as a parameter
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshot(displayToken, sourceCrop, width, height,
                minLayer, maxLayer, false, useIdentityTransform, rotation);
    }

    /**
     * Like {@link SurfaceControl#screenshot(Rect, int, int, int, int, boolean, int)}
     * but returns a GraphicBuffer.
     */
    public static GraphicBuffer screenshotToBuffer(Rect sourceCrop, int width, int height,
            int minLayer, int maxLayer, boolean useIdentityTransform,
            int rotation) {
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        return nativeScreenshotToBuffer(displayToken, sourceCrop, width, height,
                minLayer, maxLayer, false, useIdentityTransform, rotation);
    }

    /**
     * Like {@link SurfaceControl#screenshot(Rect, int, int, int, int, boolean, int)} but
     * includes all Surfaces in the screenshot. This will also update the orientation so it
     * sends the correct coordinates to SF based on the rotation value.
     *
     * @param sourceCrop The portion of the screen to capture into the Bitmap;
     * caller may pass in 'new Rect()' if no cropping is desired.
     * @param width The desired width of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param height The desired height of the returned bitmap; the raw
     * screen will be scaled down to this size.
     * @param rotation Apply a custom clockwise rotation to the screenshot, i.e.
     * Surface.ROTATION_0,90,180,270. Surfaceflinger will always take
     * screenshots in its native portrait orientation by default, so this is
     * useful for returning screenshots that are independent of device
     * orientation.
     * @return Returns a Bitmap containing the screen contents, or null
     * if an error occurs. Make sure to call Bitmap.recycle() as soon as
     * possible, once its content is not needed anymore.
     */
    public static Bitmap screenshot(Rect sourceCrop, int width, int height, int rotation) {
        // TODO: should take the display as a parameter
        IBinder displayToken = SurfaceControl.getBuiltInDisplay(
                SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN);
        if (rotation == ROTATION_90 || rotation == ROTATION_270) {
            rotation = (rotation == ROTATION_90) ? ROTATION_270 : ROTATION_90;
        }

        SurfaceControl.rotateCropForSF(sourceCrop, rotation);
        return nativeScreenshot(displayToken, sourceCrop, width, height, 0, 0, true,
                false, rotation);
    }

    private static void screenshot(IBinder display, Surface consumer, Rect sourceCrop,
            int width, int height, int minLayer, int maxLayer, boolean allLayers,
            boolean useIdentityTransform) {
        if (display == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }
        if (consumer == null) {
            throw new IllegalArgumentException("consumer must not be null");
        }
        nativeScreenshot(display, consumer, sourceCrop, width, height,
                minLayer, maxLayer, allLayers, useIdentityTransform);
    }

    private static void rotateCropForSF(Rect crop, int rot) {
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            int tmp = crop.top;
            crop.top = crop.left;
            crop.left = tmp;
            tmp = crop.right;
            crop.right = crop.bottom;
            crop.bottom = tmp;
        }
    }

    /**
     * Captures a layer and its children and returns a {@link GraphicBuffer} with the content.
     *
     * @param layerHandleToken The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     *
     * @return Returns a GraphicBuffer that contains the layer capture.
     */
    public static GraphicBuffer captureLayers(IBinder layerHandleToken, Rect sourceCrop,
            float frameScale) {
        return nativeCaptureLayers(layerHandleToken, sourceCrop, frameScale);
    }

    public static class Transaction implements Closeable {
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Transaction.class.getClassLoader(),
                nativeGetNativeTransactionFinalizer(), 512);
        private long mNativeObject;

        private final ArrayMap<SurfaceControl, Point> mResizedSurfaces = new ArrayMap<>();
        Runnable mFreeNativeResources;

        public Transaction() {
            mNativeObject = nativeCreateTransaction();
            mFreeNativeResources
                = sRegistry.registerNativeAllocation(this, mNativeObject);
        }

        /**
         * Apply the transaction, clearing it's state, and making it usable
         * as a new transaction.
         */
        public void apply() {
            apply(false);
        }

        /**
         * Close the transaction, if the transaction was not already applied this will cancel the
         * transaction.
         */
        @Override
        public void close() {
            mFreeNativeResources.run();
            mNativeObject = 0;
        }

        /**
         * Jankier version of apply. Avoid use (b/28068298).
         */
        public void apply(boolean sync) {
            applyResizedSurfaces();
            nativeApplyTransaction(mNativeObject, sync);
        }

        private void applyResizedSurfaces() {
            for (int i = mResizedSurfaces.size() - 1; i >= 0; i--) {
                final Point size = mResizedSurfaces.valueAt(i);
                final SurfaceControl surfaceControl = mResizedSurfaces.keyAt(i);
                synchronized (surfaceControl.mSizeLock) {
                    surfaceControl.mWidth = size.x;
                    surfaceControl.mHeight = size.y;
                }
            }
            mResizedSurfaces.clear();
        }

        public Transaction show(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SURFACE_HIDDEN);
            return this;
        }

        public Transaction hide(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_HIDDEN, SURFACE_HIDDEN);
            return this;
        }

        public Transaction setPosition(SurfaceControl sc, float x, float y) {
            sc.checkNotReleased();
            nativeSetPosition(mNativeObject, sc.mNativeObject, x, y);
            return this;
        }

        public Transaction setSize(SurfaceControl sc, int w, int h) {
            sc.checkNotReleased();
            mResizedSurfaces.put(sc, new Point(w, h));
            nativeSetSize(mNativeObject, sc.mNativeObject, w, h);
            return this;
        }

        public Transaction setLayer(SurfaceControl sc, int z) {
            sc.checkNotReleased();
            nativeSetLayer(mNativeObject, sc.mNativeObject, z);
            return this;
        }

        public Transaction setRelativeLayer(SurfaceControl sc, SurfaceControl relativeTo, int z) {
            sc.checkNotReleased();
            nativeSetRelativeLayer(mNativeObject, sc.mNativeObject,
                    relativeTo.getHandle(), z);
            return this;
        }

        public Transaction setTransparentRegionHint(SurfaceControl sc, Region transparentRegion) {
            sc.checkNotReleased();
            nativeSetTransparentRegionHint(mNativeObject,
                    sc.mNativeObject, transparentRegion);
            return this;
        }

        public Transaction setAlpha(SurfaceControl sc, float alpha) {
            sc.checkNotReleased();
            nativeSetAlpha(mNativeObject, sc.mNativeObject, alpha);
            return this;
        }

        public Transaction setMatrix(SurfaceControl sc,
                float dsdx, float dtdx, float dtdy, float dsdy) {
            sc.checkNotReleased();
            nativeSetMatrix(mNativeObject, sc.mNativeObject,
                    dsdx, dtdx, dtdy, dsdy);
            return this;
        }

        public Transaction setMatrix(SurfaceControl sc, Matrix matrix, float[] float9) {
            matrix.getValues(float9);
            setMatrix(sc, float9[MSCALE_X], float9[MSKEW_Y],
                    float9[MSKEW_X], float9[MSCALE_Y]);
            setPosition(sc, float9[MTRANS_X], float9[MTRANS_Y]);
            return this;
        }

        public Transaction setWindowCrop(SurfaceControl sc, Rect crop) {
            sc.checkNotReleased();
            if (crop != null) {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject,
                        crop.left, crop.top, crop.right, crop.bottom);
            } else {
                nativeSetWindowCrop(mNativeObject, sc.mNativeObject, 0, 0, 0, 0);
            }

            return this;
        }

        public Transaction setFinalCrop(SurfaceControl sc, Rect crop) {
            sc.checkNotReleased();
            if (crop != null) {
                nativeSetFinalCrop(mNativeObject, sc.mNativeObject,
                        crop.left, crop.top, crop.right, crop.bottom);
            } else {
                nativeSetFinalCrop(mNativeObject, sc.mNativeObject, 0, 0, 0, 0);
            }

            return this;
        }

        public Transaction setLayerStack(SurfaceControl sc, int layerStack) {
            sc.checkNotReleased();
            nativeSetLayerStack(mNativeObject, sc.mNativeObject, layerStack);
            return this;
        }

        public Transaction deferTransactionUntil(SurfaceControl sc, IBinder handle,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            sc.checkNotReleased();
            nativeDeferTransactionUntil(mNativeObject, sc.mNativeObject, handle, frameNumber);
            return this;
        }

        public Transaction deferTransactionUntilSurface(SurfaceControl sc, Surface barrierSurface,
                long frameNumber) {
            if (frameNumber < 0) {
                return this;
            }
            sc.checkNotReleased();
            nativeDeferTransactionUntilSurface(mNativeObject, sc.mNativeObject,
                    barrierSurface.mNativeObject, frameNumber);
            return this;
        }

        public Transaction reparentChildren(SurfaceControl sc, IBinder newParentHandle) {
            sc.checkNotReleased();
            nativeReparentChildren(mNativeObject, sc.mNativeObject, newParentHandle);
            return this;
        }

        /** Re-parents a specific child layer to a new parent */
        public Transaction reparent(SurfaceControl sc, IBinder newParentHandle) {
            sc.checkNotReleased();
            nativeReparent(mNativeObject, sc.mNativeObject,
                    newParentHandle);
            return this;
        }

        public Transaction detachChildren(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSeverChildren(mNativeObject, sc.mNativeObject);
            return this;
        }

        public Transaction setOverrideScalingMode(SurfaceControl sc, int overrideScalingMode) {
            sc.checkNotReleased();
            nativeSetOverrideScalingMode(mNativeObject, sc.mNativeObject,
                    overrideScalingMode);
            return this;
        }

        /**
         * Sets a color for the Surface.
         * @param color A float array with three values to represent r, g, b in range [0..1]
         */
        public Transaction setColor(SurfaceControl sc, @Size(3) float[] color) {
            sc.checkNotReleased();
            nativeSetColor(mNativeObject, sc.mNativeObject, color);
            return this;
        }

        /**
         * If the buffer size changes in this transaction, position and crop updates specified
         * in this transaction will not complete until a buffer of the new size
         * arrives. As transform matrix and size are already frozen in this fashion,
         * this enables totally freezing the surface until the resize has completed
         * (at which point the geometry influencing aspects of this transaction will then occur)
         */
        public Transaction setGeometryAppliesWithResize(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeSetGeometryAppliesWithResize(mNativeObject, sc.mNativeObject);
            return this;
        }

        /**
         * Sets the security of the surface.  Setting the flag is equivalent to creating the
         * Surface with the {@link #SECURE} flag.
         */
        public Transaction setSecure(SurfaceControl sc, boolean isSecure) {
            sc.checkNotReleased();
            if (isSecure) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SECURE, SECURE);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SECURE);
            }
            return this;
        }

        /**
         * Sets the opacity of the surface.  Setting the flag is equivalent to creating the
         * Surface with the {@link #OPAQUE} flag.
         */
        public Transaction setOpaque(SurfaceControl sc, boolean isOpaque) {
            sc.checkNotReleased();
            if (isOpaque) {
                nativeSetFlags(mNativeObject, sc.mNativeObject, SURFACE_OPAQUE, SURFACE_OPAQUE);
            } else {
                nativeSetFlags(mNativeObject, sc.mNativeObject, 0, SURFACE_OPAQUE);
            }
            return this;
        }

        /**
         * Same as {@link #destroy()} except this is invoked in a transaction instead of
         * immediately.
         */
        public Transaction destroy(SurfaceControl sc) {
            sc.checkNotReleased();
            nativeDestroy(mNativeObject, sc.mNativeObject);
            return this;
        }

        public Transaction setDisplaySurface(IBinder displayToken, Surface surface) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }

            if (surface != null) {
                synchronized (surface.mLock) {
                    nativeSetDisplaySurface(mNativeObject, displayToken, surface.mNativeObject);
                }
            } else {
                nativeSetDisplaySurface(mNativeObject, displayToken, 0);
            }
            return this;
        }

        public Transaction setDisplayLayerStack(IBinder displayToken, int layerStack) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            nativeSetDisplayLayerStack(mNativeObject, displayToken, layerStack);
            return this;
        }

        public Transaction setDisplayProjection(IBinder displayToken,
                int orientation, Rect layerStackRect, Rect displayRect) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            if (layerStackRect == null) {
                throw new IllegalArgumentException("layerStackRect must not be null");
            }
            if (displayRect == null) {
                throw new IllegalArgumentException("displayRect must not be null");
            }
            nativeSetDisplayProjection(mNativeObject, displayToken, orientation,
                    layerStackRect.left, layerStackRect.top, layerStackRect.right, layerStackRect.bottom,
                    displayRect.left, displayRect.top, displayRect.right, displayRect.bottom);
            return this;
        }

        public Transaction setDisplaySize(IBinder displayToken, int width, int height) {
            if (displayToken == null) {
                throw new IllegalArgumentException("displayToken must not be null");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be positive");
            }

            nativeSetDisplaySize(mNativeObject, displayToken, width, height);
            return this;
        }

        /** flag the transaction as an animation */
        public Transaction setAnimationTransaction() {
            nativeSetAnimationTransaction(mNativeObject);
            return this;
        }

        /**
         * Indicate that SurfaceFlinger should wake up earlier than usual as a result of this
         * transaction. This should be used when the caller thinks that the scene is complex enough
         * that it's likely to hit GL composition, and thus, SurfaceFlinger needs to more time in
         * order not to miss frame deadlines.
         * <p>
         * Corresponds to setting ISurfaceComposer::eEarlyWakeup
         */
        public Transaction setEarlyWakeup() {
            nativeSetEarlyWakeup(mNativeObject);
            return this;
        }

        /**
         * Merge the other transaction into this transaction, clearing the
         * other transaction as if it had been applied.
         */
        public Transaction merge(Transaction other) {
            mResizedSurfaces.putAll(other.mResizedSurfaces);
            other.mResizedSurfaces.clear();
            nativeMergeTransaction(mNativeObject, other.mNativeObject);
            return this;
        }
    }
}
