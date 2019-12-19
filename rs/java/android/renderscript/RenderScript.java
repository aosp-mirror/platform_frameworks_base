/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

package android.renderscript;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// TODO: Clean up the whitespace that separates methods in this class.

/**
 * This class provides access to a RenderScript context, which controls RenderScript
 * initialization, resource management, and teardown. An instance of the RenderScript
 * class must be created before any other RS objects can be created.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses RenderScript, read the
 * <a href="{@docRoot}guide/topics/renderscript/index.html">RenderScript</a> developer guide.</p>
 * </div>
 **/
public class RenderScript {
    static final long TRACE_TAG = Trace.TRACE_TAG_RS;

    static final String LOG_TAG = "RenderScript_jni";
    static final boolean DEBUG  = false;
    @SuppressWarnings({"UnusedDeclaration", "deprecation"})
    static final boolean LOG_ENABLED = false;

    static private ArrayList<RenderScript> mProcessContextList = new ArrayList<RenderScript>();
    private boolean mIsProcessContext = false;
    private int mContextFlags = 0;
    private int mContextSdkVersion = 0;


    private Context mApplicationContext;

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"}) // TODO: now used locally; remove?
    static boolean sInitialized;
    native static void _nInit();

    static Object sRuntime;
    static Method registerNativeAllocation;
    static Method registerNativeFree;

    /*
     * Context creation flag that specifies a normal context.
    */
    public static final int CREATE_FLAG_NONE = 0x0000;

    /*
     * Context creation flag which specifies a context optimized for low
     * latency over peak performance. This is a hint and may have no effect
     * on some implementations.
    */
    public static final int CREATE_FLAG_LOW_LATENCY = 0x0002;

    /*
     * Context creation flag which specifies a context optimized for long
     * battery life over peak performance. This is a hint and may have no effect
     * on some implementations.
    */
    public static final int CREATE_FLAG_LOW_POWER = 0x0004;

    /**
     * @hide
     * Context creation flag which instructs the implementation to wait for
     * a debugger to be attached before continuing execution.
    */
    public static final int CREATE_FLAG_WAIT_FOR_ATTACH = 0x0008;


    /*
     * Detect the bitness of the VM to allow FieldPacker to do the right thing.
     */
    static native int rsnSystemGetPointerSize();
    @UnsupportedAppUsage
    static int sPointerSize;

    static {
        sInitialized = false;
        if (!SystemProperties.getBoolean("config.disable_renderscript", false)) {
            try {
                Class<?> vm_runtime = Class.forName("dalvik.system.VMRuntime");
                Method get_runtime = vm_runtime.getDeclaredMethod("getRuntime");
                sRuntime = get_runtime.invoke(null);
                registerNativeAllocation =
                        vm_runtime.getDeclaredMethod("registerNativeAllocation", Long.TYPE);
                registerNativeFree = vm_runtime.getDeclaredMethod("registerNativeFree", Long.TYPE);
            } catch (Exception e) {
                Log.e(LOG_TAG, "Error loading GC methods: " + e);
                throw new RSRuntimeException("Error loading GC methods: " + e);
            }
            try {
                System.loadLibrary("rs_jni");
                _nInit();
                sInitialized = true;
                sPointerSize = rsnSystemGetPointerSize();
            } catch (UnsatisfiedLinkError e) {
                Log.e(LOG_TAG, "Error loading RS jni library: " + e);
                throw new RSRuntimeException("Error loading RS jni library: " + e);
            }
        }
    }

    // Non-threadsafe functions.
    native long  nDeviceCreate();
    native void nDeviceDestroy(long dev);
    native void nDeviceSetConfig(long dev, int param, int value);
    native int nContextGetUserMessage(long con, int[] data);
    native String nContextGetErrorMessage(long con);
    native int  nContextPeekMessage(long con, int[] subID);
    native void nContextInitToClient(long con);
    native void nContextDeinitToClient(long con);

    // this should be a monotonically increasing ID
    // used in conjunction with the API version of a device
    static final long sMinorVersion = 1;

    /**
     * @hide
     *
     * Only exist to be compatible with old version RenderScript Support lib.
     * Will eventually be removed.
     *
     * @return Always return 1
     *
     */
    @UnsupportedAppUsage
    public static long getMinorID() {
        return 1;
    }


    /**
     * Returns an identifier that can be used to identify a particular
     * minor version of RS.
     *
     * @return The minor RenderScript version number
     *
     */
    public static long getMinorVersion() {
        return sMinorVersion;
    }

    /**
     * ContextType specifies the specific type of context to be created.
     *
     */
    public enum ContextType {
        /**
         * NORMAL context, this is the default and what shipping apps should
         * use.
         */
        NORMAL (0),

        /**
         * DEBUG context, perform extra runtime checks to validate the
         * kernels and APIs are being used as intended.  Get and SetElementAt
         * will be bounds checked in this mode.
         */
        DEBUG (1),

        /**
         * PROFILE context, Intended to be used once the first time an
         * application is run on a new device.  This mode allows the runtime to
         * do additional testing and performance tuning.
         */
        PROFILE (2);

        int mID;
        ContextType(int id) {
            mID = id;
        }
    }

    ContextType mContextType;
    ReentrantReadWriteLock mRWLock;

    // Methods below are wrapped to protect the non-threadsafe
    // lockless fifo.
    native long  rsnContextCreateGL(long dev, int ver, int sdkVer,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ, int dpi);
    synchronized long nContextCreateGL(long dev, int ver, int sdkVer,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ, int dpi) {
        return rsnContextCreateGL(dev, ver, sdkVer, colorMin, colorPref,
                                  alphaMin, alphaPref, depthMin, depthPref,
                                  stencilMin, stencilPref,
                                  samplesMin, samplesPref, samplesQ, dpi);
    }
    native long  rsnContextCreate(long dev, int ver, int sdkVer, int contextType);
    synchronized long nContextCreate(long dev, int ver, int sdkVer, int contextType) {
        return rsnContextCreate(dev, ver, sdkVer, contextType);
    }
    native void rsnContextDestroy(long con);
    synchronized void nContextDestroy() {
        validate();

        // take teardown lock
        // teardown lock can only be taken when no objects are being destroyed
        ReentrantReadWriteLock.WriteLock wlock = mRWLock.writeLock();
        wlock.lock();

        long curCon = mContext;
        // context is considered dead as of this point
        mContext = 0;

        wlock.unlock();
        rsnContextDestroy(curCon);
    }
    native void rsnContextSetSurface(long con, int w, int h, Surface sur);
    synchronized void nContextSetSurface(int w, int h, Surface sur) {
        validate();
        rsnContextSetSurface(mContext, w, h, sur);
    }
    native void rsnContextSetSurfaceTexture(long con, int w, int h, SurfaceTexture sur);
    synchronized void nContextSetSurfaceTexture(int w, int h, SurfaceTexture sur) {
        validate();
        rsnContextSetSurfaceTexture(mContext, w, h, sur);
    }
    native void rsnContextSetPriority(long con, int p);
    synchronized void nContextSetPriority(int p) {
        validate();
        rsnContextSetPriority(mContext, p);
    }
    native void rsnContextSetCacheDir(long con, String cacheDir);
    synchronized void nContextSetCacheDir(String cacheDir) {
        validate();
        rsnContextSetCacheDir(mContext, cacheDir);
    }
    native void rsnContextDump(long con, int bits);
    synchronized void nContextDump(int bits) {
        validate();
        rsnContextDump(mContext, bits);
    }
    native void rsnContextFinish(long con);
    synchronized void nContextFinish() {
        validate();
        rsnContextFinish(mContext);
    }

    native void rsnContextSendMessage(long con, int id, int[] data);
    synchronized void nContextSendMessage(int id, int[] data) {
        validate();
        rsnContextSendMessage(mContext, id, data);
    }

