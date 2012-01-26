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
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element.Builder;
import android.renderscript.Font.Style;
import android.renderscript.Program.TextureType;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;
import com.android.scenegraph.SceneManager.SceneLoadedCallback;


public class ColladaScene {

    private String modelName;
    private static String TAG = "ColladaScene";
    private final int STATE_LAST_FOCUS = 1;
    boolean mLoadFromSD = false;

    SceneLoadedCallback mCallback;

    public ColladaScene(String name, SceneLoadedCallback cb) {
        modelName = name;
        mCallback = cb;
    }

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;

        mLoadFromSD = SceneManager.isSDCardPath(modelName);

        new ColladaLoaderTask().execute(modelName);
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    Scene mActiveScene;

    private class ColladaLoaderTask extends AsyncTask<String, Void, Boolean> {
        ColladaParser sceneSource;
        protected Boolean doInBackground(String... names) {
            String rootDir = names[0].substring(0, names[0].lastIndexOf('/') + 1);
            long start = System.currentTimeMillis();
            sceneSource = new ColladaParser();
            InputStream is = null;
            try {
                if (!mLoadFromSD) {
                    is = mRes.getAssets().open(names[0]);
                } else {
                    File f = new File(names[0]);
                    is = new BufferedInputStream(new FileInputStream(f));
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not open collada file");
                return new Boolean(false);
            }
            long end = System.currentTimeMillis();
            Log.v("TIMER", "Stream load time: " + (end - start));

            start = System.currentTimeMillis();
            sceneSource.init(is, rootDir);
            end = System.currentTimeMillis();
            Log.v("TIMER", "Collada parse time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
            mActiveScene = sceneSource.getScene();
            if (mCallback != null) {
                mCallback.mLoadedScene = mActiveScene;
                mCallback.run();
            }

            String shortName = modelName.substring(0, modelName.lastIndexOf('.'));
            new A3DLoaderTask().execute(shortName + ".a3d");
        }
    }

    private class A3DLoaderTask extends AsyncTask<String, Void, Boolean> {
        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();
            FileA3D model;
            if (!mLoadFromSD) {
                model = FileA3D.createFromAsset(mRS, mRes.getAssets(), names[0]);
            } else {
                model = FileA3D.createFromFile(mRS, names[0]);
            }
            int numModels = model.getIndexEntryCount();
            for (int i = 0; i < numModels; i ++) {
                FileA3D.IndexEntry entry = model.getIndexEntry(i);
                if (entry != null && entry.getEntryType() == FileA3D.EntryType.MESH) {
                    mActiveScene.meshLoaded(entry.getMesh());
                }
            }
            long end = System.currentTimeMillis();
            Log.v("TIMER", "A3D load time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
        }
    }

}



