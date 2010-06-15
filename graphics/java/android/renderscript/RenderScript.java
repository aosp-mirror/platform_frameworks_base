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

    native void nInitElements(int a8, int rgba4444, int rgba8888, int rgb565);

    native int  nDeviceCreate();
    native void nDeviceDestroy(int dev);
    native void nDeviceSetConfig(int dev, int param, int value);
    native int  nContextCreateGL(int dev, int ver, boolean useDepth);
    native int  nContextCreate(int dev, int ver);
    native void nContextDestroy(int con);
    native void nContextSetSurface(int w, int h, Surface sur);
    native void nContextSetPriority(int p);
    native void nContextDump(int bits);
    native void nContextFinish();

    native void nContextBindRootScript(int script);
    native void nContextBindSampler(int sampler, int slot);
    native void nContextBindProgramStore(int pfs);
    native void nContextBindProgramFragment(int pf);
    native void nContextBindProgramVertex(int pf);
    native void nContextBindProgramRaster(int pr);
    native void nContextPause();
    native void nContextResume();
    native int nContextGetMessage(int[] data, boolean wait);
    native void nContextInitToClient();
    native void nContextDeinitToClient();

    native void nAssignName(int obj, byte[] name);
    native void nObjDestroy(int id);
    native void nObjDestroyOOB(int id);
    native int  nFileOpen(byte[] name);


    native int  nElementCreate(int type, int kind, boolean norm, int vecSize);
    native int  nElementCreate2(int[] elements, String[] names);

    native void nTypeBegin(int elementID);
    native void nTypeAdd(int dim, int val);
    native int  nTypeCreate();
    native void nTypeFinalDestroy(Type t);
    native void nTypeSetupFields(Type t, int[] types, int[] bits, Field[] IDs);

    native int  nAllocationCreateTyped(int type);
    native int  nAllocationCreateFromBitmap(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateBitmapRef(int type, Bitmap bmp);
    native int  nAllocationCreateFromBitmapBoxed(int dstFmt, boolean genMips, Bitmap bmp);
    native int  nAllocationCreateFromAssetStream(int dstFmt, boolean genMips, int assetStream);

    native void nAllocationUploadToTexture(int alloc, boolean genMips, int baseMioLevel);
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

    native int  nFileA3DCreateFromAssetStream(int assetStream);
    native int  nFileA3DGetNumIndexEntries(int fileA3D);
    native void nFileA3DGetIndexEntries(int fileA3D, int numEntries, int[] IDs, String[] names);
    native int  nFileA3DGetEntryByIndex(int fileA3D, int index);

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
    native void nScriptSetTimeZone(int script, byte[] timeZone);
    native void nScriptInvoke(int id, int slot);
    native void nScriptInvokeV(int id, int slot, byte[] params);
    native void nScriptSetVarI(int id, int slot, int val);
    native void nScriptSetVarF(int id, int slot, float val);
    native void nScriptSetVarV(int id, int slot, byte[] val);

    native void nScriptCBegin();
    native void nScriptCSetScript(byte[] script, int offset, int length);
    native int  nScriptCCreate();

    native void nSamplerBegin();
    native void nSamplerSet(int param, int value);
    native int  nSamplerCreate();

    native void nProgramStoreBegin(int in, int out);
    native void nProgramStoreDepthFunc(int func);
    native void nProgramStoreDepthMask(boolean enable);
    native void nProgramStoreColorMask(boolean r, boolean g, boolean b, boolean a);
    native void nProgramStoreBlendFunc(int src, int dst);
    native void nProgramStoreDither(boolean enable);
    native int  nProgramStoreCreate();

    native int  nProgramRasterCreate(int in, int out, boolean pointSmooth, boolean lineSmooth, boolean pointSprite);
    native void nProgramRasterSetLineWidth(int pr, float v);
    native void nProgramRasterSetPointSize(int pr, float v);

    native void nProgramBindConstants(int pv, int slot, int mID);
    native void nProgramBindTexture(int vpf, int slot, int a);
    native void nProgramBindSampler(int vpf, int slot, int s);

    native int  nProgramFragmentCreate(int[] params);
    native int  nProgramFragmentCreate2(String shader, int[] params);

    native int  nProgramVertexCreate(boolean texMat);
    native int  nProgramVertexCreate2(String shader, int[] params);

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

    ///////////////////////////////////////////////////////////////////////////////////
    // Root state

    protected int safeID(BaseObj o) {
        if(o != null) {
            return o.mID;
        }
        return 0;
    }
}


