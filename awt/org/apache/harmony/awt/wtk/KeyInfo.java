/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Pavel Dolgov
 * @version $Revision$
 */
package org.apache.harmony.awt.wtk;

import java.awt.event.KeyEvent;

/**
 * Keystroke information
 */

public final class KeyInfo {

    public int vKey;
    public int keyLocation;
    public final StringBuffer keyChars;

    public static final int DEFAULT_VKEY = KeyEvent.VK_UNDEFINED;
    public static final int DEFAULT_LOCATION = KeyEvent.KEY_LOCATION_STANDARD;

    public KeyInfo() {
        vKey = DEFAULT_VKEY;
        keyLocation = DEFAULT_LOCATION;
        keyChars = new StringBuffer();
    }

    public void setKeyChars(char ch) {
        keyChars.setLength(0);
        keyChars.append(ch);
    }

    public void setKeyChars(StringBuffer sb) {
        keyChars.setLength(0);
        keyChars.append(sb);
    }
}
