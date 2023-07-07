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

package android.view.accessibility;

import android.annotation.NonNull;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.WindowManager;

import java.util.Objects;

/**
 * This class represents the attributes of a window needed for {@link AccessibilityWindowInfo}.
 *
 * @hide
 */
public final class AccessibilityWindowAttributes implements Parcelable {

    private final CharSequence mWindowTitle;
    private final LocaleList mLocales;

    public AccessibilityWindowAttributes(@NonNull WindowManager.LayoutParams layoutParams,
            @NonNull LocaleList locales) {
        mWindowTitle = populateWindowTitle(layoutParams);
        mLocales = locales;
    }

    private AccessibilityWindowAttributes(Parcel in) {
        mWindowTitle = in.readCharSequence();
        LocaleList inLocales = in.readParcelable(null, LocaleList.class);
        if (inLocales != null) {
            mLocales = inLocales;
        } else {
            mLocales = LocaleList.getEmptyLocaleList();
        }
    }

    public CharSequence getWindowTitle() {
        return mWindowTitle;
    }

    private CharSequence populateWindowTitle(@NonNull WindowManager.LayoutParams layoutParams) {
        CharSequence windowTitle = layoutParams.accessibilityTitle;
        // Panel windows have no public way to set the a11y title directly. Use the
        // regular title as a fallback.
        final boolean isPanelWindow =
                (layoutParams.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW)
                        && (layoutParams.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW);
        // Accessibility overlays should have titles that work for accessibility, and can't set
        // the a11y title themselves.
        final boolean isAccessibilityOverlay =
                layoutParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

        if (TextUtils.isEmpty(windowTitle) && (isPanelWindow
                || isAccessibilityOverlay)) {
            windowTitle = TextUtils.isEmpty(layoutParams.getTitle()) ? null
                    : layoutParams.getTitle();
        }
        return  windowTitle;
    }

    public @NonNull LocaleList getLocales() {
        return mLocales;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AccessibilityWindowAttributes)) return false;

        AccessibilityWindowAttributes that = (AccessibilityWindowAttributes) o;

        return TextUtils.equals(mWindowTitle, that.mWindowTitle) && Objects.equals(
                mLocales, that.mLocales);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWindowTitle, mLocales);
    }

    public static final Creator<AccessibilityWindowAttributes> CREATOR =
            new Creator<AccessibilityWindowAttributes>() {
                @Override
                public AccessibilityWindowAttributes createFromParcel(Parcel in) {
                    return new AccessibilityWindowAttributes(in);
                }

                @Override
                public AccessibilityWindowAttributes[] newArray(int size) {
                    return new AccessibilityWindowAttributes[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeCharSequence(mWindowTitle);
        parcel.writeParcelable(mLocales, flags);
    }

    @Override
    public String toString() {
        return "AccessibilityWindowAttributes{"
                + "mAccessibilityWindowTitle=" + mWindowTitle
                + "mLocales=" + mLocales
                + '}';
    }
}
