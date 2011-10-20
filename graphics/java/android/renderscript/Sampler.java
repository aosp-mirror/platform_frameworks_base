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
import android.os.Bundle;
import android.util.Log;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/**
 * Sampler object which defines how data is extracted from textures. Samplers
 * are attached to Program objects (currently only ProgramFragment) when those objects
 * need to access texture data.
 **/
public class Sampler extends BaseObj {
    public enum Value {
        NEAREST (0),
        LINEAR (1),
        LINEAR_MIP_LINEAR (2),
        LINEAR_MIP_NEAREST (5),
        WRAP (3),
        CLAMP (4);

        int mID;
        Value(int id) {
            mID = id;
        }
    }

    Value mMin;
    Value mMag;
    Value mWrapS;
    Value mWrapT;
    Value mWrapR;
    float mAniso;

    Sampler(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * @hide
     * @return minification setting for the sampler
     */
    public Value getMinification() {
        return mMin;
    }

    /**
     * @hide
     * @return magnification setting for the sampler
     */
    public Value getMagnification() {
        return mMag;
    }

    /**
     * @hide
     * @return S wrapping mode for the sampler
     */
    public Value getWrapS() {
        return mWrapS;
    }

    /**
     * @hide
     * @return T wrapping mode for the sampler
     */
    public Value getWrapT() {
        return mWrapT;
    }

    /**
     * @hide
     * @return anisotropy setting for the sampler
     */
    public float getAnisotropy() {
        return mAniso;
    }

    /**
     * Retrieve a sampler with min and mag set to nearest and wrap modes set to
     * clamp.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler CLAMP_NEAREST(RenderScript rs) {
        if(rs.mSampler_CLAMP_NEAREST == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.NEAREST);
            b.setMagnification(Value.NEAREST);
            b.setWrapS(Value.CLAMP);
            b.setWrapT(Value.CLAMP);
            rs.mSampler_CLAMP_NEAREST = b.create();
        }
        return rs.mSampler_CLAMP_NEAREST;
    }

    /**
     * Retrieve a sampler with min and mag set to linear and wrap modes set to
     * clamp.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler CLAMP_LINEAR(RenderScript rs) {
        if(rs.mSampler_CLAMP_LINEAR == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.LINEAR);
            b.setMagnification(Value.LINEAR);
            b.setWrapS(Value.CLAMP);
            b.setWrapT(Value.CLAMP);
            rs.mSampler_CLAMP_LINEAR = b.create();
        }
        return rs.mSampler_CLAMP_LINEAR;
    }

    /**
     * Retrieve a sampler with ag set to linear, min linear mipmap linear, and
     * to and wrap modes set to clamp.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler CLAMP_LINEAR_MIP_LINEAR(RenderScript rs) {
        if(rs.mSampler_CLAMP_LINEAR_MIP_LINEAR == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.LINEAR_MIP_LINEAR);
            b.setMagnification(Value.LINEAR);
            b.setWrapS(Value.CLAMP);
            b.setWrapT(Value.CLAMP);
            rs.mSampler_CLAMP_LINEAR_MIP_LINEAR = b.create();
        }
        return rs.mSampler_CLAMP_LINEAR_MIP_LINEAR;
    }

    /**
     * Retrieve a sampler with min and mag set to nearest and wrap modes set to
     * wrap.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler WRAP_NEAREST(RenderScript rs) {
        if(rs.mSampler_WRAP_NEAREST == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.NEAREST);
            b.setMagnification(Value.NEAREST);
            b.setWrapS(Value.WRAP);
            b.setWrapT(Value.WRAP);
            rs.mSampler_WRAP_NEAREST = b.create();
        }
        return rs.mSampler_WRAP_NEAREST;
    }

    /**
     * Retrieve a sampler with min and mag set to nearest and wrap modes set to
     * wrap.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler WRAP_LINEAR(RenderScript rs) {
        if(rs.mSampler_WRAP_LINEAR == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.LINEAR);
            b.setMagnification(Value.LINEAR);
            b.setWrapS(Value.WRAP);
            b.setWrapT(Value.WRAP);
            rs.mSampler_WRAP_LINEAR = b.create();
        }
        return rs.mSampler_WRAP_LINEAR;
    }

    /**
     * Retrieve a sampler with ag set to linear, min linear mipmap linear, and
     * to and wrap modes set to wrap.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler WRAP_LINEAR_MIP_LINEAR(RenderScript rs) {
        if(rs.mSampler_WRAP_LINEAR_MIP_LINEAR == null) {
            Builder b = new Builder(rs);
            b.setMinification(Value.LINEAR_MIP_LINEAR);
            b.setMagnification(Value.LINEAR);
            b.setWrapS(Value.WRAP);
            b.setWrapT(Value.WRAP);
            rs.mSampler_WRAP_LINEAR_MIP_LINEAR = b.create();
        }
        return rs.mSampler_WRAP_LINEAR_MIP_LINEAR;
    }


    /**
     * Builder for creating non-standard samplers.  Usefull if mix and match of
     * wrap modes is necesary or if anisotropic filtering is desired.
     *
     */
    public static class Builder {
        RenderScript mRS;
        Value mMin;
        Value mMag;
        Value mWrapS;
        Value mWrapT;
        Value mWrapR;
        float mAniso;

        public Builder(RenderScript rs) {
            mRS = rs;
            mMin = Value.NEAREST;
            mMag = Value.NEAREST;
            mWrapS = Value.WRAP;
            mWrapT = Value.WRAP;
            mWrapR = Value.WRAP;
            mAniso = 1.0f;
        }

        public void setMinification(Value v) {
            if (v == Value.NEAREST ||
                v == Value.LINEAR ||
                v == Value.LINEAR_MIP_LINEAR ||
                v == Value.LINEAR_MIP_NEAREST) {
                mMin = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public void setMagnification(Value v) {
            if (v == Value.NEAREST || v == Value.LINEAR) {
                mMag = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public void setWrapS(Value v) {
            if (v == Value.WRAP || v == Value.CLAMP) {
                mWrapS = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public void setWrapT(Value v) {
            if (v == Value.WRAP || v == Value.CLAMP) {
                mWrapT = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public void setAnisotropy(float v) {
            if(v >= 0.0f) {
                mAniso = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public Sampler create() {
            mRS.validate();
            int id = mRS.nSamplerCreate(mMag.mID, mMin.mID, 
                                        mWrapS.mID, mWrapT.mID, mWrapR.mID, mAniso);
            Sampler sampler = new Sampler(id, mRS);
            sampler.mMin = mMin;
            sampler.mMag = mMag;
            sampler.mWrapS = mWrapS;
            sampler.mWrapT = mWrapT;
            sampler.mWrapR = mWrapR;
            sampler.mAniso = mAniso;
            return sampler;
        }
    }

}

