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


import android.graphics.Matrix;
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

            int id = mRS.nProgramVertexCreate(mShader, tmp);
            ProgramVertex pv = new ProgramVertex(id, mRS);
            initProgram(pv);
            return pv;
        }
    }

    public static class Builder extends ShaderBuilder {
        boolean mTextureMatrixEnable;

        public Builder(RenderScript rs, Element in, Element out) {
            super(rs);
        }
        public Builder(RenderScript rs) {
            super(rs);
        }

        public Builder setTextureMatrixEnable(boolean enable) {
            mTextureMatrixEnable = enable;
            return this;
        }
        static Type getConstantInputType(RenderScript rs) {
            Element.Builder b = new Element.Builder(rs);
            b.add(Element.MATRIX4X4(rs), "MV");
            b.add(Element.MATRIX4X4(rs), "P");
            b.add(Element.MATRIX4X4(rs), "TexMatrix");
            b.add(Element.MATRIX4X4(rs), "MVP");

            Type.Builder typeBuilder = new Type.Builder(rs, b.create());
            typeBuilder.add(Dimension.X, 1);
            return typeBuilder.create();
        }

        private void buildShaderString() {

            mShader  = "//rs_shader_internal\n";
            mShader += "varying vec4 varColor;\n";
            mShader += "varying vec4 varTex0;\n";

            mShader += "void main() {\n";
            mShader += "  gl_Position = UNI_MVP * ATTRIB_position;\n";
            mShader += "  gl_PointSize = 1.0;\n";

            mShader += "  varColor = ATTRIB_color;\n";
            if (mTextureMatrixEnable) {
                mShader += "  varTex0 = UNI_TexMatrix * ATTRIB_texture0;\n";
            } else {
                mShader += "  varTex0 = ATTRIB_texture0;\n";
            }
            mShader += "}\n";
        }

        @Override
        public ProgramVertex create() {
            buildShaderString();

            addConstant(getConstantInputType(mRS));

            Element.Builder b = new Element.Builder(mRS);
            b.add(Element.F32_4(mRS), "position");
            b.add(Element.F32_4(mRS), "color");
            b.add(Element.F32_3(mRS), "normal");
            b.add(Element.F32_4(mRS), "texture0");
            addInput(b.create());

            return super.create();
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
        private FieldPacker mIOBuffer;

        public MatrixAllocation(RenderScript rs) {
            Type constInputType = ProgramVertex.Builder.getConstantInputType(rs);
            mAlloc = Allocation.createTyped(rs, constInputType);
            int bufferSize = constInputType.getElement().getSizeBytes()*
                             constInputType.getElementCount();
            mIOBuffer = new FieldPacker(bufferSize);
            loadModelview(new Matrix4f());
            loadProjection(new Matrix4f());
            loadTexture(new Matrix4f());
        }

        public void destroy() {
            mAlloc.destroy();
            mAlloc = null;
        }

        private void addToBuffer(int offset, Matrix4f m) {
            mIOBuffer.reset(offset);
            for(int i = 0; i < 16; i ++) {
                mIOBuffer.addF32(m.mMat[i]);
            }
            mAlloc.data(mIOBuffer.getData());
        }

        public void loadModelview(Matrix4f m) {
            mModel = m;
            addToBuffer(MODELVIEW_OFFSET*4, m);
        }

        public void loadProjection(Matrix4f m) {
            mProjection = m;
            addToBuffer(PROJECTION_OFFSET*4, m);
        }

        public void loadTexture(Matrix4f m) {
            mTexture = m;
            addToBuffer(TEXTURE_OFFSET*4, m);
        }

        public void setupOrthoWindow(int w, int h) {
            mProjection.loadOrtho(0,w, h,0, -1,1);
            addToBuffer(PROJECTION_OFFSET*4, mProjection);
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
            addToBuffer(PROJECTION_OFFSET*4, mProjection);
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
            addToBuffer(PROJECTION_OFFSET*4, mProjection);
        }

    }

}

