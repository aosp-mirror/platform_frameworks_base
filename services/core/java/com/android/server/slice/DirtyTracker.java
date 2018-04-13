/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * A parent object that cares when a Persistable changes and will schedule a serialization
 * in response to the onPersistableDirty callback.
 */
public interface DirtyTracker {
    void onPersistableDirty(Persistable obj);

    /**
     * An object that can be written to XML.
     */
    interface Persistable {
        String getFileName();
        void writeTo(XmlSerializer out) throws IOException;
    }
}
