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


import android.util.Log;


/**
 * @hide
 * @deprecated in API 16
 * <p>ProgramFragmentFixedFunction is a helper class that provides
 * a way to make a simple fragment shader without writing any
 * GLSL code. This class allows for display of constant color, interpolated
 * color from the vertex shader, or combinations of the both
 * blended with results of up to two texture lookups.</p
 *
 **/
public class ProgramFragmentFixedFunction extends ProgramFragment {
    ProgramFragmentFixedFunction(int id, RenderScript rs) {
        super(id, rs);
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
         * Creates ProgramFragmentFixedFunction from the current state
         * of the builder
         *
         * @return  ProgramFragmentFixedFunction
         */
        public ProgramFragmentFixedFunction create() {
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

            int id = mRS.nProgramFragmentCreate(mShader, texNames, tmp);
            ProgramFragmentFixedFunction pf = new ProgramFragmentFixedFunction(id, mRS);
            initProgram(pf);
            return pf;
        }
    }

    /**
     * @deprecated in API 16
     */
    public static class Builder {
        /**
         * @deprecated in API 16
         */
        public static final int MAX_TEXTURE = 2;
        int mNumTextures;
        boolean mPointSpriteEnable;
        boolean mVaryingColorEnable;
        String mShader;
        RenderScript mRS;

        /**
         * @deprecated in API 16
         * EnvMode describes how textures are combined with the existing
         * color in the fixed function fragment shader
         *
         **/
        public enum EnvMode {
            /**
             * @deprecated in API 16
             **/
            REPLACE (1),
            /**
             * @deprecated in API 16
             **/
            MODULATE (2),
            /**
             * @deprecated in API 16
             **/
            DECAL (3);

            int mID;
            EnvMode(int id) {
                mID = id;
            }
        }

        /**
         * @deprecated in API 16
         * Format describes the pixel format of textures in the fixed
         * function fragment shader and how they are sampled
         *
         **/
        public enum Format {
            /**
             * @deprecated in API 16
             **/
            ALPHA (1),
            /**
             * @deprecated in API 16
             **/
            LUMINANCE_ALPHA (2),
            /**
             * @deprecated in API 16
             **/
            RGB (3),
            /**
             * @deprecated in API 16
             **/
            RGBA (4);

            int mID;
            Format(int id) {
                mID = id;
            }
        }

        private class Slot {
            EnvMode env;
            Format format;
            Slot(EnvMode _env, Format _fmt) {
                env = _env;
                format = _fmt;
            }
        }
        Slot[] mSlots;

        private void buildShaderString() {
            mShader  = "//rs_shader_internal\n";
            mShader += "varying lowp vec4 varColor;\n";
            mShader += "varying vec2 varTex0;\n";

            mShader += "void main() {\n";
            if (mVaryingColorEnable) {
                mShader += "  lowp vec4 col = varColor;\n";
            } else {
                mShader += "  lowp vec4 col = UNI_Color;\n";
            }

            if (mNumTextures != 0) {
                if (mPointSpriteEnable) {
                    mShader += "  vec2 t0 = gl_PointCoord;\n";
                } else {
                    mShader += "  vec2 t0 = varTex0.xy;\n";
                }
            }

            for(int i = 0; i < mNumTextures; i ++) {
                switch(mSlots[i].env) {
                case REPLACE:
                    switch (mSlots[i].format) {
                    case ALPHA:
                        mShader += "  col.a = texture2D(UNI_Tex0, t0).a;\n";
                        break;
                    case LUMINANCE_ALPHA:
                        mShader += "  col.rgba = texture2D(UNI_Tex0, t0).rgba;\n";
                        break;
                    case RGB:
                        mShader += "  col.rgb = texture2D(UNI_Tex0, t0).rgb;\n";
                        break;
                    case RGBA:
                        mShader += "  col.rgba = texture2D(UNI_Tex0, t0).rgba;\n";
                        break;
                    }
                    break;
                case MODULATE:
                    switch (mSlots[i].format) {
                    case ALPHA:
                        mShader += "  col.a *= texture2D(UNI_Tex0, t0).a;\n";
                        break;
                    case LUMINANCE_ALPHA:
                        mShader += "  col.rgba *= texture2D(UNI_Tex0, t0).rgba;\n";
                        break;
                    case RGB:
                        mShader += "  col.rgb *= texture2D(UNI_Tex0, t0).rgb;\n";
                        break;
                    case RGBA:
                        mShader += "  col.rgba *= texture2D(UNI_Tex0, t0).rgba;\n";
                        break;
                    }
                    break;
                case DECAL:
                    mShader += "  col = texture2D(UNI_Tex0, t0);\n";
                    break;
                }
            }

            mShader += "  gl_FragColor = col;\n";
            mShader += "}\n";
        }

