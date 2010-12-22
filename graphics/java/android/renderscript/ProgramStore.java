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
        LESS_OR_EQUAL (2),
        GREATER (3),
        GREATER_OR_EQUAL (4),
        EQUAL (5),
        NOT_EQUAL (6);

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

    public static ProgramStore BLEND_NONE_DEPTH_TEST(RenderScript rs) {
        if(rs.mProgramStore_BLEND_NONE_DEPTH_TEST == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnabled(false);
            builder.setDepthMaskEnabled(true);
            rs.mProgramStore_BLEND_NONE_DEPTH_TEST = builder.create();
        }
        return rs.mProgramStore_BLEND_NONE_DEPTH_TEST;
    }
    public static ProgramStore BLEND_NONE_DEPTH_NONE(RenderScript rs) {
        if(rs.mProgramStore_BLEND_NONE_DEPTH_NO_DEPTH == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
            builder.setDitherEnabled(false);
            builder.setDepthMaskEnabled(false);
            rs.mProgramStore_BLEND_NONE_DEPTH_NO_DEPTH = builder.create();
        }
        return rs.mProgramStore_BLEND_NONE_DEPTH_NO_DEPTH;
    }

    public static ProgramStore BLEND_ALPHA_DEPTH_TEST(RenderScript rs) {
        if(rs.mProgramStore_BLEND_ALPHA_DEPTH_TEST == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.LESS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnabled(false);
            builder.setDepthMaskEnabled(true);
            rs.mProgramStore_BLEND_ALPHA_DEPTH_TEST = builder.create();
        }
        return rs.mProgramStore_BLEND_ALPHA_DEPTH_TEST;
    }
    public static ProgramStore BLEND_ALPHA_DEPTH_NONE(RenderScript rs) {
        if(rs.mProgramStore_BLEND_ALPHA_DEPTH_NO_DEPTH == null) {
            ProgramStore.Builder builder = new ProgramStore.Builder(rs);
            builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
            builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE_MINUS_SRC_ALPHA);
            builder.setDitherEnabled(false);
            builder.setDepthMaskEnabled(false);
            rs.mProgramStore_BLEND_ALPHA_DEPTH_NO_DEPTH = builder.create();
        }
        return rs.mProgramStore_BLEND_ALPHA_DEPTH_NO_DEPTH;
    }

    public static class Builder {
        RenderScript mRS;
        DepthFunc mDepthFunc;
        boolean mDepthMask;
        boolean mColorMaskR;
        boolean mColorMaskG;
        boolean mColorMaskB;
        boolean mColorMaskA;
        BlendSrcFunc mBlendSrc;
        BlendDstFunc mBlendDst;
        boolean mDither;

        public Builder(RenderScript rs) {
            mRS = rs;
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

        public Builder setDepthMaskEnabled(boolean enable) {
            mDepthMask = enable;
            return this;
        }

        public Builder setColorMaskEnabled(boolean r, boolean g, boolean b, boolean a) {
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

        public Builder setDitherEnabled(boolean enable) {
            mDither = enable;
            return this;
        }

        static synchronized ProgramStore internalCreate(RenderScript rs, Builder b) {
            rs.nProgramStoreBegin(0, 0);
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




