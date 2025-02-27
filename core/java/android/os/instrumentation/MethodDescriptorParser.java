/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.os.instrumentation;

import android.annotation.NonNull;

import java.lang.reflect.Method;

/**
 * A utility class for dynamic instrumentation / uprobestats.
 *
 * @hide
 */
public final class MethodDescriptorParser {

    /**
     * Parses a {@link MethodDescriptor} (in string representation) into a {@link Method}.
     */
    public static Method parseMethodDescriptor(ClassLoader classLoader,
            @NonNull MethodDescriptor descriptor) {
        try {
            Class<?> javaClass = classLoader.loadClass(descriptor.fullyQualifiedClassName);
            Class<?>[] parameters = new Class[descriptor.fullyQualifiedParameters.length];
            for (int i = 0; i < descriptor.fullyQualifiedParameters.length; i++) {
                String typeName = descriptor.fullyQualifiedParameters[i];
                boolean isArrayType = typeName.endsWith("[]");
                if (isArrayType) {
                    typeName = typeName.substring(0, typeName.length() - 2);
                }
                switch (typeName) {
                    case "boolean":
                        parameters[i] = isArrayType ? boolean.class.arrayType() : boolean.class;
                        break;
                    case "byte":
                        parameters[i] = isArrayType ? byte.class.arrayType() : byte.class;
                        break;
                    case "char":
                        parameters[i] = isArrayType ? char.class.arrayType() : char.class;
                        break;
                    case "short":
                        parameters[i] = isArrayType ? short.class.arrayType() : short.class;
                        break;
                    case "int":
                        parameters[i] = isArrayType ? int.class.arrayType() : int.class;
                        break;
                    case "long":
                        parameters[i] = isArrayType ? long.class.arrayType() : long.class;
                        break;
                    case "float":
                        parameters[i] = isArrayType ? float.class.arrayType() : float.class;
                        break;
                    case "double":
                        parameters[i] = isArrayType ? double.class.arrayType() : double.class;
                        break;
                    default:
                        parameters[i] = isArrayType ? classLoader.loadClass(typeName).arrayType()
                                : classLoader.loadClass(typeName);
                }
            }

            return javaClass.getDeclaredMethod(descriptor.methodName, parameters);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "The specified method cannot be found. Is this descriptor valid? "
                            + descriptor, e);
        }
    }
}
