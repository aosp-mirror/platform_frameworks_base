/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.scenegraph;

import java.lang.Math;

import com.android.scenegraph.SceneManager;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

/**
 * @hide
 */
public class Texture2D extends TextureBase {
    String mFileName;
    String mFileDir;
    int mResourceID;

    public Texture2D() {
        super(ScriptC_export.const_TextureType_TEXTURE_2D);
    }

    public Texture2D(Allocation tex) {
        super(ScriptC_export.const_TextureType_TEXTURE_2D);
        setTexture(tex);
    }

    public Texture2D(String dir, String file) {
        super(ScriptC_export.const_TextureType_TEXTURE_CUBE);
        setFileDir(dir);
        setFileName(file);
    }

    public Texture2D(int resourceID) {
        super(ScriptC_export.const_TextureType_TEXTURE_2D);
        mResourceID = resourceID;
    }

    public void setFileDir(String dir) {
        mFileDir = dir;
    }

    public void setFileName(String file) {
        mFileName = file;
    }

    public String getFileName() {
        return mFileName;
    }

    public void setTexture(Allocation tex) {
        mData.texture = tex != null ? tex : SceneManager.getDefaultTex2D();
        if (mField != null) {
            mField.set_texture(0, mData.texture, true);
        }
    }

    void load() {
        RenderScriptGL rs = SceneManager.getRS();
        Resources res = SceneManager.getRes();
        if (mFileName != null && mFileName.length() > 0) {
            String shortName = mFileName.substring(mFileName.lastIndexOf('/') + 1);
            setTexture(SceneManager.loadTexture2D(mFileDir + shortName, rs, res));
        } else if (mResourceID != 0) {
            setTexture(SceneManager.loadTexture2D(mResourceID, rs, res));
        }
    }

    ScriptField_Texture_s getRsData(boolean loadNow) {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        Resources res = SceneManager.getRes();
        if (rs == null || res == null) {
            return null;
        }

        mField = new ScriptField_Texture_s(rs, 1);

        if (loadNow) {
            load();
        } else {
            mData.texture = SceneManager.getDefaultTex2D();
            new SingleImageLoaderTask().execute(this);
        }

        mField.set(mData, 0, true);
        return mField;
    }
}





