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

import static com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule.DREAM_SMARTSPACE_LAYOUT_PARAMS;

import android.content.Context;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.CoreStartable;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.smartspace.DreamSmartspaceController;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * {@link SmartSpaceComplication} embodies the SmartSpace view found on the lockscreen as a
 * {@link Complication}
 */
public class SmartSpaceComplication implements Complication {
    private final Provider<SmartSpaceComplicationViewHolder> mViewHolderProvider;

    @Inject
    public SmartSpaceComplication(Provider<SmartSpaceComplicationViewHolder> viewHolderProvider) {
        mViewHolderProvider = viewHolderProvider;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mViewHolderProvider.get();
    }

    @Override
    public int getRequiredTypeAvailability() {
        return COMPLICATION_TYPE_SMARTSPACE;
    }

    /**
     * {@link CoreStartable} responsbile for registering {@link SmartSpaceComplication} with
     * SystemUI.
     */
    public static class Registrant extends CoreStartable {
        private final DreamSmartspaceController mSmartSpaceController;
        private final DreamOverlayStateController mDreamOverlayStateController;
        private final SmartSpaceComplication mComplication;

        private final BcSmartspaceDataPlugin.SmartspaceTargetListener mSmartspaceListener =
                new BcSmartspaceDataPlugin.SmartspaceTargetListener() {
            @Override
            public void onSmartspaceTargetsUpdated(List<? extends Parcelable> targets) {
                mDreamOverlayStateController.addComplication(mComplication);
            }
        };

        /**
         * Default constructor for {@link SmartSpaceComplication}.
         */
        @Inject
        public Registrant(Context context,
                DreamOverlayStateController dreamOverlayStateController,
                SmartSpaceComplication smartSpaceComplication,
                DreamSmartspaceController smartSpaceController) {
            super(context);
            mDreamOverlayStateController = dreamOverlayStateController;
            mComplication = smartSpaceComplication;
            mSmartSpaceController = smartSpaceController;
        }

        @Override
        public void start() {
            mDreamOverlayStateController.addCallback(new DreamOverlayStateController.Callback() {
                @Override
                public void onStateChanged() {
                    if (mDreamOverlayStateController.isOverlayActive()) {
                        mSmartSpaceController.addListener(mSmartspaceListener);
                    } else {
                        mSmartSpaceController.removeListener(mSmartspaceListener);
                        mDreamOverlayStateController.removeComplication(mComplication);
                    }
                }
            });
        }
    }

    static class SmartSpaceComplicationViewHolder implements ViewHolder {
        private View mView = null;
        private final DreamSmartspaceController mSmartSpaceController;
        private final Context mContext;
        private final ComplicationLayoutParams mLayoutParams;

        @Inject
        protected SmartSpaceComplicationViewHolder(
                Context context,
                DreamSmartspaceController smartSpaceController,
                @Named(DREAM_SMARTSPACE_LAYOUT_PARAMS) ComplicationLayoutParams layoutParams) {
            mSmartSpaceController = smartSpaceController;
            mContext = context;
            mLayoutParams = layoutParams;
        }

        @Override
        public View getView() {
            if (mView != null) {
                return mView;
            }
            final FrameLayout smartSpaceContainer = new FrameLayout(mContext);
            smartSpaceContainer.addView(
                    mSmartSpaceController.buildAndConnectView(smartSpaceContainer),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));

            mView = smartSpaceContainer;
            return smartSpaceContainer;
        }

        @Override
        public ComplicationLayoutParams getLayoutParams() {
            return mLayoutParams;
        }
    }
}
