/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

/**
 * The class representing VCard related configurations  
 */
public class VCardConfig {
    static final int LOG_LEVEL_NONE = 0;
    static final int LOG_LEVEL_PERFORMANCE_MEASUREMENT = 0x1;
    static final int LOG_LEVEL_SHOW_WARNING = 0x2;
    static final int LOG_LEVEL_VERBOSE =
        LOG_LEVEL_PERFORMANCE_MEASUREMENT | LOG_LEVEL_SHOW_WARNING;
    
    // Assumes that "iso-8859-1" is able to map "all" 8bit characters to some unicode and
    // decode the unicode to the original charset. If not, this setting will cause some bug. 
    public static final String DEFAULT_CHARSET = "iso-8859-1";
    
    // TODO: use this flag
    public static boolean IGNORE_CASE_EXCEPT_VALUE = true;
    
    protected static final int LOG_LEVEL = LOG_LEVEL_PERFORMANCE_MEASUREMENT;
    
    // Note: phonetic name probably should be "LAST FIRST MIDDLE" for European languages, and
    //       space should be added between each element while it should not be in Japanese.
    //       But unfortunately, we currently do not have the data and are not sure whether we should
    //       support European version of name ordering.
    //
    // TODO: Implement the logic described above if we really need European version of
    //        phonetic name handling. Also, adding the appropriate test case of vCard would be
    //        highly appreciated.
    public static final int NAME_ORDER_TYPE_ENGLISH = 0;
    public static final int NAME_ORDER_TYPE_JAPANESE = 1;
    
    public static final int NAME_ORDER_TYPE_DEFAULT = NAME_ORDER_TYPE_ENGLISH; 
    
    /**
     * @hide temporal. may be deleted
     */
    public static boolean showPerformanceLog() {
        return (LOG_LEVEL & LOG_LEVEL_PERFORMANCE_MEASUREMENT) != 0;
    }
    
    private VCardConfig() {
    }
}