        /**
         * @deprecated
         * Creates a builder for fixed function fragment program
         *
         * @param rs Context to which the program will belong.
         */
        public Builder(RenderScript rs) {
            mRS = rs;
            mSlots = new Slot[MAX_TEXTURE];
            mPointSpriteEnable = false;
        }

        /**
         * @deprecated in API 16
         * Adds a texture to be fetched as part of the fixed function
         * fragment program
         *
         * @param env specifies how the texture is combined with the
         *            current color
         * @param fmt specifies the format of the texture and how its
         *            components will be used to combine with the
         *            current color
         * @param slot index of the texture to apply the operations on
         *
         * @return this
         */
        public Builder setTexture(EnvMode env, Format fmt, int slot)
            throws IllegalArgumentException {
            if((slot < 0) || (slot >= MAX_TEXTURE)) {
                throw new IllegalArgumentException("MAX_TEXTURE exceeded.");
            }
            mSlots[slot] = new Slot(env, fmt);
            return this;
        }

        /**
         * @deprecated in API 16
         * Specifies whether the texture coordinate passed from the
         * vertex program is replaced with an openGL internal point
         * sprite texture coordinate
         *
         **/
        public Builder setPointSpriteTexCoordinateReplacement(boolean enable) {
            mPointSpriteEnable = enable;
            return this;
        }

        /**
         * @deprecated in API 16
         * Specifies whether the varying color passed from the vertex
         * program or the constant color set on the fragment program is
         * used in the final color calculation in the fixed function
         * fragment shader
         *
         **/
        public Builder setVaryingColor(boolean enable) {
            mVaryingColorEnable = enable;
            return this;
        }

        /**
         * @deprecated in API 16
        * Creates the fixed function fragment program from the current
        * state of the builder.
        *
        */
        public ProgramFragmentFixedFunction create() {
            InternalBuilder sb = new InternalBuilder(mRS);
            mNumTextures = 0;
            for(int i = 0; i < MAX_TEXTURE; i ++) {
                if(mSlots[i] != null) {
                    mNumTextures ++;
                }
            }
            buildShaderString();
            sb.setShader(mShader);

            Type constType = null;
            if (!mVaryingColorEnable) {
                Element.Builder b = new Element.Builder(mRS);
                b.add(Element.F32_4(mRS), "Color");
                Type.Builder typeBuilder = new Type.Builder(mRS, b.create());
                typeBuilder.setX(1);
                constType = typeBuilder.create();
                sb.addConstant(constType);
            }
            for (int i = 0; i < mNumTextures; i ++) {
                sb.addTexture(TextureType.TEXTURE_2D);
            }

            ProgramFragmentFixedFunction pf = sb.create();
            pf.mTextureCount = MAX_TEXTURE;
            if (!mVaryingColorEnable) {
                Allocation constantData = Allocation.createTyped(mRS,constType);
                FieldPacker fp = new FieldPacker(16);
                Float4 f4 = new Float4(1.f, 1.f, 1.f, 1.f);
                fp.addF32(f4);
                constantData.setFromFieldPacker(0, fp);
                pf.bindConstants(constantData, 0);
            }
            return pf;
        }
    }
}




