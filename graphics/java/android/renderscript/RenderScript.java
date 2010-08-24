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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Config;
import android.util.Log;
import android.view.Surface;


/**
 * @hide
 *
 **/
public class RenderScript {
    static final String LOG_TAG = "RenderScript_jni";
    protected static final boolean DEBUG  = false;
    @SuppressWarnings({"UnusedDeclaration", "deprecation"})
    protected static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;



     /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
    protected static boolean sInitialized;
    native protected static void _nInit();


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
    native void nInitElements(int a8, int rgba4444, int rgba8888, int rgb565);
    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native void nDeviceSetConfig(int dev, int param, int value);
    native int  nContextGetMessage(int con, int[] data, boolean wait);
    native void nContextInitToClient(int con);
    native void nContextDeinitToClient(int con);


    // Methods below are wrapped to protect the non-threadsafe
    // lockless fifo.
    native int  rsnContextCreateGL(int dev, int ver, boolean useDepth);
    synchronized int nContextCreateGL(int dev, int ver, boolean useDepth) {
        return rsnContextCreateGL(dev, ver, useDepth);
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
    native int  rsnFileOpen(int con, byte[] name);
    synchronized int nFileOpen(byte[] name) {
        return rsnFileOpen(mContext, name);
    }

    native int  rsnElementCreate(int con, int type, int kind, boolean norm, int vecSize);
    synchronized int nElementCreate(int type, int kind, boolean norm, int vecSize) {
        return rsnElementCreate(mContext, type, kind, norm, vecSize);
    }
    native int  rsnElementCreate2(int con, int[] elements, String[] names);
    synchronized int nElementCreate2(int[] elements, String[] names) {
        return rsnElementCreate2(mContext, elements, names);
    }
    native void rsnElementGetNativeData(int con, int id, int[] elementData);
    synchronized void nElementGetNativeData(int id, int[] elementData) {
        rsnElementGetNativeData(mContext, id, elementData);
    }
    native void rsnElementGetSubElements(int con, int id, int[] IDs, String[] names);
    synchronized void nElementGetSubElements(int id, int[] IDs, String[] names) {
        rsnElementGetSubElements(mContext, id, IDs, names);
    }

    native void rsnTypeBegin(int con, int elementID);
    synchronized void nTypeBegin(int elementID) {
        rsnTypeBegin(mContext, elementID);
    }
    native void rsnTypeAdd(int con, int dim, int val);
    synchronized void nTypeAdd(int dim, int val) {
        rsnTypeAdd(mContext, dim, val);
    }
    native int  rsnTypeCreate(int con);
    synchronized int nTypeCreate() {
        return rsnTypeCreate(mContext);
    }
    native void rsnTypeFinalDestroy(int con, Type t);
    synchronized void nTypeFinalDestroy(Type t) {
        rsnTypeFinalDestroy(mContext, t);
    }
    native void rsnTypeSetupFields(int con, Type t, int[] types, int[] bits, Field[] IDs);
    synchronized void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs) {
        rsnTypeSetupFields(mContext, t, types, bits, IDs);
    }
    native void rsnTypeGetNativeData(int con, int id, int[] typeData);
    synchronized void nTypeGetNativeData(int id, int[] typeData) {
        rsnTypeGetNativeData(mContext, id, typeData);
    }

