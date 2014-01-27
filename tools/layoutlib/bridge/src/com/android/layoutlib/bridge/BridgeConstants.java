/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

/**
 * Constant definition class.<br>
 * <br>
 * Most constants have a prefix defining the content.
 * <ul>
 * <li><code>WS_</code> Workspace path constant. Those are absolute paths,
 * from the project root.</li>
 * <li><code>OS_</code> OS path constant. These paths are different depending on the platform.</li>
 * <li><code>FN_</code> File name constant.</li>
 * <li><code>FD_</code> Folder name constant.</li>
 * <li><code>EXT_</code> File extension constant. This does NOT include a dot.</li>
 * <li><code>DOT_</code> File extension constant. This start with a dot.</li>
 * <li><code>RE_</code> Regexp constant.</li>
 * <li><code>NS_</code> Namespace constant.</li>
 * <li><code>CLASS_</code> Fully qualified class name.</li>
 * </ul>
 *
 */
public class BridgeConstants {

    /** Namespace for the resource XML */
    public final static String NS_RESOURCES = "http://schemas.android.com/apk/res/android";

    /** App auto namespace */
    public final static String NS_APP_RES_AUTO = "http://schemas.android.com/apk/res-auto";

    public final static String R = "com.android.internal.R";


    public final static String MATCH_PARENT = "match_parent";
    public final static String FILL_PARENT = "fill_parent";
    public final static String WRAP_CONTENT = "wrap_content";
}
