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

import static com.android.systemui.dreams.complication.dagger.DreamMediaEntryComplicationComponent.DreamMediaEntryModule.DREAM_MEDIA_ENTRY_VIEW;
import static com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule.DREAM_MEDIA_ENTRY_LAYOUT_PARAMS;
import static com.android.systemui.flags.Flags.DREAM_MEDIA_TAP_TO_OPEN;

import android.app.PendingIntent;
import android.util.Log;
import android.view.View;

import com.android.systemui.ActivityIntentHelper;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.dagger.DreamMediaEntryComplicationComponent;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.MediaCarouselController;
import com.android.systemui.media.dream.MediaDreamComplication;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A dream complication that shows a media entry chip to launch media control view.
 */
public class DreamMediaEntryComplication implements Complication {
    private final DreamMediaEntryComplicationComponent.Factory mComponentFactory;

    @Inject
    public DreamMediaEntryComplication(
            DreamMediaEntryComplicationComponent.Factory componentFactory) {
        mComponentFactory = componentFactory;
    }

    @Override
    public ViewHolder createView(ComplicationViewModel model) {
        return mComponentFactory.create().getViewHolder();
    }

    /**
     * Contains values/logic associated with the dream complication view.
     */
    public static class DreamMediaEntryViewHolder implements ViewHolder {
        private final View mView;
        private final ComplicationLayoutParams mLayoutParams;
        private final DreamMediaEntryViewController mViewController;

        @Inject
        DreamMediaEntryViewHolder(
                DreamMediaEntryViewController dreamMediaEntryViewController,
                @Named(DREAM_MEDIA_ENTRY_VIEW) View view,
                @Named(DREAM_MEDIA_ENTRY_LAYOUT_PARAMS) ComplicationLayoutParams layoutParams
        ) {
            mView = view;
            mLayoutParams = layoutParams;
            mViewController = dreamMediaEntryViewController;
            mViewController.init();
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

    /**
     * Controls behavior of the dream complication.
     */
    static class DreamMediaEntryViewController extends ViewController<View> {
        private static final String TAG = "DreamMediaEntryVwCtrl";
        private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

        private final DreamOverlayStateController mDreamOverlayStateController;
        private final MediaDreamComplication mMediaComplication;
        private final MediaCarouselController mMediaCarouselController;

        private final ActivityStarter mActivityStarter;
        private final ActivityIntentHelper mActivityIntentHelper;
        private final KeyguardStateController mKeyguardStateController;
        private final NotificationLockscreenUserManager mLockscreenUserManager;

        private final FeatureFlags mFeatureFlags;
        private boolean mIsTapToOpenEnabled;

        private boolean mMediaComplicationAdded;

        @Inject
        DreamMediaEntryViewController(
                @Named(DREAM_MEDIA_ENTRY_VIEW) View view,
                DreamOverlayStateController dreamOverlayStateController,
                MediaDreamComplication mediaComplication,
                MediaCarouselController mediaCarouselController,
                ActivityStarter activityStarter,
                ActivityIntentHelper activityIntentHelper,
                KeyguardStateController keyguardStateController,
                NotificationLockscreenUserManager lockscreenUserManager,
                FeatureFlags featureFlags) {
            super(view);
            mDreamOverlayStateController = dreamOverlayStateController;
            mMediaComplication = mediaComplication;
            mMediaCarouselController = mediaCarouselController;
            mActivityStarter = activityStarter;
            mActivityIntentHelper = activityIntentHelper;
            mKeyguardStateController = keyguardStateController;
            mLockscreenUserManager = lockscreenUserManager;
            mFeatureFlags = featureFlags;
            mView.setOnClickListener(this::onClickMediaEntry);
        }

        @Override
        protected void onViewAttached() {
            mIsTapToOpenEnabled = mFeatureFlags.isEnabled(DREAM_MEDIA_TAP_TO_OPEN);
        }

        @Override
        protected void onViewDetached() {
            removeMediaComplication();
        }

        private void onClickMediaEntry(View v) {
            if (DEBUG) Log.d(TAG, "media entry complication tapped");

            if (mIsTapToOpenEnabled) {
                final PendingIntent clickIntent =
                        mMediaCarouselController.getCurrentVisibleMediaContentIntent();

                if (clickIntent == null) {
                    return;
                }

                // See StatusBarNotificationActivityStarter#onNotificationClicked
                final boolean showOverLockscreen = mKeyguardStateController.isShowing()
                        && mActivityIntentHelper.wouldShowOverLockscreen(clickIntent.getIntent(),
                        mLockscreenUserManager.getCurrentUserId());

                if (showOverLockscreen) {
                    mActivityStarter.startActivity(clickIntent.getIntent(),
                            /* dismissShade */ true,
                            /* animationController */ null,
                            /* showOverLockscreenWhenLocked */ true);
                } else {
                    mActivityStarter.postStartActivityDismissingKeyguard(clickIntent, null);
                }

                return;
            }

            if (!mMediaComplicationAdded) {
                addMediaComplication();
            } else {
                removeMediaComplication();
            }
        }

        private void addMediaComplication() {
            mView.setSelected(true);
            mDreamOverlayStateController.addComplication(mMediaComplication);
            mMediaComplicationAdded = true;
        }

        private void removeMediaComplication() {
            mView.setSelected(false);
            mDreamOverlayStateController.removeComplication(mMediaComplication);
            mMediaComplicationAdded = false;
        }
    }
}
