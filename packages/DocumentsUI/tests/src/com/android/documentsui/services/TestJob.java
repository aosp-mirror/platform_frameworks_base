/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.services;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.app.Notification;
import android.app.Notification.Builder;
import android.content.Context;

import com.android.documentsui.clipping.UrisSupplier;
import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

import java.text.NumberFormat;

public class TestJob extends Job {

    private boolean mStarted;
    private Runnable mStartRunnable;

    private int mNumOfNotifications = 0;

    TestJob(
            Context service, Listener listener, String id,
            @OpType int opType, DocumentStack stack, UrisSupplier srcs, Runnable startRunnable) {
        super(service, listener, id, opType, stack, srcs);

        mStartRunnable = startRunnable;
    }

    @Override
    void start() {
        mStarted = true;

        mStartRunnable.run();
    }

    void assertStarted() {
        assertTrue(mStarted);
    }

    void assertNotStarted() {
        assertFalse(mStarted);
    }

    void fail(DocumentInfo doc) {
        onFileFailed(doc);
    }

    int getNumOfNotifications() {
        return mNumOfNotifications;
    }

    @Override
    Notification getSetupNotification() {
        ++mNumOfNotifications;
        return getSetupNotification(service.getString(R.string.copy_preparing));
    }

    @Override
    Notification getProgressNotification() {
        ++mNumOfNotifications;
        double completed = mStarted ? 1F : 0F;
        return mProgressBuilder
                .setProgress(1, (int) completed, true)
                .setSubText(NumberFormat.getPercentInstance().format(completed))
                .build();
    }

    @Override
    Notification getFailureNotification() {
        // the "copy" stuff was just convenient and available :)
        return getFailureNotification(
                R.plurals.copy_error_notification_title, R.drawable.ic_menu_copy);
    }

    @Override
    Notification getWarningNotification() {
        throw new UnsupportedOperationException();
    }

    @Override
    Builder createProgressBuilder() {
        ++mNumOfNotifications;
        // the "copy" stuff was just convenient and available :)
        return super.createProgressBuilder(
                service.getString(R.string.copy_notification_title),
                R.drawable.ic_menu_copy,
                service.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }
}
