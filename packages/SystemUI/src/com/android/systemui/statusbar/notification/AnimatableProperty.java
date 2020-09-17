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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.graphics.drawable.Drawable;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.view.View;

import com.android.systemui.R;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * An animatable property of a view. Used with {@link PropertyAnimator}
 */
public abstract class AnimatableProperty {

    public static final AnimatableProperty X = AnimatableProperty.from(View.X,
            R.id.x_animator_tag, R.id.x_animator_tag_start_value, R.id.x_animator_tag_end_value);
    public static final AnimatableProperty Y = AnimatableProperty.from(View.Y,
            R.id.y_animator_tag, R.id.y_animator_tag_start_value, R.id.y_animator_tag_end_value);

    /**
     * Similar to X, however this doesn't allow for any other modifications other than from this
     * property. When using X, it's possible that the view is laid out during the animation,
     * which could break the continuity
     */
    public static final AnimatableProperty ABSOLUTE_X = AnimatableProperty.from(
            new FloatProperty<View>("ViewAbsoluteX") {
                @Override
                public void setValue(View view, float value) {
                    view.setTag(R.id.absolute_x_current_value, value);
                    View.X.set(view, value);
                }

                @Override
                public Float get(View view) {
                    Object tag = view.getTag(R.id.absolute_x_current_value);
                    if (tag instanceof Float) {
                        return (Float) tag;
                    }
                    return View.X.get(view);
                }
            },
            R.id.absolute_x_animator_tag,
            R.id.absolute_x_animator_start_tag,
            R.id.absolute_x_animator_end_tag);

    /**
     * Similar to Y, however this doesn't allow for any other modifications other than from this
     * property. When using X, it's possible that the view is laid out during the animation,
     * which could break the continuity
     */
    public static final AnimatableProperty ABSOLUTE_Y = AnimatableProperty.from(
            new FloatProperty<View>("ViewAbsoluteY") {
                @Override
                public void setValue(View view, float value) {
                    view.setTag(R.id.absolute_y_current_value, value);
                    View.Y.set(view, value);
                }

                @Override
                public Float get(View view) {
                    Object tag = view.getTag(R.id.absolute_y_current_value);
                    if (tag instanceof Float) {
                        return (Float) tag;
                    }
                    return View.Y.get(view);
                }
            },
            R.id.absolute_y_animator_tag,
            R.id.absolute_y_animator_start_tag,
            R.id.absolute_y_animator_end_tag);

    public static final AnimatableProperty WIDTH = AnimatableProperty.from(
            new FloatProperty<View>("ViewWidth") {
                @Override
                public void setValue(View view, float value) {
                    view.setTag(R.id.view_width_current_value, value);
                    view.setRight((int) (view.getLeft() + value));
                }

                @Override
                public Float get(View view) {
                    Object tag = view.getTag(R.id.view_width_current_value);
                    if (tag instanceof Float) {
                        return (Float) tag;
                    }
                    return (float) view.getWidth();
                }
            },
            R.id.view_width_animator_tag,
            R.id.view_width_animator_start_tag,
            R.id.view_width_animator_end_tag);

    public static final AnimatableProperty HEIGHT = AnimatableProperty.from(
            new FloatProperty<View>("ViewHeight") {
                @Override
                public void setValue(View view, float value) {
                    view.setTag(R.id.view_height_current_value, value);
                    view.setBottom((int) (view.getTop() + value));
                }

                @Override
                public Float get(View view) {
                    Object tag = view.getTag(R.id.view_height_current_value);
                    if (tag instanceof Float) {
                        return (Float) tag;
                    }
                    return (float) view.getHeight();
                }
            },
            R.id.view_height_animator_tag,
            R.id.view_height_animator_start_tag,
            R.id.view_height_animator_end_tag);

    public abstract int getAnimationStartTag();

    public abstract int getAnimationEndTag();

    public abstract int getAnimatorTag();

    public abstract Property getProperty();

    public static <T extends View> AnimatableProperty from(String name, BiConsumer<T, Float> setter,
            Function<T, Float> getter, int animatorTag, int startValueTag, int endValueTag) {
        Property<T, Float> property = new FloatProperty<T>(name) {

            @Override
            public Float get(T object) {
                return getter.apply(object);
            }

            @Override
            public void setValue(T object, float value) {
                setter.accept(object, value);
            }
        };
        return new AnimatableProperty() {
            @Override
            public int getAnimationStartTag() {
                return startValueTag;
            }

            @Override
            public int getAnimationEndTag() {
                return endValueTag;
            }

            @Override
            public int getAnimatorTag() {
                return animatorTag;
            }

            @Override
            public Property getProperty() {
                return property;
            }
        };
    }

    public static <T extends View> AnimatableProperty from(Property<T, Float> property,
            int animatorTag, int startValueTag, int endValueTag) {
        return new AnimatableProperty() {
            @Override
            public int getAnimationStartTag() {
                return startValueTag;
            }

            @Override
            public int getAnimationEndTag() {
                return endValueTag;
            }

            @Override
            public int getAnimatorTag() {
                return animatorTag;
            }

            @Override
            public Property getProperty() {
                return property;
            }
        };
    }
}
