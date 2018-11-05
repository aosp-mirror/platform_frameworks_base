/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.intelligence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;

import com.android.internal.util.Preconditions;

import java.util.Set;

/**
 * TODO(b/111276913): add javadocs / implement / add SystemService / PackageFeature
 */
public final class IntelligenceManager {

    /**
     * Used to indicate that a text change was caused by user input (for example, through IME).
     */
    //TODO(b/111276913): link to notifyTextChanged() method once available
    public static final int FLAG_USER_INPUT = 0x1;

    private final Context mContext;

    /** @hide */
    public IntelligenceManager(@NonNull Context context) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
    }

    /**
     * Returns the component name of the {@code android.service.intelligence.IntelligenceService}
     * that is enabled for the current user.
     */
    @Nullable
    public ComponentName getIntelligenceServiceComponentName() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Checks whether contents capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        //TODO(b/111276913): implement
        return false;
    }

    /**
     * Called by apps to disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void disableContentCapture() {
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities with such
     * {@link android.content.ComponentName}.
     *
     * <p>Useful to blacklist a particular activity.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setActivityContentCaptureEnabled(@NonNull ComponentName activity,
            boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities of the app with such
     * {@code packageName}.
     *
     * <p>Useful to blacklist any activity from a particular app.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setPackageContentCaptureEnabled(@NonNull String packageName, boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Gets the activities where content capture was disabled by
     * {@link #setActivityContentCaptureEnabled(ComponentName, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<ComponentName> getContentCaptureDisabledActivities() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Gets the apps where content capture was disabled by
     * {@link #setPackageContentCaptureEnabled(String, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<String> getContentCaptureDisabledPackages() {
        //TODO(b/111276913): implement
        return null;
    }
}
