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

import android.compat.annotation.UnsupportedAppUsage;
import android.filterfw.core.Filter;
import android.filterfw.core.FilterFactory;
import android.filterfw.core.FilterFunction;
import android.filterfw.core.Frame;

/**
 * Effect subclass for effects based on a single Filter. Subclasses need only invoke the
 * constructor with the correct arguments to obtain an Effect implementation.
 *
 * @hide
 */
public class SingleFilterEffect extends FilterEffect {

    protected FilterFunction mFunction;
    protected String mInputName;
    protected String mOutputName;

    /**
     * Constructs a new FilterFunctionEffect.
     *
     * @param name The name of this effect (used to create it in the EffectFactory).
     * @param filterClass The class of the filter to wrap.
     * @param inputName The name of the input image port.
     * @param outputName The name of the output image port.
     * @param finalParameters Key-value pairs of final input port assignments.
     */
    @UnsupportedAppUsage
    public SingleFilterEffect(EffectContext context,
                              String name,
                              Class filterClass,
                              String inputName,
                              String outputName,
                              Object... finalParameters) {
        super(context, name);

        mInputName = inputName;
        mOutputName = outputName;

        String filterName = filterClass.getSimpleName();
        FilterFactory factory = FilterFactory.sharedFactory();
        Filter filter = factory.createFilterByClass(filterClass, filterName);
        filter.initWithAssignmentList(finalParameters);

        mFunction = new FilterFunction(getFilterContext(), filter);
    }

    @Override
    public void apply(int inputTexId, int width, int height, int outputTexId) {
        beginGLEffect();

        Frame inputFrame = frameFromTexture(inputTexId, width, height);
        Frame outputFrame = frameFromTexture(outputTexId, width, height);

        Frame resultFrame = mFunction.executeWithArgList(mInputName, inputFrame);

        outputFrame.setDataFromFrame(resultFrame);

        inputFrame.release();
        outputFrame.release();
        resultFrame.release();

        endGLEffect();
    }

    @Override
    public void setParameter(String parameterKey, Object value) {
        mFunction.setInputValue(parameterKey, value);
    }

    @Override
    public void release() {
        mFunction.tearDown();
        mFunction = null;
    }
}

