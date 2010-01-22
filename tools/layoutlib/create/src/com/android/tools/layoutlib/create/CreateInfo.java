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

package com.android.tools.layoutlib.create;

public class CreateInfo {
    /**
     * The list of class from layoutlib_create to inject in layoutlib.
     */
    public final static Class<?>[] INJECTED_CLASSES = new Class<?>[] {
            OverrideMethod.class,
            MethodListener.class,
            MethodAdapter.class,
            CreateInfo.class
        };

    /**
     * The list of methods to stub out. Each entry must be in the form
     *  "package.package.OuterClass$InnerClass#MethodName".
     */
    public final static String[] OVERRIDDEN_METHODS = new String[] {
            "android.view.View#isInEditMode",
            "android.content.res.Resources$Theme#obtainStyledAttributes",
        };

    /**
     *  The list of classes to rename, must be an even list: the binary FQCN
     *  of class to replace followed by the new FQCN.
     */
    public final static String[] RENAMED_CLASSES =
        new String[] {
            "android.graphics.Bitmap",              "android.graphics._Original_Bitmap",
            "android.graphics.BitmapFactory",       "android.graphics._Original_BitmapFactory",
            "android.graphics.BitmapShader",        "android.graphics._Original_BitmapShader",
            "android.graphics.Canvas",              "android.graphics._Original_Canvas",
            "android.graphics.ComposeShader",       "android.graphics._Original_ComposeShader",
            "android.graphics.DashPathEffect",       "android.graphics._Original_DashPathEffect",
            "android.graphics.LinearGradient",      "android.graphics._Original_LinearGradient",
            "android.graphics.Matrix",              "android.graphics._Original_Matrix",
            "android.graphics.Paint",               "android.graphics._Original_Paint",
            "android.graphics.Path",                "android.graphics._Original_Path",
            "android.graphics.PorterDuffXfermode",  "android.graphics._Original_PorterDuffXfermode",
            "android.graphics.RadialGradient",      "android.graphics._Original_RadialGradient",
            "android.graphics.Shader",              "android.graphics._Original_Shader",
            "android.graphics.SweepGradient",       "android.graphics._Original_SweepGradient",
            "android.graphics.Typeface",            "android.graphics._Original_Typeface",
            "android.os.ServiceManager",            "android.os._Original_ServiceManager",
            "android.util.FloatMath",               "android.util._Original_FloatMath",
            "android.view.SurfaceView",             "android.view._Original_SurfaceView",
            "android.view.accessibility.AccessibilityManager", "android.view.accessibility._Original_AccessibilityManager",
        };

    /**
     * List of classes for which the methods returning them should be deleted.
     * The array contains a list of null terminated section starting with the name of the class
     * to rename in which the methods are deleted, followed by a list of return types identifying
     * the methods to delete.
     */
    public final static String[] REMOVED_METHODS =
        new String[] {
            "android.graphics.Paint",       // class to delete methods from
            "android.graphics.Paint$Align", // list of type identifying methods to delete
            "android.graphics.Paint$Style",
            "android.graphics.Paint$Join",
            "android.graphics.Paint$Cap",
            "android.graphics.Paint$FontMetrics",
            "android.graphics.Paint$FontMetricsInt",
            null };                         // separator, for next class/methods list.
}

