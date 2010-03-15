/*
 * Copyright (C) 2006 The Android Open Source Project
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

import java.util.HashMap;

public class JType {

    String baseType;
    boolean isArray;
    boolean isClass;
    boolean isString;

    static HashMap<CType,JType> typeMapping = new HashMap<CType,JType>();
    static HashMap<CType,JType> arrayTypeMapping = new HashMap<CType,JType>();

    static {
    // Primitive types
    typeMapping.put(new CType("GLbitfield"), new JType("int"));
    typeMapping.put(new CType("GLboolean"), new JType("boolean"));
    typeMapping.put(new CType("GLclampf"), new JType("float"));
    typeMapping.put(new CType("GLclampx"), new JType("int"));
    typeMapping.put(new CType("GLenum"), new JType("int"));
    typeMapping.put(new CType("GLfloat"), new JType("float"));
    typeMapping.put(new CType("GLfixed"), new JType("int"));
    typeMapping.put(new CType("GLint"), new JType("int"));
    typeMapping.put(new CType("GLintptr"), new JType("int"));
    typeMapping.put(new CType("GLshort"), new JType("short"));
    typeMapping.put(new CType("GLsizei"), new JType("int"));
    typeMapping.put(new CType("GLsizeiptr"), new JType("int"));
    typeMapping.put(new CType("GLubyte"), new JType("byte"));
    typeMapping.put(new CType("GLuint"), new JType("int"));
    typeMapping.put(new CType("void"), new JType("void"));
    typeMapping.put(new CType("GLubyte", true, true), new JType("String", false, false));
    typeMapping.put(new CType("char", false, true), new JType("byte"));
    typeMapping.put(new CType("char", true, true), new JType("String", false, false));
    typeMapping.put(new CType("int"), new JType("int"));

    // Untyped pointers map to untyped Buffers
    typeMapping.put(new CType("GLvoid", true, true),
            new JType("java.nio.Buffer", true, false));
    typeMapping.put(new CType("GLvoid", false, true),
            new JType("java.nio.Buffer", true, false));
    typeMapping.put(new CType("void", false, true),
            new JType("java.nio.Buffer", true, false));
    typeMapping.put(new CType("GLeglImageOES", false, false),
            new JType("java.nio.Buffer", true, false));

    // Typed pointers map to typed Buffers
    typeMapping.put(new CType("GLboolean", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLenum", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLfixed", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLfixed", true, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLfloat", false, true),
            new JType("java.nio.FloatBuffer", true, false));
    typeMapping.put(new CType("GLfloat", true, true),
            new JType("java.nio.FloatBuffer", true, false));
    typeMapping.put(new CType("GLint", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLint", true, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLsizei", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLuint", false, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLuint", true, true),
            new JType("java.nio.IntBuffer", true, false));
    typeMapping.put(new CType("GLshort", true, true),
            new JType("java.nio.ShortBuffer", true, false));

    // Typed pointers map to arrays + offsets
    arrayTypeMapping.put(new CType("char", false, true),
            new JType("byte", false, true));
    arrayTypeMapping.put(new CType("GLboolean", false, true),
                 new JType("boolean", false, true));
    arrayTypeMapping.put(new CType("GLenum", false, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLfixed", true, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLfixed", false, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLfloat", false, true), new JType("float", false, true));
    arrayTypeMapping.put(new CType("GLfloat", true, true), new JType("float", false, true));
    arrayTypeMapping.put(new CType("GLint", false, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLint", true, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLshort", true, true), new JType("short", false, true));
    arrayTypeMapping.put(new CType("GLsizei", false, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLsizei", true, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLuint", false, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLuint", true, true), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLintptr"), new JType("int", false, true));
    arrayTypeMapping.put(new CType("GLsizeiptr"), new JType("int", false, true));
    }

    public JType() {
    }

    public JType(String primitiveTypeName) {
    this.baseType = primitiveTypeName;
    this.isClass = false;
    this.isArray = false;
    }

    public JType(String primitiveTypeName, boolean isClass, boolean isArray) {
    this.baseType = primitiveTypeName;
    this.isClass = isClass;
    this.isArray = isArray;
    }

    public String getBaseType() {
    return baseType;
    }

    @Override
    public String toString() {
    return baseType + (isArray ? "[]" : "");
    }

    public boolean isArray() {
    return isArray;
    }

    public boolean isClass() {
    return isClass;
    }

    public boolean isString() {
        return baseType.equals("String");
    }

    public boolean isPrimitive() {
    return !isClass() && !isArray();
    }

    public boolean isVoid() {
    return baseType.equals("void");
    }

    public boolean isBuffer() {
    return baseType.indexOf("Buffer") != -1;
    }

    public boolean isTypedBuffer() {
    return !baseType.equals("java.nio.Buffer") &&
        (baseType.indexOf("Buffer") != -1);
    }

    public static JType convert(CType ctype, boolean useArray) {
     JType javaType = null;
     if (useArray) {
         javaType = arrayTypeMapping.get(ctype);
     }
     if (javaType == null) {
         javaType = typeMapping.get(ctype);
     }
     if (javaType == null) {
         throw new RuntimeException("Unsupported C type: " + ctype);
     }
     return javaType;
    }
}
