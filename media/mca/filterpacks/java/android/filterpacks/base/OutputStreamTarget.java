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


package android.filterpacks.base;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;

import java.io.OutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * @hide
 */
public class OutputStreamTarget extends Filter {

    @GenerateFieldPort(name = "stream")
    private OutputStream mOutputStream;

    public OutputStreamTarget(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addInputPort("data");
    }

    @Override
    public void process(FilterContext context) {
        Frame input = pullInput("data");
        ByteBuffer data;

        if (input.getFormat().getObjectClass() == String.class) {
            String stringVal = (String)input.getObjectValue();
            data = ByteBuffer.wrap(stringVal.getBytes());
        } else {
            data = input.getData();
        }
        try {
            mOutputStream.write(data.array(), 0, data.limit());
            mOutputStream.flush();
        } catch (IOException exception) {
            throw new RuntimeException(
                "OutputStreamTarget: Could not write to stream: " + exception.getMessage() + "!");
        }
    }
}
