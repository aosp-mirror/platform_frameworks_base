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

import android.annotation.UnsupportedAppUsage;
import dalvik.system.CloseGuard;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * BaseObj is the base class for all RenderScript objects owned by a RS context.
 * It is responsible for lifetime management and resource tracking. This class
 * should not be used by a user application.
 *
 **/
public class BaseObj {
    BaseObj(long id, RenderScript rs) {
        rs.validate();
        mRS = rs;
        mID = id;
        mDestroyed = false;
    }

    void setID(long id) {
        if (mID != 0) {
            throw new RSRuntimeException("Internal Error, reset of object ID.");
        }
        mID = id;
    }

    /**
     * Lookup the native object ID for this object.  Primarily used by the
     * generated reflected code.
     *
     * @param rs Context to verify against internal context for
     *           match.
     *
     * @return long
     */
    long getID(RenderScript rs) {
        mRS.validate();
        if (mDestroyed) {
            throw new RSInvalidStateException("using a destroyed object.");
        }
        if (mID == 0) {
            throw new RSRuntimeException("Internal error: Object id 0.");
        }
        if ((rs != null) && (rs != mRS)) {
            throw new RSInvalidStateException("using object with mismatched context.");
        }
        return mID;
    }

    void checkValid() {
        if (mID == 0) {
            throw new RSIllegalArgumentException("Invalid object.");
        }
    }

    private long mID;
    final CloseGuard guard = CloseGuard.get();
    private boolean mDestroyed;
    private String mName;
    @UnsupportedAppUsage
    RenderScript mRS;

    /**
     * setName assigns a name to an object.  This object can later be looked up
     * by this name.
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

    private void helpDestroy() {
        boolean shouldDestroy = false;
        synchronized(this) {
            if (!mDestroyed) {
                shouldDestroy = true;
                mDestroyed = true;
            }
        }

        if (shouldDestroy) {
            guard.close();
            // must include nObjDestroy in the critical section
            ReentrantReadWriteLock.ReadLock rlock = mRS.mRWLock.readLock();
            rlock.lock();
            // AllocationAdapters are BaseObjs with an ID of 0 but should not be passed to nObjDestroy
            if(mRS.isAlive() && mID != 0) {
                mRS.nObjDestroy(mID);
            }
            rlock.unlock();
            mRS = null;
            mID = 0;
        }
    }

    protected void finalize() throws Throwable {
        try {
            if (guard != null) {
                guard.warnIfOpen();
            }
            helpDestroy();
        } finally {
            super.finalize();
        }
    }

    /**
     * Frees any native resources associated with this object.  The
     * primary use is to force immediate cleanup of resources when it is
     * believed the GC will not respond quickly enough.
     */
    public void destroy() {
        if(mDestroyed) {
            throw new RSInvalidStateException("Object already destroyed.");
        }
        helpDestroy();
    }

    /**
     * If an object came from an a3d file, java fields need to be
     * created with objects from the native layer
     */
    void updateFromNative() {
        mRS.validate();
        mName = mRS.nGetName(getID(mRS));
    }

    /**
     * Calculates the hash code value for a BaseObj.
     *
     * @return int
     */
    @Override
    public int hashCode() {
        return (int)((mID & 0xfffffff) ^ (mID >> 32));
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

        if (obj == null) {
            return false;
        }

        if (getClass() != obj.getClass()) {
            return false;
        }

        BaseObj b = (BaseObj) obj;
        return mID == b.mID;
    }
}

