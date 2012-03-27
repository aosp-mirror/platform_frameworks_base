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
public class FinalPort extends FieldPort {

    public FinalPort(Filter filter, String name, Field field, boolean hasDefault) {
        super(filter, name, field, hasDefault);
    }

    @Override
    protected synchronized void setFieldFrame(Frame frame, boolean isAssignment) {
        assertPortIsOpen();
        checkFrameType(frame, isAssignment);
        if (mFilter.getStatus() != Filter.STATUS_PREINIT) {
            throw new RuntimeException("Attempting to modify " + this + "!");
        } else {
            super.setFieldFrame(frame, isAssignment);
            super.transfer(null);
        }
    }

    @Override
    public String toString() {
        return "final " + super.toString();
    }

}
