/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accessibilityservice;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * This class describes an {@link AccessibilityService}. The system
 * notifies an {@link AccessibilityService} for
 * {@link android.view.accessibility.AccessibilityEvent}s
 * according to the information encapsulated in this class.
 *
 * @see AccessibilityService
 * @see android.view.accessibility.AccessibilityEvent
 */
public class AccessibilityServiceInfo implements Parcelable {

    /**
     * Denotes spoken feedback.
     */
    public static final int FEEDBACK_SPOKEN = 0x0000001;

    /**
     * Denotes haptic feedback.
     */
    public static final int FEEDBACK_HAPTIC =  0x0000002;

    /**
     * Denotes audible (not spoken) feedback.
     */
    public static final int FEEDBACK_AUDIBLE = 0x0000004;

    /**
     * Denotes visual feedback.
     */
    public static final int FEEDBACK_VISUAL = 0x0000008;

    /**
     * Denotes generic feedback.
     */
    public static final int FEEDBACK_GENERIC = 0x0000010;

    /**
     * If an {@link AccessibilityService} is the default for a given type.
     * Default service is invoked only if no package specific one exists. In case of
     * more than one package specific service only the earlier registered is notified.
     */
    public static final int DEFAULT = 0x0000001;

    /**
     * The event types an {@link AccessibilityService} is interested in.
     *
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_CLICKED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_LONG_CLICKED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_FOCUSED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_SELECTED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_VIEW_TEXT_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED
     * @see android.view.accessibility.AccessibilityEvent#TYPE_NOTIFICATION_STATE_CHANGED
     */
    public int eventTypes;

    /**
     * The package names an {@link AccessibilityService} is interested in. Setting
     * to null is equivalent to all packages. 
     */
    public String[] packageNames;

    /**
     * The feedback type an {@link AccessibilityService} provides.
     *
     * @see #FEEDBACK_AUDIBLE
     * @see #FEEDBACK_GENERIC
     * @see #FEEDBACK_HAPTIC
     * @see #FEEDBACK_SPOKEN
     * @see #FEEDBACK_VISUAL
     */
    public int feedbackType;

    /**
     * The timeout after the most recent event of a given type before an
     * {@link AccessibilityService} is notified.
     * <p>
     * Note: The event notification timeout is useful to avoid propagating events to the client
     *       too frequently since this is accomplished via an expensive interprocess call.
     *       One can think of the timeout as a criteria to determine when event generation has
     *       settled down
     */
    public long notificationTimeout;

    /**
     * This field represents a set of flags used for configuring an
     * {@link AccessibilityService}.
     *
     * @see #DEFAULT
     */
    public int flags;

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flagz) {
        parcel.writeInt(eventTypes);
        parcel.writeStringArray(packageNames);
        parcel.writeInt(feedbackType);
        parcel.writeLong(notificationTimeout);
        parcel.writeInt(flags);
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<AccessibilityServiceInfo> CREATOR =
            new Parcelable.Creator<AccessibilityServiceInfo>() {
        public AccessibilityServiceInfo createFromParcel(Parcel parcel) {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = parcel.readInt();
            info.packageNames = parcel.readStringArray();
            info.feedbackType = parcel.readInt();
            info.notificationTimeout = parcel.readLong();
            info.flags = parcel.readInt();
            return info;
        }

        public AccessibilityServiceInfo[] newArray(int size) {
            return new AccessibilityServiceInfo[size];
        }
    };
}
