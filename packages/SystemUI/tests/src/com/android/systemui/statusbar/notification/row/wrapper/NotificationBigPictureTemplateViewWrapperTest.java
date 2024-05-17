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

package com.android.systemui.statusbar.notification.row.wrapper;

import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.widget.BigPictureNotificationImageView;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationBigPictureTemplateViewWrapperTest extends SysuiTestCase {

    private View mView;
    private ExpandableNotificationRow mRow;


    @Before
    public void setup() throws Exception {
        NotificationTestHelper helper = new NotificationTestHelper(mContext, mDependency);
        mView = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.notification_template_material_big_picture, null);
        mRow = helper.createRow();
    }

    @Test
    public void invalidLargeIconBig_noCrash() {
        NotificationViewWrapper wrapper = new NotificationBigPictureTemplateViewWrapper(
                mContext, mView, mRow);
        // should be Icon.class
        mRow.getEntry().getSbn().getNotification().setSmallIcon(
                Icon.createWithResource(mContext, 0));
        mRow.getEntry().getSbn().getNotification().extras.putParcelable(
                Notification.EXTRA_LARGE_ICON_BIG, new Bundle());
        wrapper.onContentUpdated(mRow);
    }

    @Test
    public void setAnimationsRunning_Run() {
        BigPictureNotificationImageView imageView = mView.findViewById(R.id.big_picture);
        AnimatedImageDrawable mockDrawable = mock(AnimatedImageDrawable.class);

        assertNotNull(imageView);
        imageView.setImageDrawable(mockDrawable);

        NotificationViewWrapper wrapper = new NotificationBigPictureTemplateViewWrapper(mContext,
                mView, mRow);
        // Required to re-initialize the imageView to the imageView created above.
        wrapper.onContentUpdated(mRow);

        wrapper.setAnimationsRunning(true);
        verify(mockDrawable).start();
    }

    @Test
    public void setAnimationsRunning_Stop() {
        BigPictureNotificationImageView imageView = mView.findViewById(R.id.big_picture);
        AnimatedImageDrawable mockDrawable = mock(AnimatedImageDrawable.class);

        assertNotNull(imageView);
        imageView.setImageDrawable(mockDrawable);

        NotificationViewWrapper wrapper = new NotificationBigPictureTemplateViewWrapper(mContext,
                mView, mRow);
        // Required to re-initialize the imageView to the imageView created above.
        wrapper.onContentUpdated(mRow);

        wrapper.setAnimationsRunning(false);
        verify(mockDrawable).stop();
    }
}
