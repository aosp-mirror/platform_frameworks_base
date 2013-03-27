/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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
import android.util.Log;


/**
 * @hide
 * @deprecated in API 16
 * ProgramVertexFixedFunction is a helper class that provides a
 * simple way to create a fixed function emulation vertex shader
 * without writing any GLSL code.
 *
 **/
public class ProgramVertexFixedFunction extends ProgramVertex {

    ProgramVertexFixedFunction(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * @deprecated in API 16
     * Binds the constant buffer containing fixed function emulation
     * matrices
     *
     * @param va allocation containing fixed function matrices
     */
    public void bindConstants(Constants va) {
        mRS.validate();
        bindConstants(va.getAllocation(), 0);
    }

    static class InternalBuilder extends BaseProgramBuilder {
        /**
         * @deprecated in API 16
         */
        public InternalBuilder(RenderScript rs) {
            super(rs);
        }

        /**
         * @deprecated in API 16
         */
        public InternalBuilder addInput(Element e) throws IllegalStateException {
            // Should check for consistant and non-conflicting names...
            if(mInputCount >= MAX_INPUT) {
                throw new RSIllegalArgumentException("Max input count exceeded.");
            }
            if (e.isComplex()) {
                throw new RSIllegalArgumentException("Complex elements not allowed.");
            }
            mInputs[mInputCount++] = e;
            return this;
        }

        /**
         * @deprecated in API 16
         * Creates ProgramVertexFixedFunction from the current state of
         * the builder
         *
         * @return  ProgramVertexFixedFunction
         */
        public ProgramVertexFixedFunction create() {
            mRS.validate();
            int[] tmp = new int[(mInputCount + mOutputCount + mConstantCount + mTextureCount) * 2];
            String[] texNames = new String[mTextureCount];
            int idx = 0;

            for (int i=0; i < mInputCount; i++) {
                tmp[idx++] = ProgramParam.INPUT.mID;
                tmp[idx++] = mInputs[i].getID(mRS);
            }
            for (int i=0; i < mOutputCount; i++) {
                tmp[idx++] = ProgramParam.OUTPUT.mID;
                tmp[idx++] = mOutputs[i].getID(mRS);
            }
            for (int i=0; i < mConstantCount; i++) {
                tmp[idx++] = ProgramParam.CONSTANT.mID;
                tmp[idx++] = mConstants[i].getID(mRS);
            }
            for (int i=0; i < mTextureCount; i++) {
                tmp[idx++] = ProgramParam.TEXTURE_TYPE.mID;
                tmp[idx++] = mTextureTypes[i].mID;
                texNames[i] = mTextureNames[i];
            }

            int id = mRS.nProgramVertexCreate(mShader, texNames, tmp);
            ProgramVertexFixedFunction pv = new ProgramVertexFixedFunction(id, mRS);
            initProgram(pv);
            return pv;
        }
    }

    /**
     * @deprecated in API 16
     */
    public static class Builder {
        boolean mTextureMatrixEnable;
        String mShader;
        RenderScript mRS;

        /**
         * @deprecated in API 16
         * Creates a builder for fixed function vertex program
         *
         * @param rs Context to which the program will belong.
         */
        public Builder(RenderScript rs) {
            mRS = rs;
        }

        /**
         * @deprecated in API 16
         * Specifies whether texture matrix calculations are to be added
         * to the shader
         *
         */
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
            typeBuilder.setX(1);
            return typeBuilder.create();
        }

        private void buildShaderString() {

            mShader  = "//rs_shader_internal\n";
            mShader += "varying vec4 varColor;\n";
            mShader += "varying vec2 varTex0;\n";

            mShader += "void main() {\n";
            mShader += "  gl_Position = UNI_MVP * ATTRIB_position;\n";
            mShader += "  gl_PointSize = 1.0;\n";

            mShader += "  varColor = ATTRIB_color;\n";
            if (mTextureMatrixEnable) {
                mShader += "  varTex0 = (UNI_TexMatrix * vec4(ATTRIB_texture0, 0.0, 1.0)).xy;\n";
            } else {
                mShader += "  varTex0 = ATTRIB_texture0;\n";
            }
            mShader += "}\n";
        }

        /**
         * @deprecated in API 16
         * Creates ProgramVertexFixedFunction from the current state of
         * the builder
         *
         * @return Fixed function emulation ProgramVertex
         */
        public ProgramVertexFixedFunction create() {
            buildShaderString();

            InternalBuilder sb = new InternalBuilder(mRS);
            sb.setShader(mShader);
            sb.addConstant(getConstantInputType(mRS));

            Element.Builder b = new Element.Builder(mRS);
            b.add(Element.F32_4(mRS), "position");
            b.add(Element.F32_4(mRS), "color");
            b.add(Element.F32_3(mRS), "normal");
            b.add(Element.F32_2(mRS), "texture0");
            sb.addInput(b.create());

            return sb.create();
        }
    }

