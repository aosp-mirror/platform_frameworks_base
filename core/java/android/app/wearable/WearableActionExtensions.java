/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.wearable;

import android.app.Notification;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wearable extensions to notification actions. To add extensions to an action,
 * create a new {@link WearableActionExtensions} object using
 * {@link WearableActionExtensions.Builder} and apply it to a
 * {@link android.app.Notification.Action.Builder}.
 *
 * <pre class="prettyprint">
 * Notification.Action action = new Notification.Action.Builder(
 *         R.drawable.archive_all, "Archive all", actionIntent)
 *         .apply(new WearableActionExtensions.Builder()
 *                 .setAvailableOffline(false)
 *                 .build())
 *         .build();
 * </pre>
 */
public final class WearableActionExtensions implements Notification.Action.Builder.Extender,
        Parcelable {
    /** Notification action extra which contains wearable extensions */
    static final String EXTRA_WEARABLE_EXTENSIONS = "android.wearable.EXTENSIONS";

    // Flags bitwise-ored to mFlags
    static final int FLAG_AVAILABLE_OFFLINE = 1 << 0;

    // Default value for flags integer
    static final int DEFAULT_FLAGS = FLAG_AVAILABLE_OFFLINE;

    private final int mFlags;

    private WearableActionExtensions(int flags) {
        mFlags = flags;
    }

    private WearableActionExtensions(Parcel in) {
        mFlags = in.readInt();
    }

    /**
     * Create a {@link WearableActionExtensions} by reading wearable extensions present on an
     * existing notification action.
     * @param action the notification action to inspect.
     * @return a new {@link WearableActionExtensions} object.
     */
    public static WearableActionExtensions from(Notification.Action action) {
        WearableActionExtensions extensions = action.getExtras().getParcelable(
                EXTRA_WEARABLE_EXTENSIONS);
        if (extensions != null) {
            return extensions;
        } else {
            // Return a WearableActionExtensions with default values.
            return new Builder().build();
        }
    }

    /**
     * Get whether this action is available when the wearable device is not connected to
     * a companion device. The user can still trigger this action when the wearable device is
     * offline, but a visual hint will indicate that the action may not be available.
     * Defaults to true.
     */
    public boolean isAvailableOffline() {
        return (mFlags & FLAG_AVAILABLE_OFFLINE) != 0;
    }

    @Override
    public Notification.Action.Builder applyTo(Notification.Action.Builder builder) {
        builder.getExtras().putParcelable(EXTRA_WEARABLE_EXTENSIONS, this);
        return builder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mFlags);
    }

    /**
     * Builder for {@link WearableActionExtensions} objects, which adds wearable extensions to
     * notification actions. To extend an action, create an instance of this class, call the set
     * methods present, call {@link #build}, and finally apply the options to a
     * {@link Notification.Builder} using its {@link android.app.Notification.Builder#apply} method.
     */
    public static final class Builder {
        private int mFlags = DEFAULT_FLAGS;

        /**
         * Construct a builder to be used for adding wearable extensions to notification actions.
         *
         * <pre class="prettyprint">
         * Notification.Action action = new Notification.Action.Builder(
         *         R.drawable.archive_all, "Archive all", actionIntent)
         *         .apply(new WearableActionExtensions.Builder()
         *                 .setAvailableOffline(false)
         *                 .build())
         *         .build();</pre>
         */
        public Builder() {
        }

        /**
         * Create a {@link Builder} by reading wearable extensions present on an
         * existing {@code WearableActionExtensions} object.
         * @param other the existing extensions to inspect.
         */
        public Builder(WearableActionExtensions other) {
            mFlags = other.mFlags;
        }

        /**
         * Set whether this action is available when the wearable device is not connected to
         * a companion device. The user can still trigger this action when the wearable device is
         * offline, but a visual hint will indicate that the action may not be available.
         * Defaults to true.
         */
        public Builder setAvailableOffline(boolean availableOffline) {
            setFlag(FLAG_AVAILABLE_OFFLINE, availableOffline);
            return this;
        }

        /**
         * Build a new {@link WearableActionExtensions} object with the extensions
         * currently present on this builder.
         * @return the extensions object.
         */
        public WearableActionExtensions build() {
            return new WearableActionExtensions(mFlags);
        }

        private void setFlag(int mask, boolean value) {
            if (value) {
                mFlags |= mask;
            } else {
                mFlags &= ~mask;
            }
        }
    }
}
