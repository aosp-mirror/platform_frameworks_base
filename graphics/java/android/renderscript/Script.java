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

/**
 * @hide
 **/
public class Script extends BaseObj {
    boolean mIsRoot;
    Type[] mTypes;

    Script(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void destroy() {
        if(mDestroyed) {
            throw new IllegalStateException("Object already destroyed.");
        }
        mDestroyed = true;
        mRS.nScriptDestroy(mID);
    }

    public void bindAllocation(Allocation va, int slot) {
        mRS.nScriptBindAllocation(mID, va.mID, slot);
    }

    public void setClearColor(float r, float g, float b, float a) {
        mRS.nScriptSetClearColor(mID, r, g, b, a);
    }

    public void setClearDepth(float d) {
        mRS.nScriptSetClearDepth(mID, d);
    }

    public void setClearStencil(int stencil) {
        mRS.nScriptSetClearStencil(mID, stencil);
    }

    public void setTimeZone(String timeZone) {
        try {
            mRS.nScriptSetTimeZone(mID, timeZone.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Builder {
        RenderScript mRS;
        boolean mIsRoot = false;
        Type[] mTypes;
        int mTypeCount;

        Builder(RenderScript rs) {
            mRS = rs;
            mTypes = new Type[4];
            mTypeCount = 0;
        }

        public void addType(Type t) {
            if(mTypeCount >= mTypes.length) {
                Type[] nt = new Type[mTypeCount * 2];
                for(int ct=0; ct < mTypeCount; ct++) {
                    nt[ct] = mTypes[ct];
                }
                mTypes = nt;
            }
            mTypes[mTypeCount] = t;
            mTypeCount++;
        }

        void transferCreate() {
            mRS.nScriptCSetRoot(mIsRoot);
            for(int ct=0; ct < mTypeCount; ct++) {
                mRS.nScriptCAddType(mTypes[ct].mID);
            }
        }

        void transferObject(Script s) {
            s.mIsRoot = mIsRoot;
            s.mTypes = new Type[mTypeCount];
            for(int ct=0; ct < mTypeCount; ct++) {
                s.mTypes[ct] = mTypes[ct];
            }
        }

        public void setRoot(boolean r) {
            mIsRoot = r;
        }

    }

}

