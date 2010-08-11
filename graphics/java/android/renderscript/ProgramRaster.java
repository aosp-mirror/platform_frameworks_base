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

    boolean mPointSmooth;
    boolean mLineSmooth;
    boolean mPointSprite;
    float mLineWidth;
    CullMode mCullMode;

    ProgramRaster(int id, RenderScript rs) {
        super(id, rs);

        mLineWidth = 1.0f;
        mPointSmooth = false;
        mLineSmooth = false;
        mPointSprite = false;

        mCullMode = CullMode.BACK;
    }

    public void setLineWidth(float w) {
        mRS.validate();
        mLineWidth = w;
        mRS.nProgramRasterSetLineWidth(mID, w);
    }

    public void setCullMode(CullMode m) {
        mRS.validate();
        mCullMode = m;
        mRS.nProgramRasterSetCullMode(mID, m.mID);
    }

    public static class Builder {
        RenderScript mRS;
        boolean mPointSprite;
        boolean mPointSmooth;
        boolean mLineSmooth;

        public Builder(RenderScript rs) {
            mRS = rs;
            mPointSmooth = false;
            mLineSmooth = false;
            mPointSprite = false;
        }

        public Builder setPointSpriteEnable(boolean enable) {
            mPointSprite = enable;
            return this;
        }

        public Builder setPointSmoothEnable(boolean enable) {
            mPointSmooth = enable;
            return this;
        }

        public Builder setLineSmoothEnable(boolean enable) {
            mLineSmooth = enable;
            return this;
        }

        static synchronized ProgramRaster internalCreate(RenderScript rs, Builder b) {
            int id = rs.nProgramRasterCreate(b.mPointSmooth, b.mLineSmooth, b.mPointSprite);
            ProgramRaster pr = new ProgramRaster(id, rs);
            return pr;
        }

        public ProgramRaster create() {
            mRS.validate();
            return internalCreate(mRS, this);
        }
    }

}






