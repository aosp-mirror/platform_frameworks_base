/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.complication.dagger.DreamClockDateComplicationModule.DREAM_CLOCK_DATE_COMPLICATION_LAYOUT_PARAMS;
import static com.android.systemui.dreams.complication.dagger.DreamClockDateComplicationModule.DREAM_CLOCK_DATE_COMPLICATION_VIEW;

import android.content.Context;
import android.view.View;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.DreamOverlayStateController;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * Clock Date Complication that produce Clock Date view holder.
 */
public class DreamClockDateComplication implements Complication {
    private final Provider<DreamClockDateViewHolder> mDreamClockDateViewHolderProvider;

    /**
     * Default constructor for {@link DreamClockDateComplication}.
     */
    @Inject
    public DreamClockDateComplication(
            Provider<DreamClockDateViewHolder> dreamClockDateViewHolderProvider) {
        mDreamClockDateViewHolderProvider = dreamClockDateViewHolderProvider;
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_DATE;
    }

    /**
     * Create {@link DreamClockDateViewHolder}.
     */
    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mDreamClockDateViewHolderProvider.get();
    }

    /**
     * {@link CoreStartable} responsible for registering {@link DreamClockDateComplication} with
     * SystemUI.
     */
    public static class Registrant extends CoreStartable {
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final DreamClockDateComplication mComplication;

        /**
         * Default constructor to register {@link DreamClockDateComplication}.
         */
        @Inject
        public Registrant(Context context,
                DreamOverlayStateController dreamOverlayStateController,
                DreamClockDateComplication dreamClockDateComplication) {
            super(context);
            mDreamOverlayStateController = dreamOverlayStateController;
            mComplication = dreamClockDateComplication;
        }

        @Override
        public void start() {
            mDreamOverlayStateController.addComplication(mComplication);
        }
    }

    /**
     * {@link ViewHolder} to contain value/logic associated with {@link DreamClockDateComplication}.
     */
    public static class DreamClockDateViewHolder implements ViewHolder {
        private final View mView;
        private final ComplicationLayoutParams mLayoutParams;

        @Inject
        DreamClockDateViewHolder(@Named(DREAM_CLOCK_DATE_COMPLICATION_VIEW) View view,
                @Named(DREAM_CLOCK_DATE_COMPLICATION_LAYOUT_PARAMS)
                        ComplicationLayoutParams layoutParams) {
            mView = view;
            mLayoutParams = layoutParams;
        }

        @Override
        public View getView() {
            return mView;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }
    }
}
