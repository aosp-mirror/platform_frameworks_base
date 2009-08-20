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


import android.util.Config;
import android.util.Log;


/**
 * @hide
 *
 **/
public class ProgramFragment extends BaseObj {
    public static final int MAX_SLOT = 2;

    public enum EnvMode {
        REPLACE (0),
        MODULATE (1),
        DECAL (2);

        int mID;
        EnvMode(int id) {
            mID = id;
        }
    }


    ProgramFragment(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void bindTexture(Allocation va, int slot)
        throws IllegalArgumentException {
        if((slot < 0) || (slot >= MAX_SLOT)) {
            throw new IllegalArgumentException("Slot ID out of range.");
        }

        mRS.nProgramFragmentBindTexture(mID, slot, va.mID);
    }

    public void bindSampler(Sampler vs, int slot)
        throws IllegalArgumentException {
        if((slot < 0) || (slot >= MAX_SLOT)) {
            throw new IllegalArgumentException("Slot ID out of range.");
        }

        mRS.nProgramFragmentBindSampler(mID, slot, vs.mID);
    }


    public static class Builder {
        RenderScript mRS;
        Element mIn;
        Element mOut;
        boolean mPointSpriteEnable;

        private class Slot {
            Type mType;
            EnvMode mEnv;
            boolean mTexEnable;

            Slot() {
                mTexEnable = false;
            }
        }
        Slot[] mSlots;

        public Builder(RenderScript rs, Element in, Element out) {
            mRS = rs;
            mIn = in;
            mOut = out;
            mSlots = new Slot[MAX_SLOT];
            mPointSpriteEnable = false;
            for(int ct=0; ct < MAX_SLOT; ct++) {
                mSlots[ct] = new Slot();
            }
        }

        public void setType(int slot, Type t)
            throws IllegalArgumentException {
            if((slot < 0) || (slot >= MAX_SLOT)) {
                throw new IllegalArgumentException("Slot ID out of range.");
            }

            mSlots[slot].mType = t;
        }

        public void setTexEnable(boolean enable, int slot)
            throws IllegalArgumentException {
            if((slot < 0) || (slot >= MAX_SLOT)) {
                throw new IllegalArgumentException("Slot ID out of range.");
            }

            mSlots[slot].mTexEnable = enable;
        }

        public void setTexEnvMode(EnvMode env, int slot)
            throws IllegalArgumentException {
            if((slot < 0) || (slot >= MAX_SLOT)) {
                throw new IllegalArgumentException("Slot ID out of range.");
            }

            mSlots[slot].mEnv = env;
        }

        public void setPointSpriteTexCoordinateReplacement(boolean enable) {
            mPointSpriteEnable = enable;
        }

        static synchronized ProgramFragment internalCreate(RenderScript rs, Builder b) {
            int inID = 0;
            int outID = 0;
            if (b.mIn != null) {
                inID = b.mIn.mID;
            }
            if (b.mOut != null) {
                outID = b.mOut.mID;
            }
            rs.nProgramFragmentBegin(inID, outID, b.mPointSpriteEnable);
            for(int ct=0; ct < MAX_SLOT; ct++) {
                if(b.mSlots[ct].mTexEnable) {
                    Slot s = b.mSlots[ct];
                    int typeID = 0;
                    if(s.mType != null) {
                        typeID = s.mType.mID;
                    }
                    rs.nProgramFragmentSetSlot(ct, true, s.mEnv.mID, typeID);
                }
            }

            int id = rs.nProgramFragmentCreate();
            return new ProgramFragment(id, rs);
        }

        public ProgramFragment create() {
            return internalCreate(mRS, this);
        }
    }
}



