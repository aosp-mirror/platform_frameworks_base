/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.lang.reflect.Field;

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



/**
 * Renderscript base master class.  An instance of this class creates native
 * worker threads for processing commands from this object.  This base class
 * does not provide any extended capabilities beyond simple data processing.
 * For extended capabilities use derived classes such as RenderScriptGL.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating an application that uses Renderscript, read the
 * <a href="{@docRoot}guide/topics/graphics/renderscript.html">Renderscript</a> developer guide.</p>
 * </div>
 **/
public class RenderScript {
    static final String LOG_TAG = "RenderScript_jni";
    static final boolean DEBUG  = false;
    @SuppressWarnings({"UnusedDeclaration", "deprecation"})
    static final boolean LOG_ENABLED = false;

    private Context mApplicationContext;

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    static boolean sInitialized;
    native static void _nInit();


    static {
        sInitialized = false;
        try {
            System.loadLibrary("rs_jni");
            _nInit();
            sInitialized = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(LOG_TAG, "Error loading RS jni library: " + e);
            throw new RSRuntimeException("Error loading RS jni library: " + e);
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
    native int  rsnContextCreate(int dev, int ver, int sdkVer);
    synchronized int nContextCreate(int dev, int ver, int sdkVer) {
        return rsnContextCreate(dev, ver, sdkVer);
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

    native int rsnTypeCreate(int con, int eid, int x, int y, int z, boolean mips, boolean faces);
    synchronized int nTypeCreate(int eid, int x, int y, int z, boolean mips, boolean faces) {
        validate();
        return rsnTypeCreate(mContext, eid, x, y, z, mips, faces);
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
    native int rsnAllocationGetSurfaceTextureID(int con, int alloc);
    synchronized int nAllocationGetSurfaceTextureID(int alloc) {
        validate();
        return rsnAllocationGetSurfaceTextureID(mContext, alloc);
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
    native void rsnAllocationResize2D(int con, int id, int dimX, int dimY);
    synchronized void nAllocationResize2D(int id, int dimX, int dimY) {
        validate();
        rsnAllocationResize2D(mContext, id, dimX, dimY);
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
    synchronized void nScriptForEach(int id, int slot, int ain, int aout, byte[] params) {
        validate();
        if (params == null) {
            rsnScriptForEach(mContext, id, slot, ain, aout);
        } else {
            rsnScriptForEach(mContext, id, slot, ain, aout, params);
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
    native void rsnScriptSetVarJ(int con, int id, int slot, long val);
    synchronized void nScriptSetVarJ(int id, int slot, long val) {
        validate();
        rsnScriptSetVarJ(mContext, id, slot, val);
    }
    native void rsnScriptSetVarF(int con, int id, int slot, float val);
    synchronized void nScriptSetVarF(int id, int slot, float val) {
        validate();
        rsnScriptSetVarF(mContext, id, slot, val);
    }
    native void rsnScriptSetVarD(int con, int id, int slot, double val);
    synchronized void nScriptSetVarD(int id, int slot, double val) {
        validate();
        rsnScriptSetVarD(mContext, id, slot, val);
    }
    native void rsnScriptSetVarV(int con, int id, int slot, byte[] val);
    synchronized void nScriptSetVarV(int id, int slot, byte[] val) {
        validate();
        rsnScriptSetVarV(mContext, id, slot, val);
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
    native int  rsnProgramFragmentCreate(int con, String shader, int[] params);
    synchronized int nProgramFragmentCreate(String shader, int[] params) {
        validate();
        return rsnProgramFragmentCreate(mContext, shader, params);
    }
    native int  rsnProgramVertexCreate(int con, String shader, int[] params);
    synchronized int nProgramVertexCreate(String shader, int[] params) {
        validate();
        return rsnProgramVertexCreate(mContext, shader, params);
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

    Element mElement_MATRIX_4X4;
    Element mElement_MATRIX_3X3;
    Element mElement_MATRIX_2X2;

    Sampler mSampler_CLAMP_NEAREST;
    Sampler mSampler_CLAMP_LINEAR;
    Sampler mSampler_CLAMP_LINEAR_MIP_LINEAR;
    Sampler mSampler_WRAP_NEAREST;
    Sampler mSampler_WRAP_LINEAR;
    Sampler mSampler_WRAP_LINEAR_MIP_LINEAR;

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
     * Base class application should derive from for handling RS messages
     * coming from their scripts.  When a script calls sendToClient the data
     * fields will be filled in and then the run method called by a message
     * handling thread.  This will occur some time after sendToClient completes
     * in the script.
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
     * If an application is expecting messages it should set this field to an
     * instance of RSMessage.  This instance will receive all the user messages
     * sent from sendToClient by scripts from this context.
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
     * Runtime error base class.  An application should derive from this class
     * if it wishes to install an error handler.  When errors occur at runtime
     * the fields in this class will be filled and the run method called.
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
     * RSRuntimeException will instead be thrown with details about the error.
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
     * RenderScript worker threads priority enumeration.  The default value is
     * NORMAL.  Applications wishing to do background processing such as
     * wallpapers should set their priority to LOW to avoid starving forground
     * processes.
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
                        throw new RSDriverException("Error processing message from Renderscript.");
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

                    if (subID >= RS_ERROR_FATAL_UNKNOWN) {
                        throw new RSRuntimeException("Fatal error " + subID + ", details: " + e);
                    }

                    if(mRS.mErrorCallback != null) {
                        mRS.mErrorCallback.mErrorMessage = e;
                        mRS.mErrorCallback.mErrorNum = subID;
                        mRS.mErrorCallback.run();
                    } else {
                        //throw new RSRuntimeException("Received error num " + subID + ", details: " + e);
                    }
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
            Log.d(LOG_TAG, "MessageThread exiting.");
        }
    }

    RenderScript(Context ctx) {
        mApplicationContext = ctx.getApplicationContext();
    }

    /**
     * Gets the application context associated with the RenderScript context.
     *
     * @return The application context.
     */
    public final Context getApplicationContext() {
        return mApplicationContext;
    }

    static int getTargetSdkVersion(Context ctx) {
        return ctx.getApplicationInfo().targetSdkVersion;
    }

    /**
     * Create a basic RenderScript context.
     *
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx) {
        RenderScript rs = new RenderScript(ctx);

        int sdkVersion = getTargetSdkVersion(ctx);

        rs.mDev = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(rs.mDev, 0, sdkVersion);
        if (rs.mContext == 0) {
            throw new RSDriverException("Failed to create RS context.");
        }
        rs.mMessageThread = new MessageThread(rs);
        rs.mMessageThread.start();
        return rs;
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
     * Wait for any commands in the fifo between the java bindings and native to
     * be processed.
     *
     */
    public void finish() {
        nContextFinish();
    }

    /**
     * Destroy this renderscript context.  Once this function is called its no
     * longer legal to use this or any objects created by this context.
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
            return o.getID();
        }
        return 0;
    }
}
