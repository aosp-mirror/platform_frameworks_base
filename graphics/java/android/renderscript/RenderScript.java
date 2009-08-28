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

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.renderscript.Type;
import android.util.Config;
import android.util.Log;
import android.view.Surface;


/**
 * @hide
 *
 **/
public class RenderScript {
    static final String LOG_TAG = "libRS_jni";
    private static final boolean DEBUG  = false;
    private static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;



     /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    private static boolean sInitialized;
    native private static void _nInit();


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

    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native int  nContextCreate(int dev, Surface sur, int ver, boolean useDepth);
    native void nContextDestroy(int con);

    //void rsContextBindSampler (uint32_t slot, RsSampler sampler);
    //void rsContextBindRootScript (RsScript sampler);
    native void nContextBindRootScript(int script);
    native void nContextBindSampler(int sampler, int slot);
    native void nContextBindProgramFragmentStore(int pfs);
    native void nContextBindProgramFragment(int pf);
    native void nContextBindProgramVertex(int pf);
    native void nContextAddDefineI32(String name, int value);
    native void nContextAddDefineF(String name, float value);

    native void nAssignName(int obj, byte[] name);
    native void nObjDestroy(int id);
    native void nObjDestroyOOB(int id);
    native int  nFileOpen(byte[] name);

    native void nElementBegin();
    native void nElementAddPredefined(int predef);
    native void nElementAdd(int kind, int type, int norm, int bits, String s);
    native int  nElementCreate();
    native int  nElementGetPredefined(int predef);

