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

import java.lang.reflect.Field;

import android.renderscript.Element;
import android.util.Config;
import android.util.Log;

/**
 * @hide
 *
 **/
public class Type extends BaseObj {
    Dimension[] mDimensions;
    int[] mValues;
    Element mElement;
    private int mNativeCache;
    Class mJavaClass;


    Type(int id, RenderScript rs) {
        super(rs);
        mID = id;
        mNativeCache = 0;
    }

    protected void finalize() throws Throwable {
        if(mNativeCache != 0) {
            mRS.nTypeFinalDestroy(this);
            mNativeCache = 0;
        }
        super.finalize();
    }

    public void destroy() {
        if(mDestroyed) {
            throw new IllegalStateException("Object already destroyed.");
        }
        mDestroyed = true;
        mRS.nTypeDestroy(mID);
    }

    public static Type createFromClass(RenderScript rs, Class c, int size) {
        Element e = Element.createFromClass(rs, c);
        Builder b = new Builder(rs, e);
        b.add(Dimension.X, size);
        Type t = b.create();
        e.destroy();

        // native fields
        {
            Field[] fields = c.getFields();
            int[] arTypes = new int[fields.length];
            int[] arBits = new int[fields.length];

            for(int ct=0; ct < fields.length; ct++) {
                Field f = fields[ct];
                Class fc = f.getType();
                if(fc == int.class) {
                    arTypes[ct] = Element.DataType.SIGNED.mID;
                    arBits[ct] = 32;
                } else if(fc == short.class) {
                    arTypes[ct] = Element.DataType.SIGNED.mID;
                    arBits[ct] = 16;
                } else if(fc == byte.class) {
                    arTypes[ct] = Element.DataType.SIGNED.mID;
                    arBits[ct] = 8;
                } else if(fc == float.class) {
                    arTypes[ct] = Element.DataType.FLOAT.mID;
                    arBits[ct] = 32;
                } else {
                    throw new IllegalArgumentException("Unkown field type");
                }
            }
            rs.nTypeSetupFields(t, arTypes, arBits, fields);
        }
        t.mJavaClass = c;
        return t;
    }

    public static Type createFromClass(RenderScript rs, Class c, int size, String scriptName) {
        Type t = createFromClass(rs, c, size);
        t.setName(scriptName);
        return t;
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
            Type t = internalCreate(mRS, this);
            t.mElement = mElement;
            t.mDimensions = new Dimension[mEntryCount];
            t.mValues = new int[mEntryCount];
            for(int ct=0; ct < mEntryCount; ct++) {
                t.mDimensions[ct] = mEntries[ct].mDim;
                t.mValues[ct] = mEntries[ct].mValue;
            }
            return t;
        }
    }

}
