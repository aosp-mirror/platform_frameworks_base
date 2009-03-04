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

package android.app;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;

/**
 * Base class for those who need to maintain global application state. You can
 * provide your own implementation by specifying its name in your
 * AndroidManifest.xml's &lt;application&gt; tag, which will cause that class
 * to be instantiated for you when the process for your application/package is
 * created.
 */
public class Application extends ContextWrapper implements ComponentCallbacks {
    
    public Application() {
        super(null);
    }

    /**
     * Called when the application is starting, before any other application
     * objects have been created.  Implementations should be as quick as
     * possible (for example using lazy initialization of state) since the time
     * spent in this function directly impacts the performance of starting the
     * first activity, service, or receiver in a process.
     * If you override this method, be sure to call super.onCreate().
     */
    public void onCreate() {
    }

    /**
     * Called when the application is stopping.  There are no more application
     * objects running and the process will exit.  <em>Note: never depend on
     * this method being called; in many cases an unneeded application process
     * will simply be killed by the kernel without executing any application
     * code.</em>
     * If you override this method, be sure to call super.onTerminate().
     */
    public void onTerminate() {
    }
    
    public void onConfigurationChanged(Configuration newConfig) {
    }
    
    public void onLowMemory() {
    }
    
    // ------------------ Internal API ------------------
    
    /**
     * @hide
     */
    /* package */ final void attach(Context context) {
        attachBaseContext(context);
    }

}
