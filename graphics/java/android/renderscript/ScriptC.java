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

import java.io.IOException;
import java.io.InputStream;

import android.content.res.Resources;


/**
 * @hide
 **/
public class ScriptC extends Script {
    ScriptC(int id, RenderScript rs) {
        super(id, rs);
    }




    public static class Builder extends Script.Builder {
        byte[] mProgram;
        int mProgramLength;

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

        public void  setScript(InputStream is) throws IOException {
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

            b.mRS.nScriptCSetScript(b.mProgram, 0, b.mProgramLength);


            int id = b.mRS.nScriptCCreate();
            ScriptC obj = new ScriptC(id, b.mRS);
            b.transferObject(obj);
            return obj;
        }

        public ScriptC create() {
            return internalCreate(this);
        }
    }

}

