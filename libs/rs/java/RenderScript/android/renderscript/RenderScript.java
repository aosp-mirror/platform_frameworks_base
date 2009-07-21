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

import java.io.InputStream;
import java.io.IOException;

import android.os.Bundle;
import android.content.res.Resources;
import android.util.Log;
import android.util.Config;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.View;
import android.view.Surface;
import android.graphics.Bitmap;
import android.graphics.Color;

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
            System.loadLibrary("RS_jni");
            _nInit();
            sInitialized = true;
        } catch (UnsatisfiedLinkError e) {
            Log.d(LOG_TAG, "RenderScript JNI library not found!");
        }
    }

    native private int  nDeviceCreate();
    native private void nDeviceDestroy(int dev);
    native private int  nContextCreate(int dev, Surface sur, int ver);
    native private void nContextDestroy(int con);

    //void rsContextBindSampler (uint32_t slot, RsSampler sampler);
    //void rsContextBindRootScript (RsScript sampler);
    native private void nContextBindRootScript(int script);
    native private void nContextBindSampler(int sampler, int slot);
    native private void nContextBindProgramFragmentStore(int pfs);
    native private void nContextBindProgramFragment(int pf);
    native private void nContextBindProgramVertex(int pf);

    native private void nAssignName(int obj, byte[] name);
    native private int  nFileOpen(byte[] name);

    native private void nElementBegin();
    native private void nElementAddPredefined(int predef);
    native private void nElementAdd(int kind, int type, int norm, int bits);
    native private int  nElementCreate();
    native private int  nElementGetPredefined(int predef);
    native private void nElementDestroy(int obj);

    native private void nTypeBegin(int elementID);
    native private void nTypeAdd(int dim, int val);
    native private int  nTypeCreate();
    native private void nTypeDestroy(int id);

    native private int  nAllocationCreateTyped(int type);
    native private int  nAllocationCreatePredefSized(int predef, int count);
    native private int  nAllocationCreateSized(int elem, int count);
    native private int  nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp);

    native private void nAllocationUploadToTexture(int alloc, int baseMioLevel);
    native private void nAllocationDestroy(int alloc);
    native private void nAllocationData(int id, int[] d);
    native private void nAllocationData(int id, float[] d);
    native private void nAllocationSubData1D(int id, int off, int count, int[] d);
    native private void nAllocationSubData1D(int id, int off, int count, float[] d);
    native private void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, int[] d);
    native private void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, float[] d);

    native private void nTriangleMeshDestroy(int id);
    native private void nTriangleMeshBegin(int vertex, int index);
    native private void nTriangleMeshAddVertex_XY (float x, float y);
    native private void nTriangleMeshAddVertex_XYZ (float x, float y, float z);
    native private void nTriangleMeshAddVertex_XY_ST (float x, float y, float s, float t);
    native private void nTriangleMeshAddVertex_XYZ_ST (float x, float y, float z, float s, float t);
    native private void nTriangleMeshAddVertex_XYZ_ST_NORM (float x, float y, float z, float s, float t, float nx, float ny, float nz);
    native private void nTriangleMeshAddTriangle(int i1, int i2, int i3);
    native private int  nTriangleMeshCreate();

    native private void nAdapter1DDestroy(int id);
    native private void nAdapter1DBindAllocation(int ad, int alloc);
    native private void nAdapter1DSetConstraint(int ad, int dim, int value);
    native private void nAdapter1DData(int ad, int[] d);
    native private void nAdapter1DSubData(int ad, int off, int count, int[] d);
    native private void nAdapter1DData(int ad, float[] d);
    native private void nAdapter1DSubData(int ad, int off, int count, float[] d);
    native private int  nAdapter1DCreate();

    native private void nScriptDestroy(int script);
    native private void nScriptBindAllocation(int vtm, int alloc, int slot);
    native private void nScriptCBegin();
    native private void nScriptCSetClearColor(float r, float g, float b, float a);
    native private void nScriptCSetClearDepth(float depth);
    native private void nScriptCSetClearStencil(int stencil);
    native private void nScriptCAddType(int type);
    native private void nScriptCSetRoot(boolean isRoot);
    native private void nScriptCSetScript(byte[] script, int offset, int length);
    native private int  nScriptCCreate();

    native private void nSamplerDestroy(int sampler);
    native private void nSamplerBegin();
    native private void nSamplerSet(int param, int value);
    native private int  nSamplerCreate();

    native private void nProgramFragmentStoreBegin(int in, int out);
    native private void nProgramFragmentStoreDepthFunc(int func);
    native private void nProgramFragmentStoreDepthMask(boolean enable);
    native private void nProgramFragmentStoreColorMask(boolean r, boolean g, boolean b, boolean a);
    native private void nProgramFragmentStoreBlendFunc(int src, int dst);
    native private void nProgramFragmentStoreDither(boolean enable);
    native private int  nProgramFragmentStoreCreate();
    native private void nProgramFragmentStoreDestroy(int pgm);

    native private void nProgramFragmentBegin(int in, int out);
    native private void nProgramFragmentBindTexture(int vpf, int slot, int a);
    native private void nProgramFragmentBindSampler(int vpf, int slot, int s);
    native private void nProgramFragmentSetType(int slot, int vt);
    native private void nProgramFragmentSetEnvMode(int slot, int env);
    native private void nProgramFragmentSetTexEnable(int slot, boolean enable);
    native private int  nProgramFragmentCreate();
    native private void nProgramFragmentDestroy(int pgm);

    native private void nProgramVertexDestroy(int pv);
    native private void nProgramVertexBindAllocation(int pv, int slot, int mID);
    native private void nProgramVertexBegin(int inID, int outID);
    native private void nProgramVertexSetType(int slot, int mID);
    native private void nProgramVertexSetTextureMatrixEnable(boolean enable);
    native private void nProgramVertexAddLight(int id);
    native private int  nProgramVertexCreate();

    native private void nLightBegin();
    native private void nLightSetIsMono(boolean isMono);
    native private void nLightSetIsLocal(boolean isLocal);
    native private int  nLightCreate();
    native private void nLightDestroy(int l);
    native private void nLightSetColor(int l, float r, float g, float b);
    native private void nLightSetPosition(int l, float x, float y, float z);


    private int     mDev;
    private int     mContext;
    private Surface mSurface;



    ///////////////////////////////////////////////////////////////////////////////////
    //

    RenderScript(Surface sur) {
        mSurface = sur;
        mDev = nDeviceCreate();
        mContext = nContextCreate(mDev, mSurface, 0);
    }

    private class BaseObj {
        BaseObj() {
            mID = 0;
        }

        public int getID() {
            return mID;
        }

        int mID;
        String mName;

        public void setName(String s) throws IllegalStateException, IllegalArgumentException
        {
            if(s.length() < 1) {
                throw new IllegalArgumentException("setName does not accept a zero length string.");
            }
            if(mName != null) {
                throw new IllegalArgumentException("setName object already has a name.");
            }

            try {
                byte[] bytes = s.getBytes("UTF-8");
                nAssignName(mID, bytes);
                mName = s;
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        protected void finalize() throws Throwable
        {
            if (mID != 0) {
                Log.v(LOG_TAG,
                      "Element finalized without having released the RS reference.");
            }
            super.finalize();
        }
    }


    //////////////////////////////////////////////////////////////////////////////////
    // Element

    public enum ElementPredefined {
        USER_U8 (0),
        USER_I8 (1),
        USER_U16 (2),
        USER_I16 (3),
        USER_U32 (4),
        USER_I32 (5),
        USER_FLOAT (6),

        A_8                (7),
        RGB_565            (8),
        RGB_888            (11),
        RGBA_5551          (9),
        RGBA_4444          (10),
        RGBA_8888          (12),

        INDEX_16           (13),
        INDEX_32           (14),
        XY_F32             (15),
        XYZ_F32            (16),
        ST_XY_F32          (17),
        ST_XYZ_F32         (18),
        NORM_XYZ_F32       (19),
        NORM_ST_XYZ_F32    (20);

        int mID;
        ElementPredefined(int id) {
            mID = id;
        }
    }

    public enum DataType {
        FLOAT (0),
        UNSIGNED (1),
        SIGNED (2);

        int mID;
        DataType(int id) {
            mID = id;
        }
    }

    public enum DataKind {
        USER (0),
        RED (1),
        GREEN (2),
        BLUE (3),
        ALPHA (4),
        LUMINANCE (5),
        INTENSITY (6),
        X (7),
        Y (8),
        Z (9),
        W (10),
        S (11),
        T (12),
        Q (13),
        R (14),
        NX (15),
        NY (16),
        NZ (17),
        INDEX (18);

        int mID;
        DataKind(int id) {
            mID = id;
        }
    }

    public enum DepthFunc {
        ALWAYS (0),
        LESS (1),
        LEQUAL (2),
        GREATER (3),
        GEQUAL (4),
        EQUAL (5),
        NOTEQUAL (6);

        int mID;
        DepthFunc(int id) {
            mID = id;
        }
    }

    public enum BlendSrcFunc {
        ZERO (0),
        ONE (1),
        DST_COLOR (2),
        ONE_MINUS_DST_COLOR (3),
        SRC_ALPHA (4),
        ONE_MINUS_SRC_ALPHA (5),
        DST_ALPHA (6),
        ONE_MINUS_DST_ALPA (7),
        SRC_ALPHA_SATURATE (8);

        int mID;
        BlendSrcFunc(int id) {
            mID = id;
        }
    }

    public enum BlendDstFunc {
        ZERO (0),
        ONE (1),
        SRC_COLOR (2),
        ONE_MINUS_SRC_COLOR (3),
        SRC_ALPHA (4),
        ONE_MINUS_SRC_ALPHA (5),
        DST_ALPHA (6),
        ONE_MINUS_DST_ALPA (7);

        int mID;
        BlendDstFunc(int id) {
            mID = id;
        }
    }

    public enum EnvMode {
        REPLACE (0),
        MODULATE (1),
        DECAL (2);

        int mID;
        EnvMode(int id) {
            mID = id;
        }
    }

    public enum SamplerParam {
        FILTER_MIN (0),
        FILTER_MAG (1),
        WRAP_MODE_S (2),
        WRAP_MODE_T (3),
        WRAP_MODE_R (4);

        int mID;
        SamplerParam(int id) {
            mID = id;
        }
    }

    public enum SamplerValue {
        NEAREST (0),
        LINEAR (1),
        LINEAR_MIP_LINEAR (2),
        WRAP (3),
        CLAMP (4);

        int mID;
        SamplerValue(int id) {
            mID = id;
        }
    }



    public class Element extends BaseObj {
        Element(int id) {
            mID = id;
        }

        public void estroy() {
            nElementDestroy(mID);
            mID = 0;
        }
    }

    public void elementBegin() {
        nElementBegin();
    }

    public void elementAddPredefined(ElementPredefined e) {
        nElementAddPredefined(e.mID);
    }

    public void elementAdd(DataType dt, DataKind dk, boolean isNormalized, int bits) {
        int norm = 0;
        if (isNormalized) {
            norm = 1;
        }
        nElementAdd(dt.mID, dk.mID, norm, bits);
    }

    public Element elementCreate() {
        int id = nElementCreate();
        return new Element(id);
    }

    public Element elementGetPredefined(ElementPredefined predef) {
        int id = nElementGetPredefined(predef.mID);
        return new Element(id);
    }


    //////////////////////////////////////////////////////////////////////////////////
    // Type

    public enum Dimension {
        X (0),
        Y (1),
        Z (2),
        LOD (3),
        FACE (4),
        ARRAY_0 (100);

        int mID;
        Dimension(int id) {
            mID = id;
        }
    }

    public class Type extends BaseObj {
        Type(int id) {
            mID = id;
        }

        public void destroy() {
            nTypeDestroy(mID);
            mID = 0;
        }
    }

    public void typeBegin(Element e) {
        nTypeBegin(e.mID);
    }

    public void typeAdd(Dimension d, int value) {
        nTypeAdd(d.mID, value);
    }

    public Type typeCreate() {
        int id = nTypeCreate();
        return new Type(id);
    }


    //////////////////////////////////////////////////////////////////////////////////
    // Allocation

    public class Allocation extends BaseObj {
        Allocation(int id) {
            mID = id;
        }

        public void uploadToTexture(int baseMipLevel) {
            nAllocationUploadToTexture(mID, baseMipLevel);
        }

        public void destroy() {
            nAllocationDestroy(mID);
            mID = 0;
        }

        public void data(int[] d) {
            nAllocationData(mID, d);
        }

        public void data(float[] d) {
            nAllocationData(mID, d);
        }

        public void subData1D(int off, int count, int[] d) {
            nAllocationSubData1D(mID, off, count, d);
        }

        public void subData1D(int off, int count, float[] d) {
            nAllocationSubData1D(mID, off, count, d);
        }

        public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
            nAllocationSubData2D(mID, xoff, yoff, w, h, d);
        }

        public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
            nAllocationSubData2D(mID, xoff, yoff, w, h, d);
        }
    }

    public Allocation allocationCreateTyped(Type type) {
        int id = nAllocationCreateTyped(type.mID);
        return new Allocation(id);
    }

    public Allocation allocationCreatePredefSized(ElementPredefined e, int count) {
        int id = nAllocationCreatePredefSized(e.mID, count);
        return new Allocation(id);
    }

    public Allocation allocationCreateSized(Element e, int count) {
        int id = nAllocationCreateSized(e.mID, count);
        return new Allocation(id);
    }

    public Allocation allocationCreateFromBitmap(Bitmap b, ElementPredefined dstFmt, boolean genMips) {
        int id = nAllocationCreateFromBitmap(dstFmt.mID, genMips, b); 
        return new Allocation(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // Adapter1D

    public class Adapter1D extends BaseObj {
        Adapter1D(int id) {
            mID = id;
        }

        public void destroy() {
            nAdapter1DDestroy(mID);
            mID = 0;
        }

        public void bindAllocation(Allocation a) {
            nAdapter1DBindAllocation(mID, a.mID);
        }

        public void setConstraint(Dimension dim, int value) {
            nAdapter1DSetConstraint(mID, dim.mID, value);
        }

        public void data(int[] d) {
            nAdapter1DData(mID, d);
        }

        public void subData(int off, int count, int[] d) {
            nAdapter1DSubData(mID, off, count, d);
        }

        public void data(float[] d) {
            nAdapter1DData(mID, d);
        }

        public void subData(int off, int count, float[] d) {
            nAdapter1DSubData(mID, off, count, d);
        }
    }

    public Adapter1D adapter1DCreate() {
        int id = nAdapter1DCreate();
        return new Adapter1D(id);
    }


    //////////////////////////////////////////////////////////////////////////////////
    // Triangle Mesh

    public class TriangleMesh extends BaseObj {
        TriangleMesh(int id) {
            mID = id;
        }

        public void destroy() {
            nTriangleMeshDestroy(mID);
            mID = 0;
        }
    }

    public void triangleMeshBegin(Element vertex, Element index) {
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
    // Script

    public class Script extends BaseObj {
        Script(int id) {
            mID = id;
        }

        public void destroy() {
            nScriptDestroy(mID);
            mID = 0;
        }

        public void bindAllocation(Allocation va, int slot) {
            nScriptBindAllocation(mID, va.mID, slot);
        }
    }

    public void scriptCBegin() {
        nScriptCBegin();
    }

    public void scriptCSetClearColor(float r, float g, float b, float a) {
        nScriptCSetClearColor(r, g, b, a);
    }

    public void scriptCSetClearDepth(float d) {
        nScriptCSetClearDepth(d);
    }

    public void scriptCSetClearStencil(int stencil) {
        nScriptCSetClearStencil(stencil);
    }

    public void scriptCAddType(Type t) {
        nScriptCAddType(t.mID);
    }

    public void scriptCSetRoot(boolean r) {
        nScriptCSetRoot(r);
    }

    public void scriptCSetScript(String s) {
        try {
            byte[] bytes = s.getBytes("UTF-8");
            nScriptCSetScript(bytes, 0, bytes.length);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public void scriptCSetScript(Resources resources, int id) {
        InputStream is = resources.openRawResource(id);
        try {
            try {
                scriptCSetScript(is);
            } finally {
                is.close();
            }
        } catch(IOException e) {
            throw new Resources.NotFoundException();
        }
    }

    public void  scriptCSetScript(InputStream is) throws IOException {
        byte[] buf = new byte[1024];
        int currentPos = 0;
        while(true) {
            int bytesLeft = buf.length - currentPos;
            if (bytesLeft == 0) {
                byte[] buf2 = new byte[buf.length * 2];
                System.arraycopy(buf, 0, buf2, 0, buf.length);
                buf = buf2;
                bytesLeft = buf.length - currentPos;
            }
            int bytesRead = is.read(buf, currentPos, bytesLeft);
            if (bytesRead <= 0) {
                break;
            }
            currentPos += bytesRead;
        }
        nScriptCSetScript(buf, 0, currentPos);
    }

    public Script scriptCCreate() {
        int id = nScriptCCreate();
        return new Script(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // ProgramVertex

    public class ProgramVertex extends BaseObj {
        ProgramVertex(int id) {
            mID = id;
        }

        public void destroy() {
            nProgramVertexDestroy(mID);
            mID = 0;
        }

        public void bindAllocation(int slot, Allocation va) {
            nProgramVertexBindAllocation(mID, slot, va.mID);
        }
    }

    public void programVertexBegin(Element in, Element out) {
        int inID = 0;
        int outID = 0;
        if (in != null) {
            inID = in.mID;
        }
        if (out != null) {
            outID = out.mID;
        }
        nProgramVertexBegin(inID, outID);
    }

    public void programVertexSetType(int slot, Type t) {
        nProgramVertexSetType(slot, t.mID);
    }

    public void programVertexSetTextureMatrixEnable(boolean enable) {
        nProgramVertexSetTextureMatrixEnable(enable);
    }

    public void programVertexAddLight(Light l) {
        nProgramVertexAddLight(l.mID);
    }

    public ProgramVertex programVertexCreate() {
        int id = nProgramVertexCreate();
        return new ProgramVertex(id);
    }


    //////////////////////////////////////////////////////////////////////////////////
    // ProgramFragmentStore

    public class ProgramFragmentStore extends BaseObj {
        ProgramFragmentStore(int id) {
            mID = id;
        }

        public void destroy() {
            nProgramFragmentStoreDestroy(mID);
            mID = 0;
        }
    }

    public void programFragmentStoreBegin(Element in, Element out) {
        int inID = 0;
        int outID = 0;
        if (in != null) {
            inID = in.mID;
        }
        if (out != null) {
            outID = out.mID;
        }
        nProgramFragmentStoreBegin(inID, outID);
    }

    public void programFragmentStoreDepthFunc(DepthFunc func) {
        nProgramFragmentStoreDepthFunc(func.mID);
    }

    public void programFragmentStoreDepthMask(boolean enable) {
        nProgramFragmentStoreDepthMask(enable);
    }

    public void programFragmentStoreColorMask(boolean r, boolean g, boolean b, boolean a) {
        nProgramFragmentStoreColorMask(r,g,b,a);
    }

    public void programFragmentStoreBlendFunc(BlendSrcFunc src, BlendDstFunc dst) {
        nProgramFragmentStoreBlendFunc(src.mID, dst.mID);
    }

    public void programFragmentStoreDitherEnable(boolean enable) {
        nProgramFragmentStoreDither(enable);
    }

    public ProgramFragmentStore programFragmentStoreCreate() {
        int id = nProgramFragmentStoreCreate();
        return new ProgramFragmentStore(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // ProgramFragment

    public class ProgramFragment extends BaseObj {
        ProgramFragment(int id) {
            mID = id;
        }

        public void destroy() {
            nProgramFragmentDestroy(mID);
            mID = 0;
        }

        public void bindTexture(Allocation va, int slot) {
            nProgramFragmentBindTexture(mID, slot, va.mID);
        }

        public void bindSampler(Sampler vs, int slot) {
            nProgramFragmentBindSampler(mID, slot, vs.mID);
        }
    }

    public void programFragmentBegin(Element in, Element out) {
        int inID = 0;
        int outID = 0;
        if (in != null) {
            inID = in.mID;
        }
        if (out != null) {
            outID = out.mID;
        }
        nProgramFragmentBegin(inID, outID);
    }

    public void programFragmentSetType(int slot, Type t) {
        nProgramFragmentSetType(slot, t.mID);
    }

    public void programFragmentSetType(int slot, EnvMode t) {
        nProgramFragmentSetEnvMode(slot, t.mID);
    }

    public void programFragmentSetTexEnable(int slot, boolean enable) {
        nProgramFragmentSetTexEnable(slot, enable);
    }

    public void programFragmentSetTexEnvMode(int slot, EnvMode env) {
        nProgramFragmentSetEnvMode(slot, env.mID);
    }

    public ProgramFragment programFragmentCreate() {
        int id = nProgramFragmentCreate();
        return new ProgramFragment(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // Sampler

    public class Sampler extends BaseObj {
        Sampler(int id) {
            mID = id;
        }

        public void destroy() {
            nSamplerDestroy(mID);
            mID = 0;
        }
    }

    public void samplerBegin() {
        nSamplerBegin();
    }

    public void samplerSet(SamplerParam p, SamplerValue v) {
        nSamplerSet(p.mID, v.mID);
    }

    public Sampler samplerCreate() {
        int id = nSamplerCreate();
        return new Sampler(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // Light

    public class Light extends BaseObj {
        Light(int id) {
            mID = id;
        }

        public void destroy() {
            nLightDestroy(mID);
            mID = 0;
        }

        public void setColor(float r, float g, float b) {
            nLightSetColor(mID, r, g, b);
        }

        public void setPosition(float x, float y, float z) {
            nLightSetPosition(mID, x, y, z);
        }
    }

    public void lightBegin() {
        nLightBegin();
    }

    public void lightSetIsMono(boolean isMono) {
        nLightSetIsMono(isMono);
    }

    public void lightSetIsLocal(boolean isLocal) {
        nLightSetIsLocal(isLocal);
    }

    public Light lightCreate() {
        int id = nLightCreate();
        return new Light(id);
    }

    //////////////////////////////////////////////////////////////////////////////////
    // File

    public class File extends BaseObj {
        File(int id) {
            mID = id;
        }

        public void destroy() {
            //nLightDestroy(mID);
            mID = 0;
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
        nContextBindRootScript(s.mID);
    }

    //public void contextBindSampler(Sampler s, int slot) {
        //nContextBindSampler(s.mID);
    //}

    public void contextBindProgramFragmentStore(ProgramFragmentStore pfs) {
        nContextBindProgramFragmentStore(pfs.mID);
    }

    public void contextBindProgramFragment(ProgramFragment pf) {
        nContextBindProgramFragment(pf.mID);
    }

    public void contextBindProgramVertex(ProgramVertex pf) {
        nContextBindProgramVertex(pf.mID);
    }

/*
    RsAdapter2D rsAdapter2DCreate ();
    void rsAdapter2DBindAllocation (RsAdapter2D adapt, RsAllocation alloc);
    void rsAdapter2DDestroy (RsAdapter2D adapter);
    void rsAdapter2DSetConstraint (RsAdapter2D adapter, RsDimension dim, uint32_t value);
    void rsAdapter2DData (RsAdapter2D adapter, const void * data);
    void rsAdapter2DSubData (RsAdapter2D adapter, uint32_t xoff, uint32_t yoff, uint32_t w, uint32_t h, const void * data);
    void rsSamplerBegin ();
    void rsSamplerSet (RsSamplerParam p, RsSamplerValue value);
    RsSampler rsSamplerCreate ();
    void rsSamplerBind (RsSampler sampler, RsAllocation alloc);
*/

}

