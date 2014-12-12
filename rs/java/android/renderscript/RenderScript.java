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

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.os.SystemProperties;
import android.os.Trace;

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

    /*
     * Detect the bitness of the VM to allow FieldPacker to do the right thing.
     */
    static native int rsnSystemGetPointerSize();
    static int sPointerSize;

    static {
        sInitialized = false;
        if (!SystemProperties.getBoolean("config.disable_renderscript", false)) {
            try {
                Class<?> vm_runtime = Class.forName("dalvik.system.VMRuntime");
                Method get_runtime = vm_runtime.getDeclaredMethod("getRuntime");
                sRuntime = get_runtime.invoke(null);
                registerNativeAllocation = vm_runtime.getDeclaredMethod("registerNativeAllocation", Integer.TYPE);
                registerNativeFree = vm_runtime.getDeclaredMethod("registerNativeFree", Integer.TYPE);
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

    static File mCacheDir;

    // this should be a monotonically increasing ID
    // used in conjunction with the API version of a device
    static final long sMinorID = 1;

    /**
     * Returns an identifier that can be used to identify a particular
     * minor version of RS.
     *
     * @hide
     */
    public static long getMinorID() {
        return sMinorID;
    }

     /**
     * Sets the directory to use as a persistent storage for the
     * renderscript object file cache.
     *
     * @hide
     * @param cacheDir A directory the current process can write to
     */
    public static void setupDiskCache(File cacheDir) {
        if (!sInitialized) {
            Log.e(LOG_TAG, "RenderScript.setupDiskCache() called when disabled");
            return;
        }

        // Defer creation of cache path to nScriptCCreate().
        mCacheDir = cacheDir;
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
    native long rsnAllocationCreateFromBitmap(long con, long type, int mip, Bitmap bmp, int usage);
    synchronized long nAllocationCreateFromBitmap(long type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCreateFromBitmap(mContext, type, mip, bmp, usage);
    }

    native long rsnAllocationCreateBitmapBackedAllocation(long con, long type, int mip, Bitmap bmp, int usage);
    synchronized long nAllocationCreateBitmapBackedAllocation(long type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCreateBitmapBackedAllocation(mContext, type, mip, bmp, usage);
    }

    native long rsnAllocationCubeCreateFromBitmap(long con, long type, int mip, Bitmap bmp, int usage);
    synchronized long nAllocationCubeCreateFromBitmap(long type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCubeCreateFromBitmap(mContext, type, mip, bmp, usage);
    }
    native long  rsnAllocationCreateBitmapRef(long con, long type, Bitmap bmp);
    synchronized long nAllocationCreateBitmapRef(long type, Bitmap bmp) {
        validate();
        return rsnAllocationCreateBitmapRef(mContext, type, bmp);
    }
    native long  rsnAllocationCreateFromAssetStream(long con, int mips, int assetStream, int usage);
    synchronized long nAllocationCreateFromAssetStream(int mips, int assetStream, int usage) {
        validate();
        return rsnAllocationCreateFromAssetStream(mContext, mips, assetStream, usage);
    }

    native void  rsnAllocationCopyToBitmap(long con, long alloc, Bitmap bmp);
    synchronized void nAllocationCopyToBitmap(long alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyToBitmap(mContext, alloc, bmp);
    }


    native void rsnAllocationSyncAll(long con, long alloc, int src);
    synchronized void nAllocationSyncAll(long alloc, int src) {
        validate();
        rsnAllocationSyncAll(mContext, alloc, src);
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
    native void rsnAllocationIoReceive(long con, long alloc);
    synchronized void nAllocationIoReceive(long alloc) {
        validate();
        rsnAllocationIoReceive(mContext, alloc);
    }


    native void rsnAllocationGenerateMipmaps(long con, long alloc);
    synchronized void nAllocationGenerateMipmaps(long alloc) {
        validate();
        rsnAllocationGenerateMipmaps(mContext, alloc);
    }
    native void  rsnAllocationCopyFromBitmap(long con, long alloc, Bitmap bmp);
    synchronized void nAllocationCopyFromBitmap(long alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyFromBitmap(mContext, alloc, bmp);
    }


    native void rsnAllocationData1D(long con, long id, int off, int mip, int count, Object d, int sizeBytes, int dt);
    synchronized void nAllocationData1D(long id, int off, int mip, int count, Object d, int sizeBytes, Element.DataType dt) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes, dt.mID);
    }

    native void rsnAllocationElementData1D(long con,long id, int xoff, int mip, int compIdx, byte[] d, int sizeBytes);
    synchronized void nAllocationElementData1D(long id, int xoff, int mip, int compIdx, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationElementData1D(mContext, id, xoff, mip, compIdx, d, sizeBytes);
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
                                    int w, int h, Object d, int sizeBytes, int dt);
    synchronized void nAllocationData2D(long id, int xoff, int yoff, int mip, int face,
                                        int w, int h, Object d, int sizeBytes, Element.DataType dt) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes, dt.mID);
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
                                    int w, int h, int depth, Object d, int sizeBytes, int dt);
    synchronized void nAllocationData3D(long id, int xoff, int yoff, int zoff, int mip,
                                        int w, int h, int depth, Object d, int sizeBytes, Element.DataType dt) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes, dt.mID);
    }

    native void rsnAllocationRead(long con, long id, Object d, int dt);
    synchronized void nAllocationRead(long id, Object d, Element.DataType dt) {
        validate();
        rsnAllocationRead(mContext, id, d, dt.mID);
    }

    native void rsnAllocationRead1D(long con, long id, int off, int mip, int count, Object d,
                                    int sizeBytes, int dt);
    synchronized void nAllocationRead1D(long id, int off, int mip, int count, Object d,
                                        int sizeBytes, Element.DataType dt) {
        validate();
        rsnAllocationRead1D(mContext, id, off, mip, count, d, sizeBytes, dt.mID);
    }

    native void rsnAllocationRead2D(long con, long id, int xoff, int yoff, int mip, int face,
                                    int w, int h, Object d, int sizeBytes, int dt);
    synchronized void nAllocationRead2D(long id, int xoff, int yoff, int mip, int face,
                                        int w, int h, Object d, int sizeBytes, Element.DataType dt) {
        validate();
        rsnAllocationRead2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes, dt.mID);
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
    native void rsnScriptForEach(long con, long id, int slot, long ain, long aout, byte[] params);
    native void rsnScriptForEach(long con, long id, int slot, long ain, long aout);
    native void rsnScriptForEachClipped(long con, long id, int slot, long ain, long aout, byte[] params,
                                        int xstart, int xend, int ystart, int yend, int zstart, int zend);
    native void rsnScriptForEachClipped(long con, long id, int slot, long ain, long aout,
                                        int xstart, int xend, int ystart, int yend, int zstart, int zend);
    synchronized void nScriptForEach(long id, int slot, long ain, long aout, byte[] params) {
        validate();
        if (params == null) {
            rsnScriptForEach(mContext, id, slot, ain, aout);
        } else {
            rsnScriptForEach(mContext, id, slot, ain, aout, params);
        }
    }

    synchronized void nScriptForEachClipped(long id, int slot, long ain, long aout, byte[] params,
                                            int xstart, int xend, int ystart, int yend, int zstart, int zend) {
        validate();
        if (params == null) {
            rsnScriptForEachClipped(mContext, id, slot, ain, aout, xstart, xend, ystart, yend, zstart, zend);
        } else {
            rsnScriptForEachClipped(mContext, id, slot, ain, aout, params, xstart, xend, ystart, yend, zstart, zend);
        }
    }

    /**
     * Multi-input code.
     *
     */

    // @hide
    native void rsnScriptForEachMultiClipped(long con, long id, int slot, long[] ains, long aout, byte[] params,
                                             int xstart, int xend, int ystart, int yend, int zstart, int zend);
    // @hide
    native void rsnScriptForEachMultiClipped(long con, long id, int slot, long[] ains, long aout,
                                             int xstart, int xend, int ystart, int yend, int zstart, int zend);

    // @hide
    synchronized void nScriptForEachMultiClipped(long id, int slot, long[] ains, long aout, byte[] params,
                                                 int xstart, int xend, int ystart, int yend, int zstart, int zend) {
        validate();
        if (params == null) {
            rsnScriptForEachMultiClipped(mContext, id, slot, ains, aout, xstart, xend, ystart, yend, zstart, zend);
        } else {
            rsnScriptForEachMultiClipped(mContext, id, slot, ains, aout, params, xstart, xend, ystart, yend, zstart, zend);
        }
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

    native long rsnPathCreate(long con, int prim, boolean isStatic, long vtx, long loop, float q);
    synchronized long nPathCreate(int prim, boolean isStatic, long vtx, long loop, float q) {
        validate();
        return rsnPathCreate(mContext, prim, isStatic, vtx, loop, q);
    }

    long     mDev;
    long     mContext;
    @SuppressWarnings({"FieldCanBeLocal"})
    MessageThread mMessageThread;

    Element mElement_U8;
    Element mElement_I8;
    Element mElement_U16;
    Element mElement_I16;
    Element mElement_U32;
    Element mElement_I32;
    Element mElement_U64;
    Element mElement_I64;
    Element mElement_F32;
    Element mElement_F64;
    Element mElement_BOOLEAN;

    Element mElement_ELEMENT;
    Element mElement_TYPE;
    Element mElement_ALLOCATION;
    Element mElement_SAMPLER;
    Element mElement_SCRIPT;
    Element mElement_MESH;
    Element mElement_PROGRAM_FRAGMENT;
    Element mElement_PROGRAM_VERTEX;
    Element mElement_PROGRAM_RASTER;
    Element mElement_PROGRAM_STORE;
    Element mElement_FONT;

    Element mElement_A_8;
    Element mElement_RGB_565;
    Element mElement_RGB_888;
    Element mElement_RGBA_5551;
    Element mElement_RGBA_4444;
    Element mElement_RGBA_8888;

    Element mElement_FLOAT_2;
    Element mElement_FLOAT_3;
    Element mElement_FLOAT_4;

    Element mElement_DOUBLE_2;
    Element mElement_DOUBLE_3;
    Element mElement_DOUBLE_4;

    Element mElement_UCHAR_2;
    Element mElement_UCHAR_3;
    Element mElement_UCHAR_4;

    Element mElement_CHAR_2;
    Element mElement_CHAR_3;
    Element mElement_CHAR_4;

    Element mElement_USHORT_2;
    Element mElement_USHORT_3;
    Element mElement_USHORT_4;

    Element mElement_SHORT_2;
    Element mElement_SHORT_3;
    Element mElement_SHORT_4;

    Element mElement_UINT_2;
    Element mElement_UINT_3;
    Element mElement_UINT_4;

    Element mElement_INT_2;
    Element mElement_INT_3;
    Element mElement_INT_4;

    Element mElement_ULONG_2;
    Element mElement_ULONG_3;
    Element mElement_ULONG_4;

    Element mElement_LONG_2;
    Element mElement_LONG_3;
    Element mElement_LONG_4;

    Element mElement_YUV;

    Element mElement_MATRIX_4X4;
    Element mElement_MATRIX_3X3;
    Element mElement_MATRIX_2X2;

    Sampler mSampler_CLAMP_NEAREST;
    Sampler mSampler_CLAMP_LINEAR;
    Sampler mSampler_CLAMP_LINEAR_MIP_LINEAR;
    Sampler mSampler_WRAP_NEAREST;
    Sampler mSampler_WRAP_LINEAR;
    Sampler mSampler_WRAP_LINEAR_MIP_LINEAR;
    Sampler mSampler_MIRRORED_REPEAT_NEAREST;
    Sampler mSampler_MIRRORED_REPEAT_LINEAR;
    Sampler mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR;

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
        LOW (Process.THREAD_PRIORITY_BACKGROUND + (5 * Process.THREAD_PRIORITY_LESS_FAVORABLE)),
        NORMAL (Process.THREAD_PRIORITY_DISPLAY);

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
            registerNativeAllocation.invoke(sRuntime, 4 * 1024 * 1024 * 1024); // 4MB for GC sake
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
     * @hide
     */
    public static RenderScript create(Context ctx, int sdkVersion) {
        return create(ctx, sdkVersion, ContextType.NORMAL, CREATE_FLAG_NONE);
    }

    /**
     * Create a RenderScript context.
     *
     * @hide
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx, int sdkVersion, ContextType ct, int flags) {
        if (!sInitialized) {
            Log.e(LOG_TAG, "RenderScript.create() called when disabled; someone is likely to crash");
            return null;
        }

        if ((flags & ~(CREATE_FLAG_LOW_LATENCY | CREATE_FLAG_LOW_POWER)) != 0) {
            throw new RSIllegalArgumentException("Invalid flags passed.");
        }

        RenderScript rs = new RenderScript(ctx);

        rs.mDev = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(rs.mDev, flags, sdkVersion, ct.mID);
        rs.mContextType = ct;
        if (rs.mContext == 0) {
            throw new RSDriverException("Failed to create RS context.");
        }
        rs.mMessageThread = new MessageThread(rs);
        rs.mMessageThread.start();
        return rs;
    }

    /**
     * Create a RenderScript context.
     *
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx) {
        return create(ctx, ContextType.NORMAL);
    }

    /**
     * Create a RenderScript context.
     *
     *
     * @param ctx The context.
     * @param ct The type of context to be created.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx, ContextType ct) {
        int v = ctx.getApplicationInfo().targetSdkVersion;
        return create(ctx, v, ct, CREATE_FLAG_NONE);
    }

     /**
     * Create a RenderScript context.
     *
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

    /**
     * Destroys this RenderScript context.  Once this function is called,
     * using this context or any objects belonging to this context is
     * illegal.
     *
     */
    public void destroy() {
        validate();
        nContextFinish();

        nContextDeinitToClient(mContext);
        mMessageThread.mRun = false;
        try {
            mMessageThread.join();
        } catch(InterruptedException e) {
        }

        nContextDestroy();

        nDeviceDestroy(mDev);
        mDev = 0;
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
