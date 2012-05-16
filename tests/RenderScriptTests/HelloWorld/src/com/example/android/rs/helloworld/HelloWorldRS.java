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

package com.example.android.rs.helloworld;

import android.content.res.Resources;
import android.renderscript.*;

// This is the renderer for the HelloWorldView
public class HelloWorldRS {
    private Resources mRes;
    private RenderScriptGL mRS;

    private ScriptC_helloworld mScript;

    public HelloWorldRS() {
    }

    // This provides us with the renderscript context and resources that
    // allow us to create the script that does rendering
    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    public void onActionDown(int x, int y) {
        mScript.set_gTouchX(x);
        mScript.set_gTouchY(y);
    }

    private void initRS() {
        mScript = new ScriptC_helloworld(mRS, mRes, R.raw.helloworld);
        mRS.bindRootScript(mScript);
    }
}



