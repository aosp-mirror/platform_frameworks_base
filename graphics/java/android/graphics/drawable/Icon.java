/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.graphics.drawable;

import static android.content.Context.CONTEXT_INCLUDE_CODE;
import static android.content.Context.CONTEXT_RESTRICTED;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.IUriGrantsManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * An umbrella container for several serializable graphics representations, including Bitmaps,
 * compressed bitmap images (e.g. JPG or PNG), and drawable resources (including vectors).
 *
 * <a href="https://developer.android.com/training/displaying-bitmaps/index.html">Much ink</a>
 * has been spilled on the best way to load images, and many clients may have different needs when
 * it comes to threading and fetching. This class is therefore focused on encapsulation rather than
 * behavior.
 */

public final class Icon implements Parcelable {
    private static final String TAG = "Icon";
    private static final boolean DEBUG = false;

    /**
     * An icon that was created using {@link Icon#createWithBitmap(Bitmap)}.
     * @see #getType
     */
    public static final int TYPE_BITMAP   = 1;
    /**
     * An icon that was created using {@link Icon#createWithResource}.
     * @see #getType
     */
    public static final int TYPE_RESOURCE = 2;
    /**
     * An icon that was created using {@link Icon#createWithData(byte[], int, int)}.
     * @see #getType
     */
    public static final int TYPE_DATA     = 3;
    /**
     * An icon that was created using {@link Icon#createWithContentUri}
     * or {@link Icon#createWithFilePath(String)}.
     * @see #getType
     */
    public static final int TYPE_URI      = 4;
    /**
     * An icon that was created using {@link Icon#createWithAdaptiveBitmap}.
     * @see #getType
     */
    public static final int TYPE_ADAPTIVE_BITMAP = 5;
    /**
     * An icon that was created using {@link Icon#createWithAdaptiveBitmapContentUri}.
     * @see #getType
     */
    public static final int TYPE_URI_ADAPTIVE_BITMAP = 6;

    /**
     * @hide
     */
    @IntDef({TYPE_BITMAP, TYPE_RESOURCE, TYPE_DATA, TYPE_URI, TYPE_ADAPTIVE_BITMAP,
            TYPE_URI_ADAPTIVE_BITMAP})
    @Retention(RetentionPolicy.SOURCE)
    public @interface IconType {
    }

    private static final int VERSION_STREAM_SERIALIZER = 1;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mType;

    private ColorStateList mTintList;
    static final BlendMode DEFAULT_BLEND_MODE = Drawable.DEFAULT_BLEND_MODE; // SRC_IN
    private BlendMode mBlendMode = Drawable.DEFAULT_BLEND_MODE;

    // To avoid adding unnecessary overhead, we have a few basic objects that get repurposed
    // based on the value of mType.

    // TYPE_BITMAP: Bitmap
    // TYPE_ADAPTIVE_BITMAP: Bitmap
    // TYPE_RESOURCE: Resources
    // TYPE_DATA: DataBytes
    private Object          mObj1;
    private boolean mCachedAshmem = false;

    // TYPE_RESOURCE: package name
    // TYPE_URI: uri string
    // TYPE_URI_ADAPTIVE_BITMAP: uri string
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private String          mString1;

    // TYPE_RESOURCE: resId
    // TYPE_DATA: data length
    private int             mInt1;

    // TYPE_DATA: data offset
    private int             mInt2;

    /**
     * Gets the type of the icon provided.
     * <p>
     * Note that new types may be added later, so callers should guard against other
     * types being returned.
     */
    @IconType
    public int getType() {
        return mType;
    }

    /**
     * @return The {@link android.graphics.Bitmap} held by this {@link #TYPE_BITMAP} or
     * {@link #TYPE_ADAPTIVE_BITMAP} Icon.
     *
     * Note that this will always return an immutable Bitmap.
     * @hide
     */
    @UnsupportedAppUsage
    public Bitmap getBitmap() {
        if (mType != TYPE_BITMAP && mType != TYPE_ADAPTIVE_BITMAP) {
            throw new IllegalStateException("called getBitmap() on " + this);
        }
        return (Bitmap) mObj1;
    }

