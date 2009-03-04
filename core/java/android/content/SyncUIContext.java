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

package android.content;

/**
 * Class with callback methods for SyncAdapters and ContentProviders
 * that are called in response to the calls on SyncContext.  This class
 * is really only meant to be used by the Sync UI activities.
 *
 * <p>All of the onXXX callback methods here are called from a handler
 * on the thread this object was created in.
 *
 * <p>This interface is unused. It should be removed.
 * 
 * @hide
 */
@Deprecated
public interface SyncUIContext {
    
    void setStatusText(String text);

    Context context();
}
