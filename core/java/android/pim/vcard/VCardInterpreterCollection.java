/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import java.util.Collection;
import java.util.List;

/**
 * The {@link VCardInterpreter} implementation which aggregates more than one
 * {@link VCardInterpreter} objects and make a user object treat them as one
 * {@link VCardInterpreter} object.
 */
public class VCardInterpreterCollection implements VCardInterpreter {
    private final Collection<VCardInterpreter> mInterpreterCollection;
    
    public VCardInterpreterCollection(Collection<VCardInterpreter> interpreterCollection) {
        mInterpreterCollection = interpreterCollection;
    }

    public Collection<VCardInterpreter> getCollection() {
        return mInterpreterCollection;
    }

    public void start() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.start();
        }
    }

    public void end() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.end();
        }
    }

    public void startEntry() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.startEntry();
        }
    }

    public void endEntry() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.endEntry();
        }
    }

    public void startProperty() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.startProperty();
        }
    }

    public void endProperty() {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.endProperty();
        }
    }

    public void propertyGroup(String group) {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.propertyGroup(group);
        }
    }

    public void propertyName(String name) {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.propertyName(name);
        }
    }

    public void propertyParamType(String type) {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.propertyParamType(type);
        }
    }

    public void propertyParamValue(String value) {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.propertyParamValue(value);
        }
    }

    public void propertyValues(List<String> values) {
        for (VCardInterpreter builder : mInterpreterCollection) {
            builder.propertyValues(values);
        }
    }
}
