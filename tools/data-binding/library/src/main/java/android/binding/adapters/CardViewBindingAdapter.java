/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.binding.adapters;

import android.binding.BindingAdapter;
import android.binding.BindingMethod;
import android.binding.BindingMethods;
import android.support.v7.widget.CardView;

@BindingMethods({
        @BindingMethod(type = "android.support.v7.widget.CardView", attribute = "cardCornerRadius", method = "setRadius"),
        @BindingMethod(type = "android.support.v7.widget.CardView", attribute = "cardMaxElevation", method = "setMaxCardElevation"),
        @BindingMethod(type = "android.support.v7.widget.CardView", attribute = "cardPreventCornerOverlap", method = "setPreventCornerOverlap"),
        @BindingMethod(type = "android.support.v7.widget.CardView", attribute = "cardUseCompatPadding", method = "setUseCompatPadding"),
})
public class CardViewBindingAdapter {

    @BindingAdapter("contentPadding")
    public static void setContentPadding(CardView view, int padding) {
        view.setContentPadding(padding, padding, padding, padding);
    }

    @BindingAdapter("contentPaddingLeft")
    public static void setContentPaddingLeft(CardView view, int left) {
        int top = view.getContentPaddingTop();
        int right = view.getContentPaddingRight();
        int bottom = view.getContentPaddingBottom();
        view.setContentPadding(left, top, right, bottom);
    }

    @BindingAdapter("contentPaddingTop")
    public static void setContentPaddingTop(CardView view, int top) {
        int left = view.getContentPaddingLeft();
        int right = view.getContentPaddingRight();
        int bottom = view.getContentPaddingBottom();
        view.setContentPadding(left, top, right, bottom);
    }

    @BindingAdapter("contentPaddingRight")
    public static void setContentPaddingRight(CardView view, int right) {
        int left = view.getContentPaddingLeft();
        int top = view.getContentPaddingTop();
        int bottom = view.getContentPaddingBottom();
        view.setContentPadding(left, top, right, bottom);
    }

    @BindingAdapter("contentPaddingBottom")
    public static void setContentPaddingBottom(CardView view, int bottom) {
        int left = view.getContentPaddingLeft();
        int top = view.getContentPaddingTop();
        int right = view.getContentPaddingRight();
        view.setContentPadding(left, top, right, bottom);
    }
}
