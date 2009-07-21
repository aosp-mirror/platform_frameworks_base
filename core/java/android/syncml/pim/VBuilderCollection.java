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

package android.syncml.pim;

import java.util.Collection;
import java.util.List;

public class VBuilderCollection implements VBuilder {

    private final Collection<VBuilder> mVBuilderCollection;
    
    public VBuilderCollection(Collection<VBuilder> vBuilderCollection) {
        mVBuilderCollection = vBuilderCollection; 
    }
    
    public Collection<VBuilder> getVBuilderCollection() {
        return mVBuilderCollection;
    }
    
    public void start() {
        for (VBuilder builder : mVBuilderCollection) {
            builder.start();
        }
    }
    
    public void end() {
        for (VBuilder builder : mVBuilderCollection) {
            builder.end();
        }
    }

    public void startRecord(String type) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.startRecord(type);
        }
    }
    
    public void endRecord() {
        for (VBuilder builder : mVBuilderCollection) {
            builder.endRecord();
        }
    }

    public void startProperty() {
        for (VBuilder builder : mVBuilderCollection) {
            builder.startProperty();
        }
    }

    
    public void endProperty() {
        for (VBuilder builder : mVBuilderCollection) {
            builder.endProperty();
        }
    }

    public void propertyGroup(String group) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.propertyGroup(group);
        }
    }

    public void propertyName(String name) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.propertyName(name);
        }
    }

    public void propertyParamType(String type) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.propertyParamType(type);
        }
    }

    public void propertyParamValue(String value) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.propertyParamValue(value);
        }
    }

    public void propertyValues(List<String> values) {
        for (VBuilder builder : mVBuilderCollection) {
            builder.propertyValues(values);
        }
    }
}