    /**
     * @deprecated in API 16
     * Helper class to store modelview, projection and texture
     * matrices for ProgramVertexFixedFunction
     *
     */
    public static class Constants {
        static final int MODELVIEW_OFFSET = 0;
        static final int PROJECTION_OFFSET = 16;
        static final int TEXTURE_OFFSET = 32;

        Matrix4f mModel;
        Matrix4f mProjection;
        Matrix4f mTexture;

        Allocation mAlloc;
        Allocation getAllocation() {
            return mAlloc;
        }
        private FieldPacker mIOBuffer;

        /**
        * @deprecated in API 16
        * Creates a buffer to store fixed function emulation matrices
        *
        * @param rs Context to which the allocation will belong.
        **/
        public Constants(RenderScript rs) {
            Type constInputType = ProgramVertexFixedFunction.Builder.getConstantInputType(rs);
            mAlloc = Allocation.createTyped(rs, constInputType);
            int bufferSize = constInputType.getElement().getBytesSize()*
                             constInputType.getCount();
            mIOBuffer = new FieldPacker(bufferSize);
            mModel = new Matrix4f();
            mProjection = new Matrix4f();
            mTexture = new Matrix4f();
            setModelview(new Matrix4f());
            setProjection(new Matrix4f());
            setTexture(new Matrix4f());
        }

        /**
        * @deprecated in API 16
        * Forces deallocation of memory backing the contant matrices.
        * Normally, this is unnecessary and will be garbage collected
        *
        */
        public void destroy() {
            mAlloc.destroy();
            mAlloc = null;
        }

        private void addToBuffer(int offset, Matrix4f m) {
            mIOBuffer.reset(offset);
            for(int i = 0; i < 16; i ++) {
                mIOBuffer.addF32(m.mMat[i]);
            }
            mAlloc.setFromFieldPacker(0, mIOBuffer);
        }

        /**
        * @deprecated in API 16
        * Sets the modelview matrix in the fixed function matrix buffer
        *
        * @param m modelview matrix
        */
        public void setModelview(Matrix4f m) {
            mModel.load(m);
            addToBuffer(MODELVIEW_OFFSET*4, m);
        }

        /**
        * @deprecated in API 16
        * Sets the projection matrix in the fixed function matrix buffer
        *
        * @param m projection matrix
        */
        public void setProjection(Matrix4f m) {
            mProjection.load(m);
            addToBuffer(PROJECTION_OFFSET*4, m);
        }

        /**
        * @deprecated in API 16
        * Sets the texture matrix in the fixed function matrix buffer.
        * Texture matrix must be enabled in the
        * ProgramVertexFixedFunction builder for the shader to utilize
        * it.
        *
        * @param m modelview matrix
        */
        public void setTexture(Matrix4f m) {
            mTexture.load(m);
            addToBuffer(TEXTURE_OFFSET*4, m);
        }
    }
}
