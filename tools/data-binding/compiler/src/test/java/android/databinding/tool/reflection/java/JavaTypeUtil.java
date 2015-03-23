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

import android.databinding.tool.reflection.ModelClass;
import android.databinding.tool.reflection.ModelMethod;
import android.databinding.tool.reflection.TypeUtil;
import android.databinding.tool.util.L;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

public class JavaTypeUtil extends TypeUtil {

    @Override
    public String getDescription(ModelClass modelClass) {
        return modelClass.getCanonicalName().replace('.', '/');
    }

    @Override
    public String getDescription(ModelMethod modelMethod) {
        Method method = ((JavaMethod) modelMethod).mMethod;
        StringBuilder sb  = new StringBuilder();
        sb.append(method.getName());
        sb.append("(");
        for (Class param : method.getParameterTypes()) {
            sb.append(getDescription(param));
        }
        sb.append(")");
        sb.append(getDescription(method.getReturnType()));
        return sb.toString();
    }

    private String getDescription(Class klass) {
        if (klass == null) {
            throw new UnsupportedOperationException();
        }
        if (boolean.class.equals(klass)) {
            return BOOLEAN;
        }
        if (byte.class.equals(klass)) {
            return BYTE;
        }
        if (short.class.equals(klass)) {
            return SHORT;
        }
        if (int.class.equals(klass)) {
            return INT;
        }
        if (long.class.equals(klass)) {
            return LONG;
        }
        if (char.class.equals(klass)) {
            return CHAR;
        }
        if (float.class.equals(klass)) {
            return FLOAT;
        }
        if (double.class.equals(klass)) {
            return DOUBLE;
        }
        if (void.class.equals(klass)) {
            return VOID;
        }
        if (Object.class.isAssignableFrom(klass)) {
            return CLASS_PREFIX + klass.getCanonicalName().replace('.', '/') + CLASS_SUFFIX;
        }
        if (Array.class.isAssignableFrom(klass)) {
            return ARRAY + getDescription(klass.getComponentType());
        }

        UnsupportedOperationException ex
                = new UnsupportedOperationException("cannot understand type "
                + klass.toString() + ", kind:");
        L.e(ex, "cannot create JNI type for %s", klass.getCanonicalName());
        throw ex;
    }
}
