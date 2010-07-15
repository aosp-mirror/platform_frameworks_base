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
public class ProgramVertex extends Program {
    public static final int MAX_LIGHT = 8;


    ProgramVertex(int id, RenderScript rs) {
        super(id, rs);
    }

    public void bindAllocation(MatrixAllocation va) {
        mRS.validate();
        bindConstants(va.mAlloc, 0);
    }


    public static class Builder {
        RenderScript mRS;
        boolean mTextureMatrixEnable;

        public Builder(RenderScript rs, Element in, Element out) {
            mRS = rs;
        }

        public Builder setTextureMatrixEnable(boolean enable) {
            mTextureMatrixEnable = enable;
            return this;
        }

        public ProgramVertex create() {
            int id = mRS.nProgramVertexCreate(mTextureMatrixEnable);
            return new ProgramVertex(id, mRS);
        }
    }

    public static class ShaderBuilder extends BaseProgramBuilder {
        public ShaderBuilder(RenderScript rs) {
            super(rs);
        }

        public ProgramVertex create() {
            mRS.validate();
            int[] tmp = new int[(mInputCount + mOutputCount + mConstantCount +1) * 2];
            int idx = 0;

            for (int i=0; i < mInputCount; i++) {
                tmp[idx++] = 0;
                tmp[idx++] = mInputs[i].mID;
            }
            for (int i=0; i < mOutputCount; i++) {
                tmp[idx++] = 1;
                tmp[idx++] = mOutputs[i].mID;
            }
            for (int i=0; i < mConstantCount; i++) {
                tmp[idx++] = 2;
                tmp[idx++] = mConstants[i].mID;
            }
            tmp[idx++] = 3;
            tmp[idx++] = mTextureCount;

            int id = mRS.nProgramVertexCreate2(mShader, tmp);
            ProgramVertex pv = new ProgramVertex(id, mRS);
            initProgram(pv);
            return pv;
        }
    }



    public static class MatrixAllocation {
        static final int MODELVIEW_OFFSET = 0;
        static final int PROJECTION_OFFSET = 16;
        static final int TEXTURE_OFFSET = 32;

        Matrix4f mModel;
        Matrix4f mProjection;
        Matrix4f mTexture;

        public Allocation mAlloc;

        public MatrixAllocation(RenderScript rs) {
            mModel = new Matrix4f();
            mProjection = new Matrix4f();
            mTexture = new Matrix4f();

            mAlloc = Allocation.createSized(rs, Element.createUser(rs, Element.DataType.FLOAT_32), 48);
            mAlloc.subData1D(MODELVIEW_OFFSET, 16, mModel.mMat);
            mAlloc.subData1D(PROJECTION_OFFSET, 16, mProjection.mMat);
            mAlloc.subData1D(TEXTURE_OFFSET, 16, mTexture.mMat);
        }

        public void destroy() {
            mAlloc.destroy();
            mAlloc = null;
        }

        public void loadModelview(Matrix4f m) {
            mModel = m;
            mAlloc.subData1D(MODELVIEW_OFFSET, 16, m.mMat);
        }

        public void loadProjection(Matrix4f m) {
            mProjection = m;
            mAlloc.subData1D(PROJECTION_OFFSET, 16, m.mMat);
        }

        public void loadTexture(Matrix4f m) {
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
            Matrix4f m1 = new Matrix4f();
            Matrix4f m2 = new Matrix4f();

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