    /**
     * Sets the Icon's contents to a particular Bitmap. Note that this may make a copy of the Bitmap
     * if the supplied Bitmap is mutable. In that case, the value returned by getBitmap() may not
     * equal the Bitmap passed to setBitmap().
     *
     * @hide
     */
    private void setBitmap(Bitmap b) {
        if (b.isMutable()) {
            mObj1 = b.copy(b.getConfig(), false);
        } else {
            mObj1 = b;
        }
        mCachedAshmem = false;
    }

    /**
     * @return The length of the compressed bitmap byte array held by this {@link #TYPE_DATA} Icon.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getDataLength() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataLength() on " + this);
        }
        synchronized (this) {
            return mInt1;
        }
    }

    /**
     * @return The offset into the byte array held by this {@link #TYPE_DATA} Icon at which
     * valid compressed bitmap data is found.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public int getDataOffset() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataOffset() on " + this);
        }
        synchronized (this) {
            return mInt2;
        }
    }

    /**
     * @return The byte array held by this {@link #TYPE_DATA} Icon ctonaining compressed
     * bitmap data.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public byte[] getDataBytes() {
        if (mType != TYPE_DATA) {
            throw new IllegalStateException("called getDataBytes() on " + this);
        }
        synchronized (this) {
            return (byte[]) mObj1;
        }
    }

    /**
     * @return The {@link android.content.res.Resources} for this {@link #TYPE_RESOURCE} Icon.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public Resources getResources() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResources() on " + this);
        }
        return (Resources) mObj1;
    }

    /**
     * Gets the package used to create this icon.
     * <p>
     * Only valid for icons of type {@link #TYPE_RESOURCE}.
     * Note: This package may not be available if referenced in the future, and it is
     * up to the caller to ensure safety if this package is re-used and/or persisted.
     */
    @NonNull
    public String getResPackage() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResPackage() on " + this);
        }
        return mString1;
    }

    /**
     * Gets the resource used to create this icon.
     * <p>
     * Only valid for icons of type {@link #TYPE_RESOURCE}.
     * Note: This resource may not be available if the application changes at all, and it is
     * up to the caller to ensure safety if this resource is re-used and/or persisted.
     */
    @DrawableRes
    public int getResId() {
        if (mType != TYPE_RESOURCE) {
            throw new IllegalStateException("called getResId() on " + this);
        }
        return mInt1;
    }

    /**
     * @return The URI (as a String) for this {@link #TYPE_URI} or {@link #TYPE_URI_ADAPTIVE_BITMAP}
     * Icon.
     * @hide
     */
    public String getUriString() {
        if (mType != TYPE_URI && mType != TYPE_URI_ADAPTIVE_BITMAP) {
            throw new IllegalStateException("called getUriString() on " + this);
        }
        return mString1;
    }

    /**
     * Gets the uri used to create this icon.
     * <p>
     * Only valid for icons of type {@link #TYPE_URI} and {@link #TYPE_URI_ADAPTIVE_BITMAP}.
     * Note: This uri may not be available in the future, and it is
     * up to the caller to ensure safety if this uri is re-used and/or persisted.
     */
    @NonNull
    public Uri getUri() {
        return Uri.parse(getUriString());
    }

    private static final String typeToString(int x) {
        switch (x) {
            case TYPE_BITMAP: return "BITMAP";
            case TYPE_ADAPTIVE_BITMAP: return "BITMAP_MASKABLE";
            case TYPE_DATA: return "DATA";
            case TYPE_RESOURCE: return "RESOURCE";
            case TYPE_URI: return "URI";
            case TYPE_URI_ADAPTIVE_BITMAP: return "URI_MASKABLE";
            default: return "UNKNOWN";
        }
    }

    /**
     * Invokes {@link #loadDrawable(Context)} on the given {@link android.os.Handler Handler}
     * and then sends <code>andThen</code> to the same Handler when finished.
     *
     * @param context {@link android.content.Context Context} in which to load the drawable; see
     *                {@link #loadDrawable(Context)}
     * @param andThen {@link android.os.Message} to send to its target once the drawable
     *                is available. The {@link android.os.Message#obj obj}
     *                property is populated with the Drawable.
     */
    public void loadDrawableAsync(@NonNull Context context, @NonNull Message andThen) {
        if (andThen.getTarget() == null) {
            throw new IllegalArgumentException("callback message must have a target handler");
        }
        new LoadDrawableTask(context, andThen).runAsync();
    }

    /**
     * Invokes {@link #loadDrawable(Context)} on a background thread and notifies the <code>
     * {@link OnDrawableLoadedListener#onDrawableLoaded listener} </code> on the {@code handler}
     * when finished.
     *
     * @param context {@link Context Context} in which to load the drawable; see
     *                {@link #loadDrawable(Context)}
     * @param listener to be {@link OnDrawableLoadedListener#onDrawableLoaded notified} when
     *                 {@link #loadDrawable(Context)} finished
     * @param handler {@link Handler} on which to notify the {@code listener}
     */
    public void loadDrawableAsync(@NonNull Context context, final OnDrawableLoadedListener listener,
            Handler handler) {
        new LoadDrawableTask(context, handler, listener).runAsync();
    }

    /**
     * Returns a Drawable that can be used to draw the image inside this Icon, constructing it
     * if necessary. Depending on the type of image, this may not be something you want to do on
     * the UI thread, so consider using
     * {@link #loadDrawableAsync(Context, Message) loadDrawableAsync} instead.
     *
     * @param context {@link android.content.Context Context} in which to load the drawable; used
     *                to access {@link android.content.res.Resources Resources}, for example.
     * @return A fresh instance of a drawable for this image, yours to keep.
     */
    public @Nullable Drawable loadDrawable(Context context) {
        final Drawable result = loadDrawableInner(context);
        if (result != null && hasTint()) {
            result.mutate();
            result.setTintList(mTintList);
            result.setTintBlendMode(mBlendMode);
        }
        return result;
    }

    /**
     * Resizes image if size too large for Canvas to draw
     * @param bitmap Bitmap to be resized if size > {@link RecordingCanvas.MAX_BITMAP_SIZE}
     * @return resized bitmap
     */
    private Bitmap fixMaxBitmapSize(Bitmap bitmap) {
        if (bitmap != null && bitmap.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE) {
            int bytesPerPixel = bitmap.getRowBytes() / bitmap.getWidth();
            int maxNumPixels = RecordingCanvas.MAX_BITMAP_SIZE / bytesPerPixel;
            float aspRatio = (float) bitmap.getWidth() / (float) bitmap.getHeight();
            int newHeight = (int) Math.sqrt(maxNumPixels / aspRatio);
            int newWidth = (int) (newHeight * aspRatio);

            if (DEBUG) {
                Log.d(TAG,
                        "Image size too large: " + bitmap.getByteCount() + ". Resizing bitmap to: "
                                + newWidth + " " + newHeight);
            }

            return scaleDownIfNecessary(bitmap, newWidth, newHeight);
        }
        return bitmap;
    }

    /**
     * Resizes BitmapDrawable if size too large for Canvas to draw
     * @param drawable Drawable to be resized if size > {@link RecordingCanvas.MAX_BITMAP_SIZE}
     * @return resized Drawable
     */
    private Drawable fixMaxBitmapSize(Resources res, Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap scaledBmp = fixMaxBitmapSize(((BitmapDrawable) drawable).getBitmap());
            return new BitmapDrawable(res, scaledBmp);
        }
        return drawable;
    }

    /**
     * Do the heavy lifting of loading the drawable, but stop short of applying any tint.
     */
    private Drawable loadDrawableInner(Context context) {
        switch (mType) {
            case TYPE_BITMAP:
                return new BitmapDrawable(context.getResources(), fixMaxBitmapSize(getBitmap()));
            case TYPE_ADAPTIVE_BITMAP:
                return new AdaptiveIconDrawable(null,
                    new BitmapDrawable(context.getResources(), fixMaxBitmapSize(getBitmap())));
            case TYPE_RESOURCE:
                if (getResources() == null) {
                    // figure out where to load resources from
                    String resPackage = getResPackage();
                    if (TextUtils.isEmpty(resPackage)) {
                        // if none is specified, try the given context
                        resPackage = context.getPackageName();
                    }
                    if ("android".equals(resPackage)) {
                        mObj1 = Resources.getSystem();
                    } else {
                        final PackageManager pm = context.getPackageManager();
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(
                                    resPackage,
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                                    | PackageManager.GET_SHARED_LIBRARY_FILES);
                            if (ai != null) {
                                mObj1 = pm.getResourcesForApplication(ai);
                            } else {
                                break;
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG, String.format("Unable to find pkg=%s for icon %s",
                                    resPackage, this), e);
                            break;
                        }
                    }
                }
                try {
                    return fixMaxBitmapSize(getResources(),
                            getResources().getDrawable(getResId(), context.getTheme()));
                } catch (RuntimeException e) {
                    Log.e(TAG, String.format("Unable to load resource 0x%08x from pkg=%s",
                                    getResId(),
                                    getResPackage()),
                            e);
                }
                break;
            case TYPE_DATA:
                return new BitmapDrawable(context.getResources(), fixMaxBitmapSize(
                        BitmapFactory.decodeByteArray(getDataBytes(), getDataOffset(),
                                getDataLength())));
            case TYPE_URI:
                InputStream is = getUriInputStream(context);
                if (is != null) {
                    return new BitmapDrawable(context.getResources(),
                            fixMaxBitmapSize(BitmapFactory.decodeStream(is)));
                }
                break;
            case TYPE_URI_ADAPTIVE_BITMAP:
                is = getUriInputStream(context);
                if (is != null) {
                    return new AdaptiveIconDrawable(null, new BitmapDrawable(context.getResources(),
                            fixMaxBitmapSize(BitmapFactory.decodeStream(is))));
                }
                break;
        }
        return null;
    }

    private @Nullable InputStream getUriInputStream(Context context) {
        final Uri uri = getUri();
        final String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme)) {
            try {
                return context.getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                Log.w(TAG, "Unable to load image from URI: " + uri, e);
            }
        } else {
            try {
                return new FileInputStream(new File(mString1));
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Unable to load image from path: " + uri, e);
            }
        }
        return null;
    }

    /**
     * Load the requested resources under the given userId, if the system allows it,
     * before actually loading the drawable.
     *
     * @hide
     */
    public Drawable loadDrawableAsUser(Context context, int userId) {
        if (mType == TYPE_RESOURCE) {
            String resPackage = getResPackage();
            if (TextUtils.isEmpty(resPackage)) {
                resPackage = context.getPackageName();
            }
            if (getResources() == null && !(getResPackage().equals("android"))) {
                // TODO(b/173307037): Move CONTEXT_INCLUDE_CODE to ContextImpl.createContextAsUser
                final Context userContext;
                if (context.getUserId() == userId) {
                    userContext = context;
                } else {
                    final boolean sameAppWithProcess =
                            UserHandle.isSameApp(context.getApplicationInfo().uid, Process.myUid());
                    final int flags = (sameAppWithProcess ? CONTEXT_INCLUDE_CODE : 0)
                            | CONTEXT_RESTRICTED;
                    userContext = context.createContextAsUser(UserHandle.of(userId), flags);
                }

                final PackageManager pm = userContext.getPackageManager();
                try {
                    // assign getResources() as the correct user
                    mObj1 = pm.getResourcesForApplication(resPackage);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, String.format("Unable to find pkg=%s user=%d",
                                    getResPackage(),
                                    userId),
                            e);
                }
            }
        }
        return loadDrawable(context);
    }

    /**
     * Load a drawable, but in the case of URI types, it will check if the passed uid has a grant
     * to load the resource. The check will be performed using the permissions of the passed uid,
     * and not those of the caller.
     * <p>
     * This should be called for {@link Icon} objects that come from a not trusted source and may
     * contain a URI.
     *
     * After the check, if passed, {@link #loadDrawable} will be called. If failed, this will
     * return {@code null}.
     *
     * @see #loadDrawable
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public Drawable loadDrawableCheckingUriGrant(
            Context context,
            IUriGrantsManager iugm,
            int callingUid,
            String packageName
    ) {
        if (getType() == TYPE_URI || getType() == TYPE_URI_ADAPTIVE_BITMAP) {
            try {
                iugm.checkGrantUriPermission_ignoreNonSystem(
                        callingUid,
                        packageName,
                        ContentProvider.getUriWithoutUserId(getUri()),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        ContentProvider.getUserIdFromUri(getUri())
                );
            } catch (SecurityException | RemoteException e) {
                Log.e(TAG, "Failed to get URI permission for: " + getUri(), e);
                return null;
            }
        }
        return loadDrawable(context);
    }

    /** @hide */
    public static final int MIN_ASHMEM_ICON_SIZE = 128 * (1 << 10);

    /**
     * Puts the memory used by this instance into Ashmem memory, if possible.
     * @hide
     */
    public void convertToAshmem() {
        if ((mType == TYPE_BITMAP || mType == TYPE_ADAPTIVE_BITMAP) &&
            getBitmap().isMutable() &&
            getBitmap().getAllocationByteCount() >= MIN_ASHMEM_ICON_SIZE) {
            setBitmap(getBitmap().asShared());
        }
        mCachedAshmem = true;
    }

    /**
     * Writes a serialized version of an Icon to the specified stream.
     *
     * @param stream The stream on which to serialize the Icon.
     * @hide
     */
    public void writeToStream(@NonNull OutputStream stream) throws IOException {
        DataOutputStream dataStream = new DataOutputStream(stream);

        dataStream.writeInt(VERSION_STREAM_SERIALIZER);
        dataStream.writeByte(mType);

        switch (mType) {
            case TYPE_BITMAP:
            case TYPE_ADAPTIVE_BITMAP:
                getBitmap().compress(Bitmap.CompressFormat.PNG, 100, dataStream);
                break;
            case TYPE_DATA:
                dataStream.writeInt(getDataLength());
                dataStream.write(getDataBytes(), getDataOffset(), getDataLength());
                break;
            case TYPE_RESOURCE:
                dataStream.writeUTF(getResPackage());
                dataStream.writeInt(getResId());
                break;
            case TYPE_URI:
            case TYPE_URI_ADAPTIVE_BITMAP:
                dataStream.writeUTF(getUriString());
                break;
        }
    }

    private Icon(int mType) {
        this.mType = mType;
    }

    /**
     * Create an Icon from the specified stream.
     *
     * @param stream The input stream from which to reconstruct the Icon.
     * @hide
     */
    public static @Nullable Icon createFromStream(@NonNull InputStream stream) throws IOException {
        DataInputStream inputStream = new DataInputStream(stream);

        final int version = inputStream.readInt();
        if (version >= VERSION_STREAM_SERIALIZER) {
            final int type = inputStream.readByte();
            switch (type) {
                case TYPE_BITMAP:
                    return createWithBitmap(BitmapFactory.decodeStream(inputStream));
                case TYPE_ADAPTIVE_BITMAP:
                    return createWithAdaptiveBitmap(BitmapFactory.decodeStream(inputStream));
                case TYPE_DATA:
                    final int length = inputStream.readInt();
                    final byte[] data = new byte[length];
                    inputStream.read(data, 0 /* offset */, length);
                    return createWithData(data, 0 /* offset */, length);
                case TYPE_RESOURCE:
                    final String packageName = inputStream.readUTF();
                    final int resId = inputStream.readInt();
                    return createWithResource(packageName, resId);
                case TYPE_URI:
                    final String uriOrPath = inputStream.readUTF();
                    return createWithContentUri(uriOrPath);
                case TYPE_URI_ADAPTIVE_BITMAP:
                    final String uri = inputStream.readUTF();
                    return createWithAdaptiveBitmapContentUri(uri);
            }
        }
        return null;
    }

    /**
     * Compares if this icon is constructed from the same resources as another icon.
     * Note that this is an inexpensive operation and doesn't do deep Bitmap equality comparisons.
     *
     * @param otherIcon the other icon
     * @return whether this icon is the same as the another one
     * @hide
     */
    public boolean sameAs(@NonNull Icon otherIcon) {
        if (otherIcon == this) {
            return true;
        }
        if (mType != otherIcon.getType()) {
            return false;
        }
        switch (mType) {
            case TYPE_BITMAP:
            case TYPE_ADAPTIVE_BITMAP:
                return getBitmap() == otherIcon.getBitmap();
            case TYPE_DATA:
                return getDataLength() == otherIcon.getDataLength()
                        && getDataOffset() == otherIcon.getDataOffset()
                        && Arrays.equals(getDataBytes(), otherIcon.getDataBytes());
            case TYPE_RESOURCE:
                return getResId() == otherIcon.getResId()
                        && Objects.equals(getResPackage(), otherIcon.getResPackage());
            case TYPE_URI:
            case TYPE_URI_ADAPTIVE_BITMAP:
                return Objects.equals(getUriString(), otherIcon.getUriString());
        }
        return false;
    }

    /**
     * Create an Icon pointing to a drawable resource.
     * @param context The context for the application whose resources should be used to resolve the
     *                given resource ID.
     * @param resId ID of the drawable resource
     */
    public static @NonNull Icon createWithResource(Context context, @DrawableRes int resId) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null.");
        }
        final Icon rep = new Icon(TYPE_RESOURCE);
        rep.mInt1 = resId;
        rep.mString1 = context.getPackageName();
        return rep;
    }

    /**
     * Version of createWithResource that takes Resources. Do not use.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static @NonNull Icon createWithResource(Resources res, @DrawableRes int resId) {
        if (res == null) {
            throw new IllegalArgumentException("Resource must not be null.");
        }
        final Icon rep = new Icon(TYPE_RESOURCE);
        rep.mInt1 = resId;
        rep.mString1 = res.getResourcePackageName(resId);
        return rep;
    }

    /**
     * Create an Icon pointing to a drawable resource.
     * @param resPackage Name of the package containing the resource in question
     * @param resId ID of the drawable resource
     */
    public static @NonNull Icon createWithResource(String resPackage, @DrawableRes int resId) {
        if (resPackage == null) {
            throw new IllegalArgumentException("Resource package name must not be null.");
        }
        final Icon rep = new Icon(TYPE_RESOURCE);
        rep.mInt1 = resId;
        rep.mString1 = resPackage;
        return rep;
    }

    /**
     * Create an Icon pointing to a bitmap in memory.
     * @param bits A valid {@link android.graphics.Bitmap} object
     */
    public static @NonNull Icon createWithBitmap(Bitmap bits) {
        if (bits == null) {
            throw new IllegalArgumentException("Bitmap must not be null.");
        }
        final Icon rep = new Icon(TYPE_BITMAP);
        rep.setBitmap(bits);
        return rep;
    }

    /**
     * Create an Icon pointing to a bitmap in memory that follows the icon design guideline defined
     * by {@link AdaptiveIconDrawable}.
     * @param bits A valid {@link android.graphics.Bitmap} object
     */
    public static @NonNull Icon createWithAdaptiveBitmap(Bitmap bits) {
        if (bits == null) {
            throw new IllegalArgumentException("Bitmap must not be null.");
        }
        final Icon rep = new Icon(TYPE_ADAPTIVE_BITMAP);
        rep.setBitmap(bits);
        return rep;
    }

    /**
     * Create an Icon pointing to a compressed bitmap stored in a byte array.
     * @param data Byte array storing compressed bitmap data of a type that
     *             {@link android.graphics.BitmapFactory}
     *             can decode (see {@link android.graphics.Bitmap.CompressFormat}).
     * @param offset Offset into <code>data</code> at which the bitmap data starts
     * @param length Length of the bitmap data
     */
    public static @NonNull Icon createWithData(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("Data must not be null.");
        }
        final Icon rep = new Icon(TYPE_DATA);
        rep.mObj1 = data;
        rep.mInt1 = length;
        rep.mInt2 = offset;
        return rep;
    }

    /**
     * Create an Icon pointing to an image file specified by URI.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    public static @NonNull Icon createWithContentUri(String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri must not be null.");
        }
        final Icon rep = new Icon(TYPE_URI);
        rep.mString1 = uri;
        return rep;
    }

    /**
     * Create an Icon pointing to an image file specified by URI.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    public static @NonNull Icon createWithContentUri(Uri uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri must not be null.");
        }
        return createWithContentUri(uri.toString());
    }

    /**
     * Create an Icon pointing to an image file specified by URI. Image file should follow the icon
     * design guideline defined by {@link AdaptiveIconDrawable}.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    public static @NonNull Icon createWithAdaptiveBitmapContentUri(@NonNull String uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri must not be null.");
        }
        final Icon rep = new Icon(TYPE_URI_ADAPTIVE_BITMAP);
        rep.mString1 = uri;
        return rep;
    }

    /**
     * Create an Icon pointing to an image file specified by URI. Image file should follow the icon
     * design guideline defined by {@link AdaptiveIconDrawable}.
     *
     * @param uri A uri referring to local content:// or file:// image data.
     */
    @NonNull
    public static Icon createWithAdaptiveBitmapContentUri(@NonNull Uri uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Uri must not be null.");
        }
        return createWithAdaptiveBitmapContentUri(uri.toString());
    }

    /**
     * Store a color to use whenever this Icon is drawn.
     *
     * @param tint a color, as in {@link Drawable#setTint(int)}
     * @return this same object, for use in chained construction
     */
    public @NonNull Icon setTint(@ColorInt int tint) {
        return setTintList(ColorStateList.valueOf(tint));
    }

    /**
     * Store a color to use whenever this Icon is drawn.
     *
     * @param tintList as in {@link Drawable#setTintList(ColorStateList)}, null to remove tint
     * @return this same object, for use in chained construction
     */
    public @NonNull Icon setTintList(ColorStateList tintList) {
        mTintList = tintList;
        return this;
    }

    /** @hide */
    public @Nullable ColorStateList getTintList() {
        return mTintList;
    }

    /**
     * Store a blending mode to use whenever this Icon is drawn.
     *
     * @param mode a blending mode, as in {@link Drawable#setTintMode(PorterDuff.Mode)}, may be null
     * @return this same object, for use in chained construction
     */
    public @NonNull Icon setTintMode(@NonNull PorterDuff.Mode mode) {
        mBlendMode = BlendMode.fromValue(mode.nativeInt);
        return this;
    }

    /**
     * Store a blending mode to use whenever this Icon is drawn.
     *
     * @param mode a blending mode, as in {@link Drawable#setTintMode(PorterDuff.Mode)}, may be null
     * @return this same object, for use in chained construction
     */
    public @NonNull Icon setTintBlendMode(@NonNull BlendMode mode) {
        mBlendMode = mode;
        return this;
    }

    /** @hide */
    public @NonNull BlendMode getTintBlendMode() {
        return mBlendMode;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean hasTint() {
        return (mTintList != null) || (mBlendMode != DEFAULT_BLEND_MODE);
    }

    /**
     * Create an Icon pointing to an image file specified by path.
     *
     * @param path A path to a file that contains compressed bitmap data of
     *           a type that {@link android.graphics.BitmapFactory} can decode.
     */
    public static @NonNull Icon createWithFilePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Path must not be null.");
        }
        final Icon rep = new Icon(TYPE_URI);
        rep.mString1 = path;
        return rep;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Icon(typ=").append(typeToString(mType));
        switch (mType) {
            case TYPE_BITMAP:
            case TYPE_ADAPTIVE_BITMAP:
                sb.append(" size=")
                        .append(getBitmap().getWidth())
                        .append("x")
                        .append(getBitmap().getHeight());
                break;
            case TYPE_RESOURCE:
                sb.append(" pkg=")
                        .append(getResPackage())
                        .append(" id=")
                        .append(String.format("0x%08x", getResId()));
                break;
            case TYPE_DATA:
                sb.append(" len=").append(getDataLength());
                if (getDataOffset() != 0) {
                    sb.append(" off=").append(getDataOffset());
                }
                break;
            case TYPE_URI:
            case TYPE_URI_ADAPTIVE_BITMAP:
                sb.append(" uri=").append(getUriString());
                break;
        }
        if (mTintList != null) {
            sb.append(" tint=");
            String sep = "";
            for (int c : mTintList.getColors()) {
                sb.append(String.format("%s0x%08x", sep, c));
                sep = "|";
            }
        }
        if (mBlendMode != DEFAULT_BLEND_MODE) sb.append(" mode=").append(mBlendMode);
        sb.append(")");
        return sb.toString();
    }

    /**
     * Parcelable interface
     */
    public int describeContents() {
        return (mType == TYPE_BITMAP || mType == TYPE_ADAPTIVE_BITMAP || mType == TYPE_DATA)
                ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
    }

    // ===== Parcelable interface ======

    private Icon(Parcel in) {
        this(in.readInt());
        switch (mType) {
            case TYPE_BITMAP:
            case TYPE_ADAPTIVE_BITMAP:
                final Bitmap bits = Bitmap.CREATOR.createFromParcel(in);
                mObj1 = bits;
                break;
            case TYPE_RESOURCE:
                final String pkg = in.readString();
                final int resId = in.readInt();
                mString1 = pkg;
                mInt1 = resId;
                break;
            case TYPE_DATA:
                final int len = in.readInt();
                final byte[] a = in.readBlob();
                if (len != a.length) {
                    throw new RuntimeException("internal unparceling error: blob length ("
                            + a.length + ") != expected length (" + len + ")");
                }
                mInt1 = len;
                mObj1 = a;
                break;
            case TYPE_URI:
            case TYPE_URI_ADAPTIVE_BITMAP:
                final String uri = in.readString();
                mString1 = uri;
                break;
            default:
                throw new RuntimeException("invalid "
                        + this.getClass().getSimpleName() + " type in parcel: " + mType);
        }
        if (in.readInt() == 1) {
            mTintList = ColorStateList.CREATOR.createFromParcel(in);
        }
        mBlendMode = BlendMode.fromValue(in.readInt());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        switch (mType) {
            case TYPE_BITMAP:
            case TYPE_ADAPTIVE_BITMAP:
                if (!mCachedAshmem) {
                    mObj1 = ((Bitmap) mObj1).asShared();
                    mCachedAshmem = true;
                }
                getBitmap().writeToParcel(dest, flags);
                break;
            case TYPE_RESOURCE:
                dest.writeString(getResPackage());
                dest.writeInt(getResId());
                break;
            case TYPE_DATA:
                dest.writeInt(getDataLength());
                dest.writeBlob(getDataBytes(), getDataOffset(), getDataLength());
                break;
            case TYPE_URI:
            case TYPE_URI_ADAPTIVE_BITMAP:
                dest.writeString(getUriString());
                break;
        }
        if (mTintList == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            mTintList.writeToParcel(dest, flags);
        }
        dest.writeInt(BlendMode.toValue(mBlendMode));
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Icon> CREATOR
            = new Parcelable.Creator<Icon>() {
        public Icon createFromParcel(Parcel in) {
            return new Icon(in);
        }

        public Icon[] newArray(int size) {
            return new Icon[size];
        }
    };

    /**
     * Scale down a bitmap to a given max width and max height. The scaling will be done in a uniform way
     * @param bitmap the bitmap to scale down
     * @param maxWidth the maximum width allowed
     * @param maxHeight the maximum height allowed
     *
     * @return the scaled bitmap if necessary or the original bitmap if no scaling was needed
     * @hide
     */
    public static Bitmap scaleDownIfNecessary(Bitmap bitmap, int maxWidth, int maxHeight) {
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();
        if (bitmapWidth > maxWidth || bitmapHeight > maxHeight) {
            float scale = Math.min((float) maxWidth / bitmapWidth,
                    (float) maxHeight / bitmapHeight);
            bitmap = Bitmap.createScaledBitmap(bitmap,
                    Math.max(1, (int) (scale * bitmapWidth)),
                    Math.max(1, (int) (scale * bitmapHeight)),
                    true /* filter */);
        }
        return bitmap;
    }

    /**
     * Scale down this icon to a given max width and max height.
     * The scaling will be done in a uniform way and currently only bitmaps are supported.
     * @param maxWidth the maximum width allowed
     * @param maxHeight the maximum height allowed
     *
     * @hide
     */
    public void scaleDownIfNecessary(int maxWidth, int maxHeight) {
        if (mType != TYPE_BITMAP && mType != TYPE_ADAPTIVE_BITMAP) {
            return;
        }
        Bitmap bitmap = getBitmap();
        setBitmap(scaleDownIfNecessary(bitmap, maxWidth, maxHeight));
    }

    /**
     * Implement this interface to receive a callback when
     * {@link #loadDrawableAsync(Context, OnDrawableLoadedListener, Handler) loadDrawableAsync}
     * is finished and your Drawable is ready.
     */
    public interface OnDrawableLoadedListener {
        void onDrawableLoaded(Drawable d);
    }

    /**
     * Wrapper around loadDrawable that does its work on a pooled thread and then
     * fires back the given (targeted) Message.
     */
    private class LoadDrawableTask implements Runnable {
        final Context mContext;
        final Message mMessage;

        public LoadDrawableTask(Context context, final Handler handler,
                final OnDrawableLoadedListener listener) {
            mContext = context;
            mMessage = Message.obtain(handler, new Runnable() {
                    @Override
                    public void run() {
                        listener.onDrawableLoaded((Drawable) mMessage.obj);
                    }
                });
        }

        public LoadDrawableTask(Context context, Message message) {
            mContext = context;
            mMessage = message;
        }

        @Override
        public void run() {
            mMessage.obj = loadDrawable(mContext);
            mMessage.sendToTarget();
        }

        public void runAsync() {
            AsyncTask.THREAD_POOL_EXECUTOR.execute(this);
        }
    }
}
