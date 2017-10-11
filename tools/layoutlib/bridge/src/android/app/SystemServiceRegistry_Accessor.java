/*
 * Copyright (C) 2017 The Android Open Source Project
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

/**
 * Class to allow accessing {@link SystemServiceRegistry#getSystemServiceName}
 */
public class SystemServiceRegistry_Accessor {
    /**
     * Gets the name of the system-level service that is represented by the specified class.
     */
    public static String getSystemServiceName(Class<?> serviceClass) {
        return SystemServiceRegistry.getSystemServiceName(serviceClass);
    }
}
