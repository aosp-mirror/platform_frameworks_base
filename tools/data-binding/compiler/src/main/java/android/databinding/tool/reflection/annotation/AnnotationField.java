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
package android.databinding.tool.reflection.annotation;

import android.databinding.Bindable;
import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelField;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

class AnnotationField extends ModelField {

    final VariableElement mField;

    final TypeElement mDeclaredClass;

    public AnnotationField(TypeElement declaredClass, VariableElement field) {
        mDeclaredClass = declaredClass;
        mField = field;
    }

    @Override
    public String toString() {
        return mField.toString();
    }

    @Override
    public boolean isBindable() {
        return mField.getAnnotation(Bindable.class) != null;
    }

    @Override
    public String getName() {
        return mField.getSimpleName().toString();
    }

    @Override
    public boolean isPublic() {
        return mField.getModifiers().contains(Modifier.PUBLIC);
    }

    @Override
    public boolean isStatic() {
        return mField.getModifiers().contains(Modifier.STATIC);
    }

    @Override
    public boolean isFinal() {
        return mField.getModifiers().contains(Modifier.FINAL);
    }

    @Override
    public ModelClass getFieldType() {
        return new AnnotationClass(mField.asType());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof AnnotationField) {
            AnnotationField that = (AnnotationField) obj;
            return mDeclaredClass.equals(that.mDeclaredClass) && AnnotationAnalyzer.get()
                    .getTypeUtils().isSameType(mField.asType(), that.mField.asType());
        } else {
            return false;
        }
    }
}
