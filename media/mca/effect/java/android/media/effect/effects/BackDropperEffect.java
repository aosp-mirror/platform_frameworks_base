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

package android.media.effect.effects;

import android.filterfw.core.Filter;
import android.filterfw.core.OneShotScheduler;
import android.media.effect.EffectContext;
import android.media.effect.FilterGraphEffect;
import android.media.effect.EffectUpdateListener;

import android.filterpacks.videoproc.BackDropperFilter;
import android.filterpacks.videoproc.BackDropperFilter.LearningDoneListener;

/**
 * Background replacement Effect.
 *
 * Replaces the background of the input video stream with a selected video
 * Learns the background when it first starts up;
 * needs unobstructed view of background when this happens.
 *
 * Effect parameters:
 *   source: A URI for the background video
 * Listener: Called when learning period is complete
 *
 * @hide
 */
public class BackDropperEffect extends FilterGraphEffect {
    private static final String mGraphDefinition =
            "@import android.filterpacks.base;\n" +
            "@import android.filterpacks.videoproc;\n" +
            "@import android.filterpacks.videosrc;\n" +
            "\n" +
            "@filter GLTextureSource foreground {\n" +
            "  texId = 0;\n" + // Will be set by base class
            "  width = 0;\n" +
            "  height = 0;\n" +
            "  repeatFrame = true;\n" +
            "}\n" +
            "\n" +
            "@filter MediaSource background {\n" +
            "  sourceUrl = \"no_file_specified\";\n" +
            "  waitForNewFrame = false;\n" +
            "  sourceIsUrl = true;\n" +
            "}\n" +
            "\n" +
            "@filter BackDropperFilter replacer {\n" +
            "  autowbToggle = 1;\n" +
            "}\n" +
            "\n" +
            "@filter GLTextureTarget output {\n" +
            "  texId = 0;\n" +
            "}\n" +
            "\n" +
            "@connect foreground[frame]  => replacer[video];\n" +
            "@connect background[video]  => replacer[background];\n" +
            "@connect replacer[video]    => output[frame];\n";

    private EffectUpdateListener mEffectListener = null;

    private LearningDoneListener mLearningListener = new LearningDoneListener() {
        public void onLearningDone(BackDropperFilter filter) {
            if (mEffectListener != null) {
                mEffectListener.onEffectUpdated(BackDropperEffect.this, null);
            }
        }
    };

    public BackDropperEffect(EffectContext context, String name) {
        super(context, name, mGraphDefinition, "foreground", "output", OneShotScheduler.class);

        Filter replacer = mGraph.getFilter("replacer");
        replacer.setInputValue("learningDoneListener", mLearningListener);
    }

    @Override
    public void setParameter(String parameterKey, Object value) {
        if (parameterKey.equals("source")) {
            Filter background = mGraph.getFilter("background");
            background.setInputValue("sourceUrl", value);
        }
    }

    @Override
    public void setUpdateListener(EffectUpdateListener listener) {
        mEffectListener = listener;
    }

}