    native void nTypeBegin(int elementID);
    native void nTypeAdd(int dim, int val);
    native int  nTypeCreate();
    native void nTypeFinalDestroy(Type t);
    native void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs);

    native int  nAllocationCreateTyped(int type);
    native int  nAllocationCreatePredefSized(int predef, int count);
    native int  nAllocationCreateSized(int elem, int count);
    native int  nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateFromBitmapBoxed(int dstFmt, boolean genMips, Bitmap bmp);

    native void nAllocationUploadToTexture(int alloc, int baseMioLevel);
    native void nAllocationUploadToBufferObject(int alloc);
    native void nAllocationData(int id, int[] d, int sizeBytes);
    native void nAllocationData(int id, float[] d, int sizeBytes);
    native void nAllocationSubData1D(int id, int off, int count, int[] d, int sizeBytes);
    native void nAllocationSubData1D(int id, int off, int count, float[] d, int sizeBytes);
    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, int[] d, int sizeBytes);
    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, float[] d, int sizeBytes);
    native void nAllocationRead(int id, int[] d);
    native void nAllocationRead(int id, float[] d);
    native void nAllocationDataFromObject(int id, Type t, Object o);

    native void nTriangleMeshBegin(int vertex, int index);
    native void nTriangleMeshAddVertex_XY (float x, float y);
    native void nTriangleMeshAddVertex_XYZ (float x, float y, float z);
    native void nTriangleMeshAddVertex_XY_ST (float x, float y, float s, float t);
    native void nTriangleMeshAddVertex_XYZ_ST (float x, float y, float z, float s, float t);
    native void nTriangleMeshAddVertex_XYZ_ST_NORM (float x, float y, float z, float s, float t, float nx, float ny, float nz);
    native void nTriangleMeshAddTriangle(int i1, int i2, int i3);
    native int  nTriangleMeshCreate();

    native void nAdapter1DBindAllocation(int ad, int alloc);
    native void nAdapter1DSetConstraint(int ad, int dim, int value);
    native void nAdapter1DData(int ad, int[] d);
    native void nAdapter1DData(int ad, float[] d);
    native void nAdapter1DSubData(int ad, int off, int count, int[] d);
    native void nAdapter1DSubData(int ad, int off, int count, float[] d);
    native int  nAdapter1DCreate();

    native void nAdapter2DBindAllocation(int ad, int alloc);
    native void nAdapter2DSetConstraint(int ad, int dim, int value);
    native void nAdapter2DData(int ad, int[] d);
    native void nAdapter2DData(int ad, float[] d);
    native void nAdapter2DSubData(int ad, int xoff, int yoff, int w, int h, int[] d);
    native void nAdapter2DSubData(int ad, int xoff, int yoff, int w, int h, float[] d);
    native int  nAdapter2DCreate();

    native void nScriptBindAllocation(int script, int alloc, int slot);
    native void nScriptSetClearColor(int script, float r, float g, float b, float a);
    native void nScriptSetClearDepth(int script, float depth);
    native void nScriptSetClearStencil(int script, int stencil);
    native void nScriptSetTimeZone(int script, byte[] timeZone);
    native void nScriptSetType(int type, boolean writable, String name, int slot);
    native void nScriptSetRoot(boolean isRoot);

    native void nScriptCBegin();
    native void nScriptCSetScript(byte[] script, int offset, int length);
    native int  nScriptCCreate();
    native void nScriptCAddDefineI32(String name, int value);
    native void nScriptCAddDefineF(String name, float value);

    native void nSamplerBegin();
    native void nSamplerSet(int param, int value);
    native int  nSamplerCreate();

    native void nProgramFragmentStoreBegin(int in, int out);
    native void nProgramFragmentStoreDepthFunc(int func);
    native void nProgramFragmentStoreDepthMask(boolean enable);
    native void nProgramFragmentStoreColorMask(boolean r, boolean g, boolean b, boolean a);
    native void nProgramFragmentStoreBlendFunc(int src, int dst);
    native void nProgramFragmentStoreDither(boolean enable);
    native int  nProgramFragmentStoreCreate();

    native void nProgramFragmentBegin(int in, int out, boolean pointSpriteEnable);
    native void nProgramFragmentBindTexture(int vpf, int slot, int a);
    native void nProgramFragmentBindSampler(int vpf, int slot, int s);
    native void nProgramFragmentSetSlot(int slot, boolean enable, int env, int vt);
    native int  nProgramFragmentCreate();

    native void nProgramVertexBindAllocation(int pv, int mID);
    native void nProgramVertexBegin(int inID, int outID);
    native void nProgramVertexSetTextureMatrixEnable(boolean enable);
    native void nProgramVertexAddLight(int id);
    native int  nProgramVertexCreate();

    native void nLightBegin();
    native void nLightSetIsMono(boolean isMono);
    native void nLightSetIsLocal(boolean isLocal);
    native int  nLightCreate();
    native void nLightSetColor(int l, float r, float g, float b);
    native void nLightSetPosition(int l, float x, float y, float z);

    native int  nSimpleMeshCreate(int batchID, int idxID, int[] vtxID, int prim);
    native void nSimpleMeshBindVertex(int id, int alloc, int slot);
    native void nSimpleMeshBindIndex(int id, int alloc);

    native void nAnimationBegin(int attribCount, int keyframeCount);
    native void nAnimationAdd(float time, float[] attribs);
    native int  nAnimationCreate();

    private int     mDev;
    private int     mContext;
    private Surface mSurface;

    private static boolean mElementsInitialized = false;

    ///////////////////////////////////////////////////////////////////////////////////
    //

    public RenderScript(Surface sur, boolean useDepth) {
        mSurface = sur;
        mDev = nDeviceCreate();
        mContext = nContextCreate(mDev, mSurface, 0, useDepth);

        // TODO: This should be protected by a lock
        if(!mElementsInitialized) {
            Element.init(this);
            mElementsInitialized = true;
        }
    }

    public void destroy() {
        nContextDestroy(mContext);
        mContext = 0;

        nDeviceDestroy(mDev);
        mDev = 0;
    }

    //////////////////////////////////////////////////////////////////////////////////
    // Triangle Mesh

    public class TriangleMesh extends BaseObj {
        TriangleMesh(int id) {
            super(RenderScript.this);
            mID = id;
        }
    }

    public void triangleMeshBegin(Element vertex, Element index) {
        Log.e("rs", "vtx " + vertex.toString() + "  " + vertex.mID + "  " + vertex.mPredefinedID);
        nTriangleMeshBegin(vertex.mID, index.mID);
    }

    public void triangleMeshAddVertex_XY(float x, float y) {
        nTriangleMeshAddVertex_XY(x, y);
    }

    public void triangleMeshAddVertex_XYZ(float x, float y, float z) {
        nTriangleMeshAddVertex_XYZ(x, y, z);
    }

    public void triangleMeshAddVertex_XY_ST(float x, float y, float s, float t) {
        nTriangleMeshAddVertex_XY_ST(x, y, s, t);
    }

    public void triangleMeshAddVertex_XYZ_ST(float x, float y, float z, float s, float t) {
        nTriangleMeshAddVertex_XYZ_ST(x, y, z, s, t);
    }

    public void triangleMeshAddVertex_XYZ_ST_NORM(float x, float y, float z, float s, float t, float nx, float ny, float nz) {
        nTriangleMeshAddVertex_XYZ_ST_NORM(x, y, z, s, t, nx, ny, nz);
    }

    public void triangleMeshAddTriangle(int i1, int i2, int i3) {
        nTriangleMeshAddTriangle(i1, i2, i3);
    }

    public TriangleMesh triangleMeshCreate() {
        int id = nTriangleMeshCreate();
        return new TriangleMesh(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // File

    public class File extends BaseObj {
        File(int id) {
            super(RenderScript.this);
            mID = id;
        }
    }

    public File fileOpen(String s) throws IllegalStateException, IllegalArgumentException
    {
        if(s.length() < 1) {
            throw new IllegalArgumentException("fileOpen does not accept a zero length string.");
        }

        try {
            byte[] bytes = s.getBytes("UTF-8");
            int id = nFileOpen(bytes);
            return new File(id);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////
    // Root state

    public void contextBindRootScript(Script s) {
        int id = 0;
        if(s != null) {
            id = s.mID;
        }
        nContextBindRootScript(id);
    }

    //public void contextBindSampler(Sampler s, int slot) {
        //nContextBindSampler(s.mID);
    //}

    public void contextBindProgramFragmentStore(ProgramStore pfs) {
        nContextBindProgramFragmentStore(pfs.mID);
    }

    public void contextBindProgramFragment(ProgramFragment pf) {
        nContextBindProgramFragment(pf.mID);
    }

    public void contextBindProgramVertex(ProgramVertex pf) {
        nContextBindProgramVertex(pf.mID);
    }

}


