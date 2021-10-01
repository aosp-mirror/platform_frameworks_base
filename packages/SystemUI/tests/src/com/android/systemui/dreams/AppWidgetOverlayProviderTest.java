/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.appwidget.AppWidgetHostView;
import android.content.ComponentName;
import android.testing.AndroidTestingRunner;
import android.widget.RemoteViews;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.dreams.appwidgets.AppWidgetOverlayProvider;
import com.android.systemui.dreams.appwidgets.AppWidgetProvider;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AppWidgetOverlayProviderTest extends SysuiTestCase {
    @Mock
    ActivityStarter mActivityStarter;

    @Mock
    ComponentName mComponentName;

    @Mock
    AppWidgetProvider mAppWidgetProvider;

    @Mock
    AppWidgetHostView mAppWidgetHostView;

    @Mock
    OverlayHost.CreationCallback mCreationCallback;

    @Mock
    OverlayHost.InteractionCallback mInteractionCallback;

    @Mock
    PendingIntent mPendingIntent;

    @Mock
    RemoteViews.RemoteResponse mRemoteResponse;

    AppWidgetOverlayProvider mOverlayProvider;

    RemoteViews.InteractionHandler mInteractionHandler;

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Rule
    public SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), mLeakCheck);

    OverlayHostView.LayoutParams mLayoutParams = new OverlayHostView.LayoutParams(
            OverlayHostView.LayoutParams.MATCH_PARENT, OverlayHostView.LayoutParams.MATCH_PARENT);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mPendingIntent.isActivity()).thenReturn(true);
        when(mAppWidgetProvider.getWidget(mComponentName)).thenReturn(mAppWidgetHostView);

        mOverlayProvider = new AppWidgetOverlayProvider(
                mActivityStarter,
                mComponentName,
                mAppWidgetProvider,
                mLayoutParams
        );

        final ArgumentCaptor<RemoteViews.InteractionHandler> creationCallbackCapture =
                ArgumentCaptor.forClass(RemoteViews.InteractionHandler.class);

        mOverlayProvider.onCreateOverlay(mContext, mCreationCallback, mInteractionCallback);
        verify(mAppWidgetHostView, times(1))
                .setInteractionHandler(creationCallbackCapture.capture());
        mInteractionHandler = creationCallbackCapture.getValue();
    }


    @Test
    public void testWidgetBringup() {
        // Make sure widget was requested.
        verify(mAppWidgetProvider, times(1)).getWidget(eq(mComponentName));

        // Make sure widget was returned to callback.
        verify(mCreationCallback, times(1)).onCreated(eq(mAppWidgetHostView),
                eq(mLayoutParams));
    }

    @Test
    public void testWidgetInteraction() {
        // Trigger interaction.
        mInteractionHandler.onInteraction(mAppWidgetHostView, mPendingIntent,
                mRemoteResponse);

        // Ensure activity is started.
        verify(mActivityStarter, times(1))
                .startPendingIntentDismissingKeyguard(eq(mPendingIntent), isNull(),
                        eq(mAppWidgetHostView));
        // Verify exit is requested.
        verify(mInteractionCallback, times(1)).onExit();
    }
}
