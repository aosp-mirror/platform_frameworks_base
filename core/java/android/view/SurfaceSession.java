/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;


/**
 * An instance of this class represents a connection to the surface
 * flinger, in which you can create one or more Surface instances that will
 * be composited to the screen.
 * {@hide}
 */
public class SurfaceSession {
    /** Create a new connection with the surface flinger. */
    public SurfaceSession() {
        init();
    }

    /** Forcibly detach native resources associated with this object.
     *  Unlike destroy(), after this call any surfaces that were created
     *  from the session will no longer work. The session itself is destroyed.
     */
    public native void kill();

    /* no user serviceable parts here ... */
    @Override
    protected void finalize() throws Throwable {
        destroy();
    }
    
    private native void init();
    private native void destroy();
    
    private int mClient;
}

