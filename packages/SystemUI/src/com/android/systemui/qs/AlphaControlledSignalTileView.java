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

package com.android.systemui.qs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import com.android.systemui.qs.tileimpl.SlashImageView;


/**
 * Creates AlphaControlledSlashImageView instead of SlashImageView
 */
public class AlphaControlledSignalTileView extends SignalTileView {
    public AlphaControlledSignalTileView(Context context) {
        super(context);
    }

    @Override
    protected SlashImageView createSlashImageView(Context context) {
        return new AlphaControlledSlashImageView(context);
    }

    /**
     * Creates AlphaControlledSlashDrawable instead of regular SlashDrawables
     */
    public static class AlphaControlledSlashImageView extends SlashImageView {
        public AlphaControlledSlashImageView(Context context) {
            super(context);
        }

        public void setFinalImageTintList(ColorStateList tint) {
            super.setImageTintList(tint);
            final SlashDrawable slash = getSlash();
            if (slash != null) {
                ((AlphaControlledSlashDrawable)slash).setFinalTintList(tint);
            }
        }

        @Override
        protected void ensureSlashDrawable() {
            if (getSlash() == null) {
                final SlashDrawable slash = new AlphaControlledSlashDrawable(getDrawable());
                setSlash(slash);
                slash.setAnimationEnabled(getAnimationEnabled());
                setImageViewDrawable(slash);
            }
        }
    }

    /**
     * SlashDrawable that disobeys orders to change its drawable's tint except when you tell
     * it not to disobey. The slash still will animate its alpha.
     */
    public static class AlphaControlledSlashDrawable extends SlashDrawable {
        AlphaControlledSlashDrawable(Drawable d) {
            super(d);
        }

        @Override
        protected void setDrawableTintList(ColorStateList tint) {
        }

        /**
         * Set a target tint list instead of
         */
        public void setFinalTintList(ColorStateList tint) {
            super.setDrawableTintList(tint);
        }
    }
}

