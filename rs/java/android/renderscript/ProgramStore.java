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


/**
 * @hide
 * <p>ProgramStore contains a set of parameters that control how
 * the graphics hardware handles writes to the framebuffer.
 * It could be used to:</p>
 * <ul>
 *   <li>enable/disable depth testing</li>
 *   <li>specify wheather depth writes are performed</li>
 *   <li>setup various blending modes for use in effects like
 *     transparency</li>
 *   <li>define write masks for color components written into the
 *     framebuffer</li>
 *  </ul>
 *
 **/
public class ProgramStore extends BaseObj {
    /**
    * Specifies the function used to determine whether a fragment
    * will be drawn during the depth testing stage in the rendering
    * pipeline by comparing its value with that already in the depth
    * buffer. DepthFunc is only valid when depth buffer is present
    * and depth testing is enabled
    */
    public enum DepthFunc {

        /**
        * Always drawn
        */
        ALWAYS (0),
        /**
        * Drawn if the incoming depth value is less than that in the
        * depth buffer
        */
        LESS (1),
        /**
        * Drawn if the incoming depth value is less or equal to that in
        * the depth buffer
        */
        LESS_OR_EQUAL (2),
        /**
        * Drawn if the incoming depth value is greater than that in the
        * depth buffer
        */
        GREATER (3),
        /**
        * Drawn if the incoming depth value is greater or equal to that
        * in the depth buffer
        */
        GREATER_OR_EQUAL (4),
        /**
        * Drawn if the incoming depth value is equal to that in the
        * depth buffer
        */
        EQUAL (5),
        /**
        * Drawn if the incoming depth value is not equal to that in the
        * depth buffer
        */
        NOT_EQUAL (6);

        int mID;
        DepthFunc(int id) {
            mID = id;
        }
    }

    /**
    * Specifies the functions used to combine incoming pixels with
    * those already in the frame buffer.
    *
    * BlendSrcFunc describes how the coefficient used to scale the
    * source pixels during the blending operation is computed
    *
    */
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

    /**
    * Specifies the functions used to combine incoming pixels with
    * those already in the frame buffer.
    *
    * BlendDstFunc describes how the coefficient used to scale the
    * pixels already in the framebuffer is computed during the
    * blending operation
    *
    */
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

    DepthFunc mDepthFunc;
    boolean mDepthMask;
    boolean mColorMaskR;
    boolean mColorMaskG;
    boolean mColorMaskB;
    boolean mColorMaskA;
    BlendSrcFunc mBlendSrc;
    BlendDstFunc mBlendDst;
    boolean mDither;

