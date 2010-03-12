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

import java.util.ArrayList;
import java.util.List;

public class JFunc {

    String className = "com.google.android.gles_jni.GL11Impl";

    CFunc cfunc;
    JType ftype;
    String fname;

    List<String> argNames = new ArrayList<String>();
    List<JType> argTypes = new ArrayList<JType>();
    List<Integer> argCIndices = new ArrayList<Integer>();

    boolean hasBufferArg = false;
    boolean hasTypedBufferArg = false;
    ArrayList<String> bufferArgNames = new ArrayList<String>();

    public JFunc(CFunc cfunc) {
        this.cfunc = cfunc;
    }

    public CFunc getCFunc() {
        return cfunc;
    }

    public void setName(String fname) {
        this.fname = fname;
    }

    public String getName() {
        return fname;
    }

    public void setType(JType ftype) {
        this.ftype = ftype;
    }

    public JType getType() {
        return ftype;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getClassName() {
        return className;
    }

    public boolean hasBufferArg() {
        return hasBufferArg;
    }

    public boolean hasTypedBufferArg() {
        return hasTypedBufferArg;
    }

    public String getBufferArgName(int index) {
        return bufferArgNames.get(index);
    }

    public void addArgument(String argName, JType argType, int cindex) {
        argNames.add(argName);
        argTypes.add(argType);
        argCIndices.add(new Integer(cindex));

        if (argType.isBuffer()) {
            hasBufferArg = true;
            bufferArgNames.add(argName);
        }
        if (argType.isTypedBuffer()) {
            hasTypedBufferArg = true;
            bufferArgNames.add(argName);
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

    public JType getArgType(int index) {
        return argTypes.get(index);
    }

    public int getArgCIndex(int index) {
        return argCIndices.get(index).intValue();
    }

    public static JFunc convert(CFunc cfunc, boolean useArray) {
        try {
            JFunc jfunc = new JFunc(cfunc);
            jfunc.setName(cfunc.getName());
            jfunc.setType(JType.convert(cfunc.getType(), false));

            int numArgs = cfunc.getNumArgs();
            int numOffsets = 0;
            for (int i = 0; i < numArgs; i++) {
                CType cArgType = cfunc.getArgType(i);
                if (cArgType.isTypedPointer() && useArray) {
                    ++numOffsets;
                }
            }

            for (int i = 0; i < numArgs; i++) {
                String cArgName = cfunc.getArgName(i);
                CType cArgType = cfunc.getArgType(i);

                jfunc.addArgument(cArgName, JType.convert(cArgType, useArray), i);
                if (cArgType.isTypedPointer() && useArray) {
                    if (numOffsets > 1) {
                        jfunc.addArgument(cArgName + "Offset", new JType("int"), i);
                    } else {
                        jfunc.addArgument("offset", new JType("int"), i);
                    }
                }
            }

            return jfunc;
        } catch (RuntimeException e) {
            System.err.println("Failed to convert function " + cfunc);
            throw e;
        }
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

}
