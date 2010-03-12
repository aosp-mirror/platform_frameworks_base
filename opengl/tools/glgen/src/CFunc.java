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

import java.util.*;

public class CFunc {

    String original;

    CType ftype;
    String fname;

    List<String> argNames = new ArrayList<String>();
    List<CType> argTypes = new ArrayList<CType>();

    boolean hasPointerArg = false;
    boolean hasTypedPointerArg = false;

    public CFunc(String original) {
        this.original = original;
    }

    public String getOriginal() {
        return original;
    }

    public void setName(String fname) {
        this.fname = fname;
    }

    public String getName() {
        return fname;
    }

    public void setType(CType ftype) {
        this.ftype = ftype;
    }

    public CType getType() {
        return ftype;
    }

    public void addArgument(String argName, CType argType) {
        argNames.add(argName);
        argTypes.add(argType);

        if (argType.isPointer()) {
            hasPointerArg = true;
        }
        if (argType.isTypedPointer()) {
            hasTypedPointerArg = true;
        }
    }

    public int getNumArgs() {
        return argNames.size();
    }

    public int getArgIndex(String name) {
        int len = argNames.size();
        for (int i = 0; i < len; i++) {
            if (name.equals(argNames.get(i))) {
                return i;
            }
        }
        return -1;
    }

    public String getArgName(int index) {
        return argNames.get(index);
    }

    public CType getArgType(int index) {
        return argTypes.get(index);
    }

    public boolean hasPointerArg() {
        return hasPointerArg;
    }

    public boolean hasTypedPointerArg() {
        return hasTypedPointerArg;
    }

    @Override
    public String toString() {
        String s =  "Function " + fname + " returns " + ftype + ": ";
        for (int i = 0; i < argNames.size(); i++) {
            if (i > 0) {
                s += ", ";
            }
            s += argTypes.get(i) + " " + argNames.get(i);
        }
        return s;
    }

    public static CFunc parseCFunc(String s) {
        CFunc cfunc = new CFunc(s);
        String[] tokens = s.split("\\s");

        int i = 0;
        CType ftype = new CType();
        String ftypeName = tokens[i++];
        if (ftypeName.equals("const")) {
            ftype.setIsConst(true);
            ftypeName = tokens[i++];
        }
        ftype.setBaseType(ftypeName);

        String fname = tokens[i++];
        if (fname.equals("*")) {
            ftype.setIsPointer(true);
            fname = tokens[i++];
        }

        cfunc.setName(fname);
        cfunc.setType(ftype);

        while (i < tokens.length) {
            String tok = tokens[i++];

            if (tok.equals("(")) {
                continue;
            }
            if (tok.equals(")")) {
                break;
            }

            CType argType = new CType();

            String argTypeName = tok;
            String argName = "";

            if (argTypeName.equals("const")) {
                argType.setIsConst(true);
                argTypeName = tokens[i++];
            }
            argType.setBaseType(argTypeName);

            if (argTypeName.equals("void")) {
                break;
            }

            argName = tokens[i++];
            if (argName.startsWith("*")) {
                argType.setIsPointer(true);
                argName = argName.substring(1, argName.length());
            }
            if (argName.endsWith(",")) {
                argName = argName.substring(0, argName.length() - 1);
            }

            cfunc.addArgument(argName, argType);
        }

        return cfunc;
    }
}