    ProgramStore(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
    * Returns the function used to test writing into the depth
    * buffer
    * @return depth function
    */
    public DepthFunc getDepthFunc() {
        return mDepthFunc;
    }

    /**
    * Queries whether writes are enabled into the depth buffer
    * @return depth mask
    */
    public boolean isDepthMaskEnabled() {
        return mDepthMask;
    }

    /**
    * Queries whether red channel is written
    * @return red color channel mask
    */
    public boolean isColorMaskRedEnabled() {
        return mColorMaskR;
    }

    /**
    * Queries whether green channel is written
    * @return green color channel mask
    */
    public boolean isColorMaskGreenEnabled() {
        return mColorMaskG;
    }

    /**
    * Queries whether blue channel is written
    * @return blue color channel mask
    */
    public boolean isColorMaskBlueEnabled() {
        return mColorMaskB;
    }

    /**
    * Queries whether alpha channel is written
    * @return alpha channel mask
    */
    public boolean isColorMaskAlphaEnabled() {
        return mColorMaskA;
    }

    /**
    * Specifies how the source blending factor is computed
    * @return source blend function
    */
    public BlendSrcFunc getBlendSrcFunc() {
        return mBlendSrc;
    }

    /**
    * Specifies how the destination blending factor is computed
    * @return destination blend function
    */
    public BlendDstFunc getBlendDstFunc() {
        return mBlendDst;
    }

    /**
    * Specifies whether colors are dithered before writing into the
    * framebuffer
    * @return whether dither is enabled
    */
    public boolean isDitherEnabled() {
        return mDither;
    }

    /**
    * Returns a pre-defined program store object with the following
    * characteristics:
    *  - incoming pixels are drawn if their depth value is less than
    *    the stored value in the depth buffer. If the pixel is
    *    drawn, its value is also stored in the depth buffer
    *  - incoming pixels override the value stored in the color
    *    buffer if it passes the depth test
    *
    *  @param rs Context to which the program will belong.
    **/
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
    /**
    * Returns a pre-defined program store object with the following
    * characteristics:
    *  - incoming pixels always pass the depth test and their value
    *    is not stored in the depth buffer
    *  - incoming pixels override the value stored in the color
    *    buffer
    *
    *  @param rs Context to which the program will belong.
    **/
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
    /**
    * Returns a pre-defined program store object with the following
    * characteristics:
    *  - incoming pixels are drawn if their depth value is less than
    *    the stored value in the depth buffer. If the pixel is
    *    drawn, its value is also stored in the depth buffer
    *  - if the incoming (Source) pixel passes depth test, its value
    *    is combined with the stored color (Dest) using the
    *    following formula
    *  Final.RGB = Source.RGB * Source.A + Dest.RGB * (1 - Source.A)
    *
    *  @param rs Context to which the program will belong.
    **/
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
    /**
    * Returns a pre-defined program store object with the following
    * characteristics:
    *  - incoming pixels always pass the depth test and their value
    *    is not stored in the depth buffer
    *  - incoming pixel's value is combined with the stored color
    *    (Dest) using the following formula
    *  Final.RGB = Source.RGB * Source.A + Dest.RGB * (1 - Source.A)
    *
    *  @param rs Context to which the program will belong.
    **/
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

    /**
    * Builder class for ProgramStore object. If the builder is left
    * empty, the equivalent of BLEND_NONE_DEPTH_NONE would be
    * returned
    */
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

        /**
        * Specifies the depth testing behavior
        *
        * @param func function used for depth testing
        *
        * @return this
        */
        public Builder setDepthFunc(DepthFunc func) {
            mDepthFunc = func;
            return this;
        }

        /**
        * Enables writes into the depth buffer
        *
        * @param enable specifies whether depth writes are
        *         enabled or disabled
        *
        * @return this
        */
        public Builder setDepthMaskEnabled(boolean enable) {
            mDepthMask = enable;
            return this;
        }

        /**
        * Enables writes into the color buffer
        *
        * @param r specifies whether red channel is written
        * @param g specifies whether green channel is written
        * @param b specifies whether blue channel is written
        * @param a specifies whether alpha channel is written
        *
        * @return this
        */
        public Builder setColorMaskEnabled(boolean r, boolean g, boolean b, boolean a) {
            mColorMaskR = r;
            mColorMaskG = g;
            mColorMaskB = b;
            mColorMaskA = a;
            return this;
        }

        /**
        * Specifies how incoming pixels are combined with the pixels
        * stored in the framebuffer
        *
        * @param src specifies how the source blending factor is
        *            computed
        * @param dst specifies how the destination blending factor is
        *            computed
        *
        * @return this
        */
        public Builder setBlendFunc(BlendSrcFunc src, BlendDstFunc dst) {
            mBlendSrc = src;
            mBlendDst = dst;
            return this;
        }

        /**
        * Enables dithering
        *
        * @param enable specifies whether dithering is enabled or
        *               disabled
        *
        * @return this
        */
        public Builder setDitherEnabled(boolean enable) {
            mDither = enable;
            return this;
        }

        /**
        * Creates a program store from the current state of the builder
        */
        public ProgramStore create() {
            mRS.validate();
            long id = mRS.nProgramStoreCreate(mColorMaskR, mColorMaskG, mColorMaskB, mColorMaskA,
                                             mDepthMask, mDither,
                                             mBlendSrc.mID, mBlendDst.mID, mDepthFunc.mID);
            ProgramStore programStore = new ProgramStore(id, mRS);
            programStore.mDepthFunc = mDepthFunc;
            programStore.mDepthMask = mDepthMask;
            programStore.mColorMaskR = mColorMaskR;
            programStore.mColorMaskG = mColorMaskG;
            programStore.mColorMaskB = mColorMaskB;
            programStore.mColorMaskA = mColorMaskA;
            programStore.mBlendSrc = mBlendSrc;
            programStore.mBlendDst = mBlendDst;
            programStore.mDither = mDither;
            return programStore;
        }
    }

}




