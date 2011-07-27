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

import android.util.Log;

/**
 * BaseObj is the base class for interfacing with native renderscript objects.
 * It primarly contains code for tracking the native object ID and forcably
 * disconecting the object from the native allocation for early cleanup.
 *
 **/
public class BaseObj {
    BaseObj(int id, RenderScript rs) {
        rs.validate();
        mRS = rs;
        mID = id;
        mDestroyed = false;
    }

    void setID(int id) {
        if (mID != 0) {
            throw new RSRuntimeException("Internal Error, reset of object ID.");
        }
        mID = id;
    }

    /**
     * Lookup the native object ID for this object.  Primarily used by the
     * generated reflected code.
     *
     *
     * @return int
     */
    int getID() {
        if (mDestroyed) {
            throw new RSInvalidStateException("using a destroyed object.");
        }
        if (mID == 0) {
            throw new RSRuntimeException("Internal error: Object id 0.");
        }
        return mID;
    }

    void checkValid() {
        if (mID == 0) {
            throw new RSIllegalArgumentException("Invalid object.");
        }
    }

    private int mID;
    private boolean mDestroyed;
    private String mName;
    RenderScript mRS;

    /**
     * setName assigns a name to an object.  This object can later be looked up
     * by this name.  This name will also be retained if the object is written
     * to an A3D file.
     *
     * @param name The name to assign to the object.
     */
    public void setName(String name) {
        if (name == null) {
            throw new RSIllegalArgumentException(
                "setName requires a string of non-zero length.");
        }
        if(name.length() < 1) {
            throw new RSIllegalArgumentException(
                "setName does not accept a zero length string.");
        }
        if(mName != null) {
            throw new RSIllegalArgumentException(
                "setName object already has a name.");
        }

        try {
            byte[] bytes = name.getBytes("UTF-8");
            mRS.nAssignName(mID, bytes);
            mName = name;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return name of the renderscript object
     */
    public String getName() {
        return mName;
    }

    protected void finalize() throws Throwable {
        if (!mDestroyed) {
            if(mID != 0 && mRS.isAlive()) {
                mRS.nObjDestroy(mID);
            }
            mRS = null;
            mID = 0;
            mDestroyed = true;
            //Log.v(RenderScript.LOG_TAG, getClass() +
            // " auto finalizing object without having released the RS reference.");
        }
        super.finalize();
    }

    /**
     * destroy disconnects the object from the native object effectively
     * rendering this java object dead.  The primary use is to force immediate
     * cleanup of resources when it is believed the GC will not respond quickly
     * enough.
     */
    synchronized public void destroy() {
        if(mDestroyed) {
            throw new RSInvalidStateException("Object already destroyed.");
        }
        mDestroyed = true;
        mRS.nObjDestroy(mID);
    }

    /**
     * If an object came from an a3d file, java fields need to be
     * created with objects from the native layer
     */
    void updateFromNative() {
        mRS.validate();
        mName = mRS.nGetName(getID());
    }

    /**
     * Calculates the hash code value for a BaseObj.
     *
     * @return int
     */
    @Override
    public int hashCode() {
        return mID;
    }

    /**
     * Compare the current BaseObj with another BaseObj for equality.
     *
     * @param obj The object to check equality with.
     *
     * @return boolean
     */
    @Override
    public boolean equals(Object obj) {
        // Early-out check to see if both BaseObjs are actually the same
        if (this == obj)
            return true;

        if (getClass() != obj.getClass()) {
            return false;
        }

        BaseObj b = (BaseObj) obj;
        return mID == b.mID;
    }
}

