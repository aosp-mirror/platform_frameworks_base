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
public class Texture2D extends SceneGraphBase {
    String mFileName;
    String mFileDir;
    Allocation mRsTexture;

    public Texture2D() {
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

    Allocation getRsData(RenderScriptGL rs, Resources res) {
        if (mRsTexture != null) {
            return mRsTexture;
        }

        String shortName = mFileName.substring(mFileName.lastIndexOf('/') + 1);
        mRsTexture = SceneManager.loadTexture2D(mFileDir + shortName, rs, res);

        return mRsTexture;
    }
}





