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
 * Sampler object that defines how Allocations can be read as textures within a
 * kernel. Samplers are used in conjunction with the {@code rsSample} runtime
 * function to return values from normalized coordinates.
 *
 * Any Allocation used with a Sampler must have been created with {@link
 * android.renderscript.Allocation#USAGE_GRAPHICS_TEXTURE}; using a Sampler on
 * an {@link android.renderscript.Allocation} that was not created with {@link
 * android.renderscript.Allocation#USAGE_GRAPHICS_TEXTURE} is undefined.
 **/
public class Sampler extends BaseObj {
    public enum Value {
        NEAREST (0),
        LINEAR (1),
        LINEAR_MIP_LINEAR (2),
        LINEAR_MIP_NEAREST (5),
        WRAP (3),
        CLAMP (4),
        MIRRORED_REPEAT (6);

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

    Sampler(long id, RenderScript rs) {
        super(id, rs);
        guard.open("destroy");
    }

    /**
     * @return minification setting for the sampler
     */
    public Value getMinification() {
        return mMin;
    }

    /**
     * @return magnification setting for the sampler
     */
    public Value getMagnification() {
        return mMag;
    }

    /**
     * @return S wrapping mode for the sampler
     */
    public Value getWrapS() {
        return mWrapS;
    }

    /**
     * @return T wrapping mode for the sampler
     */
    public Value getWrapT() {
        return mWrapT;
    }

    /**
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
        if (rs.mSampler_CLAMP_NEAREST == null) {
            synchronized (rs) {
                if (rs.mSampler_CLAMP_NEAREST == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.NEAREST);
                    b.setMagnification(Value.NEAREST);
                    b.setWrapS(Value.CLAMP);
                    b.setWrapT(Value.CLAMP);
                    rs.mSampler_CLAMP_NEAREST = b.create();
                }
            }
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
        if (rs.mSampler_CLAMP_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_CLAMP_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.CLAMP);
                    b.setWrapT(Value.CLAMP);
                    rs.mSampler_CLAMP_LINEAR = b.create();
                }
            }
        }
        return rs.mSampler_CLAMP_LINEAR;
    }

    /**
     * Retrieve a sampler with mag set to linear, min linear mipmap linear, and
     * wrap modes set to clamp.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler CLAMP_LINEAR_MIP_LINEAR(RenderScript rs) {
        if (rs.mSampler_CLAMP_LINEAR_MIP_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_CLAMP_LINEAR_MIP_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR_MIP_LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.CLAMP);
                    b.setWrapT(Value.CLAMP);
                    rs.mSampler_CLAMP_LINEAR_MIP_LINEAR = b.create();
                }
            }
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
        if (rs.mSampler_WRAP_NEAREST == null) {
            synchronized (rs) {
                if (rs.mSampler_WRAP_NEAREST == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.NEAREST);
                    b.setMagnification(Value.NEAREST);
                    b.setWrapS(Value.WRAP);
                    b.setWrapT(Value.WRAP);
                    rs.mSampler_WRAP_NEAREST = b.create();
                }
            }
        }
        return rs.mSampler_WRAP_NEAREST;
    }

    /**
     * Retrieve a sampler with min and mag set to linear and wrap modes set to
     * wrap.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler WRAP_LINEAR(RenderScript rs) {
        if (rs.mSampler_WRAP_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_WRAP_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.WRAP);
                    b.setWrapT(Value.WRAP);
                    rs.mSampler_WRAP_LINEAR = b.create();
                }
            }
        }
        return rs.mSampler_WRAP_LINEAR;
    }

    /**
     * Retrieve a sampler with mag set to linear, min linear mipmap linear, and
     * wrap modes set to wrap.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler WRAP_LINEAR_MIP_LINEAR(RenderScript rs) {
        if (rs.mSampler_WRAP_LINEAR_MIP_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_WRAP_LINEAR_MIP_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR_MIP_LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.WRAP);
                    b.setWrapT(Value.WRAP);
                    rs.mSampler_WRAP_LINEAR_MIP_LINEAR = b.create();
                }
            }
        }
        return rs.mSampler_WRAP_LINEAR_MIP_LINEAR;
    }

    /**
     * Retrieve a sampler with min and mag set to nearest and wrap modes set to
     * mirrored repeat.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler MIRRORED_REPEAT_NEAREST(RenderScript rs) {
        if (rs.mSampler_MIRRORED_REPEAT_NEAREST == null) {
            synchronized (rs) {
                if (rs.mSampler_MIRRORED_REPEAT_NEAREST == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.NEAREST);
                    b.setMagnification(Value.NEAREST);
                    b.setWrapS(Value.MIRRORED_REPEAT);
                    b.setWrapT(Value.MIRRORED_REPEAT);
                    rs.mSampler_MIRRORED_REPEAT_NEAREST = b.create();
                }
            }
        }
        return rs.mSampler_MIRRORED_REPEAT_NEAREST;
    }

    /**
     * Retrieve a sampler with min and mag set to linear and wrap modes set to
     * mirrored repeat.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler MIRRORED_REPEAT_LINEAR(RenderScript rs) {
        if (rs.mSampler_MIRRORED_REPEAT_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_MIRRORED_REPEAT_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.MIRRORED_REPEAT);
                    b.setWrapT(Value.MIRRORED_REPEAT);
                    rs.mSampler_MIRRORED_REPEAT_LINEAR = b.create();
                }
            }
        }
        return rs.mSampler_MIRRORED_REPEAT_LINEAR;
    }

    /**
     * Retrieve a sampler with min and mag set to linear and wrap modes set to
     * mirrored repeat.
     *
     * @param rs Context to which the sampler will belong.
     *
     * @return Sampler
     */
    public static Sampler MIRRORED_REPEAT_LINEAR_MIP_LINEAR(RenderScript rs) {
        if (rs.mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR == null) {
            synchronized (rs) {
                if (rs.mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR == null) {
                    Builder b = new Builder(rs);
                    b.setMinification(Value.LINEAR_MIP_LINEAR);
                    b.setMagnification(Value.LINEAR);
                    b.setWrapS(Value.MIRRORED_REPEAT);
                    b.setWrapT(Value.MIRRORED_REPEAT);
                    rs.mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR = b.create();
                }
            }
        }
        return rs.mSampler_MIRRORED_REPEAT_LINEAR_MIP_LINEAR;
    }

    /**
     * Builder for creating non-standard samplers.  This is only necessary if
     * a Sampler with different min and mag modes is desired.
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
            if (v == Value.WRAP || v == Value.CLAMP || v == Value.MIRRORED_REPEAT) {
                mWrapS = v;
            } else {
                throw new IllegalArgumentException("Invalid value");
            }
        }

        public void setWrapT(Value v) {
            if (v == Value.WRAP || v == Value.CLAMP || v == Value.MIRRORED_REPEAT) {
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
            long id = mRS.nSamplerCreate(mMag.mID, mMin.mID,
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

