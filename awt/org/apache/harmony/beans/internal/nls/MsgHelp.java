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

/*
 * This implementation is based on the class of the same name in
 * org.apache.harmony.luni.util.
 */

package org.apache.harmony.beans.internal.nls;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.MissingResourceException;

/**
 * This class contains helper methods for loading resource bundles and
 * formatting external message strings.
 */
public final class MsgHelp {
    /** name of the resource for this class */
    private static final String RESOURCE_NAME =
        "/org/apache/harmony/beans/internal/nls/messages.properties";

    /** the resource bundle for this class */
    private static final ResourceBundle THE_BUNDLE;

    static {
        ResourceBundle rb = null;

        try {
            InputStream in = MsgHelp.class.getResourceAsStream(
                    RESOURCE_NAME);
            rb = new PropertyResourceBundle(in);
        } catch (IOException ex) {
            Logger.global.warning("Couldn't read resource bundle: " +
                    ex);
        } catch (RuntimeException ex) {
            // Shouldn't happen, but deal at least somewhat gracefully.
            Logger.global.warning("Couldn't find resource bundle: " +
                    ex);
        }

        THE_BUNDLE = rb;
    }
    
    public static String getString(String msg) {
        if (THE_BUNDLE == null) {
            return msg;
        }
        try {
            return THE_BUNDLE.getString(msg);
        } catch (MissingResourceException e) {
            return "Missing message: " + msg;
        }
    }
    
    static public String getString(String msg, Object[] args) {
        String format = msg;
        if (THE_BUNDLE != null) {
            try {
                format = THE_BUNDLE.getString(msg);
            } catch (MissingResourceException e) {
            }
        }

        return org.apache.harmony.luni.util.MsgHelp.format(format, args);
    }
}