    native void rsnContextBindRootScript(long con, long script);
    synchronized void nContextBindRootScript(long script) {
        validate();
        rsnContextBindRootScript(mContext, script);
    }
    native void rsnContextBindSampler(long con, int sampler, int slot);
    synchronized void nContextBindSampler(int sampler, int slot) {
        validate();
        rsnContextBindSampler(mContext, sampler, slot);
    }
    native void rsnContextBindProgramStore(long con, long pfs);
    synchronized void nContextBindProgramStore(long pfs) {
        validate();
        rsnContextBindProgramStore(mContext, pfs);
    }
    native void rsnContextBindProgramFragment(long con, long pf);
    synchronized void nContextBindProgramFragment(long pf) {
        validate();
        rsnContextBindProgramFragment(mContext, pf);
    }
    native void rsnContextBindProgramVertex(long con, long pv);
    synchronized void nContextBindProgramVertex(long pv) {
        validate();
        rsnContextBindProgramVertex(mContext, pv);
    }
    native void rsnContextBindProgramRaster(long con, long pr);
    synchronized void nContextBindProgramRaster(long pr) {
        validate();
        rsnContextBindProgramRaster(mContext, pr);
    }
    native void rsnContextPause(long con);
    synchronized void nContextPause() {
        validate();
        rsnContextPause(mContext);
    }
    native void rsnContextResume(long con);
    synchronized void nContextResume() {
        validate();
        rsnContextResume(mContext);
    }

    native long rsnClosureCreate(long con, long kernelID, long returnValue,
        long[] fieldIDs, long[] values, int[] sizes, long[] depClosures,
        long[] depFieldIDs);
    synchronized long nClosureCreate(long kernelID, long returnValue,
        long[] fieldIDs, long[] values, int[] sizes, long[] depClosures,
        long[] depFieldIDs) {
      validate();
      long c = rsnClosureCreate(mContext, kernelID, returnValue, fieldIDs, values,
          sizes, depClosures, depFieldIDs);
      if (c == 0) {
          throw new RSRuntimeException("Failed creating closure.");
      }
      return c;
    }

    native long rsnInvokeClosureCreate(long con, long invokeID, byte[] params,
        long[] fieldIDs, long[] values, int[] sizes);
    synchronized long nInvokeClosureCreate(long invokeID, byte[] params,
        long[] fieldIDs, long[] values, int[] sizes) {
      validate();
      long c = rsnInvokeClosureCreate(mContext, invokeID, params, fieldIDs,
          values, sizes);
      if (c == 0) {
          throw new RSRuntimeException("Failed creating closure.");
      }
      return c;
    }

    native void rsnClosureSetArg(long con, long closureID, int index,
      long value, int size);
    synchronized void nClosureSetArg(long closureID, int index, long value,
        int size) {
      validate();
      rsnClosureSetArg(mContext, closureID, index, value, size);
    }

    native void rsnClosureSetGlobal(long con, long closureID, long fieldID,
        long value, int size);
    // Does this have to be synchronized?
    synchronized void nClosureSetGlobal(long closureID, long fieldID,
        long value, int size) {
      validate(); // TODO: is this necessary?
      rsnClosureSetGlobal(mContext, closureID, fieldID, value, size);
    }

    native long rsnScriptGroup2Create(long con, String name, String cachePath,
                                      long[] closures);
    synchronized long nScriptGroup2Create(String name, String cachePath,
                                          long[] closures) {
      validate();
      long g = rsnScriptGroup2Create(mContext, name, cachePath, closures);
      if (g == 0) {
          throw new RSRuntimeException("Failed creating script group.");
      }
      return g;
    }

    native void rsnScriptGroup2Execute(long con, long groupID);
    synchronized void nScriptGroup2Execute(long groupID) {
      validate();
      rsnScriptGroup2Execute(mContext, groupID);
    }

    native void rsnAssignName(long con, long obj, byte[] name);
    synchronized void nAssignName(long obj, byte[] name) {
        validate();
        rsnAssignName(mContext, obj, name);
    }
    native String rsnGetName(long con, long obj);
    synchronized String nGetName(long obj) {
        validate();
        return rsnGetName(mContext, obj);
    }

    // nObjDestroy is explicitly _not_ synchronous to prevent crashes in finalizers
    native void rsnObjDestroy(long con, long id);
    void nObjDestroy(long id) {
        // There is a race condition here.  The calling code may be run
        // by the gc while teardown is occuring.  This protects againts
        // deleting dead objects.
        if (mContext != 0) {
            rsnObjDestroy(mContext, id);
        }
    }

    native long rsnElementCreate(long con, long type, int kind, boolean norm, int vecSize);
    synchronized long nElementCreate(long type, int kind, boolean norm, int vecSize) {
        validate();
        return rsnElementCreate(mContext, type, kind, norm, vecSize);
    }
    native long rsnElementCreate2(long con, long[] elements, String[] names, int[] arraySizes);
    synchronized long nElementCreate2(long[] elements, String[] names, int[] arraySizes) {
        validate();
        return rsnElementCreate2(mContext, elements, names, arraySizes);
    }
    native void rsnElementGetNativeData(long con, long id, int[] elementData);
    synchronized void nElementGetNativeData(long id, int[] elementData) {
        validate();
        rsnElementGetNativeData(mContext, id, elementData);
    }
    native void rsnElementGetSubElements(long con, long id,
                                         long[] IDs, String[] names, int[] arraySizes);
    synchronized void nElementGetSubElements(long id, long[] IDs, String[] names, int[] arraySizes) {
        validate();
        rsnElementGetSubElements(mContext, id, IDs, names, arraySizes);
    }

    native long rsnTypeCreate(long con, long eid, int x, int y, int z, boolean mips, boolean faces, int yuv);
    synchronized long nTypeCreate(long eid, int x, int y, int z, boolean mips, boolean faces, int yuv) {
        validate();
        return rsnTypeCreate(mContext, eid, x, y, z, mips, faces, yuv);
    }
    native void rsnTypeGetNativeData(long con, long id, long[] typeData);
    synchronized void nTypeGetNativeData(long id, long[] typeData) {
        validate();
        rsnTypeGetNativeData(mContext, id, typeData);
    }

    native long rsnAllocationCreateTyped(long con, long type, int mip, int usage, long pointer);
    synchronized long nAllocationCreateTyped(long type, int mip, int usage, long pointer) {
        validate();
        return rsnAllocationCreateTyped(mContext, type, mip, usage, pointer);
    }
    native long rsnAllocationCreateFromBitmap(long con, long type, int mip, long bitmapHandle,
                int usage);
    synchronized long nAllocationCreateFromBitmap(long type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCreateFromBitmap(mContext, type, mip, bmp.getNativeInstance(), usage);
    }

    native long rsnAllocationCreateBitmapBackedAllocation(long con, long type, int mip, long bitmapHandle,
                int usage);
    synchronized long nAllocationCreateBitmapBackedAllocation(long type, int mip, Bitmap bmp,
                int usage) {
        validate();
        return rsnAllocationCreateBitmapBackedAllocation(mContext, type, mip, bmp.getNativeInstance(),
                usage);
    }

    native long rsnAllocationCubeCreateFromBitmap(long con, long type, int mip, long bitmapHandle,
                int usage);
    synchronized long nAllocationCubeCreateFromBitmap(long type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCubeCreateFromBitmap(mContext, type, mip, bmp.getNativeInstance(),
                usage);
    }
    native long  rsnAllocationCreateBitmapRef(long con, long type, long bitmapHandle);
    synchronized long nAllocationCreateBitmapRef(long type, Bitmap bmp) {
        validate();
        return rsnAllocationCreateBitmapRef(mContext, type, bmp.getNativeInstance());
    }
    native long  rsnAllocationCreateFromAssetStream(long con, int mips, int assetStream, int usage);
    synchronized long nAllocationCreateFromAssetStream(int mips, int assetStream, int usage) {
        validate();
        return rsnAllocationCreateFromAssetStream(mContext, mips, assetStream, usage);
    }

