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
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
            } catch (UnsatisfiedLinkError e) {
                Log.e(LOG_TAG, "Error loading RS jni library: " + e);
                throw new RSRuntimeException("Error loading RS jni library: " + e);
            }
        }
    }

    // Non-threadsafe functions.
    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native void nDeviceSetConfig(int dev, int param, int value);
    native int nContextGetUserMessage(int con, int[] data);
    native String nContextGetErrorMessage(int con);
    native int  nContextPeekMessage(int con, int[] subID);
    native void nContextInitToClient(int con);
    native void nContextDeinitToClient(int con);

    static File mCacheDir;

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

    // Methods below are wrapped to protect the non-threadsafe
    // lockless fifo.
    native int  rsnContextCreateGL(int dev, int ver, int sdkVer,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ, int dpi);
    synchronized int nContextCreateGL(int dev, int ver, int sdkVer,
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
    native int  rsnContextCreate(int dev, int ver, int sdkVer, int contextType);
    synchronized int nContextCreate(int dev, int ver, int sdkVer, int contextType) {
        return rsnContextCreate(dev, ver, sdkVer, contextType);
    }
    native void rsnContextDestroy(int con);
    synchronized void nContextDestroy() {
        validate();
        rsnContextDestroy(mContext);
    }
    native void rsnContextSetSurface(int con, int w, int h, Surface sur);
    synchronized void nContextSetSurface(int w, int h, Surface sur) {
        validate();
        rsnContextSetSurface(mContext, w, h, sur);
    }
    native void rsnContextSetSurfaceTexture(int con, int w, int h, SurfaceTexture sur);
    synchronized void nContextSetSurfaceTexture(int w, int h, SurfaceTexture sur) {
        validate();
        rsnContextSetSurfaceTexture(mContext, w, h, sur);
    }
    native void rsnContextSetPriority(int con, int p);
    synchronized void nContextSetPriority(int p) {
        validate();
        rsnContextSetPriority(mContext, p);
    }
    native void rsnContextDump(int con, int bits);
    synchronized void nContextDump(int bits) {
        validate();
        rsnContextDump(mContext, bits);
    }
    native void rsnContextFinish(int con);
    synchronized void nContextFinish() {
        validate();
        rsnContextFinish(mContext);
    }

    native void rsnContextSendMessage(int con, int id, int[] data);
    synchronized void nContextSendMessage(int id, int[] data) {
        validate();
        rsnContextSendMessage(mContext, id, data);
    }

    native void rsnContextBindRootScript(int con, int script);
    synchronized void nContextBindRootScript(int script) {
        validate();
        rsnContextBindRootScript(mContext, script);
    }
    native void rsnContextBindSampler(int con, int sampler, int slot);
    synchronized void nContextBindSampler(int sampler, int slot) {
        validate();
        rsnContextBindSampler(mContext, sampler, slot);
    }
    native void rsnContextBindProgramStore(int con, int pfs);
    synchronized void nContextBindProgramStore(int pfs) {
        validate();
        rsnContextBindProgramStore(mContext, pfs);
    }
    native void rsnContextBindProgramFragment(int con, int pf);
    synchronized void nContextBindProgramFragment(int pf) {
        validate();
        rsnContextBindProgramFragment(mContext, pf);
    }
    native void rsnContextBindProgramVertex(int con, int pv);
    synchronized void nContextBindProgramVertex(int pv) {
        validate();
        rsnContextBindProgramVertex(mContext, pv);
    }
    native void rsnContextBindProgramRaster(int con, int pr);
    synchronized void nContextBindProgramRaster(int pr) {
        validate();
        rsnContextBindProgramRaster(mContext, pr);
    }
    native void rsnContextPause(int con);
    synchronized void nContextPause() {
        validate();
        rsnContextPause(mContext);
    }
    native void rsnContextResume(int con);
    synchronized void nContextResume() {
        validate();
        rsnContextResume(mContext);
    }

    native void rsnAssignName(int con, int obj, byte[] name);
    synchronized void nAssignName(int obj, byte[] name) {
        validate();
        rsnAssignName(mContext, obj, name);
    }
    native String rsnGetName(int con, int obj);
    synchronized String nGetName(int obj) {
        validate();
        return rsnGetName(mContext, obj);
    }
    native void rsnObjDestroy(int con, int id);
    synchronized void nObjDestroy(int id) {
        // There is a race condition here.  The calling code may be run
        // by the gc while teardown is occuring.  This protects againts
        // deleting dead objects.
        if (mContext != 0) {
            rsnObjDestroy(mContext, id);
        }
    }

    native int  rsnElementCreate(int con, int type, int kind, boolean norm, int vecSize);
    synchronized int nElementCreate(int type, int kind, boolean norm, int vecSize) {
        validate();
        return rsnElementCreate(mContext, type, kind, norm, vecSize);
    }
    native int  rsnElementCreate2(int con, int[] elements, String[] names, int[] arraySizes);
    synchronized int nElementCreate2(int[] elements, String[] names, int[] arraySizes) {
        validate();
        return rsnElementCreate2(mContext, elements, names, arraySizes);
    }
    native void rsnElementGetNativeData(int con, int id, int[] elementData);
    synchronized void nElementGetNativeData(int id, int[] elementData) {
        validate();
        rsnElementGetNativeData(mContext, id, elementData);
    }
    native void rsnElementGetSubElements(int con, int id,
                                         int[] IDs, String[] names, int[] arraySizes);
    synchronized void nElementGetSubElements(int id, int[] IDs, String[] names, int[] arraySizes) {
        validate();
        rsnElementGetSubElements(mContext, id, IDs, names, arraySizes);
    }

    native int rsnTypeCreate(int con, int eid, int x, int y, int z, boolean mips, boolean faces, int yuv);
    synchronized int nTypeCreate(int eid, int x, int y, int z, boolean mips, boolean faces, int yuv) {
        validate();
        return rsnTypeCreate(mContext, eid, x, y, z, mips, faces, yuv);
    }
    native void rsnTypeGetNativeData(int con, int id, int[] typeData);
    synchronized void nTypeGetNativeData(int id, int[] typeData) {
        validate();
        rsnTypeGetNativeData(mContext, id, typeData);
    }

    native int  rsnAllocationCreateTyped(int con, int type, int mip, int usage, int pointer);
    synchronized int nAllocationCreateTyped(int type, int mip, int usage, int pointer) {
        validate();
        return rsnAllocationCreateTyped(mContext, type, mip, usage, pointer);
    }
    native int  rsnAllocationCreateFromBitmap(int con, int type, int mip, Bitmap bmp, int usage);
    synchronized int nAllocationCreateFromBitmap(int type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCreateFromBitmap(mContext, type, mip, bmp, usage);
    }

    native int  rsnAllocationCreateBitmapBackedAllocation(int con, int type, int mip, Bitmap bmp, int usage);
    synchronized int nAllocationCreateBitmapBackedAllocation(int type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCreateBitmapBackedAllocation(mContext, type, mip, bmp, usage);
    }


    native int  rsnAllocationCubeCreateFromBitmap(int con, int type, int mip, Bitmap bmp, int usage);
    synchronized int nAllocationCubeCreateFromBitmap(int type, int mip, Bitmap bmp, int usage) {
        validate();
        return rsnAllocationCubeCreateFromBitmap(mContext, type, mip, bmp, usage);
    }
    native int  rsnAllocationCreateBitmapRef(int con, int type, Bitmap bmp);
    synchronized int nAllocationCreateBitmapRef(int type, Bitmap bmp) {
        validate();
        return rsnAllocationCreateBitmapRef(mContext, type, bmp);
    }
    native int  rsnAllocationCreateFromAssetStream(int con, int mips, int assetStream, int usage);
    synchronized int nAllocationCreateFromAssetStream(int mips, int assetStream, int usage) {
        validate();
        return rsnAllocationCreateFromAssetStream(mContext, mips, assetStream, usage);
    }

    native void  rsnAllocationCopyToBitmap(int con, int alloc, Bitmap bmp);
    synchronized void nAllocationCopyToBitmap(int alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyToBitmap(mContext, alloc, bmp);
    }


    native void rsnAllocationSyncAll(int con, int alloc, int src);
    synchronized void nAllocationSyncAll(int alloc, int src) {
        validate();
        rsnAllocationSyncAll(mContext, alloc, src);
    }
    native Surface rsnAllocationGetSurface(int con, int alloc);
    synchronized Surface nAllocationGetSurface(int alloc) {
        validate();
        return rsnAllocationGetSurface(mContext, alloc);
    }
    native void rsnAllocationSetSurface(int con, int alloc, Surface sur);
    synchronized void nAllocationSetSurface(int alloc, Surface sur) {
        validate();
        rsnAllocationSetSurface(mContext, alloc, sur);
    }
    native void rsnAllocationIoSend(int con, int alloc);
    synchronized void nAllocationIoSend(int alloc) {
        validate();
        rsnAllocationIoSend(mContext, alloc);
    }
    native void rsnAllocationIoReceive(int con, int alloc);
    synchronized void nAllocationIoReceive(int alloc) {
        validate();
        rsnAllocationIoReceive(mContext, alloc);
    }


    native void rsnAllocationGenerateMipmaps(int con, int alloc);
    synchronized void nAllocationGenerateMipmaps(int alloc) {
        validate();
        rsnAllocationGenerateMipmaps(mContext, alloc);
    }
    native void  rsnAllocationCopyFromBitmap(int con, int alloc, Bitmap bmp);
    synchronized void nAllocationCopyFromBitmap(int alloc, Bitmap bmp) {
        validate();
        rsnAllocationCopyFromBitmap(mContext, alloc, bmp);
    }


    native void rsnAllocationData1D(int con, int id, int off, int mip, int count, int[] d, int sizeBytes);
    synchronized void nAllocationData1D(int id, int off, int mip, int count, int[] d, int sizeBytes) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes);
    }
    native void rsnAllocationData1D(int con, int id, int off, int mip, int count, short[] d, int sizeBytes);
    synchronized void nAllocationData1D(int id, int off, int mip, int count, short[] d, int sizeBytes) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes);
    }
    native void rsnAllocationData1D(int con, int id, int off, int mip, int count, byte[] d, int sizeBytes);
    synchronized void nAllocationData1D(int id, int off, int mip, int count, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes);
    }
    native void rsnAllocationData1D(int con, int id, int off, int mip, int count, float[] d, int sizeBytes);
    synchronized void nAllocationData1D(int id, int off, int mip, int count, float[] d, int sizeBytes) {
        validate();
        rsnAllocationData1D(mContext, id, off, mip, count, d, sizeBytes);
    }

    native void rsnAllocationElementData1D(int con, int id, int xoff, int mip, int compIdx, byte[] d, int sizeBytes);
    synchronized void nAllocationElementData1D(int id, int xoff, int mip, int compIdx, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationElementData1D(mContext, id, xoff, mip, compIdx, d, sizeBytes);
    }

    native void rsnAllocationData2D(int con,
                                    int dstAlloc, int dstXoff, int dstYoff,
                                    int dstMip, int dstFace,
                                    int width, int height,
                                    int srcAlloc, int srcXoff, int srcYoff,
                                    int srcMip, int srcFace);
    synchronized void nAllocationData2D(int dstAlloc, int dstXoff, int dstYoff,
                                        int dstMip, int dstFace,
                                        int width, int height,
                                        int srcAlloc, int srcXoff, int srcYoff,
                                        int srcMip, int srcFace) {
        validate();
        rsnAllocationData2D(mContext,
                            dstAlloc, dstXoff, dstYoff,
                            dstMip, dstFace,
                            width, height,
                            srcAlloc, srcXoff, srcYoff,
                            srcMip, srcFace);
    }

    native void rsnAllocationData2D(int con, int id, int xoff, int yoff, int mip, int face, int w, int h, byte[] d, int sizeBytes);
    synchronized void nAllocationData2D(int id, int xoff, int yoff, int mip, int face, int w, int h, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes);
    }
    native void rsnAllocationData2D(int con, int id, int xoff, int yoff, int mip, int face, int w, int h, short[] d, int sizeBytes);
    synchronized void nAllocationData2D(int id, int xoff, int yoff, int mip, int face, int w, int h, short[] d, int sizeBytes) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes);
    }
    native void rsnAllocationData2D(int con, int id, int xoff, int yoff, int mip, int face, int w, int h, int[] d, int sizeBytes);
    synchronized void nAllocationData2D(int id, int xoff, int yoff, int mip, int face, int w, int h, int[] d, int sizeBytes) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes);
    }
    native void rsnAllocationData2D(int con, int id, int xoff, int yoff, int mip, int face, int w, int h, float[] d, int sizeBytes);
    synchronized void nAllocationData2D(int id, int xoff, int yoff, int mip, int face, int w, int h, float[] d, int sizeBytes) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, w, h, d, sizeBytes);
    }
    native void rsnAllocationData2D(int con, int id, int xoff, int yoff, int mip, int face, Bitmap b);
    synchronized void nAllocationData2D(int id, int xoff, int yoff, int mip, int face, Bitmap b) {
        validate();
        rsnAllocationData2D(mContext, id, xoff, yoff, mip, face, b);
    }

    native void rsnAllocationData3D(int con,
                                    int dstAlloc, int dstXoff, int dstYoff, int dstZoff,
                                    int dstMip,
                                    int width, int height, int depth,
                                    int srcAlloc, int srcXoff, int srcYoff, int srcZoff,
                                    int srcMip);
    synchronized void nAllocationData3D(int dstAlloc, int dstXoff, int dstYoff, int dstZoff,
                                        int dstMip,
                                        int width, int height, int depth,
                                        int srcAlloc, int srcXoff, int srcYoff, int srcZoff,
                                        int srcMip) {
        validate();
        rsnAllocationData3D(mContext,
                            dstAlloc, dstXoff, dstYoff, dstZoff,
                            dstMip, width, height, depth,
                            srcAlloc, srcXoff, srcYoff, srcZoff, srcMip);
    }

    native void rsnAllocationData3D(int con, int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, byte[] d, int sizeBytes);
    synchronized void nAllocationData3D(int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, byte[] d, int sizeBytes) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes);
    }
    native void rsnAllocationData3D(int con, int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, short[] d, int sizeBytes);
    synchronized void nAllocationData3D(int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, short[] d, int sizeBytes) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes);
    }
    native void rsnAllocationData3D(int con, int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, int[] d, int sizeBytes);
    synchronized void nAllocationData3D(int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, int[] d, int sizeBytes) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes);
    }
    native void rsnAllocationData3D(int con, int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, float[] d, int sizeBytes);
    synchronized void nAllocationData3D(int id, int xoff, int yoff, int zoff, int mip, int w, int h, int depth, float[] d, int sizeBytes) {
        validate();
        rsnAllocationData3D(mContext, id, xoff, yoff, zoff, mip, w, h, depth, d, sizeBytes);
    }


    native void rsnAllocationRead(int con, int id, byte[] d);
    synchronized void nAllocationRead(int id, byte[] d) {
        validate();
        rsnAllocationRead(mContext, id, d);
    }
    native void rsnAllocationRead(int con, int id, short[] d);
    synchronized void nAllocationRead(int id, short[] d) {
        validate();
        rsnAllocationRead(mContext, id, d);
    }
    native void rsnAllocationRead(int con, int id, int[] d);
    synchronized void nAllocationRead(int id, int[] d) {
        validate();
        rsnAllocationRead(mContext, id, d);
    }
    native void rsnAllocationRead(int con, int id, float[] d);
    synchronized void nAllocationRead(int id, float[] d) {
        validate();
        rsnAllocationRead(mContext, id, d);
    }
    native int  rsnAllocationGetType(int con, int id);
    synchronized int nAllocationGetType(int id) {
        validate();
        return rsnAllocationGetType(mContext, id);
    }

    native void rsnAllocationResize1D(int con, int id, int dimX);
    synchronized void nAllocationResize1D(int id, int dimX) {
        validate();
        rsnAllocationResize1D(mContext, id, dimX);
    }

    native int  rsnFileA3DCreateFromAssetStream(int con, int assetStream);
    synchronized int nFileA3DCreateFromAssetStream(int assetStream) {
        validate();
        return rsnFileA3DCreateFromAssetStream(mContext, assetStream);
    }
    native int  rsnFileA3DCreateFromFile(int con, String path);
    synchronized int nFileA3DCreateFromFile(String path) {
        validate();
        return rsnFileA3DCreateFromFile(mContext, path);
    }
    native int  rsnFileA3DCreateFromAsset(int con, AssetManager mgr, String path);
    synchronized int nFileA3DCreateFromAsset(AssetManager mgr, String path) {
        validate();
        return rsnFileA3DCreateFromAsset(mContext, mgr, path);
    }
    native int  rsnFileA3DGetNumIndexEntries(int con, int fileA3D);
    synchronized int nFileA3DGetNumIndexEntries(int fileA3D) {
        validate();
        return rsnFileA3DGetNumIndexEntries(mContext, fileA3D);
    }
    native void rsnFileA3DGetIndexEntries(int con, int fileA3D, int numEntries, int[] IDs, String[] names);
    synchronized void nFileA3DGetIndexEntries(int fileA3D, int numEntries, int[] IDs, String[] names) {
        validate();
        rsnFileA3DGetIndexEntries(mContext, fileA3D, numEntries, IDs, names);
    }
    native int  rsnFileA3DGetEntryByIndex(int con, int fileA3D, int index);
    synchronized int nFileA3DGetEntryByIndex(int fileA3D, int index) {
        validate();
        return rsnFileA3DGetEntryByIndex(mContext, fileA3D, index);
    }

    native int  rsnFontCreateFromFile(int con, String fileName, float size, int dpi);
    synchronized int nFontCreateFromFile(String fileName, float size, int dpi) {
        validate();
        return rsnFontCreateFromFile(mContext, fileName, size, dpi);
    }
    native int  rsnFontCreateFromAssetStream(int con, String name, float size, int dpi, int assetStream);
    synchronized int nFontCreateFromAssetStream(String name, float size, int dpi, int assetStream) {
        validate();
        return rsnFontCreateFromAssetStream(mContext, name, size, dpi, assetStream);
    }
    native int  rsnFontCreateFromAsset(int con, AssetManager mgr, String path, float size, int dpi);
    synchronized int nFontCreateFromAsset(AssetManager mgr, String path, float size, int dpi) {
        validate();
        return rsnFontCreateFromAsset(mContext, mgr, path, size, dpi);
    }


    native void rsnScriptBindAllocation(int con, int script, int alloc, int slot);
    synchronized void nScriptBindAllocation(int script, int alloc, int slot) {
        validate();
        rsnScriptBindAllocation(mContext, script, alloc, slot);
    }
    native void rsnScriptSetTimeZone(int con, int script, byte[] timeZone);
    synchronized void nScriptSetTimeZone(int script, byte[] timeZone) {
        validate();
        rsnScriptSetTimeZone(mContext, script, timeZone);
    }
    native void rsnScriptInvoke(int con, int id, int slot);
    synchronized void nScriptInvoke(int id, int slot) {
        validate();
        rsnScriptInvoke(mContext, id, slot);
    }
    native void rsnScriptForEach(int con, int id, int slot, int ain, int aout, byte[] params);
    native void rsnScriptForEach(int con, int id, int slot, int ain, int aout);
    native void rsnScriptForEachClipped(int con, int id, int slot, int ain, int aout, byte[] params,
                                        int xstart, int xend, int ystart, int yend, int zstart, int zend);
    native void rsnScriptForEachClipped(int con, int id, int slot, int ain, int aout,
                                        int xstart, int xend, int ystart, int yend, int zstart, int zend);
    synchronized void nScriptForEach(int id, int slot, int ain, int aout, byte[] params) {
        validate();
        if (params == null) {
            rsnScriptForEach(mContext, id, slot, ain, aout);
        } else {
            rsnScriptForEach(mContext, id, slot, ain, aout, params);
        }
    }

    synchronized void nScriptForEachClipped(int id, int slot, int ain, int aout, byte[] params,
                                            int xstart, int xend, int ystart, int yend, int zstart, int zend) {
        validate();
        if (params == null) {
            rsnScriptForEachClipped(mContext, id, slot, ain, aout, xstart, xend, ystart, yend, zstart, zend);
        } else {
            rsnScriptForEachClipped(mContext, id, slot, ain, aout, params, xstart, xend, ystart, yend, zstart, zend);
        }
    }

    native void rsnScriptInvokeV(int con, int id, int slot, byte[] params);
    synchronized void nScriptInvokeV(int id, int slot, byte[] params) {
        validate();
        rsnScriptInvokeV(mContext, id, slot, params);
    }

    native void rsnScriptSetVarI(int con, int id, int slot, int val);
    synchronized void nScriptSetVarI(int id, int slot, int val) {
        validate();
        rsnScriptSetVarI(mContext, id, slot, val);
    }
    native int rsnScriptGetVarI(int con, int id, int slot);
    synchronized int nScriptGetVarI(int id, int slot) {
        validate();
        return rsnScriptGetVarI(mContext, id, slot);
    }

    native void rsnScriptSetVarJ(int con, int id, int slot, long val);
    synchronized void nScriptSetVarJ(int id, int slot, long val) {
        validate();
        rsnScriptSetVarJ(mContext, id, slot, val);
    }
    native long rsnScriptGetVarJ(int con, int id, int slot);
    synchronized long nScriptGetVarJ(int id, int slot) {
        validate();
        return rsnScriptGetVarJ(mContext, id, slot);
    }

    native void rsnScriptSetVarF(int con, int id, int slot, float val);
    synchronized void nScriptSetVarF(int id, int slot, float val) {
        validate();
        rsnScriptSetVarF(mContext, id, slot, val);
    }
    native float rsnScriptGetVarF(int con, int id, int slot);
    synchronized float nScriptGetVarF(int id, int slot) {
        validate();
        return rsnScriptGetVarF(mContext, id, slot);
    }
    native void rsnScriptSetVarD(int con, int id, int slot, double val);
    synchronized void nScriptSetVarD(int id, int slot, double val) {
        validate();
        rsnScriptSetVarD(mContext, id, slot, val);
    }
    native double rsnScriptGetVarD(int con, int id, int slot);
    synchronized double nScriptGetVarD(int id, int slot) {
        validate();
        return rsnScriptGetVarD(mContext, id, slot);
    }
    native void rsnScriptSetVarV(int con, int id, int slot, byte[] val);
    synchronized void nScriptSetVarV(int id, int slot, byte[] val) {
        validate();
        rsnScriptSetVarV(mContext, id, slot, val);
    }
    native void rsnScriptGetVarV(int con, int id, int slot, byte[] val);
    synchronized void nScriptGetVarV(int id, int slot, byte[] val) {
        validate();
        rsnScriptGetVarV(mContext, id, slot, val);
    }
    native void rsnScriptSetVarVE(int con, int id, int slot, byte[] val,
                                  int e, int[] dims);
    synchronized void nScriptSetVarVE(int id, int slot, byte[] val,
                                      int e, int[] dims) {
        validate();
        rsnScriptSetVarVE(mContext, id, slot, val, e, dims);
    }
    native void rsnScriptSetVarObj(int con, int id, int slot, int val);
    synchronized void nScriptSetVarObj(int id, int slot, int val) {
        validate();
        rsnScriptSetVarObj(mContext, id, slot, val);
    }

    native int  rsnScriptCCreate(int con, String resName, String cacheDir,
                                 byte[] script, int length);
    synchronized int nScriptCCreate(String resName, String cacheDir, byte[] script, int length) {
        validate();
        return rsnScriptCCreate(mContext, resName, cacheDir, script, length);
    }

    native int  rsnScriptIntrinsicCreate(int con, int id, int eid);
    synchronized int nScriptIntrinsicCreate(int id, int eid) {
        validate();
        return rsnScriptIntrinsicCreate(mContext, id, eid);
    }

    native int  rsnScriptKernelIDCreate(int con, int sid, int slot, int sig);
    synchronized int nScriptKernelIDCreate(int sid, int slot, int sig) {
        validate();
        return rsnScriptKernelIDCreate(mContext, sid, slot, sig);
    }

    native int  rsnScriptFieldIDCreate(int con, int sid, int slot);
    synchronized int nScriptFieldIDCreate(int sid, int slot) {
        validate();
        return rsnScriptFieldIDCreate(mContext, sid, slot);
    }

    native int  rsnScriptGroupCreate(int con, int[] kernels, int[] src, int[] dstk, int[] dstf, int[] types);
    synchronized int nScriptGroupCreate(int[] kernels, int[] src, int[] dstk, int[] dstf, int[] types) {
        validate();
        return rsnScriptGroupCreate(mContext, kernels, src, dstk, dstf, types);
    }

    native void rsnScriptGroupSetInput(int con, int group, int kernel, int alloc);
    synchronized void nScriptGroupSetInput(int group, int kernel, int alloc) {
        validate();
        rsnScriptGroupSetInput(mContext, group, kernel, alloc);
    }

    native void rsnScriptGroupSetOutput(int con, int group, int kernel, int alloc);
    synchronized void nScriptGroupSetOutput(int group, int kernel, int alloc) {
        validate();
        rsnScriptGroupSetOutput(mContext, group, kernel, alloc);
    }

    native void rsnScriptGroupExecute(int con, int group);
    synchronized void nScriptGroupExecute(int group) {
        validate();
        rsnScriptGroupExecute(mContext, group);
    }

    native int  rsnSamplerCreate(int con, int magFilter, int minFilter,
                                 int wrapS, int wrapT, int wrapR, float aniso);
    synchronized int nSamplerCreate(int magFilter, int minFilter,
                                 int wrapS, int wrapT, int wrapR, float aniso) {
        validate();
        return rsnSamplerCreate(mContext, magFilter, minFilter, wrapS, wrapT, wrapR, aniso);
    }

    native int  rsnProgramStoreCreate(int con, boolean r, boolean g, boolean b, boolean a,
                                      boolean depthMask, boolean dither,
                                      int srcMode, int dstMode, int depthFunc);
    synchronized int nProgramStoreCreate(boolean r, boolean g, boolean b, boolean a,
                                         boolean depthMask, boolean dither,
                                         int srcMode, int dstMode, int depthFunc) {
        validate();
        return rsnProgramStoreCreate(mContext, r, g, b, a, depthMask, dither, srcMode,
                                     dstMode, depthFunc);
    }

    native int  rsnProgramRasterCreate(int con, boolean pointSprite, int cullMode);
    synchronized int nProgramRasterCreate(boolean pointSprite, int cullMode) {
        validate();
        return rsnProgramRasterCreate(mContext, pointSprite, cullMode);
    }

    native void rsnProgramBindConstants(int con, int pv, int slot, int mID);
    synchronized void nProgramBindConstants(int pv, int slot, int mID) {
        validate();
        rsnProgramBindConstants(mContext, pv, slot, mID);
    }
    native void rsnProgramBindTexture(int con, int vpf, int slot, int a);
    synchronized void nProgramBindTexture(int vpf, int slot, int a) {
        validate();
        rsnProgramBindTexture(mContext, vpf, slot, a);
    }
    native void rsnProgramBindSampler(int con, int vpf, int slot, int s);
    synchronized void nProgramBindSampler(int vpf, int slot, int s) {
        validate();
        rsnProgramBindSampler(mContext, vpf, slot, s);
    }
    native int  rsnProgramFragmentCreate(int con, String shader, String[] texNames, int[] params);
    synchronized int nProgramFragmentCreate(String shader, String[] texNames, int[] params) {
        validate();
        return rsnProgramFragmentCreate(mContext, shader, texNames, params);
    }
    native int  rsnProgramVertexCreate(int con, String shader, String[] texNames, int[] params);
    synchronized int nProgramVertexCreate(String shader, String[] texNames, int[] params) {
        validate();
        return rsnProgramVertexCreate(mContext, shader, texNames, params);
    }

    native int  rsnMeshCreate(int con, int[] vtx, int[] idx, int[] prim);
    synchronized int nMeshCreate(int[] vtx, int[] idx, int[] prim) {
        validate();
        return rsnMeshCreate(mContext, vtx, idx, prim);
    }
    native int  rsnMeshGetVertexBufferCount(int con, int id);
    synchronized int nMeshGetVertexBufferCount(int id) {
        validate();
        return rsnMeshGetVertexBufferCount(mContext, id);
    }
    native int  rsnMeshGetIndexCount(int con, int id);
    synchronized int nMeshGetIndexCount(int id) {
        validate();
        return rsnMeshGetIndexCount(mContext, id);
    }
    native void rsnMeshGetVertices(int con, int id, int[] vtxIds, int vtxIdCount);
    synchronized void nMeshGetVertices(int id, int[] vtxIds, int vtxIdCount) {
        validate();
        rsnMeshGetVertices(mContext, id, vtxIds, vtxIdCount);
    }
    native void rsnMeshGetIndices(int con, int id, int[] idxIds, int[] primitives, int vtxIdCount);
    synchronized void nMeshGetIndices(int id, int[] idxIds, int[] primitives, int vtxIdCount) {
        validate();
        rsnMeshGetIndices(mContext, id, idxIds, primitives, vtxIdCount);
    }

    native int  rsnPathCreate(int con, int prim, boolean isStatic, int vtx, int loop, float q);
    synchronized int nPathCreate(int prim, boolean isStatic, int vtx, int loop, float q) {
        validate();
        return rsnPathCreate(mContext, prim, isStatic, vtx, loop, q);
    }

    int     mDev;
    int     mContext;
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
                    Allocation.sendBufferNotification(subID);
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
        return create(ctx, sdkVersion, ContextType.NORMAL);
    }

    /**
     * Create a RenderScript context.
     *
     * @hide
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx, int sdkVersion, ContextType ct) {
        if (!sInitialized) {
            Log.e(LOG_TAG, "RenderScript.create() called when disabled; someone is likely to crash");
            return null;
        }

        RenderScript rs = new RenderScript(ctx);

        rs.mDev = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(rs.mDev, 0, sdkVersion, ct.mID);
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
        return create(ctx, v, ct);
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
        nContextDeinitToClient(mContext);
        mMessageThread.mRun = false;
        try {
            mMessageThread.join();
        } catch(InterruptedException e) {
        }

        nContextDestroy();
        mContext = 0;

        nDeviceDestroy(mDev);
        mDev = 0;
    }

    boolean isAlive() {
        return mContext != 0;
    }

    int safeID(BaseObj o) {
        if(o != null) {
            return o.getID(this);
        }
        return 0;
    }
}
