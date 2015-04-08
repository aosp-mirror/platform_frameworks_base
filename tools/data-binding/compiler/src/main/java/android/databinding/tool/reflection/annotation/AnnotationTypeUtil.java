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

import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.TypeUtil;

import java.util.List;

import javax.lang.model.type.ArrayType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

public class AnnotationTypeUtil extends TypeUtil {
    javax.lang.model.util.Types mTypes;

    public AnnotationTypeUtil(
            AnnotationAnalyzer annotationAnalyzer) {
        mTypes = annotationAnalyzer.getTypeUtils();
    }

    @Override
    public String getDescription(ModelClass modelClass) {
        // TODO use interface
        return modelClass.getCanonicalName().replace('.', '/');
    }

    @Override
    public String getDescription(ModelMethod modelMethod) {
        // TODO use interface
        return modelMethod.getName() + getDescription(
                ((AnnotationMethod) modelMethod).mExecutableElement.asType());
    }

    private String getDescription(TypeMirror typeMirror) {
        if (typeMirror == null) {
            throw new UnsupportedOperationException();
        }
        switch (typeMirror.getKind()) {
            case BOOLEAN:
                return BOOLEAN;
            case BYTE:
                return BYTE;
            case SHORT:
                return SHORT;
            case INT:
                return INT;
            case LONG:
                return LONG;
            case CHAR:
                return CHAR;
            case FLOAT:
                return FLOAT;
            case DOUBLE:
                return DOUBLE;
            case DECLARED:
                return CLASS_PREFIX + mTypes.erasure(typeMirror).toString().replace('.', '/') + CLASS_SUFFIX;
            case VOID:
                return VOID;
            case ARRAY:
                final ArrayType arrayType = (ArrayType) typeMirror;
                final String componentType = getDescription(arrayType.getComponentType());
                return ARRAY + componentType;
            case TYPEVAR:
                final TypeVariable typeVariable = (TypeVariable) typeMirror;
                final String name = typeVariable.toString();
                return CLASS_PREFIX + name.replace('.', '/') + CLASS_SUFFIX;
            case EXECUTABLE:
                final ExecutableType executableType = (ExecutableType) typeMirror;
                final int argStart = mTypes.erasure(executableType).toString().indexOf('(');
                final String methodName = executableType.toString().substring(0, argStart);
                final String args = joinArgs(executableType.getParameterTypes());
                // TODO detect constructor?
                return methodName + "(" + args + ")" + getDescription(
                        executableType.getReturnType());
            default:
                throw new UnsupportedOperationException("cannot understand type "
                        + typeMirror.toString() + ", kind:" + typeMirror.getKind().name());
        }
    }

    private String joinArgs(List<? extends TypeMirror> mirrorList) {
        StringBuilder result = new StringBuilder();
        for (TypeMirror mirror : mirrorList) {
            result.append(getDescription(mirror));
        }
        return result.toString();
    }
}