    native void  rsnAllocationCopyToBitmap(long con, long alloc, long bitmapHandle);
    synchronized void nAllocationCopyToBitmap(long alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyToBitmap(mContext, alloc, bmp.getNativeInstance());
    }

    native void rsnAllocationSyncAll(long con, long alloc, int src);
    synchronized void nAllocationSyncAll(long alloc, int src) {
        validate();
        rsnAllocationSyncAll(mContext, alloc, src);
    }

    native ByteBuffer rsnAllocationGetByteBuffer(long con, long alloc, long[] stride,
                int xBytesSize, int dimY, int dimZ);
    synchronized ByteBuffer nAllocationGetByteBuffer(long alloc, long[] stride, int xBytesSize,
                int dimY, int dimZ) {
        validate();
        return rsnAllocationGetByteBuffer(mContext, alloc, stride, xBytesSize, dimY, dimZ);
    }

    native void rsnAllocationSetupBufferQueue(long con, long alloc, int numAlloc);
    synchronized void nAllocationSetupBufferQueue(long alloc, int numAlloc) {
        validate();
        rsnAllocationSetupBufferQueue(mContext, alloc, numAlloc);
    }
    native void rsnAllocationShareBufferQueue(long con, long alloc1, long alloc2);
    synchronized void nAllocationShareBufferQueue(long alloc1, long alloc2) {
        validate();
        rsnAllocationShareBufferQueue(mContext, alloc1, alloc2);
    }
    native Surface rsnAllocationGetSurface(long con, long alloc);
    synchronized Surface nAllocationGetSurface(long alloc) {
        validate();
        return rsnAllocationGetSurface(mContext, alloc);
    }
    native void rsnAllocationSetSurface(long con, long alloc, Surface sur);
    synchronized void nAllocationSetSurface(long alloc, Surface sur) {
        validate();
        rsnAllocationSetSurface(mContext, alloc, sur);
    }
    native void rsnAllocationIoSend(long con, long alloc);
    synchronized void nAllocationIoSend(long alloc) {
        validate();
        rsnAllocationIoSend(mContext, alloc);
    }
    native long rsnAllocationIoReceive(long con, long alloc);
    synchronized long nAllocationIoReceive(long alloc) {
        validate();
        return rsnAllocationIoReceive(mContext, alloc);
    }

    native void rsnAllocationGenerateMipmaps(long con, long alloc);
    synchronized void nAllocationGenerateMipmaps(long alloc) {
        validate();
        rsnAllocationGenerateMipmaps(mContext, alloc);
    }
    native void  rsnAllocationCopyFromBitmap(long con, long alloc, long bitmapHandle);
    synchronized void nAllocationCopyFromBitmap(long alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyFromBitmap(mContext, alloc, bmp.getNativeInstance());
    }


