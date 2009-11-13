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
    static final String LOG_TAG = "libRS_jni";
    private static final boolean DEBUG  = false;
    @SuppressWarnings({"UnusedDeclaration", "deprecation"})
    private static final boolean LOG_ENABLED = DEBUG ? Config.LOGD : Config.LOGV;



     /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    @SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
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

    native void nInitElements(int a8, int rgba4444, int rgba8888, int rgb565);

    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native void nDeviceSetConfig(int dev, int param, int value);
    native int  nContextCreate(int dev, Surface sur, int ver, boolean useDepth);
    native void nContextDestroy(int con);
    native void nContextSetSurface(Surface sur);

    native void nContextBindRootScript(int script);
    native void nContextBindSampler(int sampler, int slot);
    native void nContextBindProgramFragmentStore(int pfs);
    native void nContextBindProgramFragment(int pf);
    native void nContextBindProgramVertex(int pf);
    native void nContextBindProgramRaster(int pr);
    native void nContextAddDefineI32(String name, int value);
    native void nContextAddDefineF(String name, float value);
    native void nContextPause();
    native void nContextResume();
    native int nContextGetMessage(int[] data, boolean wait);
    native void nContextInitToClient();
    native void nContextDeinitToClient();

    native void nAssignName(int obj, byte[] name);
    native void nObjDestroy(int id);
    native void nObjDestroyOOB(int id);
    native int  nFileOpen(byte[] name);

    native void nElementBegin();
    native void nElementAdd(int kind, int type, boolean norm, int bits, String s);
    native int  nElementCreate();

    native void nTypeBegin(int elementID);
    native void nTypeAdd(int dim, int val);
    native int  nTypeCreate();
    native void nTypeFinalDestroy(Type t);
    native void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs);

    native int  nAllocationCreateTyped(int type);
    native int  nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateFromBitmapBoxed(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateFromAssetStream(int dstFmt, boolean genMips, int assetStream);

    native void nAllocationUploadToTexture(int alloc, int baseMioLevel);
    native void nAllocationUploadToBufferObject(int alloc);

    native void nAllocationSubData1D(int id, int off, int count, int[] d, int sizeBytes);
    native void nAllocationSubData1D(int id, int off, int count, short[] d, int sizeBytes);
    native void nAllocationSubData1D(int id, int off, int count, byte[] d, int sizeBytes);
    native void nAllocationSubData1D(int id, int off, int count, float[] d, int sizeBytes);

    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, int[] d, int sizeBytes);
    native void nAllocationSubData2D(int id, int xoff, int yoff, int w, int h, float[] d, int sizeBytes);
    native void nAllocationRead(int id, int[] d);
    native void nAllocationRead(int id, float[] d);
    native void nAllocationSubDataFromObject(int id, Type t, int offset, Object o);
    native void nAllocationSubReadFromObject(int id, Type t, int offset, Object o);

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
    native void nScriptSetInvokable(String name, int slot);
    native void nScriptInvoke(int id, int slot);

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

    native int  nProgramRasterCreate(int in, int out, boolean pointSmooth, boolean lineSmooth, boolean pointSprite);
    native void nProgramRasterSetLineWidth(int pr, float v);
    native void nProgramRasterSetPointSize(int pr, float v);

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
    @SuppressWarnings({"FieldCanBeLocal"})
    private Surface mSurface;
    private MessageThread mMessageThread;


    Element mElement_USER_U8;
    Element mElement_USER_I8;
    Element mElement_USER_U16;
    Element mElement_USER_I16;
    Element mElement_USER_U32;
    Element mElement_USER_I32;
    Element mElement_USER_FLOAT;

    Element mElement_A_8;
    Element mElement_RGB_565;
    Element mElement_RGB_888;
    Element mElement_RGBA_5551;
    Element mElement_RGBA_4444;
    Element mElement_RGBA_8888;

    Element mElement_INDEX_16;
    Element mElement_XY_F32;
    Element mElement_XYZ_F32;

    ///////////////////////////////////////////////////////////////////////////////////
    //

    public static class RSMessage implements Runnable {
        protected int[] mData;
        protected int mID;
        public void run() {
        }
    }
    public RSMessage mMessageCallback = null;

    private static class MessageThread extends Thread {
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
            mRS.nContextInitToClient();
            while(mRun) {
                int msg = mRS.nContextGetMessage(rbuf, true);
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
                //Log.d("rs", "MessageThread msg " + msg + " v1 " + rbuf[0] + " v2 " + rbuf[1] + " v3 " +rbuf[2]);
            }
            Log.d("rs", "MessageThread exiting.");
        }
    }

    public RenderScript(Surface sur, boolean useDepth, boolean forceSW) {
        mSurface = sur;
        mDev = nDeviceCreate();
        if(forceSW) {
            nDeviceSetConfig(mDev, 0, 1);
        }
        mContext = nContextCreate(mDev, mSurface, 0, useDepth);
        Element.initPredefined(this);
        mMessageThread = new MessageThread(this);
        mMessageThread.start();
    }

    public void contextSetSurface(Surface sur) {
        mSurface = sur;
        nContextSetSurface(mSurface);
    }

    public void destroy() {
        nContextDeinitToClient();
        mMessageThread.mRun = false;

        nContextDestroy(mContext);
        mContext = 0;

        nDeviceDestroy(mDev);
        mDev = 0;
    }

    boolean isAlive() {
        return mContext != 0;
    }

    void pause() {
        nContextPause();
    }

    void resume() {
        nContextResume();
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

    private int safeID(BaseObj o) {
        if(o != null) {
            return o.mID;
        }
        return 0;
    }

    public void contextBindRootScript(Script s) {
        nContextBindRootScript(safeID(s));
    }

    public void contextBindProgramFragmentStore(ProgramStore p) {
        nContextBindProgramFragmentStore(safeID(p));
    }

    public void contextBindProgramFragment(ProgramFragment p) {
        nContextBindProgramFragment(safeID(p));
    }

    public void contextBindProgramRaster(ProgramRaster p) {
        nContextBindProgramRaster(safeID(p));
    }

    public void contextBindProgramVertex(ProgramVertex p) {
        nContextBindProgramVertex(safeID(p));
    }

}


