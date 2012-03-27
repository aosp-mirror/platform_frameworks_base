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

/**
 * @hide
 */
public class ProgramVariable {

    private Program mProgram;
    private String mVarName;

    public ProgramVariable(Program program, String varName) {
        mProgram = program;
        mVarName = varName;
    }

    public Program getProgram() {
        return mProgram;
    }

    public String getVariableName() {
        return mVarName;
    }

    public void setValue(Object value) {
        if (mProgram == null) {
            throw new RuntimeException("Attempting to set program variable '" + mVarName
                + "' but the program is null!");
        }
        mProgram.setHostValue(mVarName, value);
    }

    public Object getValue() {
        if (mProgram == null) {
            throw new RuntimeException("Attempting to get program variable '" + mVarName
                + "' but the program is null!");
        }
        return mProgram.getHostValue(mVarName);
    }

}
