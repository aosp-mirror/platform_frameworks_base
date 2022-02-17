/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

/**
 * App hibernation manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class AppHibernationManagerInternal {

    /**
     * @see AppHibernationService#isHibernatingForUser
     */
    public abstract boolean isHibernatingForUser(String packageName, int userId);

    /**
     * @see AppHibernationService#setHibernatingForUser
     */
    public abstract void setHibernatingForUser(String packageName, int userId,
            boolean isHibernating);

    /**
     * @see AppHibernationService#isHibernatingGlobally
     */
    public abstract boolean isHibernatingGlobally(String packageName);

    /**
     * @see AppHibernationService#setHibernatingGlobally
     */
    public abstract void setHibernatingGlobally(String packageName, boolean isHibernating);

    /**
     * @see AppHibernationService#isOatArtifactDeletionEnabled
     */
    public abstract boolean isOatArtifactDeletionEnabled();
}
