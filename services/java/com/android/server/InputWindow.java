/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import android.view.InputChannel;

/**
 * Describes input-related window properties for use by the input dispatcher.
 * 
 * @hide
 */
public final class InputWindow {
    // The input channel associated with the window.
    public InputChannel inputChannel;
    
    // Window layout params attributes.  (WindowManager.LayoutParams)
    public int layoutParamsFlags;
    public int layoutParamsType;
    
    // Dispatching timeout.
    public long dispatchingTimeoutNanos;
    
    // Window frame position.
    public int frameLeft;
    public int frameTop;
    
    // Window touchable area.
    public int touchableAreaLeft;
    public int touchableAreaTop;
    public int touchableAreaRight;
    public int touchableAreaBottom;
    
    // Window is visible.
    public boolean visible;
    
    // Window has focus.
    public boolean hasFocus;
    
    // Window has wallpaper.  (window is the current wallpaper target)
    public boolean hasWallpaper;
    
    // Input event dispatching is paused.
    public boolean paused;
    
    // Id of process and user that owns the window.
    public int ownerPid;
    public int ownerUid;
    
    public void recycle() {
        inputChannel = null;
    }
}
