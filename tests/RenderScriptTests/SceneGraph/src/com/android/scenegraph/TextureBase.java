/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.os.AsyncTask;
import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

/**
 * @hide
 */
public abstract class TextureBase extends SceneGraphBase {

    class SingleImageLoaderTask extends AsyncTask<TextureBase, Void, Boolean> {
        protected Boolean doInBackground(TextureBase... objects) {
            TextureBase tex = objects[0];
            tex.load();
            return new Boolean(true);
        }
        protected void onPostExecute(Boolean result) {
        }
    }

    ScriptField_Texture_s.Item mData;
    ScriptField_Texture_s mField;
    TextureBase(int type) {
        mData = new ScriptField_Texture_s.Item();
        mData.type = type;
    }

    protected Allocation mRsTexture;
    abstract ScriptField_Texture_s getRsData(boolean loadNow);
    abstract void load();
}





