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

package com.android.testapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.android.scenegraph.SceneManager;

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

// This is where the scenegraph and the rendered objects are initialized and used
public class TestAppLoadingScreen {

    private static String TAG = "TestAppLoadingScreen";

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_test_app mScript;

    public TestAppLoadingScreen(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        // Shows the loading screen with some text
        renderLoading();
        // Adds a little 3D bugdroid model to the laoding screen asynchronously.
        new LoadingScreenLoaderTask().execute();
    }

    public void showLoadingScreen(boolean show) {
        if (show) {
            mRS.bindRootScript(mScript);
        } else {
            mRS.bindRootScript(SceneManager.getInstance().getRenderLoop());
        }
    }

    // The loading screen has some elements that shouldn't be loaded on the UI thread
    private class LoadingScreenLoaderTask extends AsyncTask<String, Void, Boolean> {
        Allocation robotTex;
        Mesh robotMesh;
        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();
            robotTex = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.robot,
                                                           MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                           Allocation.USAGE_GRAPHICS_TEXTURE);

            FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.robot);
            FileA3D.IndexEntry entry = model.getIndexEntry(0);
            if (entry != null && entry.getEntryType() == FileA3D.EntryType.MESH) {
                robotMesh = entry.getMesh();
            }

            mScript.set_gPFSBackground(ProgramStore.BLEND_NONE_DEPTH_TEST(mRS));

            ProgramFragmentFixedFunction.Builder b = new ProgramFragmentFixedFunction.Builder(mRS);
            b.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                         ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
            ProgramFragment pfDefault = b.create();
            pfDefault.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);
            mScript.set_gPFBackground(pfDefault);

            ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
            ProgramVertexFixedFunction pvDefault = pvb.create();
            ProgramVertexFixedFunction.Constants va = new ProgramVertexFixedFunction.Constants(mRS);
            ((ProgramVertexFixedFunction)pvDefault).bindConstants(va);
            mScript.set_gPVBackground(pvDefault);

            long end = System.currentTimeMillis();
            Log.v("TIMER", "Loading load time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
            mScript.set_gRobotTex(robotTex);
            mScript.set_gRobotMesh(robotMesh);
        }
    }

    // Creates a simple script to show a loding screen until everything is initialized
    // Could also be used to do some custom renderscript work before handing things over
    // to the scenegraph
    void renderLoading() {
        mScript = new ScriptC_test_app(mRS, mRes, R.raw.test_app);
        mRS.bindRootScript(mScript);
    }
}
