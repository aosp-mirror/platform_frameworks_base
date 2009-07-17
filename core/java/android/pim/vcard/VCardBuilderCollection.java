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

public class VCardBuilderCollection implements VCardBuilder {

    private final Collection<VCardBuilder> mVCardBuilderCollection;
    
    public VCardBuilderCollection(Collection<VCardBuilder> vBuilderCollection) {
        mVCardBuilderCollection = vBuilderCollection; 
    }
    
    public Collection<VCardBuilder> getVCardBuilderBaseCollection() {
        return mVCardBuilderCollection;
    }
    
    public void start() {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.start();
        }
    }
    
    public void end() {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.end();
        }
    }

    public void startRecord(String type) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.startRecord(type);
        }
    }
    
    public void endRecord() {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.endRecord();
        }
    }

    public void startProperty() {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.startProperty();
        }
    }

    
    public void endProperty() {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.endProperty();
        }
    }

    public void propertyGroup(String group) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.propertyGroup(group);
        }
    }

    public void propertyName(String name) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.propertyName(name);
        }
    }

    public void propertyParamType(String type) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.propertyParamType(type);
        }
    }

    public void propertyParamValue(String value) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.propertyParamValue(value);
        }
    }

    public void propertyValues(List<String> values) {
        for (VCardBuilder builder : mVCardBuilderCollection) {
            builder.propertyValues(values);
        }
    }
}
