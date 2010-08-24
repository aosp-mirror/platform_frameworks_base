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
public class ProgramStore extends BaseObj {
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
        ONE_MINUS_DST_ALPHA (7),
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
        ONE_MINUS_DST_ALPHA (7);

        int mID;
        BlendDstFunc(int id) {
            mID = id;
        }
    }


    ProgramStore(int id, RenderScript rs) {
        super(id, rs);
    }

    public static ProgramStore BlendNone_DepthTest(RenderScript rs) {
        if(rs.mProgramStore_BlendNone_DepthTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendNone_DepthTest = builder.create();
        }
        return rs.mProgramStore_BlendNone_DepthTest;
    }
    public static ProgramStore BlendNone_DepthNoDepth(RenderScript rs) {
        if(rs.mProgramStore_BlendNone_DepthNoDepth == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendNone_DepthNoDepth = builder.create();
        }
        return rs.mProgramStore_BlendNone_DepthNoDepth;
    }
    public static ProgramStore BlendNone_DepthNoTest(RenderScript rs) {
        if(rs.mProgramStore_BlendNone_DepthNoTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendNone_DepthNoTest = builder.create();
        }
        return rs.mProgramStore_BlendNone_DepthNoTest;
    }
    public static ProgramStore BlendNone_DepthNoWrite(RenderScript rs) {
        if(rs.mProgramStore_BlendNone_DepthNoWrite == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendNone_DepthNoWrite = builder.create();
        }
        return rs.mProgramStore_BlendNone_DepthNoWrite;
    }

    public static ProgramStore BlendAlpha_DepthTest(RenderScript rs) {
        if(rs.mProgramStore_BlendAlpha_DepthTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendAlpha_DepthTest = builder.create();
        }
        return rs.mProgramStore_BlendAlpha_DepthTest;
    }
    public static ProgramStore BlendAlpha_DepthNoDepth(RenderScript rs) {
        if(rs.mProgramStore_BlendAlpha_DepthNoDepth == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendAlpha_DepthNoDepth = builder.create();
        }
        return rs.mProgramStore_BlendAlpha_DepthNoDepth;
    }
    public static ProgramStore BlendAlpha_DepthNoTest(RenderScript rs) {
        if(rs.mProgramStore_BlendAlpha_DepthNoTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendAlpha_DepthNoTest = builder.create();
        }
        return rs.mProgramStore_BlendAlpha_DepthNoTest;
    }
    public static ProgramStore BlendAlpha_DepthNoWrite(RenderScript rs) {
        if(rs.mProgramStore_BlendAlpha_DepthNoWrite == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendAlpha_DepthNoWrite = builder.create();
        }
        return rs.mProgramStore_BlendAlpha_DepthNoWrite;
    }

    public static ProgramStore BlendAdd_DepthTest(RenderScript rs) {
        if(rs.mProgramStore_BlendAdd_DepthTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendAdd_DepthTest = builder.create();
        }
        return rs.mProgramStore_BlendAdd_DepthTest;
    }
    public static ProgramStore BlendAdd_DepthNoDepth(RenderScript rs) {
        if(rs.mProgramStore_BlendAdd_DepthNoDepth == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendAdd_DepthNoDepth = builder.create();
        }
        return rs.mProgramStore_BlendAdd_DepthNoDepth;
    }
    public static ProgramStore BlendAdd_DepthNoTest(RenderScript rs) {
        if(rs.mProgramStore_BlendAdd_DepthNoTest == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
            builder.setDitherEnable(false);
            builder.setDepthMask(true);
            rs.mProgramStore_BlendAdd_DepthNoDepth = builder.create();
        }
        return rs.mProgramStore_BlendAdd_DepthNoTest;
    }
    public static ProgramStore BlendAdd_DepthNoWrite(RenderScript rs) {
        if(rs.mProgramStore_BlendAdd_DepthNoWrite == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ONE);
            builder.setDitherEnable(false);
            builder.setDepthMask(false);
            rs.mProgramStore_BlendAdd_DepthNoWrite = builder.create();
        }
        return rs.mProgramStore_BlendAdd_DepthNoWrite;
    }

    public static class Builder {
        RenderScript mRS;
        Element mIn;
        Element mOut;
        DepthFunc mDepthFunc;
        boolean mDepthMask;
        boolean mColorMaskR;
        boolean mColorMaskG;
        boolean mColorMaskB;
        boolean mColorMaskA;
        BlendSrcFunc mBlendSrc;
        BlendDstFunc mBlendDst;
        boolean mDither;



        public Builder(RenderScript rs, Element in, Element out) {
            mRS = rs;
            mIn = in;
            mOut = out;
            mDepthFunc = DepthFunc.ALWAYS;
            mDepthMask = false;
            mColorMaskR = true;
            mColorMaskG = true;
            mColorMaskB = true;
            mColorMaskA = true;
            mBlendSrc = BlendSrcFunc.ONE;
            mBlendDst = BlendDstFunc.ZERO;
        }

        public Builder(RenderScript rs) {
            mRS = rs;
            mIn = null;
            mOut = null;
            mDepthFunc = DepthFunc.ALWAYS;
            mDepthMask = false;
            mColorMaskR = true;
            mColorMaskG = true;
            mColorMaskB = true;
            mColorMaskA = true;
            mBlendSrc = BlendSrcFunc.ONE;
            mBlendDst = BlendDstFunc.ZERO;
        }

        public Builder setDepthFunc(DepthFunc func) {
            mDepthFunc = func;
            return this;
        }

        public Builder setDepthMask(boolean enable) {
            mDepthMask = enable;
            return this;
        }

        public Builder setColorMask(boolean r, boolean g, boolean b, boolean a) {
            mColorMaskR = r;
            mColorMaskG = g;
            mColorMaskB = b;
            mColorMaskA = a;
            return this;
        }

        public Builder setBlendFunc(BlendSrcFunc src, BlendDstFunc dst) {
            mBlendSrc = src;
            mBlendDst = dst;
            return this;
        }

        public Builder setDitherEnable(boolean enable) {
            mDither = enable;
            return this;
        }

        static synchronized ProgramStore internalCreate(RenderScript rs, Builder b) {
            int inID = 0;
            int outID = 0;
            if (b.mIn != null) {
                inID = b.mIn.mID;
            }
            if (b.mOut != null) {
                outID = b.mOut.mID;
            }
            rs.nProgramStoreBegin(inID, outID);
            rs.nProgramStoreDepthFunc(b.mDepthFunc.mID);
            rs.nProgramStoreDepthMask(b.mDepthMask);
            rs.nProgramStoreColorMask(b.mColorMaskR,
                                              b.mColorMaskG,
                                              b.mColorMaskB,
                                              b.mColorMaskA);
            rs.nProgramStoreBlendFunc(b.mBlendSrc.mID, b.mBlendDst.mID);
            rs.nProgramStoreDither(b.mDither);

            int id = rs.nProgramStoreCreate();
            return new ProgramStore(id, rs);
        }

        public ProgramStore create() {
            mRS.validate();
            return internalCreate(mRS, this);
        }
    }

}




