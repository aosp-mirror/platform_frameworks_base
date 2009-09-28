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


import android.util.Config;
import android.util.Log;


/**
 * @hide
 *
 **/
public class ProgramVertex extends BaseObj {
    public static final int MAX_LIGHT = 8;

    ProgramVertex(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void bindAllocation(MatrixAllocation va) {
        mRS.nProgramVertexBindAllocation(mID, va.mAlloc.mID);
    }


    public static class Builder {
        RenderScript mRS;
        Element mIn;
        Element mOut;
        Light[] mLights;
        int mLightCount;
        boolean mTextureMatrixEnable;


        public Builder(RenderScript rs, Element in, Element out) {
            mRS = rs;
            mIn = in;
            mOut = out;
            mLights = new Light[MAX_LIGHT];
            mLightCount = 0;
        }

        public void setTextureMatrixEnable(boolean enable) {
            mTextureMatrixEnable = enable;
        }

        public void addLight(Light l) throws IllegalStateException {
            if(mLightCount >= MAX_LIGHT) {
                throw new IllegalArgumentException("Max light count exceeded.");
            }
            mLights[mLightCount] = l;
            mLightCount++;
        }



        static synchronized ProgramVertex internalCreate(RenderScript rs, Builder b) {
            int inID = 0;
            int outID = 0;
            if (b.mIn != null) {
                inID = b.mIn.mID;
            }
            if (b.mOut != null) {
                outID = b.mOut.mID;
            }
            rs.nProgramVertexBegin(inID, outID);
            for(int ct=0; ct < b.mLightCount; ct++) {
                rs.nProgramVertexAddLight(b.mLights[ct].mID);
            }
            rs.nProgramVertexSetTextureMatrixEnable(b.mTextureMatrixEnable);
            int id = rs.nProgramVertexCreate();
            return new ProgramVertex(id, rs);
        }

        public ProgramVertex create() {
            return internalCreate(mRS, this);
        }
    }



    public static class MatrixAllocation {
        static final int MODELVIEW_OFFSET = 0;
        static final int PROJECTION_OFFSET = 16;
        static final int TEXTURE_OFFSET = 32;

        Matrix mModel;
        Matrix mProjection;
        Matrix mTexture;

        public Allocation mAlloc;

        public MatrixAllocation(RenderScript rs) {
            mModel = new Matrix();
            mProjection = new Matrix();
            mTexture = new Matrix();

            mAlloc = Allocation.createSized(rs, Element.USER_F32(rs), 48);
            mAlloc.subData1D(MODELVIEW_OFFSET, 16, mModel.mMat);
            mAlloc.subData1D(PROJECTION_OFFSET, 16, mProjection.mMat);
            mAlloc.subData1D(TEXTURE_OFFSET, 16, mTexture.mMat);
        }

        public void destroy() {
            mAlloc.destroy();
            mAlloc = null;
        }

        public void loadModelview(Matrix m) {
            mModel = m;
            mAlloc.subData1D(MODELVIEW_OFFSET, 16, m.mMat);
        }

        public void loadProjection(Matrix m) {
            mProjection = m;
            mAlloc.subData1D(PROJECTION_OFFSET, 16, m.mMat);
        }

        public void loadTexture(Matrix m) {
            mTexture = m;
            mAlloc.subData1D(TEXTURE_OFFSET, 16, m.mMat);
        }

        public void setupOrthoWindow(int w, int h) {
            mProjection.loadOrtho(0,w, h,0, -1,1);
            mAlloc.subData1D(PROJECTION_OFFSET, 16, mProjection.mMat);
        }

        public void setupOrthoNormalized(int w, int h) {
            // range -1,1 in the narrow axis.
            if(w > h) {
                float aspect = ((float)w) / h;
                mProjection.loadOrtho(-aspect,aspect,  -1,1,  -1,1);
            } else {
                float aspect = ((float)h) / w;
                mProjection.loadOrtho(-1,1, -aspect,aspect,  -1,1);
            }
            mAlloc.subData1D(PROJECTION_OFFSET, 16, mProjection.mMat);
        }

        public void setupProjectionNormalized(int w, int h) {
            // range -1,1 in the narrow axis at z = 0.
            Matrix m1 = new Matrix();
            Matrix m2 = new Matrix();

            if(w > h) {
                float aspect = ((float)w) / h;
                m1.loadFrustum(-aspect,aspect,  -1,1,  1,100);
            } else {
                float aspect = ((float)h) / w;
                m1.loadFrustum(-1,1, -aspect,aspect, 1,100);
            }

            m2.loadRotate(180, 0, 1, 0);
            m1.loadMultiply(m1, m2);

            m2.loadScale(-2, 2, 1);
            m1.loadMultiply(m1, m2);

            m2.loadTranslate(0, 0, 2);
            m1.loadMultiply(m1, m2);

            mProjection = m1;
            mAlloc.subData1D(PROJECTION_OFFSET, 16, mProjection.mMat);
        }

    }

}

