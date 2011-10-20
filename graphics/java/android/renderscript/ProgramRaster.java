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


/**
 * Program raster is primarily used to specify whether point sprites are enabled and to control
 * the culling mode. By default, back faces are culled.
 **/
public class ProgramRaster extends BaseObj {

    public enum CullMode {
        BACK (0),
        FRONT (1),
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

    /**
     * @hide
     * @return whether point sprites are enabled
     */
    public boolean getPointSpriteEnabled() {
        return mPointSprite;
    }

    /**
     * @hide
     * @return cull mode
     */
    public CullMode getCullMode() {
        return mCullMode;
    }

    public static ProgramRaster CULL_BACK(RenderScript rs) {
        if(rs.mProgramRaster_CULL_BACK == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.BACK);
            rs.mProgramRaster_CULL_BACK = builder.create();
        }
        return rs.mProgramRaster_CULL_BACK;
    }

    public static ProgramRaster CULL_FRONT(RenderScript rs) {
        if(rs.mProgramRaster_CULL_FRONT == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.FRONT);
            rs.mProgramRaster_CULL_FRONT = builder.create();
        }
        return rs.mProgramRaster_CULL_FRONT;
    }

    public static ProgramRaster CULL_NONE(RenderScript rs) {
        if(rs.mProgramRaster_CULL_NONE == null) {
            ProgramRaster.Builder builder = new ProgramRaster.Builder(rs);
            builder.setCullMode(CullMode.NONE);
            rs.mProgramRaster_CULL_NONE = builder.create();
        }
        return rs.mProgramRaster_CULL_NONE;
    }

    public static class Builder {
        RenderScript mRS;
        boolean mPointSprite;
        CullMode mCullMode;

        public Builder(RenderScript rs) {
            mRS = rs;
            mPointSprite = false;
            mCullMode = CullMode.BACK;
        }

        public Builder setPointSpriteEnabled(boolean enable) {
            mPointSprite = enable;
            return this;
        }

        public Builder setCullMode(CullMode m) {
            mCullMode = m;
            return this;
        }

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






