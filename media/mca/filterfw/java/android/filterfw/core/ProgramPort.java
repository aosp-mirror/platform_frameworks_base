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

import java.lang.reflect.Field;

/**
 * @hide
 */
public class ProgramPort extends FieldPort {

    protected String mVarName;

    public ProgramPort(Filter filter,
                       String name,
                       String varName,
                       Field field,
                       boolean hasDefault) {
        super(filter, name, field, hasDefault);
        mVarName = varName;
    }

    @Override
    public String toString() {
        return "Program " + super.toString();
    }

    @Override
    public synchronized void transfer(FilterContext context) {
        if (mValueWaiting) {
            try {
                Object fieldValue = mField.get(mFilter);
                if (fieldValue != null) {
                    Program program = (Program)fieldValue;
                    program.setHostValue(mVarName, mValue);
                    mValueWaiting = false;
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                    "Access to program field '" + mField.getName() + "' was denied!");
            } catch (ClassCastException e) {
                throw new RuntimeException("Non Program field '" + mField.getName()
                    + "' annotated with ProgramParameter!");
            }
        }
    }
}
