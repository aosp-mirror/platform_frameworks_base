/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.api;

/**
 * Represents an android resource with a name and a string value.
 */
public interface IResourceValue {
    
    /**
     * Returns the type of the resource. For instance "drawable", "color", etc...
     */
    String getType();

    /**
     * Returns the name of the resource, as defined in the XML.
     */
    String getName();

    /**
     * Returns the value of the resource, as defined in the XML. This can be <code>null</code>
     */
    String getValue();
    
    /**
     * Returns whether the resource is a framework resource (<code>true</code>) or a project
     * resource (<code>false</false>).
     */
    boolean isFramework();
}
