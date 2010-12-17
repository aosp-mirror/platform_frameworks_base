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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.TypedValue;

/**
 * @hide
 *
 **/
public class FileA3D extends BaseObj {

    // This will go away in the clean up pass,
    // trying to avoid multiproject submits
    public enum ClassID {

        UNKNOWN,
        MESH;

        public static ClassID toClassID(int intID) {
            return ClassID.values()[intID];
        }
    }

    public enum EntryType {

        UNKNOWN (0),
        MESH (1);

        int mID;
        EntryType(int id) {
            mID = id;
        }

        static EntryType toEntryType(int intID) {
            return EntryType.values()[intID];
        }
    }

    // Read only class with index entries
    public static class IndexEntry {
        RenderScript mRS;
        int mIndex;
        int mID;
        String mName;
        ClassID mClassID;
        EntryType mEntryType;
        BaseObj mLoadedObj;

        public String getName() {
            return mName;
        }

        public ClassID getClassID() {
            return mClassID;
        }

        public EntryType getEntryType() {
            return mEntryType;
        }

        public BaseObj getObject() {
            mRS.validate();
            BaseObj obj = internalCreate(mRS, this);
            return obj;
        }

        public Mesh getMesh() {
            return (Mesh)getObject();
        }

        static synchronized BaseObj internalCreate(RenderScript rs, IndexEntry entry) {
            if(entry.mLoadedObj != null) {
                return entry.mLoadedObj;
            }

            // to be purged on cleanup
            if(entry.mEntryType == EntryType.UNKNOWN) {
                return null;
            }

            int objectID = rs.nFileA3DGetEntryByIndex(entry.mID, entry.mIndex);
            if(objectID == 0) {
                return null;
            }

            switch (entry.mEntryType) {
            case MESH:
                entry.mLoadedObj = new Mesh(objectID, rs);
                break;
            }

            entry.mLoadedObj.updateFromNative();
            return entry.mLoadedObj;
        }

        IndexEntry(RenderScript rs, int index, int id, String name, EntryType type) {
            mRS = rs;
            mIndex = index;
            mID = id;
            mName = name;
            mEntryType = type;
            mClassID = mEntryType == EntryType.MESH ? ClassID.MESH : ClassID.UNKNOWN;
            mLoadedObj = null;
        }
    }

    IndexEntry[] mFileEntries;
    InputStream mInputStream;

    FileA3D(int id, RenderScript rs, InputStream stream) {
        super(id, rs);
        mInputStream = stream;
    }

    private void initEntries() {
        int numFileEntries = mRS.nFileA3DGetNumIndexEntries(getID());
        if(numFileEntries <= 0) {
            return;
        }

        mFileEntries = new IndexEntry[numFileEntries];
        int[] ids = new int[numFileEntries];
        String[] names = new String[numFileEntries];

        mRS.nFileA3DGetIndexEntries(getID(), numFileEntries, ids, names);

        for(int i = 0; i < numFileEntries; i ++) {
            mFileEntries[i] = new IndexEntry(mRS, i, getID(), names[i], EntryType.toEntryType(ids[i]));
        }
    }

    public int getIndexEntryCount() {
        if(mFileEntries == null) {
            return 0;
        }
        return mFileEntries.length;
    }

    public IndexEntry getIndexEntry(int index) {
        if(getIndexEntryCount() == 0 || index < 0 || index >= mFileEntries.length) {
            return null;
        }
        return mFileEntries[index];
    }

    // API cleanup stand-ins
    // TODO: implement ermaining loading mechanisms
    static public FileA3D createFromAsset(RenderScript rs, AssetManager mgr, String path)
        throws IllegalArgumentException {
        return null;
    }

    static public FileA3D createFromFile(RenderScript rs, String path)
        throws IllegalArgumentException {
        return null;
    }

    static public FileA3D createFromFile(RenderScript rs, File path)
        throws IllegalArgumentException {
        return createFromFile(rs, path.getAbsolutePath());
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
            FileA3D fa3d = new FileA3D(fileId, rs, is);
            fa3d.initEntries();
            return fa3d;

        } catch (Exception e) {
            // Ignore
        }

        return null;
    }
}
