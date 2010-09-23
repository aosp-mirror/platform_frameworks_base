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
public class ProgramFragment extends Program {
    ProgramFragment(int id, RenderScript rs) {
        super(id, rs);
    }

    public static class ShaderBuilder extends BaseProgramBuilder {
        public ShaderBuilder(RenderScript rs) {
            super(rs);
        }

        public ProgramFragment create() {
            mRS.validate();
            int[] tmp = new int[(mInputCount + mOutputCount + mConstantCount + 1) * 2];
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

            int id = mRS.nProgramFragmentCreate(mShader, tmp);
            ProgramFragment pf = new ProgramFragment(id, mRS);
            initProgram(pf);
            return pf;
        }
    }

    public static class Builder extends ShaderBuilder {
        public static final int MAX_TEXTURE = 2;
        int mNumTextures;
        boolean mPointSpriteEnable;
        boolean mVaryingColorEnable;

        public enum EnvMode {
            REPLACE (1),
            MODULATE (2),
            DECAL (3);

            int mID;
            EnvMode(int id) {
                mID = id;
            }
        }

        public enum Format {
            ALPHA (1),
            LUMINANCE_ALPHA (2),
            RGB (3),
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
            mShader += "varying vec4 varTex0;\n";

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

        public Builder(RenderScript rs) {
            super(rs);
            mRS = rs;
            mSlots = new Slot[MAX_TEXTURE];
            mPointSpriteEnable = false;
        }

        public Builder setTexture(EnvMode env, Format fmt, int slot)
            throws IllegalArgumentException {
            if((slot < 0) || (slot >= MAX_TEXTURE)) {
                throw new IllegalArgumentException("MAX_TEXTURE exceeded.");
            }
            mSlots[slot] = new Slot(env, fmt);
            return this;
        }

        public Builder setPointSpriteTexCoordinateReplacement(boolean enable) {
            mPointSpriteEnable = enable;
            return this;
        }

        public Builder setVaryingColor(boolean enable) {
            mVaryingColorEnable = enable;
            return this;
        }

        @Override
        public ProgramFragment create() {
            mNumTextures = 0;
            for(int i = 0; i < MAX_TEXTURE; i ++) {
                if(mSlots[i] != null) {
                    mNumTextures ++;
                }
            }
            resetConstant();
            buildShaderString();
            Type constType = null;
            if (!mVaryingColorEnable) {
                Element.Builder b = new Element.Builder(mRS);
                b.add(Element.F32_4(mRS), "Color");
                Type.Builder typeBuilder = new Type.Builder(mRS, b.create());
                typeBuilder.add(Dimension.X, 1);
                constType = typeBuilder.create();
                addConstant(constType);
            }
            setTextureCount(mNumTextures);

            ProgramFragment pf = super.create();
            pf.mTextureCount = MAX_TEXTURE;
            if (!mVaryingColorEnable) {
                Allocation constantData = Allocation.createTyped(mRS,constType);
                float[] data = new float[4];
                data[0] = data[1] = data[2] = data[3] = 1.0f;
                constantData.data(data);
                pf.bindConstants(constantData, 0);
            }
            return pf;
        }
    }
}



