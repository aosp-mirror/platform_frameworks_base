/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.test;

/**
 * 
 * The InstrumentationUtils class has all the utility functions needed for
 * instrumentation tests.
 *
 * {@hide} - Not currently used.
 */
public class InstrumentationUtils {
    /**
     * An utility function that returns the menu identifier for a particular
     * menu item.
     * 
     * @param cls Class object of the class that handles the menu ite,.
     * @param identifier Menu identifier.
     * @return The integer corresponding to the menu item.
     */
    public static int getMenuIdentifier(Class cls, String identifier) {
        int id = -1;
        try {
            Integer field = (Integer)cls.getDeclaredField(identifier).get(cls);   
            id = field.intValue();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return id;
    }

}
