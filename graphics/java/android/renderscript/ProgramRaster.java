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


import android.util.Log;


/** @deprecated renderscript is deprecated in J
 * Program raster is primarily used to specify whether point sprites are enabled and to control
 * the culling mode. By default, back faces are culled.
 **/
public class ProgramRaster extends BaseObj {

    /** @deprecated renderscript is deprecated in J
     */
    public enum CullMode {
        /** @deprecated renderscript is deprecated in J
        */
        BACK (0),
        /** @deprecated renderscript is deprecated in J
        */
        FRONT (1),
        /** @deprecated renderscript is deprecated in J
        */
        NONE (2);

        int mID;
        CullMode(int id) {
            mID = id;
        }
    }

    boolean mPointSprite;
    CullMode mCullMode;

    ProgramRaster(int id, RenderScript rs) {
        super(id, rs);

        mPointSprite = false;
        mCullMode = CullMode.BACK;
    }

    /** @hide renderscript is deprecated in J
     * Specifies whether vertices are rendered as screen aligned
     * elements of a specified size
     * @return whether point sprites are enabled
     */
    public boolean isPointSpriteEnabled() {
        return mPointSprite;
    }

    /** @hide renderscript is deprecated in J
     * Specifies how triangles are culled based on their orientation
     * @return cull mode
     */
    public CullMode getCullMode() {
        return mCullMode;
    }

    /** @deprecated renderscript is deprecated in J
     */
    public static ProgramRaster CULL_BACK(RenderScript rs) {
        if(rs.mProgramRaster_CULL_BACK == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.BACK);
            rs.mProgramRaster_CULL_BACK = builder.create();
        }
        return rs.mProgramRaster_CULL_BACK;
    }

    /** @deprecated renderscript is deprecated in J
     */
    public static ProgramRaster CULL_FRONT(RenderScript rs) {
        if(rs.mProgramRaster_CULL_FRONT == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.FRONT);
            rs.mProgramRaster_CULL_FRONT = builder.create();
        }
        return rs.mProgramRaster_CULL_FRONT;
    }

    /** @deprecated renderscript is deprecated in J
     */
    public static ProgramRaster CULL_NONE(RenderScript rs) {
        if(rs.mProgramRaster_CULL_NONE == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.NONE);
            rs.mProgramRaster_CULL_NONE = builder.create();
        }
        return rs.mProgramRaster_CULL_NONE;
    }

    /** @deprecated renderscript is deprecated in J
     */
    public static class Builder {
        RenderScript mRS;
        boolean mPointSprite;
        CullMode mCullMode;

        /** @deprecated renderscript is deprecated in J
        */
        public Builder(RenderScript rs) {
            mRS = rs;
            mPointSprite = false;
            mCullMode = CullMode.BACK;
        }

        /** @deprecated renderscript is deprecated in J
        */
        public Builder setPointSpriteEnabled(boolean enable) {
            mPointSprite = enable;
            return this;
        }

        /** @deprecated renderscript is deprecated in J
        */
        public Builder setCullMode(CullMode m) {
            mCullMode = m;
            return this;
        }

        /** @deprecated renderscript is deprecated in J
        */
        public ProgramRaster create() {
            mRS.validate();
            int id = mRS.nProgramRasterCreate(mPointSprite, mCullMode.mID);
            ProgramRaster programRaster = new ProgramRaster(id, mRS);
            programRaster.mPointSprite = mPointSprite;
            programRaster.mCullMode = mCullMode;
            return programRaster;
        }
    }

}






