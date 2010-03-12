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

public class CType {

    String baseType;
    boolean isConst;
    boolean isPointer;

    public CType() {
    }

    public CType(String baseType) {
    setBaseType(baseType);
    }

    public CType(String baseType, boolean isConst, boolean isPointer) {
    setBaseType(baseType);
    setIsConst(isConst);
    setIsPointer(isPointer);
    }

    public String getDeclaration() {
    return baseType + (isPointer ? " *" : "");
    }

    public void setIsConst(boolean isConst) {
    this.isConst = isConst;
    }

    public boolean isConst() {
    return isConst;
    }

    public void setIsPointer(boolean isPointer) {
    this.isPointer = isPointer;
    }

    public boolean isPointer() {
    return isPointer;
    }

    boolean isVoid() {
    String baseType = getBaseType();
    return baseType.equals("GLvoid") ||
        baseType.equals("void");
    }

    public boolean isConstCharPointer() {
        return isConst && isPointer && baseType.equals("char");
    }

    public boolean isTypedPointer() {
    return isPointer() && !isVoid() && !isConstCharPointer();
    }

    public void setBaseType(String baseType) {
    this.baseType = baseType;
    }

    public String getBaseType() {
    return baseType;
    }

    @Override
    public String toString() {
    String s = "";
    if (isConst()) {
        s += "const ";
    }
    s += baseType;
    if (isPointer()) {
        s += "*";
    }

    return s;
    }

    @Override
    public int hashCode() {
    return baseType.hashCode() ^ (isPointer ? 2 : 0) ^ (isConst ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
    if (o != null && o instanceof CType) {
        CType c = (CType)o;
        return baseType.equals(c.baseType) &&
        isPointer() == c.isPointer() &&
        isConst() == c.isConst();
    }
    return false;
    }
}
