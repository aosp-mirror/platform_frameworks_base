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

import static junit.framework.Assert.assertTrue;

import android.app.Notification;
import android.app.Notification.Builder;
import android.content.Context;

import com.android.documentsui.R;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

public class TestJob extends Job {

    private boolean mStarted;

    TestJob(
            Context service, Context appContext, Listener listener,
            int operationType, String id, DocumentStack stack) {
        super(service, appContext, listener, operationType, id, stack);
    }

    @Override
    void start() {
        mStarted = true;
    }

    void assertStarted() {
        assertTrue(mStarted);
    }

    void fail(DocumentInfo doc) {
        onFileFailed(doc);
    }

    @Override
    Notification getSetupNotification() {
        return getSetupNotification(service.getString(R.string.copy_preparing));
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
        // the "copy" stuff was just convenient and available :)
        return super.createProgressBuilder(
                service.getString(R.string.copy_notification_title),
                R.drawable.ic_menu_copy,
                service.getString(android.R.string.cancel),
                R.drawable.ic_cab_cancel);
    }
}
