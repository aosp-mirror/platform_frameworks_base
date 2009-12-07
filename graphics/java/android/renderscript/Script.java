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
    public static final int MAX_SLOT = 16;

    boolean mIsRoot;
    Type[] mTypes;
    boolean[] mWritable;
    Invokable[] mInvokables;

    public static class Invokable {
        RenderScript mRS;
        Script mScript;
        int mSlot;
        String mName;

        Invokable() {
            mSlot = -1;
        }

        public void execute() {
            mRS.nScriptInvoke(mScript.mID, mSlot);
        }
    }

    Script(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void bindAllocation(Allocation va, int slot) {
        mRS.validate();
        mRS.nScriptBindAllocation(mID, va.mID, slot);
    }

    public void setClearColor(float r, float g, float b, float a) {
        mRS.validate();
        mRS.nScriptSetClearColor(mID, r, g, b, a);
    }

    public void setClearDepth(float d) {
        mRS.validate();
        mRS.nScriptSetClearDepth(mID, d);
    }

    public void setClearStencil(int stencil) {
        mRS.validate();
        mRS.nScriptSetClearStencil(mID, stencil);
    }

    public void setTimeZone(String timeZone) {
        mRS.validate();
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
        String[] mNames;
        boolean[] mWritable;
        int mInvokableCount = 0;
        Invokable[] mInvokables;

        Builder(RenderScript rs) {
            mRS = rs;
            mTypes = new Type[MAX_SLOT];
            mNames = new String[MAX_SLOT];
            mWritable = new boolean[MAX_SLOT];
            mInvokables = new Invokable[MAX_SLOT];
        }

        public void setType(Type t, int slot) {
            mTypes[slot] = t;
            mNames[slot] = null;
        }

        public void setType(Type t, String name, int slot) {
            mTypes[slot] = t;
            mNames[slot] = name;
        }

        public Invokable addInvokable(String func) {
            Invokable i = new Invokable();
            i.mName = func;
            i.mRS = mRS;
            i.mSlot = mInvokableCount;
            mInvokables[mInvokableCount++] = i;
            return i;
        }

        public void setType(boolean writable, int slot) {
            mWritable[slot] = writable;
        }

        void transferCreate() {
            mRS.nScriptSetRoot(mIsRoot);
            for(int ct=0; ct < mTypes.length; ct++) {
                if(mTypes[ct] != null) {
                    mRS.nScriptSetType(mTypes[ct].mID, mWritable[ct], mNames[ct], ct);
                }
            }
            for(int ct=0; ct < mInvokableCount; ct++) {
                mRS.nScriptSetInvokable(mInvokables[ct].mName, ct);
            }
        }

        void transferObject(Script s) {
            s.mIsRoot = mIsRoot;
            s.mTypes = mTypes;
            s.mInvokables = new Invokable[mInvokableCount];
            for(int ct=0; ct < mInvokableCount; ct++) {
                s.mInvokables[ct] = mInvokables[ct];
                s.mInvokables[ct].mScript = s;
            }
            s.mInvokables = null;
        }

        public void setRoot(boolean r) {
            mIsRoot = r;
        }

    }

}

