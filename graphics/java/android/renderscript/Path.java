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

import java.util.Vector;
import android.util.Log;

/**
 * @hide
 *
 */
public class Path extends BaseObj {

    public enum Primitive {
        QUADRATIC_BEZIER(0),
        CUBIC_BEZIER(1);

        int mID;
        Primitive(int id) {
            mID = id;
        }
    }

    Allocation mVertexBuffer;
    Allocation mLoopBuffer;
    Primitive mPrimitive;
    float mQuality;
    boolean mCoverageToAlpha;

    Path(int id, RenderScript rs, Primitive p, Allocation vtx, Allocation loop, float q) {
        super(id, rs);
        mVertexBuffer = vtx;
        mLoopBuffer = loop;
        mPrimitive = p;
        mQuality = q;
    }

    public Allocation getVertexAllocation() {
        return mVertexBuffer;
    }

    public Allocation getLoopAllocation() {
        return mLoopBuffer;
    }

    public Primitive getPrimitive() {
        return mPrimitive;
    }

    @Override
    void updateFromNative() {
    }


    public static Path createStaticPath(RenderScript rs, Primitive p, float quality, Allocation vtx) {
        int id = rs.nPathCreate(p.mID, false, vtx.getID(rs), 0, quality);
        Path newPath = new Path(id, rs, p, null, null, quality);
        return newPath;
    }

    public static Path createStaticPath(RenderScript rs, Primitive p, float quality, Allocation vtx, Allocation loops) {
        return null;
    }

    public static Path createDynamicPath(RenderScript rs, Primitive p, float quality, Allocation vtx) {
        return null;
    }

    public static Path createDynamicPath(RenderScript rs, Primitive p, float quality, Allocation vtx, Allocation loops) {
        return null;
    }


}


