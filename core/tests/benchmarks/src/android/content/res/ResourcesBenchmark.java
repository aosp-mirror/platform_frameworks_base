/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.content.res;

import android.util.AttributeSet;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;

public class ResourcesBenchmark {

    private AssetManager mAsset;
    private Resources mRes;

    private int mTextId;
    private int mColorId;
    private int mIntegerId;
    private int mLayoutId;

    @BeforeExperiment
    protected void setUp() {
        mAsset = new AssetManager();
        mAsset.addAssetPath("/system/framework/framework-res.apk");
        mRes = new Resources(mAsset, null, null);

        mTextId = mRes.getIdentifier("cancel", "string", "android");
        mColorId = mRes.getIdentifier("transparent", "color", "android");
        mIntegerId = mRes.getIdentifier("config_shortAnimTime", "integer", "android");
        mLayoutId = mRes.getIdentifier("two_line_list_item", "layout", "android");
    }

    @AfterExperiment
    protected void tearDown() {
        mAsset.close();
    }

    public void timeGetString(int reps) {
        for (int i = 0; i < reps; i++) {
            mRes.getText(mTextId);
        }
    }

    public void timeGetColor(int reps) {
        for (int i = 0; i < reps; i++) {
            mRes.getColor(mColorId, null);
        }
    }

    public void timeGetInteger(int reps) {
        for (int i = 0; i < reps; i++) {
            mRes.getInteger(mIntegerId);
        }
    }

    public void timeGetLayoutAndTraverse(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            final XmlResourceParser parser = mRes.getLayout(mLayoutId);
            try {
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    // Walk the entire tree
                }
            } finally {
                parser.close();
            }
        }
    }
}
