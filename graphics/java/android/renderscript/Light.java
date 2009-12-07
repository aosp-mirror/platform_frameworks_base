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
public class Light extends BaseObj {
    Light(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void setColor(float r, float g, float b) {
        mRS.validate();
        mRS.nLightSetColor(mID, r, g, b);
    }

    public void setPosition(float x, float y, float z) {
        mRS.validate();
        mRS.nLightSetPosition(mID, x, y, z);
    }

    public static class Builder {
        RenderScript mRS;
        boolean mIsMono;
        boolean mIsLocal;

        public Builder(RenderScript rs) {
            mRS = rs;
            mIsMono = false;
            mIsLocal = false;
        }

        public void lightSetIsMono(boolean isMono) {
            mIsMono = isMono;
        }

        public void lightSetIsLocal(boolean isLocal) {
            mIsLocal = isLocal;
        }

        static synchronized Light internalCreate(RenderScript rs, Builder b) {
            rs.nSamplerBegin();
            rs.nLightSetIsMono(b.mIsMono);
            rs.nLightSetIsLocal(b.mIsLocal);
            int id = rs.nLightCreate();
            return new Light(id, rs);
        }

        public Light create() {
            mRS.validate();
            return internalCreate(mRS, this);
        }
    }

}

