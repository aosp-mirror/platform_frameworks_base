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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Config;
import android.util.Log;
import android.view.Surface;


/**
 * @hide
 *
 * RenderScript base master class.  An instance of this class creates native
 * worker threads for processing commands from this object.  This base class
 * does not provide any extended capabilities beyond simple data processing.
 * For extended capabilities use derived classes such as RenderScriptGL.
 *
 *
 *
 **/
public class RenderScript {
    static final String LOG_TAG = "RenderScript_jni";
    static final boolean DEBUG  = false;
    @SuppressWarnings({"UnusedDeclaration", "deprecation"})
    static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;

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
            Log.d(LOG_TAG, "RenderScript JNI library not found!");
        }
    }

    // Non-threadsafe functions.
    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native void nDeviceSetConfig(int dev, int param, int value);
    native void nContextGetUserMessage(int con, int[] data);
    native String nContextGetErrorMessage(int con);
    native int  nContextPeekMessage(int con, int[] subID, boolean wait);
    native void nContextInitToClient(int con);
    native void nContextDeinitToClient(int con);


    // Methods below are wrapped to protect the non-threadsafe
    // lockless fifo.
    native int  rsnContextCreateGL(int dev, int ver,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ);
    synchronized int nContextCreateGL(int dev, int ver,
                 int colorMin, int colorPref,
                 int alphaMin, int alphaPref,
                 int depthMin, int depthPref,
                 int stencilMin, int stencilPref,
                 int samplesMin, int samplesPref, float samplesQ) {
        return rsnContextCreateGL(dev, ver, colorMin, colorPref,
                                  alphaMin, alphaPref, depthMin, depthPref,
                                  stencilMin, stencilPref,
                                  samplesMin, samplesPref, samplesQ);
    }
    native int  rsnContextCreate(int dev, int ver);
    synchronized int nContextCreate(int dev, int ver) {
        return rsnContextCreate(dev, ver);
    }
    native void rsnContextDestroy(int con);
    synchronized void nContextDestroy() {
        rsnContextDestroy(mContext);
    }
    native void rsnContextSetSurface(int con, int w, int h, Surface sur);
    synchronized void nContextSetSurface(int w, int h, Surface sur) {
        rsnContextSetSurface(mContext, w, h, sur);
    }
    native void rsnContextSetPriority(int con, int p);
    synchronized void nContextSetPriority(int p) {
        rsnContextSetPriority(mContext, p);
    }
    native void rsnContextDump(int con, int bits);
    synchronized void nContextDump(int bits) {
        rsnContextDump(mContext, bits);
    }
    native void rsnContextFinish(int con);
    synchronized void nContextFinish() {
        rsnContextFinish(mContext);
    }

    native void rsnContextBindRootScript(int con, int script);
    synchronized void nContextBindRootScript(int script) {
        rsnContextBindRootScript(mContext, script);
    }
    native void rsnContextBindSampler(int con, int sampler, int slot);
    synchronized void nContextBindSampler(int sampler, int slot) {
        rsnContextBindSampler(mContext, sampler, slot);
    }
    native void rsnContextBindProgramStore(int con, int pfs);
    synchronized void nContextBindProgramStore(int pfs) {
        rsnContextBindProgramStore(mContext, pfs);
    }
    native void rsnContextBindProgramFragment(int con, int pf);
    synchronized void nContextBindProgramFragment(int pf) {
        rsnContextBindProgramFragment(mContext, pf);
    }
    native void rsnContextBindProgramVertex(int con, int pv);
    synchronized void nContextBindProgramVertex(int pv) {
        rsnContextBindProgramVertex(mContext, pv);
    }
    native void rsnContextBindProgramRaster(int con, int pr);
    synchronized void nContextBindProgramRaster(int pr) {
        rsnContextBindProgramRaster(mContext, pr);
    }
    native void rsnContextPause(int con);
    synchronized void nContextPause() {
        rsnContextPause(mContext);
    }
    native void rsnContextResume(int con);
    synchronized void nContextResume() {
        rsnContextResume(mContext);
    }

    native void rsnAssignName(int con, int obj, byte[] name);
    synchronized void nAssignName(int obj, byte[] name) {
        rsnAssignName(mContext, obj, name);
    }
    native String rsnGetName(int con, int obj);
    synchronized String nGetName(int obj) {
        return rsnGetName(mContext, obj);
    }
    native void rsnObjDestroy(int con, int id);
    synchronized void nObjDestroy(int id) {
        rsnObjDestroy(mContext, id);
    }

    native int  rsnElementCreate(int con, int type, int kind, boolean norm, int vecSize);
    synchronized int nElementCreate(int type, int kind, boolean norm, int vecSize) {
        return rsnElementCreate(mContext, type, kind, norm, vecSize);
    }
    native int  rsnElementCreate2(int con, int[] elements, String[] names, int[] arraySizes);
    synchronized int nElementCreate2(int[] elements, String[] names, int[] arraySizes) {
        return rsnElementCreate2(mContext, elements, names, arraySizes);
    }
    native void rsnElementGetNativeData(int con, int id, int[] elementData);
    synchronized void nElementGetNativeData(int id, int[] elementData) {
        rsnElementGetNativeData(mContext, id, elementData);
    }
    native void rsnElementGetSubElements(int con, int id, int[] IDs, String[] names);
    synchronized void nElementGetSubElements(int id, int[] IDs, String[] names) {
        rsnElementGetSubElements(mContext, id, IDs, names);
    }

    native int rsnTypeCreate(int con, int eid, int x, int y, int z, boolean mips, boolean faces);
    synchronized int nTypeCreate(int eid, int x, int y, int z, boolean mips, boolean faces) {
        return rsnTypeCreate(mContext, eid, x, y, z, mips, faces);
    }
    native void rsnTypeGetNativeData(int con, int id, int[] typeData);
    synchronized void nTypeGetNativeData(int id, int[] typeData) {
        rsnTypeGetNativeData(mContext, id, typeData);
    }

    native int  rsnAllocationCreateTyped(int con, int type, int mip, int usage);
    synchronized int nAllocationCreateTyped(int type, int mip, int usage) {
        return rsnAllocationCreateTyped(mContext, type, mip, usage);
    }
    native int  rsnAllocationCreateFromBitmap(int con, int type, int mip, Bitmap bmp, int usage);
    synchronized int nAllocationCreateFromBitmap(int type, int mip, Bitmap bmp, int usage) {
        return rsnAllocationCreateFromBitmap(mContext, type, mip, bmp, usage);
    }
    native int  rsnAllocationCubeCreateFromBitmap(int con, int type, int mip, Bitmap bmp, int usage);
    synchronized int nAllocationCubeCreateFromBitmap(int type, int mip, Bitmap bmp, int usage) {
        return rsnAllocationCubeCreateFromBitmap(mContext, type, mip, bmp, usage);
    }
    native int  rsnAllocationCreateBitmapRef(int con, int type, Bitmap bmp);
    synchronized int nAllocationCreateBitmapRef(int type, Bitmap bmp) {
        return rsnAllocationCreateBitmapRef(mContext, type, bmp);
    }
    native int  rsnAllocationCreateFromAssetStream(int con, int mips, int assetStream, int usage);
    synchronized int nAllocationCreateFromAssetStream(int mips, int assetStream, int usage) {
        return rsnAllocationCreateFromAssetStream(mContext, mips, assetStream, usage);
    }

    native void  rsnAllocationCopyToBitmap(int con, int alloc, Bitmap bmp);
    synchronized void nAllocationCopyToBitmap(int alloc, Bitmap bmp) {
        rsnAllocationCopyToBitmap(mContext, alloc, bmp);
    }


    native void rsnAllocationSyncAll(int con, int alloc, int src);
    synchronized void nAllocationSyncAll(int alloc, int src) {
        rsnAllocationSyncAll(mContext, alloc, src);
    }
    native void  rsnAllocationCopyFromBitmap(int con, int alloc, Bitmap bmp);
    synchronized void nAllocationCopyFromBitmap(int alloc, Bitmap bmp) {
        rsnAllocationCopyFromBitmap(mContext, alloc, bmp);
    }

    native void rsnAllocationUploadToTexture(int con, int alloc, boolean genMips, int baseMioLevel);
    synchronized void nAllocationUploadToTexture(int alloc, boolean genMips, int baseMioLevel) {
        rsnAllocationUploadToTexture(mContext, alloc, genMips, baseMioLevel);
    }
    native void rsnAllocationUploadToBufferObject(int con, int alloc);
    synchronized void nAllocationUploadToBufferObject(int alloc) {
        rsnAllocationUploadToBufferObject(mContext, alloc);
    }

    native void rsnAllocationSubData1D(int con, int id, int off, int count, int[] d, int sizeBytes);
    synchronized void nAllocationSubData1D(int id, int off, int count, int[] d, int sizeBytes) {
        rsnAllocationSubData1D(mContext, id, off, count, d, sizeBytes);
    }
    native void rsnAllocationSubData1D(int con, int id, int off, int count, short[] d, int sizeBytes);
    synchronized void nAllocationSubData1D(int id, int off, int count, short[] d, int sizeBytes) {
        rsnAllocationSubData1D(mContext, id, off, count, d, sizeBytes);
    }
    native void rsnAllocationSubData1D(int con, int id, int off, int count, byte[] d, int sizeBytes);
    synchronized void nAllocationSubData1D(int id, int off, int count, byte[] d, int sizeBytes) {
        rsnAllocationSubData1D(mContext, id, off, count, d, sizeBytes);
    }
    native void rsnAllocationSubElementData1D(int con, int id, int xoff, int compIdx, byte[] d, int sizeBytes);
    synchronized void nAllocationSubElementData1D(int id, int xoff, int compIdx, byte[] d, int sizeBytes) {
        rsnAllocationSubElementData1D(mContext, id, xoff, compIdx, d, sizeBytes);
    }
    native void rsnAllocationSubData1D(int con, int id, int off, int count, float[] d, int sizeBytes);
    synchronized void nAllocationSubData1D(int id, int off, int count, float[] d, int sizeBytes) {
        rsnAllocationSubData1D(mContext, id, off, count, d, sizeBytes);
    }

    native void rsnAllocationSubData2D(int con, int id, int xoff, int yoff, int w, int h, int[] d, int sizeBytes);
    synchronized void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, int[] d, int sizeBytes) {
        rsnAllocationSubData2D(mContext, id, xoff, yoff, w, h, d, sizeBytes);
    }
    native void rsnAllocationSubData2D(int con, int id, int xoff, int yoff, int w, int h, float[] d, int sizeBytes);
    synchronized void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, float[] d, int sizeBytes) {
        rsnAllocationSubData2D(mContext, id, xoff, yoff, w, h, d, sizeBytes);
    }
    native void rsnAllocationRead(int con, int id, int[] d);
    synchronized void nAllocationRead(int id, int[] d) {
        rsnAllocationRead(mContext, id, d);
    }
    native void rsnAllocationRead(int con, int id, float[] d);
    synchronized void nAllocationRead(int id, float[] d) {
        rsnAllocationRead(mContext, id, d);
    }
    native int  rsnAllocationGetType(int con, int id);
    synchronized int nAllocationGetType(int id) {
        return rsnAllocationGetType(mContext, id);
    }

    native void rsnAllocationResize1D(int con, int id, int dimX);
    synchronized void nAllocationResize1D(int id, int dimX) {
        rsnAllocationResize1D(mContext, id, dimX);
    }
    native void rsnAllocationResize2D(int con, int id, int dimX, int dimY);
    synchronized void nAllocationResize2D(int id, int dimX, int dimY) {
        rsnAllocationResize2D(mContext, id, dimX, dimY);
    }

    native int  rsnFileA3DCreateFromAssetStream(int con, int assetStream);
    synchronized int nFileA3DCreateFromAssetStream(int assetStream) {
        return rsnFileA3DCreateFromAssetStream(mContext, assetStream);
    }
    native int  rsnFileA3DGetNumIndexEntries(int con, int fileA3D);
    synchronized int nFileA3DGetNumIndexEntries(int fileA3D) {
        return rsnFileA3DGetNumIndexEntries(mContext, fileA3D);
    }
    native void rsnFileA3DGetIndexEntries(int con, int fileA3D, int numEntries, int[] IDs, String[] names);
    synchronized void nFileA3DGetIndexEntries(int fileA3D, int numEntries, int[] IDs, String[] names) {
        rsnFileA3DGetIndexEntries(mContext, fileA3D, numEntries, IDs, names);
    }
    native int  rsnFileA3DGetEntryByIndex(int con, int fileA3D, int index);
    synchronized int nFileA3DGetEntryByIndex(int fileA3D, int index) {
        return rsnFileA3DGetEntryByIndex(mContext, fileA3D, index);
    }

    native int  rsnFontCreateFromFile(int con, String fileName, int size, int dpi);
    synchronized int nFontCreateFromFile(String fileName, int size, int dpi) {
        return rsnFontCreateFromFile(mContext, fileName, size, dpi);
    }

    native void rsnAdapter1DBindAllocation(int con, int ad, int alloc);
    synchronized void nAdapter1DBindAllocation(int ad, int alloc) {
        rsnAdapter1DBindAllocation(mContext, ad, alloc);
    }
    native void rsnAdapter1DSetConstraint(int con, int ad, int dim, int value);
    synchronized void nAdapter1DSetConstraint(int ad, int dim, int value) {
        rsnAdapter1DSetConstraint(mContext, ad, dim, value);
    }
    native void rsnAdapter1DData(int con, int ad, int[] d);
    synchronized void nAdapter1DData(int ad, int[] d) {
        rsnAdapter1DData(mContext, ad, d);
    }
    native void rsnAdapter1DData(int con, int ad, float[] d);
    synchronized void nAdapter1DData(int ad, float[] d) {
        rsnAdapter1DData(mContext, ad, d);
    }
    native void rsnAdapter1DSubData(int con, int ad, int off, int count, int[] d);
    synchronized void nAdapter1DSubData(int ad, int off, int count, int[] d) {
        rsnAdapter1DSubData(mContext, ad, off, count, d);
    }
    native void rsnAdapter1DSubData(int con, int ad, int off, int count, float[] d);
    synchronized void nAdapter1DSubData(int ad, int off, int count, float[] d) {
        rsnAdapter1DSubData(mContext, ad, off, count, d);
    }
    native int  rsnAdapter1DCreate(int con);
    synchronized int nAdapter1DCreate() {
        return rsnAdapter1DCreate(mContext);
    }

    native void rsnAdapter2DBindAllocation(int con, int ad, int alloc);
    synchronized void nAdapter2DBindAllocation(int ad, int alloc) {
        rsnAdapter2DBindAllocation(mContext, ad, alloc);
    }
    native void rsnAdapter2DSetConstraint(int con, int ad, int dim, int value);
    synchronized void nAdapter2DSetConstraint(int ad, int dim, int value) {
        rsnAdapter2DSetConstraint(mContext, ad, dim, value);
    }
    native void rsnAdapter2DData(int con, int ad, int[] d);
    synchronized void nAdapter2DData(int ad, int[] d) {
        rsnAdapter2DData(mContext, ad, d);
    }
    native void rsnAdapter2DData(int con, int ad, float[] d);
    synchronized void nAdapter2DData(int ad, float[] d) {
        rsnAdapter2DData(mContext, ad, d);
    }
    native void rsnAdapter2DSubData(int con, int ad, int xoff, int yoff, int w, int h, int[] d);
    synchronized void nAdapter2DSubData(int ad, int xoff, int yoff, int w, int h, int[] d) {
        rsnAdapter2DSubData(mContext, ad, xoff, yoff, w, h, d);
    }
    native void rsnAdapter2DSubData(int con, int ad, int xoff, int yoff, int w, int h, float[] d);
    synchronized void nAdapter2DSubData(int ad, int xoff, int yoff, int w, int h, float[] d) {
        rsnAdapter2DSubData(mContext, ad, xoff, yoff, w, h, d);
    }
    native int  rsnAdapter2DCreate(int con);
    synchronized int nAdapter2DCreate() {
        return rsnAdapter2DCreate(mContext);
    }

    native void rsnScriptBindAllocation(int con, int script, int alloc, int slot);
    synchronized void nScriptBindAllocation(int script, int alloc, int slot) {
        rsnScriptBindAllocation(mContext, script, alloc, slot);
    }
    native void rsnScriptSetTimeZone(int con, int script, byte[] timeZone);
    synchronized void nScriptSetTimeZone(int script, byte[] timeZone) {
        rsnScriptSetTimeZone(mContext, script, timeZone);
    }
    native void rsnScriptInvoke(int con, int id, int slot);
    synchronized void nScriptInvoke(int id, int slot) {
        rsnScriptInvoke(mContext, id, slot);
    }
    native void rsnScriptInvokeV(int con, int id, int slot, byte[] params);
    synchronized void nScriptInvokeV(int id, int slot, byte[] params) {
        rsnScriptInvokeV(mContext, id, slot, params);
    }
    native void rsnScriptSetVarI(int con, int id, int slot, int val);
    synchronized void nScriptSetVarI(int id, int slot, int val) {
        rsnScriptSetVarI(mContext, id, slot, val);
    }
    native void rsnScriptSetVarJ(int con, int id, int slot, long val);
    synchronized void nScriptSetVarJ(int id, int slot, long val) {
        rsnScriptSetVarJ(mContext, id, slot, val);
    }
    native void rsnScriptSetVarF(int con, int id, int slot, float val);
    synchronized void nScriptSetVarF(int id, int slot, float val) {
        rsnScriptSetVarF(mContext, id, slot, val);
    }
    native void rsnScriptSetVarD(int con, int id, int slot, double val);
    synchronized void nScriptSetVarD(int id, int slot, double val) {
        rsnScriptSetVarD(mContext, id, slot, val);
    }
    native void rsnScriptSetVarV(int con, int id, int slot, byte[] val);
    synchronized void nScriptSetVarV(int id, int slot, byte[] val) {
        rsnScriptSetVarV(mContext, id, slot, val);
    }
    native void rsnScriptSetVarObj(int con, int id, int slot, int val);
    synchronized void nScriptSetVarObj(int id, int slot, int val) {
        rsnScriptSetVarObj(mContext, id, slot, val);
    }

    native void rsnScriptCBegin(int con);
    synchronized void nScriptCBegin() {
        rsnScriptCBegin(mContext);
    }
    native void rsnScriptCSetScript(int con, byte[] script, int offset, int length);
    synchronized void nScriptCSetScript(byte[] script, int offset, int length) {
        rsnScriptCSetScript(mContext, script, offset, length);
    }
    native int  rsnScriptCCreate(int con, String val, String cacheDir);
    synchronized int nScriptCCreate(String resName, String cacheDir) {
      return rsnScriptCCreate(mContext, resName, cacheDir);
    }

    native void rsnSamplerBegin(int con);
    synchronized void nSamplerBegin() {
        rsnSamplerBegin(mContext);
    }
    native void rsnSamplerSet(int con, int param, int value);
    synchronized void nSamplerSet(int param, int value) {
        rsnSamplerSet(mContext, param, value);
    }
    native void rsnSamplerSet2(int con, int param, float value);
    synchronized void nSamplerSet2(int param, float value) {
        rsnSamplerSet2(mContext, param, value);
    }
    native int  rsnSamplerCreate(int con);
    synchronized int nSamplerCreate() {
        return rsnSamplerCreate(mContext);
    }

    native void rsnProgramStoreBegin(int con, int in, int out);
    synchronized void nProgramStoreBegin(int in, int out) {
        rsnProgramStoreBegin(mContext, in, out);
    }
    native void rsnProgramStoreDepthFunc(int con, int func);
    synchronized void nProgramStoreDepthFunc(int func) {
        rsnProgramStoreDepthFunc(mContext, func);
    }
    native void rsnProgramStoreDepthMask(int con, boolean enable);
    synchronized void nProgramStoreDepthMask(boolean enable) {
        rsnProgramStoreDepthMask(mContext, enable);
    }
    native void rsnProgramStoreColorMask(int con, boolean r, boolean g, boolean b, boolean a);
    synchronized void nProgramStoreColorMask(boolean r, boolean g, boolean b, boolean a) {
        rsnProgramStoreColorMask(mContext, r, g, b, a);
    }
    native void rsnProgramStoreBlendFunc(int con, int src, int dst);
    synchronized void nProgramStoreBlendFunc(int src, int dst) {
        rsnProgramStoreBlendFunc(mContext, src, dst);
    }
    native void rsnProgramStoreDither(int con, boolean enable);
    synchronized void nProgramStoreDither(boolean enable) {
        rsnProgramStoreDither(mContext, enable);
    }
    native int  rsnProgramStoreCreate(int con);
    synchronized int nProgramStoreCreate() {
        return rsnProgramStoreCreate(mContext);
    }

    native int  rsnProgramRasterCreate(int con, boolean pointSmooth, boolean lineSmooth, boolean pointSprite);
    synchronized int nProgramRasterCreate(boolean pointSmooth, boolean lineSmooth, boolean pointSprite) {
        return rsnProgramRasterCreate(mContext, pointSmooth, lineSmooth, pointSprite);
    }
    native void rsnProgramRasterSetLineWidth(int con, int pr, float v);
    synchronized void nProgramRasterSetLineWidth(int pr, float v) {
        rsnProgramRasterSetLineWidth(mContext, pr, v);
    }
    native void rsnProgramRasterSetCullMode(int con, int pr, int mode);
    synchronized void nProgramRasterSetCullMode(int pr, int mode) {
        rsnProgramRasterSetCullMode(mContext, pr, mode);
    }

    native void rsnProgramBindConstants(int con, int pv, int slot, int mID);
    synchronized void nProgramBindConstants(int pv, int slot, int mID) {
        rsnProgramBindConstants(mContext, pv, slot, mID);
    }
    native void rsnProgramBindTexture(int con, int vpf, int slot, int a);
    synchronized void nProgramBindTexture(int vpf, int slot, int a) {
        rsnProgramBindTexture(mContext, vpf, slot, a);
    }
    native void rsnProgramBindSampler(int con, int vpf, int slot, int s);
    synchronized void nProgramBindSampler(int vpf, int slot, int s) {
        rsnProgramBindSampler(mContext, vpf, slot, s);
    }
    native int  rsnProgramFragmentCreate(int con, String shader, int[] params);
    synchronized int nProgramFragmentCreate(String shader, int[] params) {
        return rsnProgramFragmentCreate(mContext, shader, params);
    }
    native int  rsnProgramVertexCreate(int con, String shader, int[] params);
    synchronized int nProgramVertexCreate(String shader, int[] params) {
        return rsnProgramVertexCreate(mContext, shader, params);
    }

    native int  rsnMeshCreate(int con, int vtxCount, int indexCount);
    synchronized int nMeshCreate(int vtxCount, int indexCount) {
        return rsnMeshCreate(mContext, vtxCount, indexCount);
    }
    native void rsnMeshBindVertex(int con, int id, int alloc, int slot);
    synchronized void nMeshBindVertex(int id, int alloc, int slot) {
        rsnMeshBindVertex(mContext, id, alloc, slot);
    }
    native void rsnMeshBindIndex(int con, int id, int alloc, int prim, int slot);
    synchronized void nMeshBindIndex(int id, int alloc, int prim, int slot) {
        rsnMeshBindIndex(mContext, id, alloc, prim, slot);
    }
    native void rsnMeshInitVertexAttribs(int con, int id);
    synchronized void nMeshInitVertexAttribs(int id) {
        rsnMeshInitVertexAttribs(mContext, id);
    }
    native int  rsnMeshGetVertexBufferCount(int con, int id);
    synchronized int nMeshGetVertexBufferCount(int id) {
        return rsnMeshGetVertexBufferCount(mContext, id);
    }
    native int  rsnMeshGetIndexCount(int con, int id);
    synchronized int nMeshGetIndexCount(int id) {
        return rsnMeshGetIndexCount(mContext, id);
    }
    native void rsnMeshGetVertices(int con, int id, int[] vtxIds, int vtxIdCount);
    synchronized void nMeshGetVertices(int id, int[] vtxIds, int vtxIdCount) {
        rsnMeshGetVertices(mContext, id, vtxIds, vtxIdCount);
    }
    native void rsnMeshGetIndices(int con, int id, int[] idxIds, int[] primitives, int vtxIdCount);
    synchronized void nMeshGetIndices(int id, int[] idxIds, int[] primitives, int vtxIdCount) {
        rsnMeshGetIndices(mContext, id, idxIds, primitives, vtxIdCount);
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
    Element mElement_UCHAR_4;

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
    ProgramStore mProgramStore_BLEND_NONE_DEPTH_NO_TEST;
    ProgramStore mProgramStore_BLEND_NONE_DEPTH_NO_WRITE;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_TEST;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_NO_DEPTH;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_NO_TEST;
    ProgramStore mProgramStore_BLEND_ALPHA_DEPTH_NO_WRITE;
    ProgramStore mProgramStore_BLEND_ADD_DEPTH_TEST;
    ProgramStore mProgramStore_BLEND_ADD_DEPTH_NO_DEPTH;
    ProgramStore mProgramStore_BLEND_ADD_DEPTH_NO_TEST;
    ProgramStore mProgramStore_BLEND_ADD_DEPTH_NO_WRITE;

    ProgramRaster mProgramRaster_CULL_BACK;
    ProgramRaster mProgramRaster_CULL_FRONT;
    ProgramRaster mProgramRaster_CULL_NONE;

    ///////////////////////////////////////////////////////////////////////////////////
    //

    /**
     * Base class application should derive from for handling RS messages
     * comming from their scripts.  When a script calls sendToClient the data
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
        // Remap these numbers to opaque...
        LOW (5),     //ANDROID_PRIORITY_BACKGROUND + 5
        NORMAL (-4);  //ANDROID_PRIORITY_DISPLAY

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
                int msg = mRS.nContextPeekMessage(mRS.mContext, mAuxData, true);
                int size = mAuxData[1];
                int subID = mAuxData[0];

                if (msg == RS_MESSAGE_TO_CLIENT_USER) {
                    if ((size>>2) >= rbuf.length) {
                        rbuf = new int[(size + 3) >> 2];
                    }
                    mRS.nContextGetUserMessage(mRS.mContext, rbuf);

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

    /**
     * Create a basic RenderScript context.
     *
     * @param ctx The context.
     * @return RenderScript
     */
    public static RenderScript create(Context ctx) {
        RenderScript rs = new RenderScript(ctx);

        rs.mDev = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(rs.mDev, 0);
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
