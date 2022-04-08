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
package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.AutofillClient;

import java.io.PrintWriter;

/**
 * Autofill options for a given package.
 *
 * <p>This object is created by the Autofill System Service and passed back to the app when the
 * application is created.
 *
 * @hide
 */
@TestApi
public final class AutofillOptions implements Parcelable {

    private static final String TAG = AutofillOptions.class.getSimpleName();

    /**
     * Logging level for {@code logcat} statements.
     */
    public final int loggingLevel;

    /**
     * Whether compatibility mode is enabled for the package.
     */
    public final boolean compatModeEnabled;

    /**
     * Whether package is whitelisted for augmented autofill.
     */
    public boolean augmentedAutofillEnabled;

    /**
     * List of whitelisted activities.
     */
    @Nullable
    public ArraySet<ComponentName> whitelistedActivitiesForAugmentedAutofill;

    /**
     * The package disable expiration by autofill service.
     */
    public long appDisabledExpiration;

    /**
     * The disabled Activities of the package. key is component name string, value is when they
     * will be enabled.
     */
    @Nullable
    public ArrayMap<String, Long> disabledActivities;

    public AutofillOptions(int loggingLevel, boolean compatModeEnabled) {
        this.loggingLevel = loggingLevel;
        this.compatModeEnabled = compatModeEnabled;
    }

    /**
     * Returns whether activity is whitelisted for augmented autofill.
     */
    public boolean isAugmentedAutofillEnabled(@NonNull Context context) {
        if (!augmentedAutofillEnabled) return false;

        final AutofillClient autofillClient = context.getAutofillClient();
        if (autofillClient == null) return false;

        final ComponentName component = autofillClient.autofillClientGetComponentName();
        return whitelistedActivitiesForAugmentedAutofill == null
                || whitelistedActivitiesForAugmentedAutofill.contains(component);
    }

    /**
     * Returns if autofill is disabled by service to the given activity.
     *
     * @hide
     */
    public boolean isAutofillDisabledLocked(@NonNull ComponentName componentName) {
        final long elapsedTime = SystemClock.elapsedRealtime();
        final String component = componentName.flattenToString();
        // Check app first.
        if (appDisabledExpiration >= elapsedTime) return true;

        // Then check activities.
        if (disabledActivities != null) {
            final Long expiration = disabledActivities.get(component);
            if (expiration != null) {
                if (expiration >= elapsedTime) return true;
                disabledActivities.remove(component);
            }
        }
        appDisabledExpiration = 0;
        return false;
    }

    /**
     * @hide
     */
    @TestApi
    public static AutofillOptions forWhitelistingItself() {
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at == null) {
            throw new IllegalStateException("No ActivityThread");
        }

        final String packageName = at.getApplication().getPackageName();

        if (!"android.autofillservice.cts".equals(packageName)) {
            Log.e(TAG, "forWhitelistingItself(): called by " + packageName);
            throw new SecurityException("Thou shall not pass!");
        }

        final AutofillOptions options = new AutofillOptions(
                AutofillManager.FLAG_ADD_CLIENT_VERBOSE, /* compatModeAllowed= */ true);
        options.augmentedAutofillEnabled = true;
        // Always log, as it's used by test only
        Log.i(TAG, "forWhitelistingItself(" + packageName + "): " + options);

        return options;
    }

    @Override
    public String toString() {
        return "AutofillOptions [loggingLevel=" + loggingLevel + ", compatMode=" + compatModeEnabled
                + ", augmentedAutofillEnabled=" + augmentedAutofillEnabled
                + ", appDisabledExpiration=" + appDisabledExpiration + "]";
    }

    /** @hide */
    public void dumpShort(@NonNull PrintWriter pw) {
        pw.print("logLvl="); pw.print(loggingLevel);
        pw.print(", compatMode="); pw.print(compatModeEnabled);
        pw.print(", augmented="); pw.print(augmentedAutofillEnabled);
        if (whitelistedActivitiesForAugmentedAutofill != null) {
            pw.print(", whitelistedActivitiesForAugmentedAutofill=");
            pw.print(whitelistedActivitiesForAugmentedAutofill);
        }
        pw.print(", appDisabledExpiration="); pw.print(appDisabledExpiration);
        if (disabledActivities != null) {
            pw.print(", disabledActivities=");
            pw.print(disabledActivities);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(loggingLevel);
        parcel.writeBoolean(compatModeEnabled);
        parcel.writeBoolean(augmentedAutofillEnabled);
        parcel.writeArraySet(whitelistedActivitiesForAugmentedAutofill);
        parcel.writeLong(appDisabledExpiration);
        final int size = disabledActivities != null ? disabledActivities.size() : 0;
        parcel.writeInt(size);
        if (size > 0) {
            for (int i = 0; i < size; i++) {
                final String key = disabledActivities.keyAt(i);
                parcel.writeString(key);
                parcel.writeLong(disabledActivities.get(key));
            }
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<AutofillOptions> CREATOR =
            new Parcelable.Creator<AutofillOptions>() {

                @Override
                public AutofillOptions createFromParcel(Parcel parcel) {
                    final int loggingLevel = parcel.readInt();
                    final boolean compatMode = parcel.readBoolean();
                    final AutofillOptions options = new AutofillOptions(loggingLevel, compatMode);
                    options.augmentedAutofillEnabled = parcel.readBoolean();
                    options.whitelistedActivitiesForAugmentedAutofill =
                            (ArraySet<ComponentName>) parcel.readArraySet(null);
                    options.appDisabledExpiration = parcel.readLong();
                    final int size = parcel.readInt();
                    if (size > 0) {
                        options.disabledActivities = new ArrayMap<>();
                        for (int i = 0; i < size; i++) {
                            options.disabledActivities.put(parcel.readString(), parcel.readLong());
                        }
                    }
                    return options;
                }

                @Override
                public AutofillOptions[] newArray(int size) {
                    return new AutofillOptions[size];
                }
    };
}
