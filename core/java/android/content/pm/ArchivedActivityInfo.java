/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Slog;

import com.android.internal.util.DataClass;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * Contains fields required to show archived package in Launcher.
 * @see ArchivedPackageInfo
 */
@DataClass(genBuilder = false, genConstructor = false, genSetters = true)
@FlaggedApi(Flags.FLAG_ARCHIVING)
public final class ArchivedActivityInfo {
    private static final String TAG = "ArchivedActivityInfo";
    /** The label for the activity. */
    private @NonNull CharSequence mLabel;
    /** The component name of this activity. */
    private @NonNull ComponentName mComponentName;
    /**
     * Icon of the activity in the app's locale. if null then the default icon would be shown in the
     * launcher.
     */
    private @Nullable Drawable mIcon;
    /** Monochrome icon, if defined, of the activity. */
    private @Nullable Drawable mMonochromeIcon;

    public ArchivedActivityInfo(@NonNull CharSequence label, @NonNull ComponentName componentName) {
        Objects.requireNonNull(label);
        Objects.requireNonNull(componentName);
        mLabel = label;
        mComponentName = componentName;
    }

    /* @hide */
    ArchivedActivityInfo(@NonNull ArchivedActivityParcel parcel) {
        mLabel = parcel.title;
        mComponentName = parcel.originalComponentName;
        mIcon = drawableFromCompressedBitmap(parcel.iconBitmap);
        mMonochromeIcon = drawableFromCompressedBitmap(parcel.monochromeIconBitmap);
    }

    /* @hide */
    @NonNull ArchivedActivityParcel getParcel() {
        var parcel = new ArchivedActivityParcel();
        parcel.title = mLabel.toString();
        parcel.originalComponentName = mComponentName;
        parcel.iconBitmap = mIcon == null ? null :
                bytesFromBitmap(drawableToBitmap(mIcon));
        parcel.monochromeIconBitmap = mMonochromeIcon == null ? null :
                bytesFromBitmap(drawableToBitmap(mMonochromeIcon));
        return parcel;
    }

    /**
     * Convert a generic drawable into a bitmap.
     * @hide
     */
    public static Bitmap drawableToBitmap(Drawable drawable) {
        return drawableToBitmap(drawable, /* iconSize= */ 0);
    }

    /**
     * Same as above, but scale the resulting image to fit iconSize.
     * @hide
     */
    public static Bitmap drawableToBitmap(Drawable drawable, int iconSize) {
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            if (drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
                // Needed for drawables that are just a single color.
                bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            } else {
                bitmap =
                    Bitmap.createBitmap(
                        drawable.getIntrinsicWidth(),
                        drawable.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
            }

            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
        }
        if (iconSize <= 0) {
            return bitmap;
        }

        if (bitmap.getWidth() < iconSize || bitmap.getHeight() < iconSize
                || bitmap.getWidth() > iconSize * 2 || bitmap.getHeight() > iconSize * 2) {
            var scaledBitmap = Bitmap.createScaledBitmap(bitmap, iconSize, iconSize, true);
            if (scaledBitmap != bitmap) {
                bitmap.recycle();
            }
            return scaledBitmap;
        }
        return bitmap;
    }

    /**
     * Compress bitmap to PNG format.
     * @hide
     */
    public static byte[] bytesFromBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(
                bitmap.getByteCount())) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to compress bitmap", e);
            return null;
        }
    }

    private static Drawable drawableFromCompressedBitmap(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new BitmapDrawable(null /*res*/, new ByteArrayInputStream(bytes));
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/ArchivedActivityInfo.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * The label for the activity.
     */
    @DataClass.Generated.Member
    public @NonNull CharSequence getLabel() {
        return mLabel;
    }

    /**
     * The component name of this activity.
     */
    @DataClass.Generated.Member
    public @NonNull ComponentName getComponentName() {
        return mComponentName;
    }

    /**
     * Icon of the activity in the app's locale. if null then the default icon would be shown in the
     * launcher.
     */
    @DataClass.Generated.Member
    public @Nullable Drawable getIcon() {
        return mIcon;
    }

    /**
     * Monochrome icon, if defined, of the activity.
     */
    @DataClass.Generated.Member
    public @Nullable Drawable getMonochromeIcon() {
        return mMonochromeIcon;
    }

    /**
     * The label for the activity.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedActivityInfo setLabel(@NonNull CharSequence value) {
        mLabel = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mLabel);
        return this;
    }

    /**
     * The component name of this activity.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedActivityInfo setComponentName(@NonNull ComponentName value) {
        mComponentName = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mComponentName);
        return this;
    }

    /**
     * Icon of the activity in the app's locale. if null then the default icon would be shown in the
     * launcher.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedActivityInfo setIcon(@NonNull Drawable value) {
        mIcon = value;
        return this;
    }

    /**
     * Monochrome icon, if defined, of the activity.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedActivityInfo setMonochromeIcon(@NonNull Drawable value) {
        mMonochromeIcon = value;
        return this;
    }

    @DataClass.Generated(
            time = 1708042076897L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/ArchivedActivityInfo.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate @android.annotation.NonNull java.lang.CharSequence mLabel\nprivate @android.annotation.NonNull android.content.ComponentName mComponentName\nprivate @android.annotation.Nullable android.graphics.drawable.Drawable mIcon\nprivate @android.annotation.Nullable android.graphics.drawable.Drawable mMonochromeIcon\n @android.annotation.NonNull android.content.pm.ArchivedActivityParcel getParcel()\npublic static  android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable)\npublic static  android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable,int)\npublic static  byte[] bytesFromBitmap(android.graphics.Bitmap)\nprivate static  android.graphics.drawable.Drawable drawableFromCompressedBitmap(byte[])\nclass ArchivedActivityInfo extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genBuilder=false, genConstructor=false, genSetters=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
