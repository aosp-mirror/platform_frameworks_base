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

package android.media.effect;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterGraph;
import android.filterfw.core.GraphRunner;
import android.filterfw.core.SimpleScheduler;
import android.filterfw.core.SyncRunner;
import android.media.effect.Effect;
import android.media.effect.FilterEffect;
import android.media.effect.EffectContext;
import android.filterfw.io.GraphIOException;
import android.filterfw.io.GraphReader;
import android.filterfw.io.TextGraphReader;

import android.util.Log;

/**
 * Effect subclass for effects based on a single Filter. Subclasses need only invoke the
 * constructor with the correct arguments to obtain an Effect implementation.
 *
 * @hide
 */
public class FilterGraphEffect extends FilterEffect {

    private static final String TAG = "FilterGraphEffect";

    protected String mInputName;
    protected String mOutputName;
    protected GraphRunner mRunner;
    protected FilterGraph mGraph;
    protected Class mSchedulerClass;

    /**
     * Constructs a new FilterGraphEffect.
     *
     * @param name The name of this effect (used to create it in the EffectFactory).
     * @param graphString The graph string to create the graph.
     * @param inputName The name of the input GLTextureSource filter.
     * @param outputName The name of the output GLTextureSource filter.
     */
    public FilterGraphEffect(EffectContext context,
                              String name,
                              String graphString,
                              String inputName,
                              String outputName,
                              Class scheduler) {
        super(context, name);

        mInputName = inputName;
        mOutputName = outputName;
        mSchedulerClass = scheduler;
        createGraph(graphString);

    }

    private void createGraph(String graphString) {
        GraphReader reader = new TextGraphReader();
        try {
            mGraph = reader.readGraphString(graphString);
        } catch (GraphIOException e) {
            throw new RuntimeException("Could not setup effect", e);
        }

        if (mGraph == null) {
            throw new RuntimeException("Could not setup effect");
        }
        mRunner = new SyncRunner(getFilterContext(), mGraph, mSchedulerClass);
    }

    @Override
    public void apply(int inputTexId, int width, int height, int outputTexId) {
        beginGLEffect();
        Filter src = mGraph.getFilter(mInputName);
        if (src != null) {
            src.setInputValue("texId", inputTexId);
            src.setInputValue("width", width);
            src.setInputValue("height", height);
        } else {
            throw new RuntimeException("Internal error applying effect");
        }
        Filter dest  = mGraph.getFilter(mOutputName);
        if (dest != null) {
            dest.setInputValue("texId", outputTexId);
        } else {
            throw new RuntimeException("Internal error applying effect");
        }
        try {
            mRunner.run();
        } catch (RuntimeException e) {
            throw new RuntimeException("Internal error applying effect: ", e);
        }
        endGLEffect();
    }

    @Override
    public void setParameter(String parameterKey, Object value) {
    }

    @Override
    public void release() {
         mGraph.tearDown(getFilterContext());
         mGraph = null;
    }
}
