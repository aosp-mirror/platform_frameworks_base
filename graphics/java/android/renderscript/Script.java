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

    protected void invoke(int slot) {
        mRS.nScriptInvoke(mID, slot);
    }

    protected void invoke(int slot, FieldPacker v) {
        if (v != null) {
            mRS.nScriptInvokeV(mID, slot, v.getData());
        } else {
            mRS.nScriptInvoke(mID, slot);
        }
    }


    Script(int id, RenderScript rs) {
        super(id, rs);
    }

    public void bindAllocation(Allocation va, int slot) {
        mRS.validate();
        if (va != null) {
            mRS.nScriptBindAllocation(mID, va.mID, slot);
        } else {
            mRS.nScriptBindAllocation(mID, 0, slot);
        }
    }

    public void setVar(int index, float v) {
        mRS.nScriptSetVarF(mID, index, v);
    }

    public void setVar(int index, int v) {
        mRS.nScriptSetVarI(mID, index, v);
    }

    public void setVar(int index, boolean v) {
        mRS.nScriptSetVarI(mID, index, v ? 1 : 0);
    }

    public void setVar(int index, FieldPacker v) {
        mRS.nScriptSetVarV(mID, index, v.getData());
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

        Builder(RenderScript rs) {
            mRS = rs;
        }
    }


    public static class FieldBase {
        protected Element mElement;
        protected Type mType;
        protected Allocation mAllocation;

        protected void init(RenderScript rs, int dimx) {
            mAllocation = Allocation.createSized(rs, mElement, dimx);
            mType = mAllocation.getType();
        }

        protected FieldBase() {
        }

        public Element getElement() {
            return mElement;
        }

        public Type getType() {
            return mType;
        }

        public Allocation getAllocation() {
            return mAllocation;
        }

        //@Override
        public void updateAllocation() {
        }


        //
        /*
        public class ScriptField_UserField
            extends android.renderscript.Script.FieldBase {

            protected

        }

        */

    }
}