    native void rsnAllocationData1D(long con, long id, int off, int mip, int count, Object d, int sizeBytes, int dt,
                                    int mSize, boolean usePadding);
    synchronized void nAllocationData1D(long id, int off, int mip, int count, Object d, int sizeBytes, Element.DataType dt,
                                        int mSize, boolean usePadding) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes, dt.mID, mSize, usePadding);
    }

    native void rsnAllocationElementData(long con,long id, int xoff, int yoff, int zoff, int mip, int compIdx, byte[] d, int sizeBytes);
    synchronized void nAllocationElementData(long id, int xoff, int yoff, int zoff, int mip, int compIdx, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationElementData(mContext, id, xoff, yoff, zoff, mip, compIdx, d, sizeBytes);
    }

    native void rsnAllocationData2D(long con,
                                    long dstAlloc, int dstXoff, int dstYoff,
                                    int dstMip, int dstFace,
                                    int width, int height,
                                    long srcAlloc, int srcXoff, int srcYoff,
                                    int srcMip, int srcFace);
    synchronized void nAllocationData2D(long dstAlloc, int dstXoff, int dstYoff,
                                        int dstMip, int dstFace,
                                        int width, int height,
                                        long srcAlloc, int srcXoff, int srcYoff,
                                        int srcMip, int srcFace) {
        validate();
        rsnAllocationData2D(mContext,
                            dstAlloc, dstXoff, dstYoff,
                            dstMip, dstFace,
                            width, height,
                            srcAlloc, srcXoff, srcYoff,
                            srcMip, srcFace);
    }

    native void rsnAllocationData2D(long con, long id, int xoff, int yoff, int mip, int face,
                                    int w, int h, Object d, int sizeBytes, int dt,
                                    int mSize, boolean usePadding);
    synchronized void nAllocationData2D(long id, int xoff, int yoff, int mip, int face,
                                        int w, int h, Object d, int sizeBytes, Element.DataType dt,
                                        int mSize, boolean usePadding) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes, dt.mID, mSize, usePadding);
    }

    native void rsnAllocationData2D(long con, long id, int xoff, int yoff, int mip, int face, Bitmap b);
    synchronized void nAllocationData2D(long id, int xoff, int yoff, int mip, int face, Bitmap b) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, b);
    }

    native void rsnAllocationData3D(long con,
                                    long dstAlloc, int dstXoff, int dstYoff, int dstZoff,
                                    int dstMip,
                                    int width, int height, int depth,
                                    long srcAlloc, int srcXoff, int srcYoff, int srcZoff,
                                    int srcMip);
    synchronized void nAllocationData3D(long dstAlloc, int dstXoff, int dstYoff, int dstZoff,
                                        int dstMip,
                                        int width, int height, int depth,
                                        long srcAlloc, int srcXoff, int srcYoff, int srcZoff,
                                        int srcMip) {
        validate();
        rsnAllocationData3D(mContext,
                            dstAlloc, dstXoff, dstYoff, dstZoff,
                            dstMip, width, height, depth,
                            srcAlloc, srcXoff, srcYoff, srcZoff, srcMip);
    }

    native void rsnAllocationData3D(long con, long id, int xoff, int yoff, int zoff, int mip,
                                    int w, int h, int depth, Object d, int sizeBytes, int dt,
                                    int mSize, boolean usePadding);
    synchronized void nAllocationData3D(long id, int xoff, int yoff, int zoff, int mip,
                                        int w, int h, int depth, Object d, int sizeBytes, Element.DataType dt,
                                        int mSize, boolean usePadding) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes,
                            dt.mID, mSize, usePadding);
    }

    native void rsnAllocationRead(long con, long id, Object d, int dt, int mSize, boolean usePadding);
    synchronized void nAllocationRead(long id, Object d, Element.DataType dt, int mSize, boolean usePadding) {
        validate();
        rsnAllocationRead(mContext, id, d, dt.mID, mSize, usePadding);
    }

    native void rsnAllocationRead1D(long con, long id, int off, int mip, int count, Object d,
                                    int sizeBytes, int dt, int mSize, boolean usePadding);
    synchronized void nAllocationRead1D(long id, int off, int mip, int count, Object d,
                                        int sizeBytes, Element.DataType dt, int mSize, boolean usePadding) {
        validate();
        rsnAllocationRead1D(mContext, id, off, mip, count, d, sizeBytes, dt.mID, mSize, usePadding);
    }

    native void rsnAllocationElementRead(long con,long id, int xoff, int yoff, int zoff,
                                         int mip, int compIdx, byte[] d, int sizeBytes);
    synchronized void nAllocationElementRead(long id, int xoff, int yoff, int zoff,
                                             int mip, int compIdx, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationElementRead(mContext, id, xoff, yoff, zoff, mip, compIdx, d, sizeBytes);
    }

    native void rsnAllocationRead2D(long con, long id, int xoff, int yoff, int mip, int face,
                                    int w, int h, Object d, int sizeBytes, int dt,
                                    int mSize, boolean usePadding);
    synchronized void nAllocationRead2D(long id, int xoff, int yoff, int mip, int face,
                                        int w, int h, Object d, int sizeBytes, Element.DataType dt,
                                        int mSize, boolean usePadding) {
        validate();
        rsnAllocationRead2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes, dt.mID, mSize, usePadding);
    }

    native void rsnAllocationRead3D(long con, long id, int xoff, int yoff, int zoff, int mip,
                                    int w, int h, int depth, Object d, int sizeBytes, int dt,
                                    int mSize, boolean usePadding);
    synchronized void nAllocationRead3D(long id, int xoff, int yoff, int zoff, int mip,
                                        int w, int h, int depth, Object d, int sizeBytes, Element.DataType dt,
                                        int mSize, boolean usePadding) {
        validate();
        rsnAllocationRead3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes, dt.mID, mSize, usePadding);
    }

    native long  rsnAllocationGetType(long con, long id);
    synchronized long nAllocationGetType(long id) {
        validate();
        return rsnAllocationGetType(mContext, id);
    }

    native void rsnAllocationResize1D(long con, long id, int dimX);
    synchronized void nAllocationResize1D(long id, int dimX) {
        validate();
        rsnAllocationResize1D(mContext, id, dimX);
    }

    native long  rsnAllocationAdapterCreate(long con, long allocId, long typeId);
    synchronized long nAllocationAdapterCreate(long allocId, long typeId) {
        validate();
        return rsnAllocationAdapterCreate(mContext, allocId, typeId);
    }

    native void  rsnAllocationAdapterOffset(long con, long id, int x, int y, int z,
                                            int mip, int face, int a1, int a2, int a3, int a4);
    synchronized void nAllocationAdapterOffset(long id, int x, int y, int z,
                                               int mip, int face, int a1, int a2, int a3, int a4) {
        validate();
        rsnAllocationAdapterOffset(mContext, id, x, y, z, mip, face, a1, a2, a3, a4);
    }

    native long rsnFileA3DCreateFromAssetStream(long con, long assetStream);
    synchronized long nFileA3DCreateFromAssetStream(long assetStream) {
        validate();
        return rsnFileA3DCreateFromAssetStream(mContext, assetStream);
    }
    native long rsnFileA3DCreateFromFile(long con, String path);
    synchronized long nFileA3DCreateFromFile(String path) {
        validate();
        return rsnFileA3DCreateFromFile(mContext, path);
    }
    native long rsnFileA3DCreateFromAsset(long con, AssetManager mgr, String path);
    synchronized long nFileA3DCreateFromAsset(AssetManager mgr, String path) {
        validate();
        return rsnFileA3DCreateFromAsset(mContext, mgr, path);
    }
    native int  rsnFileA3DGetNumIndexEntries(long con, long fileA3D);
    synchronized int nFileA3DGetNumIndexEntries(long fileA3D) {
        validate();
        return rsnFileA3DGetNumIndexEntries(mContext, fileA3D);
    }
    native void rsnFileA3DGetIndexEntries(long con, long fileA3D, int numEntries, int[] IDs, String[] names);
    synchronized void nFileA3DGetIndexEntries(long fileA3D, int numEntries, int[] IDs, String[] names) {
        validate();
        rsnFileA3DGetIndexEntries(mContext, fileA3D, numEntries, IDs, names);
    }
    native long rsnFileA3DGetEntryByIndex(long con, long fileA3D, int index);
    synchronized long nFileA3DGetEntryByIndex(long fileA3D, int index) {
        validate();
        return rsnFileA3DGetEntryByIndex(mContext, fileA3D, index);
    }

    native long rsnFontCreateFromFile(long con, String fileName, float size, int dpi);
    synchronized long nFontCreateFromFile(String fileName, float size, int dpi) {
        validate();
        return rsnFontCreateFromFile(mContext, fileName, size, dpi);
    }
    native long rsnFontCreateFromAssetStream(long con, String name, float size, int dpi, long assetStream);
    synchronized long nFontCreateFromAssetStream(String name, float size, int dpi, long assetStream) {
        validate();
        return rsnFontCreateFromAssetStream(mContext, name, size, dpi, assetStream);
    }
    native long rsnFontCreateFromAsset(long con, AssetManager mgr, String path, float size, int dpi);
    synchronized long nFontCreateFromAsset(AssetManager mgr, String path, float size, int dpi) {
        validate();
        return rsnFontCreateFromAsset(mContext, mgr, path, size, dpi);
    }


    native void rsnScriptBindAllocation(long con, long script, long alloc, int slot);
    synchronized void nScriptBindAllocation(long script, long alloc, int slot) {
        validate();
        rsnScriptBindAllocation(mContext, script, alloc, slot);
    }
    native void rsnScriptSetTimeZone(long con, long script, byte[] timeZone);
    synchronized void nScriptSetTimeZone(long script, byte[] timeZone) {
        validate();
        rsnScriptSetTimeZone(mContext, script, timeZone);
    }
    native void rsnScriptInvoke(long con, long id, int slot);
    synchronized void nScriptInvoke(long id, int slot) {
        validate();
        rsnScriptInvoke(mContext, id, slot);
    }

    native void rsnScriptForEach(long con, long id, int slot, long[] ains,
                                 long aout, byte[] params, int[] limits);

    synchronized void nScriptForEach(long id, int slot, long[] ains, long aout,
                                     byte[] params, int[] limits) {
        validate();
        rsnScriptForEach(mContext, id, slot, ains, aout, params, limits);
    }

    native void rsnScriptReduce(long con, long id, int slot, long[] ains,
                                long aout, int[] limits);
    synchronized void nScriptReduce(long id, int slot, long ains[], long aout,
                                    int[] limits) {
        validate();
        rsnScriptReduce(mContext, id, slot, ains, aout, limits);
    }

    native void rsnScriptInvokeV(long con, long id, int slot, byte[] params);
    synchronized void nScriptInvokeV(long id, int slot, byte[] params) {
        validate();
        rsnScriptInvokeV(mContext, id, slot, params);
    }

    native void rsnScriptSetVarI(long con, long id, int slot, int val);
    synchronized void nScriptSetVarI(long id, int slot, int val) {
        validate();
        rsnScriptSetVarI(mContext, id, slot, val);
    }
    native int rsnScriptGetVarI(long con, long id, int slot);
    synchronized int nScriptGetVarI(long id, int slot) {
        validate();
        return rsnScriptGetVarI(mContext, id, slot);
    }

    native void rsnScriptSetVarJ(long con, long id, int slot, long val);
    synchronized void nScriptSetVarJ(long id, int slot, long val) {
        validate();
        rsnScriptSetVarJ(mContext, id, slot, val);
    }
    native long rsnScriptGetVarJ(long con, long id, int slot);
    synchronized long nScriptGetVarJ(long id, int slot) {
        validate();
        return rsnScriptGetVarJ(mContext, id, slot);
    }

    native void rsnScriptSetVarF(long con, long id, int slot, float val);
    synchronized void nScriptSetVarF(long id, int slot, float val) {
        validate();
        rsnScriptSetVarF(mContext, id, slot, val);
    }
    native float rsnScriptGetVarF(long con, long id, int slot);
    synchronized float nScriptGetVarF(long id, int slot) {
        validate();
        return rsnScriptGetVarF(mContext, id, slot);
    }
    native void rsnScriptSetVarD(long con, long id, int slot, double val);
    synchronized void nScriptSetVarD(long id, int slot, double val) {
        validate();
        rsnScriptSetVarD(mContext, id, slot, val);
    }
    native double rsnScriptGetVarD(long con, long id, int slot);
    synchronized double nScriptGetVarD(long id, int slot) {
        validate();
        return rsnScriptGetVarD(mContext, id, slot);
    }
    native void rsnScriptSetVarV(long con, long id, int slot, byte[] val);
    synchronized void nScriptSetVarV(long id, int slot, byte[] val) {
        validate();
        rsnScriptSetVarV(mContext, id, slot, val);
    }
    native void rsnScriptGetVarV(long con, long id, int slot, byte[] val);
    synchronized void nScriptGetVarV(long id, int slot, byte[] val) {
        validate();
        rsnScriptGetVarV(mContext, id, slot, val);
    }
    native void rsnScriptSetVarVE(long con, long id, int slot, byte[] val,
                                  long e, int[] dims);
    synchronized void nScriptSetVarVE(long id, int slot, byte[] val,
                                      long e, int[] dims) {
        validate();
        rsnScriptSetVarVE(mContext, id, slot, val, e, dims);
    }
    native void rsnScriptSetVarObj(long con, long id, int slot, long val);
    synchronized void nScriptSetVarObj(long id, int slot, long val) {
        validate();
        rsnScriptSetVarObj(mContext, id, slot, val);
    }

    native long rsnScriptCCreate(long con, String resName, String cacheDir,
                                 byte[] script, int length);
    @UnsupportedAppUsage
    synchronized long nScriptCCreate(String resName, String cacheDir, byte[] script, int length) {
        validate();
        return rsnScriptCCreate(mContext, resName, cacheDir, script, length);
    }

    native long rsnScriptIntrinsicCreate(long con, int id, long eid);
    synchronized long nScriptIntrinsicCreate(int id, long eid) {
        validate();
        return rsnScriptIntrinsicCreate(mContext, id, eid);
    }

    native long  rsnScriptKernelIDCreate(long con, long sid, int slot, int sig);
    synchronized long nScriptKernelIDCreate(long sid, int slot, int sig) {
        validate();
        return rsnScriptKernelIDCreate(mContext, sid, slot, sig);
    }

    native long  rsnScriptInvokeIDCreate(long con, long sid, int slot);
    synchronized long nScriptInvokeIDCreate(long sid, int slot) {
        validate();
        return rsnScriptInvokeIDCreate(mContext, sid, slot);
    }

    native long  rsnScriptFieldIDCreate(long con, long sid, int slot);
    synchronized long nScriptFieldIDCreate(long sid, int slot) {
        validate();
        return rsnScriptFieldIDCreate(mContext, sid, slot);
    }

    native long rsnScriptGroupCreate(long con, long[] kernels, long[] src, long[] dstk, long[] dstf, long[] types);
    synchronized long nScriptGroupCreate(long[] kernels, long[] src, long[] dstk, long[] dstf, long[] types) {
        validate();
        return rsnScriptGroupCreate(mContext, kernels, src, dstk, dstf, types);
    }

    native void rsnScriptGroupSetInput(long con, long group, long kernel, long alloc);
    synchronized void nScriptGroupSetInput(long group, long kernel, long alloc) {
        validate();
        rsnScriptGroupSetInput(mContext, group, kernel, alloc);
    }

    native void rsnScriptGroupSetOutput(long con, long group, long kernel, long alloc);
    synchronized void nScriptGroupSetOutput(long group, long kernel, long alloc) {
        validate();
        rsnScriptGroupSetOutput(mContext, group, kernel, alloc);
    }

    native void rsnScriptGroupExecute(long con, long group);
    synchronized void nScriptGroupExecute(long group) {
        validate();
        rsnScriptGroupExecute(mContext, group);
    }

    native long  rsnSamplerCreate(long con, int magFilter, int minFilter,
                                 int wrapS, int wrapT, int wrapR, float aniso);
    synchronized long nSamplerCreate(int magFilter, int minFilter,
                                 int wrapS, int wrapT, int wrapR, float aniso) {
        validate();
        return rsnSamplerCreate(mContext, magFilter, minFilter, wrapS, wrapT, wrapR, aniso);
    }

    native long rsnProgramStoreCreate(long con, boolean r, boolean g, boolean b, boolean a,
                                      boolean depthMask, boolean dither,
                                      int srcMode, int dstMode, int depthFunc);
    synchronized long nProgramStoreCreate(boolean r, boolean g, boolean b, boolean a,
                                         boolean depthMask, boolean dither,
                                         int srcMode, int dstMode, int depthFunc) {
        validate();
        return rsnProgramStoreCreate(mContext, r, g, b, a, depthMask, dither, srcMode,
                                     dstMode, depthFunc);
    }

    native long rsnProgramRasterCreate(long con, boolean pointSprite, int cullMode);
    synchronized long nProgramRasterCreate(boolean pointSprite, int cullMode) {
        validate();
        return rsnProgramRasterCreate(mContext, pointSprite, cullMode);
    }

    native void rsnProgramBindConstants(long con, long pv, int slot, long mID);
    synchronized void nProgramBindConstants(long pv, int slot, long mID) {
        validate();
        rsnProgramBindConstants(mContext, pv, slot, mID);
    }
    native void rsnProgramBindTexture(long con, long vpf, int slot, long a);
    synchronized void nProgramBindTexture(long vpf, int slot, long a) {
        validate();
        rsnProgramBindTexture(mContext, vpf, slot, a);
    }
    native void rsnProgramBindSampler(long con, long vpf, int slot, long s);
    synchronized void nProgramBindSampler(long vpf, int slot, long s) {
        validate();
        rsnProgramBindSampler(mContext, vpf, slot, s);
    }
    native long rsnProgramFragmentCreate(long con, String shader, String[] texNames, long[] params);
    synchronized long nProgramFragmentCreate(String shader, String[] texNames, long[] params) {
        validate();
        return rsnProgramFragmentCreate(mContext, shader, texNames, params);
    }
    native long rsnProgramVertexCreate(long con, String shader, String[] texNames, long[] params);
    synchronized long nProgramVertexCreate(String shader, String[] texNames, long[] params) {
        validate();
        return rsnProgramVertexCreate(mContext, shader, texNames, params);
    }

    native long rsnMeshCreate(long con, long[] vtx, long[] idx, int[] prim);
    synchronized long nMeshCreate(long[] vtx, long[] idx, int[] prim) {
        validate();
        return rsnMeshCreate(mContext, vtx, idx, prim);
    }
    native int  rsnMeshGetVertexBufferCount(long con, long id);
    synchronized int nMeshGetVertexBufferCount(long id) {
        validate();
        return rsnMeshGetVertexBufferCount(mContext, id);
    }
    native int  rsnMeshGetIndexCount(long con, long id);
    synchronized int nMeshGetIndexCount(long id) {
        validate();
        return rsnMeshGetIndexCount(mContext, id);
    }
    native void rsnMeshGetVertices(long con, long id, long[] vtxIds, int vtxIdCount);
    synchronized void nMeshGetVertices(long id, long[] vtxIds, int vtxIdCount) {
        validate();
        rsnMeshGetVertices(mContext, id, vtxIds, vtxIdCount);
    }
    native void rsnMeshGetIndices(long con, long id, long[] idxIds, int[] primitives, int vtxIdCount);
    synchronized void nMeshGetIndices(long id, long[] idxIds, int[] primitives, int vtxIdCount) {
        validate();
        rsnMeshGetIndices(mContext, id, idxIds, primitives, vtxIdCount);
    }

    native void rsnScriptIntrinsicBLAS_Single(long con, long id, int func, int TransA,
                                              int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                              float alpha, long A, long B, float beta, long C, int incX, int incY,
                                              int KL, int KU);
    synchronized void nScriptIntrinsicBLAS_Single(long id, int func, int TransA,
                                                  int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                                  float alpha, long A, long B, float beta, long C, int incX, int incY,
                                                  int KL, int KU) {
        validate();
        rsnScriptIntrinsicBLAS_Single(mContext, id, func, TransA, TransB, Side, Uplo, Diag, M, N, K, alpha, A, B, beta, C, incX, incY, KL, KU);
    }

    native void rsnScriptIntrinsicBLAS_Double(long con, long id, int func, int TransA,
                                              int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                              double alpha, long A, long B, double beta, long C, int incX, int incY,
                                              int KL, int KU);
    synchronized void nScriptIntrinsicBLAS_Double(long id, int func, int TransA,
                                                  int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                                  double alpha, long A, long B, double beta, long C, int incX, int incY,
                                                  int KL, int KU) {
        validate();
        rsnScriptIntrinsicBLAS_Double(mContext, id, func, TransA, TransB, Side, Uplo, Diag, M, N, K, alpha, A, B, beta, C, incX, incY, KL, KU);
    }

    native void rsnScriptIntrinsicBLAS_Complex(long con, long id, int func, int TransA,
                                               int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                               float alphaX, float alphaY, long A, long B, float betaX, float betaY, long C, int incX, int incY,
                                               int KL, int KU);
    synchronized void nScriptIntrinsicBLAS_Complex(long id, int func, int TransA,
                                                   int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                                   float alphaX, float alphaY, long A, long B, float betaX, float betaY, long C, int incX, int incY,
                                                   int KL, int KU) {
        validate();
        rsnScriptIntrinsicBLAS_Complex(mContext, id, func, TransA, TransB, Side, Uplo, Diag, M, N, K, alphaX, alphaY, A, B, betaX, betaY, C, incX, incY, KL, KU);
    }

    native void rsnScriptIntrinsicBLAS_Z(long con, long id, int func, int TransA,
                                         int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                         double alphaX, double alphaY, long A, long B, double betaX, double betaY, long C, int incX, int incY,
                                         int KL, int KU);
    synchronized void nScriptIntrinsicBLAS_Z(long id, int func, int TransA,
                                             int TransB, int Side, int Uplo, int Diag, int M, int N, int K,
                                             double alphaX, double alphaY, long A, long B, double betaX, double betaY, long C, int incX, int incY,
                                             int KL, int KU) {
        validate();
        rsnScriptIntrinsicBLAS_Z(mContext, id, func, TransA, TransB, Side, Uplo, Diag, M, N, K, alphaX, alphaY, A, B, betaX, betaY, C, incX, incY, KL, KU);
    }

    native void rsnScriptIntrinsicBLAS_BNNM(long con, long id, int M, int N, int K,
                                             long A, int a_offset, long B, int b_offset, long C, int c_offset,
                                             int c_mult_int);
    synchronized void nScriptIntrinsicBLAS_BNNM(long id, int M, int N, int K,
                                             long A, int a_offset, long B, int b_offset, long C, int c_offset,
                                             int c_mult_int) {
        validate();
        rsnScriptIntrinsicBLAS_BNNM(mContext, id, M, N, K, A, a_offset, B, b_offset, C, c_offset, c_mult_int);
    }



    long     mContext;
    private boolean mDestroyed = false;

    @SuppressWarnings({"FieldCanBeLocal"})
    MessageThread mMessageThread;

    volatile Element mElement_U8;
    volatile Element mElement_I8;
    volatile Element mElement_U16;
    volatile Element mElement_I16;
    volatile Element mElement_U32;
    volatile Element mElement_I32;
    volatile Element mElement_U64;
    volatile Element mElement_I64;
    volatile Element mElement_F16;
    volatile Element mElement_F32;
    volatile Element mElement_F64;
    volatile Element mElement_BOOLEAN;

    volatile Element mElement_ELEMENT;
    volatile Element mElement_TYPE;
    volatile Element mElement_ALLOCATION;
    volatile Element mElement_SAMPLER;
    volatile Element mElement_SCRIPT;
    volatile Element mElement_MESH;
    volatile Element mElement_PROGRAM_FRAGMENT;
    volatile Element mElement_PROGRAM_VERTEX;
    volatile Element mElement_PROGRAM_RASTER;
    volatile Element mElement_PROGRAM_STORE;
    volatile Element mElement_FONT;

    volatile Element mElement_A_8;
    volatile Element mElement_RGB_565;
    volatile Element mElement_RGB_888;
    volatile Element mElement_RGBA_5551;
    volatile Element mElement_RGBA_4444;
    volatile Element mElement_RGBA_8888;

    volatile Element mElement_HALF_2;
    volatile Element mElement_HALF_3;
    volatile Element mElement_HALF_4;

    volatile Element mElement_FLOAT_2;
    volatile Element mElement_FLOAT_3;
    volatile Element mElement_FLOAT_4;

    volatile Element mElement_DOUBLE_2;
    volatile Element mElement_DOUBLE_3;
    volatile Element mElement_DOUBLE_4;

    volatile Element mElement_UCHAR_2;
    volatile Element mElement_UCHAR_3;
    volatile Element mElement_UCHAR_4;

    volatile Element mElement_CHAR_2;
    volatile Element mElement_CHAR_3;
    volatile Element mElement_CHAR_4;

    volatile Element mElement_USHORT_2;
    volatile Element mElement_USHORT_3;
    volatile Element mElement_USHORT_4;

    volatile Element mElement_SHORT_2;
    volatile Element mElement_SHORT_3;
    volatile Element mElement_SHORT_4;

    volatile Element mElement_UINT_2;
    volatile Element mElement_UINT_3;
    volatile Element mElement_UINT_4;

    volatile Element mElement_INT_2;
    volatile Element mElement_INT_3;
    volatile Element mElement_INT_4;

    volatile Element mElement_ULONG_2;
    volatile Element mElement_ULONG_3;
    volatile Element mElement_ULONG_4;

    volatile Element mElement_LONG_2;
    volatile Element mElement_LONG_3;
    volatile Element mElement_LONG_4;

    volatile Element mElement_YUV;

    volatile Element mElement_MATRIX_4X4;
    volatile Element mElement_MATRIX_3X3;
    volatile Element mElement_MATRIX_2X2;

    volatile Sampler mSampler_CLAMP_NEAREST;
    volatile Sampler mSampler_CLAMP_LINEAR;
    volatile Sampler mSampler_CLAMP_LINEAR_MIP_LINEAR;
    volatile Sampler mSampler_WRAP_NEAREST;
    volatile Sampler mSampler_WRAP_LINEAR;
    volatile Sampler mSampler_WRAP_LINEAR_MIP_LINEAR;
    volatile Sampler mSampler_MIRRORED_REPEAT_NEAREST;
    volatile Sampler mSampler_MIRRORED_REPEAT_LINEAR;
    volatile Sampler mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR;

    ProgramStore mProgramStore_BLEND_NONE_DEPTH_TEST;
    ProgramStore mProgramStore_BLEND_NONE_DEPTH_NO_DEPTH;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_TEST;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_NO_DEPTH;

    ProgramRaster mProgramRaster_CULL_BACK;
    ProgramRaster mProgramRaster_CULL_FRONT;
    ProgramRaster mProgramRaster_CULL_NONE;

    ///////////////////////////////////////////////////////////////////////////////////
    //

    /**
     * The base class from which an application should derive in order
     * to receive RS messages from scripts. When a script calls {@code
     * rsSendToClient}, the data fields will be filled, and the run
     * method will be called on a separate thread.  This will occur
     * some time after {@code rsSendToClient} completes in the script,
     * as {@code rsSendToClient} is asynchronous. Message handlers are
     * not guaranteed to have completed when {@link
     * android.renderscript.RenderScript#finish} returns.
     *
     */
    public static class RSMessageHandler implements Runnable {
        protected int[] mData;
        protected int mID;
        protected int mLength;
        public void run() {
        }
    }
    /**
     * If an application is expecting messages, it should set this
     * field to an instance of {@link RSMessageHandler}.  This
     * instance will receive all the user messages sent from {@code
     * sendToClient} by scripts from this context.
     *
     */
    @UnsupportedAppUsage
    RSMessageHandler mMessageCallback = null;

    public void setMessageHandler(RSMessageHandler msg) {
        mMessageCallback = msg;
    }
    public RSMessageHandler getMessageHandler() {
        return mMessageCallback;
    }

    /**
     * Place a message into the message queue to be sent back to the message
     * handler once all previous commands have been executed.
     *
     * @param id
     * @param data
     */
    public void sendMessage(int id, int[] data) {
        nContextSendMessage(id, data);
    }

    /**
     * The runtime error handler base class.  An application should derive from this class
     * if it wishes to install an error handler.  When errors occur at runtime,
     * the fields in this class will be filled, and the run method will be called.
     *
     */
    public static class RSErrorHandler implements Runnable {
        protected String mErrorMessage;
        protected int mErrorNum;
        public void run() {
        }
    }

    /**
     * Application Error handler.  All runtime errors will be dispatched to the
     * instance of RSAsyncError set here.  If this field is null a
     * {@link RSRuntimeException} will instead be thrown with details about the error.
     * This will cause program termaination.
     *
     */
    RSErrorHandler mErrorCallback = null;

    public void setErrorHandler(RSErrorHandler msg) {
        mErrorCallback = msg;
    }
    public RSErrorHandler getErrorHandler() {
        return mErrorCallback;
    }

    /**
     * RenderScript worker thread priority enumeration.  The default value is
     * NORMAL.  Applications wishing to do background processing should set
     * their priority to LOW to avoid starving forground processes.
     */
    public enum Priority {
        // These values used to represent official thread priority values
        // now they are simply enums to be used by the runtime side
        LOW (15),
        NORMAL (-8);

        int mID;
        Priority(int id) {
            mID = id;
        }
    }

    void validateObject(BaseObj o) {
        if (o != null) {
            if (o.mRS != this) {
                throw new RSIllegalArgumentException("Attempting to use an object across contexts.");
            }
        }
    }

    @UnsupportedAppUsage
    void validate() {
        if (mContext == 0) {
            throw new RSInvalidStateException("Calling RS with no Context active.");
        }
    }


    /**
     * Change the priority of the worker threads for this context.
     *
     * @param p New priority to be set.
     */
    public void setPriority(Priority p) {
        validate();
        nContextSetPriority(p.mID);
    }

    static class MessageThread extends Thread {
        RenderScript mRS;
        boolean mRun = true;
        int[] mAuxData = new int[2];

        static final int RS_MESSAGE_TO_CLIENT_NONE = 0;
        static final int RS_MESSAGE_TO_CLIENT_EXCEPTION = 1;
        static final int RS_MESSAGE_TO_CLIENT_RESIZE = 2;
        static final int RS_MESSAGE_TO_CLIENT_ERROR = 3;
        static final int RS_MESSAGE_TO_CLIENT_USER = 4;
        static final int RS_MESSAGE_TO_CLIENT_NEW_BUFFER = 5;

        static final int RS_ERROR_FATAL_DEBUG = 0x0800;
        static final int RS_ERROR_FATAL_UNKNOWN = 0x1000;

        MessageThread(RenderScript rs) {
            super("RSMessageThread");
            mRS = rs;

        }

        public void run() {
            // This function is a temporary solution.  The final solution will
            // used typed allocations where the message id is the type indicator.
            int[] rbuf = new int[16];
            mRS.nContextInitToClient(mRS.mContext);
            while(mRun) {
                rbuf[0] = 0;
                int msg = mRS.nContextPeekMessage(mRS.mContext, mAuxData);
                int size = mAuxData[1];
                int subID = mAuxData[0];

                if (msg == RS_MESSAGE_TO_CLIENT_USER) {
                    if ((size>>2) >= rbuf.length) {
                        rbuf = new int[(size + 3) >> 2];
                    }
                    if (mRS.nContextGetUserMessage(mRS.mContext, rbuf) !=
                        RS_MESSAGE_TO_CLIENT_USER) {
                        throw new RSDriverException("Error processing message from RenderScript.");
                    }

                    if(mRS.mMessageCallback != null) {
                        mRS.mMessageCallback.mData = rbuf;
                        mRS.mMessageCallback.mID = subID;
                        mRS.mMessageCallback.mLength = size;
                        mRS.mMessageCallback.run();
                    } else {
                        throw new RSInvalidStateException("Received a message from the script with no message handler installed.");
                    }
                    continue;
                }

                if (msg == RS_MESSAGE_TO_CLIENT_ERROR) {
                    String e = mRS.nContextGetErrorMessage(mRS.mContext);

                    // Throw RSRuntimeException under the following conditions:
                    //
                    // 1) It is an unknown fatal error.
                    // 2) It is a debug fatal error, and we are not in a
                    //    debug context.
                    // 3) It is a debug fatal error, and we do not have an
                    //    error callback.
                    if (subID >= RS_ERROR_FATAL_UNKNOWN ||
                        (subID >= RS_ERROR_FATAL_DEBUG &&
                         (mRS.mContextType != ContextType.DEBUG ||
                          mRS.mErrorCallback == null))) {
                        throw new RSRuntimeException("Fatal error " + subID + ", details: " + e);
                    }

                    if(mRS.mErrorCallback != null) {
                        mRS.mErrorCallback.mErrorMessage = e;
                        mRS.mErrorCallback.mErrorNum = subID;
                        mRS.mErrorCallback.run();
                    } else {
                        android.util.Log.e(LOG_TAG, "non fatal RS error, " + e);
                        // Do not throw here. In these cases, we do not have
                        // a fatal error.
                    }
                    continue;
                }

                if (msg == RS_MESSAGE_TO_CLIENT_NEW_BUFFER) {
                    if (mRS.nContextGetUserMessage(mRS.mContext, rbuf) !=
                        RS_MESSAGE_TO_CLIENT_NEW_BUFFER) {
                        throw new RSDriverException("Error processing message from RenderScript.");
                    }
                    long bufferID = ((long)rbuf[1] << 32L) + ((long)rbuf[0] & 0xffffffffL);
                    Allocation.sendBufferNotification(bufferID);
                    continue;
                }

                // 2: teardown.
                // But we want to avoid starving other threads during
                // teardown by yielding until the next line in the destructor
                // can execute to set mRun = false
                try {
                    sleep(1, 0);
                } catch(InterruptedException e) {
                }
            }
            //Log.d(LOG_TAG, "MessageThread exiting.");
        }
    }

    RenderScript(Context ctx) {
        mContextType = ContextType.NORMAL;
        if (ctx != null) {
            mApplicationContext = ctx.getApplicationContext();
        }
        mRWLock = new ReentrantReadWriteLock();
        try {
            registerNativeAllocation.invoke(sRuntime, 4 * 1024 * 1024); // 4MB for GC sake
        } catch (Exception e) {
            Log.e(RenderScript.LOG_TAG, "Couldn't invoke registerNativeAllocation:" + e);
            throw new RSRuntimeException("Couldn't invoke registerNativeAllocation:" + e);
        }

    }

    /**
     * Gets the application context associated with the RenderScript context.
     *
     * @return The application context.
     */
    public final Context getApplicationContext() {
        return mApplicationContext;
    }

    /**
     * Name of the file that holds the object cache.
     */
    private static String mCachePath;

    /**
     * Gets the path to the code cache.
     */
    static synchronized String getCachePath() {
        if (mCachePath == null) {
            final String CACHE_PATH = "com.android.renderscript.cache";
            if (RenderScriptCacheDir.mCacheDir == null) {
                throw new RSRuntimeException("RenderScript code cache directory uninitialized.");
            }
            File f = new File(RenderScriptCacheDir.mCacheDir, CACHE_PATH);
            mCachePath = f.getAbsolutePath();
            f.mkdirs();
        }
        return mCachePath;
    }

    /**
     * Create a RenderScript context.
     *
     * @param ctx The context.
     * @return RenderScript
     */
    private static RenderScript internalCreate(Context ctx, int sdkVersion, ContextType ct, int flags) {
        if (!sInitialized) {
            Log.e(LOG_TAG, "RenderScript.create() called when disabled; someone is likely to crash");
            return null;
        }

        if ((flags & ~(CREATE_FLAG_LOW_LATENCY | CREATE_FLAG_LOW_POWER |
                       CREATE_FLAG_WAIT_FOR_ATTACH)) != 0) {
            throw new RSIllegalArgumentException("Invalid flags passed.");
        }

        RenderScript rs = new RenderScript(ctx);

        long device = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(device, flags, sdkVersion, ct.mID);
        rs.mContextType = ct;
        rs.mContextFlags = flags;
        rs.mContextSdkVersion = sdkVersion;
        if (rs.mContext == 0) {
            throw new RSDriverException("Failed to create RS context.");
        }

        // set up cache directory for entire context
        rs.nContextSetCacheDir(RenderScript.getCachePath());

        rs.mMessageThread = new MessageThread(rs);
        rs.mMessageThread.start();
        return rs;
    }

    /**
     * calls create(ctx, ContextType.NORMAL, CREATE_FLAG_NONE)
     *
     * See documentation for @create for details
     *
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx) {
        return create(ctx, ContextType.NORMAL);
    }

    /**
     * calls create(ctx, ct, CREATE_FLAG_NONE)
     *
     * See documentation for @create for details
     *
     * @param ctx The context.
     * @param ct The type of context to be created.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx, ContextType ct) {
        return create(ctx, ct, CREATE_FLAG_NONE);
    }


    /**
     * Gets or creates a RenderScript context of the specified type.
     *
     * The returned context will be cached for future reuse within
     * the process. When an application is finished using
     * RenderScript it should call releaseAllContexts()
     *
     * A process context is a context designed for easy creation and
     * lifecycle management.  Multiple calls to this function will
     * return the same object provided they are called with the same
     * options.  This allows it to be used any time a RenderScript
     * context is needed.
     *
     * Prior to API 23 this always created a new context.
     *
     * @param ctx The context.
     * @param ct The type of context to be created.
     * @param flags The OR of the CREATE_FLAG_* options desired
     * @return RenderScript
     */
    public static RenderScript create(Context ctx, ContextType ct, int flags) {
        int v = ctx.getApplicationInfo().targetSdkVersion;
        return create(ctx, v, ct, flags);
    }

    /**
     * calls create(ctx, sdkVersion, ContextType.NORMAL, CREATE_FLAG_NONE)
     *
     * Used by the RenderScriptThunker to maintain backward compatibility.
     *
     * @hide
     * @param ctx The context.
     * @param sdkVersion The target SDK Version.
     * @return RenderScript
     */
    @UnsupportedAppUsage
    public static RenderScript create(Context ctx, int sdkVersion) {
        return create(ctx, sdkVersion, ContextType.NORMAL, CREATE_FLAG_NONE);
    }

     /**
     * Gets or creates a RenderScript context of the specified type.
     *
     * @param ctx The context.
     * @param ct The type of context to be created.
     * @param sdkVersion The target SDK Version.
     * @param flags The OR of the CREATE_FLAG_* options desired
     * @return RenderScript
     */
    @UnsupportedAppUsage
    private static RenderScript create(Context ctx, int sdkVersion, ContextType ct, int flags) {
        if (sdkVersion < 23) {
            return internalCreate(ctx, sdkVersion, ct, flags);
        }

        synchronized (mProcessContextList) {
            for (RenderScript prs : mProcessContextList) {
                if ((prs.mContextType == ct) &&
                    (prs.mContextFlags == flags) &&
                    (prs.mContextSdkVersion == sdkVersion)) {

                    return prs;
                }
            }

            RenderScript prs = internalCreate(ctx, sdkVersion, ct, flags);
            prs.mIsProcessContext = true;
            mProcessContextList.add(prs);
            return prs;
        }
    }

    /**
     * Releases all the process contexts.  This is the same as
     * calling .destroy() on each unique context retreived with
     * create(...). If no contexts have been created this
     * function does nothing.
     *
     * Typically you call this when your application is losing focus
     * and will not be using a context for some time.
     *
     * This has no effect on a context created with
     * createMultiContext()
     */
    public static void releaseAllContexts() {
        ArrayList<RenderScript> oldList;
        synchronized (mProcessContextList) {
            oldList = mProcessContextList;
            mProcessContextList = new ArrayList<RenderScript>();
        }

        for (RenderScript prs : oldList) {
            prs.mIsProcessContext = false;
            prs.destroy();
        }
        oldList.clear();
    }



    /**
     * Create a RenderScript context.
     *
     * This is an advanced function intended for applications which
     * need to create more than one RenderScript context to be used
     * at the same time.
     *
     * If you need a single context please use create()
     *
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript createMultiContext(Context ctx, ContextType ct, int flags, int API_number) {
        return internalCreate(ctx, API_number, ct, flags);
    }


    /**
     * Print the currently available debugging information about the state of
     * the RS context to the log.
     *
     */
    public void contextDump() {
        validate();
        nContextDump(0);
    }

    /**
     * Wait for any pending asynchronous opeations (such as copies to a RS
     * allocation or RS script executions) to complete.
     *
     */
    public void finish() {
        nContextFinish();
    }

    private void helpDestroy() {
        boolean shouldDestroy = false;
        synchronized(this) {
            if (!mDestroyed) {
                shouldDestroy = true;
                mDestroyed = true;
            }
        }

        if (shouldDestroy) {
            nContextFinish();

            nContextDeinitToClient(mContext);
            mMessageThread.mRun = false;
            // Interrupt mMessageThread so it gets to see immediately that mRun is false
            // and exit rightaway.
            mMessageThread.interrupt();

            // Wait for mMessageThread to join.  Try in a loop, in case this thread gets interrupted
            // during the wait.  If interrupted, set the "interrupted" status of the current thread.
            boolean hasJoined = false, interrupted = false;
            while (!hasJoined) {
                try {
                    mMessageThread.join();
                    hasJoined = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Log.v(LOG_TAG, "Interrupted during wait for MessageThread to join");
                Thread.currentThread().interrupt();
            }

            nContextDestroy();
        }
    }

    protected void finalize() throws Throwable {
        helpDestroy();
        super.finalize();
    }


    /**
     * Destroys this RenderScript context.  Once this function is called,
     * using this context or any objects belonging to this context is
     * illegal.
     *
     * API 23+, this function is a NOP if the context was created
     * with create().  Please use releaseAllContexts() to clean up
     * contexts created with the create function.
     *
     */
    public void destroy() {
        if (mIsProcessContext) {
            // users cannot destroy a process context
            return;
        }
        validate();
        helpDestroy();
    }

    boolean isAlive() {
        return mContext != 0;
    }

    long safeID(BaseObj o) {
        if(o != null) {
            return o.getID(this);
        }
        return 0;
    }
}
