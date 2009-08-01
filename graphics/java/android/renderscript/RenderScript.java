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

import android.content.res.Resources;
import android.graphics.Bitmap;
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
    native int  nContextCreate(int dev, Surface sur, int ver);
    native void nContextDestroy(int con);

    //void rsContextBindSampler (uint32_t slot, RsSampler sampler);
    //void rsContextBindRootScript (RsScript sampler);
    native void nContextBindRootScript(int script);
    native void nContextBindSampler(int sampler, int slot);
    native void nContextBindProgramFragmentStore(int pfs);
    native void nContextBindProgramFragment(int pf);
    native void nContextBindProgramVertex(int pf);

    native void nAssignName(int obj, byte[] name);
    native int  nFileOpen(byte[] name);

    native void nElementBegin();
    native void nElementAddPredefined(int predef);
    native void nElementAdd(int kind, int type, int norm, int bits);
    native int  nElementCreate();
    native int  nElementGetPredefined(int predef);
    native void nElementDestroy(int obj);

    native void nTypeBegin(int elementID);
    native void nTypeAdd(int dim, int val);
    native int  nTypeCreate();
    native void nTypeDestroy(int id);

    native int  nAllocationCreateTyped(int type);
    native int  nAllocationCreatePredefSized(int predef, int count);
    native int  nAllocationCreateSized(int elem, int count);
    native int  nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateFromBitmapBoxed(int dstFmt, boolean genMips, Bitmap bmp);

    native void nAllocationUploadToTexture(int alloc, int baseMioLevel);
    native void nAllocationDestroy(int alloc);
    native void nAllocationData(int id, int[] d);
    native void nAllocationData(int id, float[] d);
    native void nAllocationSubData1D(int id, int off, int count, int[] d);
    native void nAllocationSubData1D(int id, int off, int count, float[] d);
    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, int[] d);
    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, float[] d);

    native void nTriangleMeshDestroy(int id);
    native void nTriangleMeshBegin(int vertex, int index);
    native void nTriangleMeshAddVertex_XY (float x, float y);
    native void nTriangleMeshAddVertex_XYZ (float x, float y, float z);
    native void nTriangleMeshAddVertex_XY_ST (float x, float y, float s, float t);
    native void nTriangleMeshAddVertex_XYZ_ST (float x, float y, float z, float s, float t);
    native void nTriangleMeshAddVertex_XYZ_ST_NORM (float x, float y, float z, float s, float t, float nx, float ny, float nz);
    native void nTriangleMeshAddTriangle(int i1, int i2, int i3);
    native int  nTriangleMeshCreate();

    native void nAdapter1DDestroy(int id);
    native void nAdapter1DBindAllocation(int ad, int alloc);
    native void nAdapter1DSetConstraint(int ad, int dim, int value);
    native void nAdapter1DData(int ad, int[] d);
    native void nAdapter1DSubData(int ad, int off, int count, int[] d);
    native void nAdapter1DData(int ad, float[] d);
    native void nAdapter1DSubData(int ad, int off, int count, float[] d);
    native int  nAdapter1DCreate();

    native void nScriptDestroy(int script);
    native void nScriptBindAllocation(int vtm, int alloc, int slot);
    native void nScriptCBegin();
    native void nScriptCSetClearColor(float r, float g, float b, float a);
    native void nScriptCSetClearDepth(float depth);
    native void nScriptCSetClearStencil(int stencil);
    native void nScriptCSetTimeZone(byte[] timeZone);
    native void nScriptCAddType(int type);
    native void nScriptCSetRoot(boolean isRoot);
    native void nScriptCSetScript(byte[] script, int offset, int length);
    native int  nScriptCCreate();

    native void nSamplerDestroy(int sampler);
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
    native void nProgramFragmentStoreDestroy(int pgm);

    native void nProgramFragmentBegin(int in, int out);
    native void nProgramFragmentBindTexture(int vpf, int slot, int a);
    native void nProgramFragmentBindSampler(int vpf, int slot, int s);
    native void nProgramFragmentSetType(int slot, int vt);
    native void nProgramFragmentSetEnvMode(int slot, int env);
    native void nProgramFragmentSetTexEnable(int slot, boolean enable);
    native int  nProgramFragmentCreate();
    native void nProgramFragmentDestroy(int pgm);

    native void nProgramVertexDestroy(int pv);
    native void nProgramVertexBindAllocation(int pv, int slot, int mID);
    native void nProgramVertexBegin(int inID, int outID);
    native void nProgramVertexSetType(int slot, int mID);
    native void nProgramVertexSetTextureMatrixEnable(boolean enable);
    native void nProgramVertexAddLight(int id);
    native int  nProgramVertexCreate();

    native void nLightBegin();
    native void nLightSetIsMono(boolean isMono);
    native void nLightSetIsLocal(boolean isLocal);
    native int  nLightCreate();
    native void nLightDestroy(int l);
    native void nLightSetColor(int l, float r, float g, float b);
    native void nLightSetPosition(int l, float x, float y, float z);


    private int     mDev;
    private int     mContext;
    private Surface mSurface;

    private static boolean mElementsInitialized = false;

    ///////////////////////////////////////////////////////////////////////////////////
    //

    RenderScript(Surface sur) {
        mSurface = sur;
        mDev = nDeviceCreate();
        mContext = nContextCreate(mDev, mSurface, 0);

        // TODO: This should be protected by a lock
        if(!mElementsInitialized) {
            Element.init(this);
            mElementsInitialized = true;
        }
    }

    //////////////////////////////////////////////////////////////////////////////////
    // Element

    Element.Builder mElementBuilder = new Element.Builder(this);
    public Element.Builder elementBuilderCreate() throws IllegalStateException {
        mElementBuilder.begin();
        return mElementBuilder;
    }

    Type.Builder mTypeBuilder = new Type.Builder(this);
    public Type.Builder typeBuilderCreate(Element e) throws IllegalStateException {
        mTypeBuilder.begin(e);
        return mTypeBuilder;
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

    //////////////////////////////////////////////////////////////////////////////////
    // Triangle Mesh

    public class TriangleMesh extends BaseObj {
        TriangleMesh(int id) {
            super(RenderScript.this);
            mID = id;
        }

        public void destroy() {
            nTriangleMeshDestroy(mID);
            mID = 0;
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
    // Script

    public class Script extends BaseObj {
        Script(int id) {
            super(RenderScript.this);
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

    public void scriptCSetTimeZone(String timeZone) {
        try {
            byte[] bytes = timeZone.getBytes("UTF-8");
            nScriptCSetTimeZone(bytes);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
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
            super(RenderScript.this);
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
            super(RenderScript.this);
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
            super(RenderScript.this);
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
            super(RenderScript.this);
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
            super(RenderScript.this);
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
            super(RenderScript.this);
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


