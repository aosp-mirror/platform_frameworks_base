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


import java.io.IOException;
import java.io.InputStream;

import android.content.res.Resources;
import android.os.Bundle;
import android.util.Config;
import android.util.Log;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * @hide
 *
 **/
public class Type extends BaseObj {
    Type(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void destroy() {
        mRS.nTypeDestroy(mID);
        mID = 0;
    }

    public static class Builder {
        RenderScript mRS;
        boolean mActive = true;

        Builder(RenderScript rs) {
            mRS = rs;
        }

        public void begin(Element e) {
            mRS.nTypeBegin(e.mID);
        }

        public void add(Dimension d, int value) {
            mRS.nTypeAdd(d.mID, value);
        }

        public Type create() {
            int id = mRS.nTypeCreate();
            return new Type(id, mRS);
        }
    }

}
