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


package android.filterfw.core;

import android.compat.annotation.UnsupportedAppUsage;

/**
 * @hide
 */
public abstract class Program {

    @UnsupportedAppUsage
    public abstract void process(Frame[] inputs, Frame output);

    @UnsupportedAppUsage
    public void process(Frame input, Frame output) {
        Frame[] inputs = new Frame[1];
        inputs[0] = input;
        process(inputs, output);
    }

    @UnsupportedAppUsage
    public abstract void setHostValue(String variableName, Object value);

    public abstract Object getHostValue(String variableName);

    public void reset() {
    }
}
