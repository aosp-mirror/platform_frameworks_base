/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.pm.ApplicationInfo;

/**
 * This is the interface to be implemented for the class that is specified by the
 * {@link android.R.styleable#AndroidManifestApplication_zygotePreloadName
 * android:zygotePreloadName} of the &lt;application&gt; tag.
 *
 * It is responsible for preloading application code and data, that will be shared by all
 * isolated services that have the
 * {@link android.R.styleable#AndroidManifestService_useAppZygote android:useAppZygote} attribute
 * of the &lt;service&gt; tag set to <code>true</code>.
 *
 * Note that implementations of this class must provide a default constructor with no arguments.
 */
public interface ZygotePreload {
    /**
     * This method is called once every time the Application Zygote is started. It is normally
     * started the first time an isolated service that uses it is started. The Application Zygote
     * will be stopped when all isolated services that use it are stopped.
     *
     * @param appInfo The ApplicationInfo object belonging to the application
     */
    void doPreload(@NonNull ApplicationInfo appInfo);
}
