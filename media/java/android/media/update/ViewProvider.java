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

package android.media.update;

import android.annotation.SystemApi;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * Each instance object is connected to one corresponding updatable object which implements the
 * runtime behavior of that class. There should a corresponding provider method for all public
 * methods.
 *
 * All methods behave as per their namesake in the public API.
 *
 * @see android.view.View
 *
 * @hide
 */
// TODO @SystemApi
public interface ViewProvider {
    // TODO Add more (all?) methods from View
    void onAttachedToWindow_impl();
    void onDetachedFromWindow_impl();
    void onLayout_impl(boolean changed, int left, int top, int right, int bottom);
    void draw_impl(Canvas canvas);
    CharSequence getAccessibilityClassName_impl();
    boolean onTouchEvent_impl(MotionEvent ev);
    boolean onTrackballEvent_impl(MotionEvent ev);
    boolean onKeyDown_impl(int keyCode, KeyEvent event);
    void onFinishInflate_impl();
    boolean dispatchKeyEvent_impl(KeyEvent event);
    void setEnabled_impl(boolean enabled);
}
