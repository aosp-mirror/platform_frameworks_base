// Copyright (C) 2011 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

// Tell which java package name the reflected files should belong to
#pragma rs java_package_name(com.example.android.rs.helloworld)

// Built-in header with graphics API's
#include "rs_graphics.rsh"

// gTouchX and gTouchY are variables that will be reflected for use
// by the java API. We can use them to notify the script of touch events.
int gTouchX;
int gTouchY;

// This is invoked automatically when the script is created
void init() {
    gTouchX = 50.0f;
    gTouchY = 50.0f;
}

int root(void) {

    // Clear the background color
    rsgClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    // Tell the runtime what the font color should be
    rsgFontColor(1.0f, 1.0f, 1.0f, 1.0f);
    // Introuduce ourselves to the world by drawing a greeting
    // at the position user touched on the screen
    rsgDrawText("Hello World!", gTouchX, gTouchY);

    // Return value tells RS roughly how often to redraw
    // in this case 20 ms
    return 20;
}
