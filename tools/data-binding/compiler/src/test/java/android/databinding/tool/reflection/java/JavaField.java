/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.reflection.java;

import android.databinding.Bindable;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelField;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public class JavaField extends ModelField {
    public final Field mField;

    public JavaField(Field field) {
        mField = field;
    }

    @Override
    public boolean isBindable() {
        return mField.getAnnotation(Bindable.class) != null;
    }

    @Override
    public String getName() {
        return mField.getName();
    }

    @Override
    public boolean isPublic() {
        return Modifier.isPublic(mField.getModifiers());
    }

    @Override
    public boolean isStatic() {
        return Modifier.isStatic(mField.getModifiers());
    }

    @Override
    public boolean isFinal() {
        return Modifier.isFinal(mField.getModifiers());
    }

    @Override
    public ModelClass getFieldType() {
        return new JavaClass(mField.getType());
    }
}
