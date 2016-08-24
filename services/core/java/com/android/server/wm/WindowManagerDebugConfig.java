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


package com.android.server.wm;

/**
 * Common class for the various debug {@link android.util.Log} output configuration in the window
 * manager package.
 */
public class WindowManagerDebugConfig {
    // All output logs in the window manager package use the {@link #TAG_WM} string for tagging
    // their log output. This makes it easy to identify the origin of the log message when sifting
    // through a large amount of log output from multiple sources. However, it also makes trying
    // to figure-out the origin of a log message while debugging the window manager a little
    // painful. By setting this constant to true, log messages from the window manager package
    // will be tagged with their class names instead fot the generic tag.
    static final boolean TAG_WITH_CLASS_NAME = false;

    // Default log tag for the window manager package.
    static final String TAG_WM = "WindowManager";

    static final boolean DEBUG_RESIZE = false;
    static final boolean DEBUG = false;
    static final boolean DEBUG_ADD_REMOVE = false;
    static final boolean DEBUG_FOCUS = false;
    static final boolean DEBUG_FOCUS_LIGHT = DEBUG_FOCUS || false;
    static final boolean DEBUG_ANIM = false;
    static final boolean DEBUG_KEYGUARD = false;
    static final boolean DEBUG_LAYOUT = false;
    static final boolean DEBUG_LAYERS = false;
    static final boolean DEBUG_INPUT = false;
    static final boolean DEBUG_INPUT_METHOD = false;
    static final boolean DEBUG_VISIBILITY = false;
    static final boolean DEBUG_WINDOW_MOVEMENT = false;
    static final boolean DEBUG_TOKEN_MOVEMENT = false;
    static final boolean DEBUG_ORIENTATION = false;
    static final boolean DEBUG_APP_ORIENTATION = false;
    static final boolean DEBUG_CONFIGURATION = false;
    static final boolean DEBUG_APP_TRANSITIONS = false;
    static final boolean DEBUG_STARTING_WINDOW = false;
    static final boolean DEBUG_WALLPAPER = false;
    static final boolean DEBUG_WALLPAPER_LIGHT = false || DEBUG_WALLPAPER;
    static final boolean DEBUG_DRAG = false;
    static final boolean DEBUG_SCREEN_ON = false;
    static final boolean DEBUG_SCREENSHOT = false;
    static final boolean DEBUG_BOOT = false;
    static final boolean DEBUG_LAYOUT_REPEATS = false;
    static final boolean DEBUG_SURFACE_TRACE = false;
    static final boolean DEBUG_WINDOW_TRACE = false;
    static final boolean DEBUG_TASK_MOVEMENT = false;
    static final boolean DEBUG_TASK_POSITIONING = false;
    static final boolean DEBUG_STACK = false;
    static final boolean DEBUG_DISPLAY = false;
    static final boolean DEBUG_POWER = false;
    static final boolean DEBUG_DIM_LAYER = false;
    static final boolean SHOW_SURFACE_ALLOC = false;
    static final boolean SHOW_TRANSACTIONS = false;
    static final boolean SHOW_VERBOSE_TRANSACTIONS = false && SHOW_TRANSACTIONS;
    static final boolean SHOW_LIGHT_TRANSACTIONS = false || SHOW_TRANSACTIONS;
    static final boolean SHOW_STACK_CRAWLS = false;
    static final boolean DEBUG_WINDOW_CROP = false;

    static final String TAG_KEEP_SCREEN_ON = "DebugKeepScreenOn";
    static final boolean DEBUG_KEEP_SCREEN_ON = false;
}
