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
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.TypedValue;

/**
 * @hide
 *
 **/
public class FileA3D extends BaseObj {

    public enum ClassID {

        UNKNOWN,
        MESH,
        TYPE,
        ELEMENT,
        ALLOCATION,
        PROGRAM_VERTEX,
        PROGRAM_RASTER,
        PROGRAM_FRAGMENT,
        PROGRAM_STORE,
        SAMPLER,
        ANIMATION,
        LIGHT,
        ADAPTER_1D,
        ADAPTER_2D,
        SCRIPT_C;

        public static ClassID toClassID(int intID) {
            return ClassID.values()[intID];
        }
    }

    // Read only class with index entries
    public static class IndexEntry {
        RenderScript mRS;
        int mIndex;
        int mID;
        String mName;
        ClassID mClassID;
        BaseObj mLoadedObj;

        public String getName() {
            return mName;
        }

        public ClassID getClassID() {
            return mClassID;
        }

        public BaseObj getObject() {
            mRS.validate();
            BaseObj obj = internalCreate(mRS, this);
            return obj;
        }

        static synchronized BaseObj internalCreate(RenderScript rs, IndexEntry entry) {
            if(entry.mLoadedObj != null) {
                return entry.mLoadedObj;
            }

            if(entry.mClassID == ClassID.UNKNOWN) {
                return null;
            }

            int objectID = rs.nFileA3DGetEntryByIndex(entry.mID, entry.mIndex);
            if(objectID == 0) {
                return null;
            }

            switch (entry.mClassID) {
            case MESH:
                entry.mLoadedObj = new Mesh(objectID, rs);
                break;
            case TYPE:
                entry.mLoadedObj = new Type(objectID, rs);
                break;
            case ELEMENT:
                entry.mLoadedObj = null;
                break;
            case ALLOCATION:
                entry.mLoadedObj = null;
                break;
            case PROGRAM_VERTEX:
                entry.mLoadedObj = new ProgramVertex(objectID, rs);
                break;
            case PROGRAM_RASTER:
                break;
            case PROGRAM_FRAGMENT:
                break;
            case PROGRAM_STORE:
                break;
            case SAMPLER:
                break;
            case ANIMATION:
                break;
            case LIGHT:
                break;
            case ADAPTER_1D:
                break;
            case ADAPTER_2D:
                break;
            case SCRIPT_C:
                break;
            }

            entry.mLoadedObj.updateFromNative();

            return entry.mLoadedObj;
        }

        IndexEntry(RenderScript rs, int index, int id, String name, ClassID classID) {
            mRS = rs;
            mIndex = index;
            mID = id;
            mName = name;
            mClassID = classID;
            mLoadedObj = null;
        }
    }

    IndexEntry[] mFileEntries;

    FileA3D(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    private void initEntries() {
        int numFileEntries = mRS.nFileA3DGetNumIndexEntries(mID);
        if(numFileEntries <= 0) {
            return;
        }

        mFileEntries = new IndexEntry[numFileEntries];
        int[] ids = new int[numFileEntries];
        String[] names = new String[numFileEntries];

        mRS.nFileA3DGetIndexEntries(mID, numFileEntries, ids, names);

        for(int i = 0; i < numFileEntries; i ++) {
            mFileEntries[i] = new IndexEntry(mRS, i, mID, names[i], ClassID.toClassID(ids[i]));
        }
    }

    public int getNumIndexEntries() {
        if(mFileEntries == null) {
            return 0;
        }
        return mFileEntries.length;
    }

    public IndexEntry getIndexEntry(int index) {
        if(getNumIndexEntries() == 0 || index < 0 || index >= mFileEntries.length) {
            return null;
        }
        return mFileEntries[index];
    }

    static public FileA3D createFromResource(RenderScript rs, Resources res, int id)
        throws IllegalArgumentException {

        rs.validate();
        InputStream is = null;
        try {
            final TypedValue value = new TypedValue();
            is = res.openRawResource(id, value);

            int asset = ((AssetManager.AssetInputStream) is).getAssetInt();

            int fileId = rs.nFileA3DCreateFromAssetStream(asset);

            if(fileId == 0) {
                throw new IllegalStateException("Load failed.");
            }
            FileA3D fa3d = new FileA3D(fileId, rs);
            fa3d.initEntries();
            return fa3d;

        } catch (Exception e) {
            // Ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        return null;
    }
}
