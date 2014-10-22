/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.os;

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.util.Map;

/**
 * Delegate implementing the native methods of android.os.SystemProperties
 *
 * Through the layoutlib_create tool, the original native methods of SystemProperties have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 */
public class SystemProperties_Delegate {

    @LayoutlibDelegate
    /*package*/ static String native_get(String key) {
        return native_get(key, "");
    }

    @LayoutlibDelegate
    /*package*/ static String native_get(String key, String def) {
        Map<String, String> properties = Bridge.getPlatformProperties();
        String value = properties.get(key);
        if (value != null) {
            return value;
        }

        return def;
    }
    @LayoutlibDelegate
    /*package*/ static int native_get_int(String key, int def) {
        Map<String, String> properties = Bridge.getPlatformProperties();
        String value = properties.get(key);
        if (value != null) {
            return Integer.decode(value);
        }

        return def;
    }

    @LayoutlibDelegate
    /*package*/ static long native_get_long(String key, long def) {
        Map<String, String> properties = Bridge.getPlatformProperties();
        String value = properties.get(key);
        if (value != null) {
            return Long.decode(value);
        }

        return def;
    }

    /**
     * Values 'n', 'no', '0', 'false' or 'off' are considered false.
     * Values 'y', 'yes', '1', 'true' or 'on' are considered true.
     */
    @LayoutlibDelegate
    /*package*/ static boolean native_get_boolean(String key, boolean def) {
        Map<String, String> properties = Bridge.getPlatformProperties();
        String value = properties.get(key);

        if ("n".equals(value) || "no".equals(value) || "0".equals(value) || "false".equals(value)
                || "off".equals(value)) {
            return false;
        }
        //noinspection SimplifiableIfStatement
        if ("y".equals(value) || "yes".equals(value) || "1".equals(value) || "true".equals(value)
                || "on".equals(value)) {
            return true;
        }

        return def;
    }

    @LayoutlibDelegate
    /*package*/ static void native_set(String key, String def) {
        Map<String, String> properties = Bridge.getPlatformProperties();
        properties.put(key, def);
    }

    @LayoutlibDelegate
    /*package*/ static void native_add_change_callback() {
        // pass.
    }
}
