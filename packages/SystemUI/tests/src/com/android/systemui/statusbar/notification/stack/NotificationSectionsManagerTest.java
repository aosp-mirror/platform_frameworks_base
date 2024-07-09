/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.collection.render.MediaContainerController;
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationSectionsManagerTest extends SysuiTestCase {

    @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private NotificationStackScrollLayout mNssl;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private KeyguardMediaController mKeyguardMediaController;
    @Mock private NotificationSectionsFeatureManager mSectionsFeatureManager;
    @Mock private MediaContainerController mMediaContainerController;
    @Mock private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock private SectionHeaderController mIncomingHeaderController;
    @Mock private SectionHeaderController mPeopleHeaderController;
    @Mock private SectionHeaderController mAlertingHeaderController;
    @Mock private SectionHeaderController mSilentHeaderController;
    @Mock private SectionHeaderController mNewsHeaderController;
    @Mock private SectionHeaderController mSocialHeaderController;
    @Mock private SectionHeaderController mRecsHeaderController;
    @Mock private SectionHeaderController mPromoHeaderController;

    private NotificationSectionsManager mSectionsManager;

    @Before
    public void setUp() {
        when(mSectionsFeatureManager.getNumberOfBuckets()).thenAnswer(
                invocation -> {
                    int count = 2;
                    if (mSectionsFeatureManager.isFilteringEnabled()) {
                        count = 5;
                    }
                    if (mSectionsFeatureManager.isMediaControlsEnabled()) {
                        if (!mSectionsFeatureManager.isFilteringEnabled()) {
                            count = 5;
                        } else {
                            count += 1;
                        }
                    }
                    return count;
                });
        mSectionsManager =
                new NotificationSectionsManager(
                        mConfigurationController,
                        mKeyguardMediaController,
                        mSectionsFeatureManager,
                        mMediaContainerController,
                        mNotificationRoundnessManager,
                        mIncomingHeaderController,
                        mPeopleHeaderController,
                        mAlertingHeaderController,
                        mSilentHeaderController,
                        mNewsHeaderController,
                        mSocialHeaderController,
                        mRecsHeaderController,
                        mPromoHeaderController
                );
        // Required in order for the header inflation to work properly
        when(mNssl.generateLayoutParams(any(AttributeSet.class)))
                .thenReturn(new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        mSectionsManager.initialize(mNssl);
        when(mNssl.indexOfChild(any(View.class))).thenReturn(-1);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
    }

    @Test(expected =  IllegalStateException.class)
    public void testDuplicateInitializeThrows() {
        mSectionsManager.initialize(mNssl);
    }

}
