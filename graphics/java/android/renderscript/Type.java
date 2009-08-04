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
        Entry[] mEntries;
        int mEntryCount;
        Element mElement;

        class Entry {
            Dimension mDim;
            int mValue;
        }

        public Builder(RenderScript rs, Element e) {
            mRS = rs;
            mEntries = new Entry[4];
            mElement = e;
        }

        public void add(Dimension d, int value) {
            if(mEntries.length >= mEntryCount) {
                Entry[] en = new Entry[mEntryCount + 8];
                for(int ct=0; ct < mEntries.length; ct++) {
                    en[ct] = mEntries[ct];
                }
                mEntries = en;
            }
            mEntries[mEntryCount] = new Entry();
            mEntries[mEntryCount].mDim = d;
            mEntries[mEntryCount].mValue = value;
            mEntryCount++;
        }

        static synchronized Type internalCreate(RenderScript rs, Builder b) {
            rs.nTypeBegin(b.mElement.mID);
            for (int ct=0; ct < b.mEntryCount; ct++) {
                Entry en = b.mEntries[ct];
                rs.nTypeAdd(en.mDim.mID, en.mValue);
            }
            int id = rs.nTypeCreate();
            return new Type(id, rs);
        }

        public Type create() {
            return internalCreate(mRS, this);
        }
    }

}
