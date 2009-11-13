/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.renderscript;

import android.content.res.Resources;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map.Entry;
import java.util.HashMap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * @hide
 **/
public class ScriptC extends Script {
    private static final String TAG = "ScriptC";

    ScriptC(int id, RenderScript rs) {
        super(id, rs);
    }

    public static class Builder extends Script.Builder {
        byte[] mProgram;
        int mProgramLength;
        HashMap<String,Integer> mIntDefines = new HashMap();
        HashMap<String,Float> mFloatDefines = new HashMap();

        public Builder(RenderScript rs) {
            super(rs);
        }

        public void setScript(String s) {
            try {
                mProgram = s.getBytes("UTF-8");
                mProgramLength = mProgram.length;
            } catch (java.io.UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }

        public void setScript(Resources resources, int id) {
            InputStream is = resources.openRawResource(id);
            try {
                try {
                    setScript(is);
                } finally {
                    is.close();
                }
            } catch(IOException e) {
                throw new Resources.NotFoundException();
            }
        }

        public void setScript(InputStream is) throws IOException {
            byte[] buf = new byte[1024];
            int currentPos = 0;
            while(true) {
                int bytesLeft = buf.length - currentPos;
                if (bytesLeft == 0) {
                    byte[] buf2 = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, buf2, 0, buf.length);
                    buf = buf2;
                    bytesLeft = buf.length - currentPos;
                }
                int bytesRead = is.read(buf, currentPos, bytesLeft);
                if (bytesRead <= 0) {
                    break;
                }
                currentPos += bytesRead;
            }
            mProgram = buf;
            mProgramLength = currentPos;
        }

        static synchronized ScriptC internalCreate(Builder b) {
            b.mRS.nScriptCBegin();
            b.transferCreate();

            for (Entry<String,Integer> e: b.mIntDefines.entrySet()) {
                b.mRS.nScriptCAddDefineI32(e.getKey(), e.getValue().intValue());
            }
            for (Entry<String,Float> e: b.mFloatDefines.entrySet()) {
                b.mRS.nScriptCAddDefineF(e.getKey(), e.getValue().floatValue());
            }

            b.mRS.nScriptCSetScript(b.mProgram, 0, b.mProgramLength);

            int id = b.mRS.nScriptCCreate();
            ScriptC obj = new ScriptC(id, b.mRS);
            b.transferObject(obj);

            return obj;
        }

        public void addDefine(String name, int value) {
            mIntDefines.put(name, value);
        }

        public void addDefine(String name, float value) {
            mFloatDefines.put(name, value);
        }

        /**
         * Takes the all public static final fields for a class, and adds defines
         * for them, using the name of the field as the name of the define.
         */
        public void addDefines(Class cl) {
            addDefines(cl.getFields(), (Modifier.STATIC | Modifier.FINAL | Modifier.PUBLIC), null);
        }

        /**
         * Takes the all public fields for an object, and adds defines
         * for them, using the name of the field as the name of the define.
         */
        public void addDefines(Object o) {
            addDefines(o.getClass().getFields(), Modifier.PUBLIC, o);
        }

        void addDefines(Field[] fields, int mask, Object o) {
            for (Field f: fields) {
                try {
                    if ((f.getModifiers() & mask) == mask) {
                        Class t = f.getType();
                        if (t == int.class) {
                            mIntDefines.put(f.getName(), f.getInt(o));
                        }
                        else if (t == float.class) {
                            mFloatDefines.put(f.getName(), f.getFloat(o));
                        }
                    }
                } catch (IllegalAccessException ex) {
                    // TODO: Do we want this log?
                    Log.d(TAG, "addDefines skipping field " + f.getName());
                }
            }
        }

        public ScriptC create() {
            return internalCreate(this);
        }
    }
}

