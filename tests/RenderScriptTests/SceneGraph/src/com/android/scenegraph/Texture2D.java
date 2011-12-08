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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.lang.Math;
import java.net.URL;
import java.util.ArrayList;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.renderscript.*;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Matrix4f;
import android.renderscript.Type.Builder;
import android.util.Log;

/**
 * @hide
 */
public class Texture2D extends SceneGraphBase {
    private static String mSDCardPath = "sdcard/scenegraph/";
    private final boolean mLoadFromSD = true;

    String mFileName;
    Allocation mRsTexture;

    public Texture2D() {
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
        InputStream is = null;
        try {
            if (!mLoadFromSD) {
                is = res.getAssets().open(shortName);
            } else {
                File f = new File(mSDCardPath + shortName);
                is = new BufferedInputStream(new FileInputStream(f));
            }
        } catch (IOException e) {
            Log.e("Texture2D",
                  "Could not open image file " + shortName + " Message: " + e.getMessage());
            return null;
        }

        Bitmap b = BitmapFactory.decodeStream(is);
        mRsTexture = Allocation.createFromBitmap(rs, b,
                                                 Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                 Allocation.USAGE_GRAPHICS_TEXTURE);
        return mRsTexture;
    }
}





