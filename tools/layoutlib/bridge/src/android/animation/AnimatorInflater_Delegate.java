/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.animation;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;

/**
 * Delegate providing alternate implementation to static methods in {@link AnimatorInflater}.
 */
public class AnimatorInflater_Delegate {

    @LayoutlibDelegate
    /*package*/ static Animator loadAnimator(Context context, int id)
            throws NotFoundException {
        return loadAnimator(context.getResources(), context.getTheme(), id);
    }

    @LayoutlibDelegate
    /*package*/ static Animator loadAnimator(Resources resources, Theme theme, int id)
            throws NotFoundException {
        return loadAnimator(resources, theme, id, 1);
    }

    @LayoutlibDelegate
    /*package*/ static Animator loadAnimator(Resources resources, Theme theme, int id,
            float pathErrorScale) throws NotFoundException {
        // This is a temporary fix to http://b.android.com/77865. This skips loading the
        // animation altogether.
        // TODO: Remove this override when Path.approximate() is supported.
        return new FakeAnimator();
    }

    @LayoutlibDelegate
    /*package*/ static ValueAnimator loadAnimator(Resources res, Theme theme,
            AttributeSet attrs, ValueAnimator anim, float pathErrorScale)
            throws NotFoundException {
        return AnimatorInflater.loadAnimator_Original(res, theme, attrs, anim, pathErrorScale);
    }
}