    native int  rsnAllocationCreateTyped(int con, int type);
    synchronized int nAllocationCreateTyped(int type) {
        return rsnAllocationCreateTyped(mContext, type);
    }
    native int  rsnAllocationCreateFromBitmap(int con, int dstFmt, boolean genMips, Bitmap bmp);
    synchronized int nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp) {
        return rsnAllocationCreateFromBitmap(mContext, dstFmt, genMips, bmp);
    }
    native int  rsnAllocationCreateBitmapRef(int con, int type, Bitmap bmp);
    synchronized int nAllocationCreateBitmapRef(int type, Bitmap bmp) {
        return rsnAllocationCreateBitmapRef(mContext, type, bmp);
    }
    native int  rsnAllocationCreateFromBitmapBoxed(int con, int dstFmt, boolean genMips, Bitmap bmp);
    synchronized int nAllocationCreateFromBitmapBoxed(int dstFmt, boolean genMips, Bitmap bmp) {
        return rsnAllocationCreateFromBitmapBoxed(mContext, dstFmt, genMips, bmp);
    }
    native int  rsnAllocationCreateFromAssetStream(int con, int dstFmt, boolean genMips, int assetStream);
    synchronized int nAllocationCreateFromAssetStream(int dstFmt, boolean genMips, int assetStream) {
        return rsnAllocationCreateFromAssetStream(mContext, dstFmt, genMips, assetStream);
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
    native void rsnAllocationSubDataFromObject(int con, int id, Type t, int offset, Object o);
    synchronized void nAllocationSubDataFromObject(int id, Type t, int offset, Object o) {
        rsnAllocationSubDataFromObject(mContext, id, t, offset, o);
    }
    native void rsnAllocationSubReadFromObject(int con, int id, Type t, int offset, Object o);
    synchronized void nAllocationSubReadFromObject(int id, Type t, int offset, Object o) {
        rsnAllocationSubReadFromObject(mContext, id, t, offset, o);
    }
    native int  rsnAllocationGetType(int con, int id);
    synchronized int nAllocationGetType(int id) {
        return rsnAllocationGetType(mContext, id);
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
    native void rsnScriptSetVarF(int con, int id, int slot, float val);
    synchronized void nScriptSetVarF(int id, int slot, float val) {
        rsnScriptSetVarF(mContext, id, slot, val);
    }
    native void rsnScriptSetVarV(int con, int id, int slot, byte[] val);
    synchronized void nScriptSetVarV(int id, int slot, byte[] val) {
        rsnScriptSetVarV(mContext, id, slot, val);
    }

    native void rsnScriptCBegin(int con);
    synchronized void nScriptCBegin() {
        rsnScriptCBegin(mContext);
    }
    native void rsnScriptCSetScript(int con, byte[] script, int offset, int length);
    synchronized void nScriptCSetScript(byte[] script, int offset, int length) {
        rsnScriptCSetScript(mContext, script, offset, length);
    }
    native int  rsnScriptCCreate(int con);
    synchronized int nScriptCCreate() {
        return rsnScriptCCreate(mContext);
    }

    native void rsnSamplerBegin(int con);
    synchronized void nSamplerBegin() {
        rsnSamplerBegin(mContext);
    }
    native void rsnSamplerSet(int con, int param, int value);
    synchronized void nSamplerSet(int param, int value) {
        rsnSamplerSet(mContext, param, value);
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

    native int  rsnProgramFragmentCreate(int con, int[] params);
    synchronized int nProgramFragmentCreate(int[] params) {
        return rsnProgramFragmentCreate(mContext, params);
    }
    native int  rsnProgramFragmentCreate2(int con, String shader, int[] params);
    synchronized int nProgramFragmentCreate2(String shader, int[] params) {
        return rsnProgramFragmentCreate2(mContext, shader, params);
    }

    native int  rsnProgramVertexCreate(int con, boolean texMat);
    synchronized int nProgramVertexCreate(boolean texMat) {
        return rsnProgramVertexCreate(mContext, texMat);
    }
    native int  rsnProgramVertexCreate2(int con, String shader, int[] params);
    synchronized int nProgramVertexCreate2(String shader, int[] params) {
        return rsnProgramVertexCreate2(mContext, shader, params);
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


    protected int     mDev;
    protected int     mContext;
    @SuppressWarnings({"FieldCanBeLocal"})
    protected MessageThread mMessageThread;

    Element mElement_U8;
    Element mElement_I8;
    Element mElement_U16;
    Element mElement_I16;
    Element mElement_U32;
    Element mElement_I32;
    Element mElement_F32;
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

    public static class RSMessage implements Runnable {
        protected int[] mData;
        protected int mID;
        public void run() {
        }
    }
    public RSMessage mMessageCallback = null;

    public enum Priority {
        LOW (5),     //ANDROID_PRIORITY_BACKGROUND + 5
        NORMAL (-4);  //ANDROID_PRIORITY_DISPLAY

        int mID;
        Priority(int id) {
            mID = id;
        }
    }

    void validate() {
        if (mContext == 0) {
            throw new IllegalStateException("Calling RS with no Context active.");
        }
    }

    public void contextSetPriority(Priority p) {
        validate();
        nContextSetPriority(p.mID);
    }

    protected static class MessageThread extends Thread {
        RenderScript mRS;
        boolean mRun = true;

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
                int msg = mRS.nContextGetMessage(mRS.mContext, rbuf, true);
                if (msg == 0) {
                    // Should only happen during teardown.
                    // But we want to avoid starving other threads during
                    // teardown by yielding until the next line in the destructor
                    // can execute to set mRun = false
                    try {
                        sleep(1, 0);
                    } catch(InterruptedException e) {
                    }
                }
                if(mRS.mMessageCallback != null) {
                    mRS.mMessageCallback.mData = rbuf;
                    mRS.mMessageCallback.mID = msg;
                    mRS.mMessageCallback.run();
                }
            }
            Log.d(LOG_TAG, "MessageThread exiting.");
        }
    }

    protected RenderScript() {
    }

    public static RenderScript create() {
        RenderScript rs = new RenderScript();

        rs.mDev = rs.nDeviceCreate();
        rs.mContext = rs.nContextCreate(rs.mDev, 0);
        rs.mMessageThread = new MessageThread(rs);
        rs.mMessageThread.start();
        Element.initPredefined(rs);
        return rs;
    }

    public void contextDump(int bits) {
        validate();
        nContextDump(bits);
    }

    public void finish() {
        nContextFinish();
    }

    public void destroy() {
        validate();
        nContextDeinitToClient(mContext);
        mMessageThread.mRun = false;

        nContextDestroy();
        mContext = 0;

        nDeviceDestroy(mDev);
        mDev = 0;
    }

    boolean isAlive() {
        return mContext != 0;
    }

    ///////////////////////////////////////////////////////////////////////////////////
    // Root state

    protected int safeID(BaseObj o) {
        if(o != null) {
            return o.mID;
        }
        return 0;
    }
}



