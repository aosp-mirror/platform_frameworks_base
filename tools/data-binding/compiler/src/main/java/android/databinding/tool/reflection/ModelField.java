/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.databinding.tool.reflection;

public abstract class ModelField {

    /**
     * @return Whether this field has been annotated with Bindable.
     */
    public abstract boolean isBindable();

    /**
     * @return The field name.
     */
    public abstract String getName();

    /**
     * @return true if this field is marked public.
     */
    public abstract boolean isPublic();

    /**
     * @return true if this is a static field.
     */
    public abstract boolean isStatic();

    /**
     * @return true if the field was declared final.
     */
    public abstract boolean isFinal();

    /**
     * @return The declared type of the field variable.
     */
    public abstract ModelClass getFieldType();
}
