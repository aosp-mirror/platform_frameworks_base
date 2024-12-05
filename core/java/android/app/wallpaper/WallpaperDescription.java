/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.wallpaper;

import static android.app.Flags.FLAG_LIVE_WALLPAPER_CONTENT_HANDLING;

import android.annotation.FlaggedApi;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.WallpaperManager.ScreenOrientation;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannedString;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a wallpaper, including associated metadata and optional content to be used by its
 * {@link android.service.wallpaper.WallpaperService.Engine}, the {@link ComponentName} to be used
 * by {@link android.app.WallpaperManager}, and an optional id to differentiate between different
 * distinct wallpapers rendered by the same wallpaper service.
 *
 * <p>This class is used to communicate among a wallpaper rendering service, a wallpaper chooser UI,
 * and {@link android.app.WallpaperManager}. This class describes a specific instance of a live
 * wallpaper, unlike {@link WallpaperInfo} which is common to all instances of a wallpaper
 * component. Each {@link WallpaperDescription} can have distinct metadata.
 * </p>
 */
@FlaggedApi(FLAG_LIVE_WALLPAPER_CONTENT_HANDLING)
public final class WallpaperDescription implements Parcelable {
    private static final String TAG = "WallpaperDescription";
    private static final  String XML_TAG_CONTENT = "content";
    private static final String XML_TAG_DESCRIPTION = "description";

    @Nullable private final ComponentName mComponent;
    @Nullable private final String mId;
    @Nullable private final Uri mThumbnail;
    @Nullable private final CharSequence mTitle;
    @NonNull private final List<CharSequence> mDescription;
    @Nullable private final Uri mContextUri;
    @Nullable private final CharSequence mContextDescription;
    @NonNull private final PersistableBundle mContent;
    @NonNull private final SparseArray<Rect> mCropHints;
    private final float mSampleSize;

    private WallpaperDescription(@Nullable ComponentName component,
            @Nullable String id, @Nullable Uri thumbnail, @Nullable CharSequence title,
            @Nullable List<CharSequence> description, @Nullable Uri contextUri,
            @Nullable CharSequence contextDescription,
            @Nullable PersistableBundle content, @NonNull SparseArray<Rect> cropHints,
            float sampleSize) {
        this.mComponent = component;
        this.mId = id;
        this.mThumbnail = thumbnail;
        this.mTitle = title;
        this.mDescription = (description != null) ? description : new ArrayList<>();
        this.mContextUri = contextUri;
        this.mContextDescription = contextDescription;
        this.mContent = (content != null) ? content : new PersistableBundle();
        this.mCropHints = cropHints;
        this.mSampleSize = sampleSize;
    }

    /** @return the component for this wallpaper, or {@code null} for a static wallpaper */
    @Nullable public ComponentName getComponent() {
        return mComponent;
    }

    /** @return the id for this wallpaper, or {@code null} if not provided */
    @Nullable public String getId() {
        return mId;
    }

    /** @return the thumbnail for this wallpaper, or {@code null} if not provided */
    @Nullable public Uri getThumbnail() {
        return mThumbnail;
    }

    /**
     * @return the title for this wallpaper, with each list element intended to be a separate
     * line, or {@code null} if not provided
     */
    @Nullable public CharSequence getTitle() {
        return mTitle;
    }

    /** @return the description for this wallpaper */
    @NonNull
    public List<CharSequence> getDescription() {
        return mDescription;
    }

    /** @return the {@link Uri} for the action associated with the wallpaper, or {@code null} if not
     * provided */
    @Nullable public Uri getContextUri() {
        return mContextUri;
    }

    /** @return the description for the action associated with the wallpaper, or {@code null} if not
     * provided */
    @Nullable public CharSequence getContextDescription() {
        return mContextDescription;
    }

    /** @return any additional content required to render this wallpaper */
    @NonNull
    public PersistableBundle getContent() {
        return mContent;
    }

    /**
     * @return the cropping for the current image as described in
     * {@link Builder#setCropHints(SparseArray)}
     * @hide
     */
    @SystemApi
    @NonNull
    public SparseArray<Rect> getCropHints() {
        return mCropHints;
    }

    /**
     * @return the subsamling size as described in {@link Builder#setSampleSize(float)}.
     * @hide
     */
    public float getSampleSize() {
        return mSampleSize;
    }

    ////// Comparison overrides

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WallpaperDescription that)) return false;
        return Objects.equals(mComponent, that.mComponent) && Objects.equals(mId,
                that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mComponent, mId);
    }

    ////// XML storage

    /** @hide */
    public void saveToXml(TypedXmlSerializer out) throws IOException, XmlPullParserException {
        if (mComponent != null) {
            out.attribute(null, "component", mComponent.flattenToShortString());
        }
        if (mId != null) out.attribute(null, "id", mId);
        if (mThumbnail != null) out.attribute(null, "thumbnail", mThumbnail.toString());
        if (mTitle != null) out.attribute(null, "title", toHtml(mTitle));
        if (mContextUri != null) out.attribute(null, "contexturi", mContextUri.toString());
        if (mContextDescription != null) {
            out.attribute(null, "contextdescription", toHtml(mContextDescription));
        }

        for (Pair<Integer, String> pair : screenDimensionPairs()) {
            @ScreenOrientation int orientation = pair.first;
            String attrName = pair.second;
            Rect cropHint = mCropHints.get(orientation);
            if (cropHint == null) continue;
            out.attributeInt(null, "cropLeft" + attrName, cropHint.left);
            out.attributeInt(null, "cropTop" + attrName, cropHint.top);
            out.attributeInt(null, "cropRight" + attrName, cropHint.right);
            out.attributeInt(null, "cropBottom" + attrName, cropHint.bottom);
        }
        out.attributeFloat(null, "sampleSize", mSampleSize);

        out.startTag(null, XML_TAG_DESCRIPTION);
        for (CharSequence s : mDescription) out.attribute(null, "descriptionline", toHtml(s));
        out.endTag(null, XML_TAG_DESCRIPTION);

        try {
            out.startTag(null, XML_TAG_CONTENT);
            mContent.saveToXml(out);
        } catch (XmlPullParserException e) {
            // Be extra conservative and don't fail when writing content since it could come
            // from third parties
            Log.e(TAG, "unable to convert wallpaper content to XML");
        } finally {
            out.endTag(null, XML_TAG_CONTENT);
        }
    }

    /** @hide */
    public static WallpaperDescription restoreFromXml(TypedXmlPullParser in) throws IOException,
            XmlPullParserException {
        final int outerDepth = in.getDepth();
        String component = in.getAttributeValue(null, "component");
        ComponentName componentName = (component != null) ? ComponentName.unflattenFromString(
                component) : null;
        String id = in.getAttributeValue(null, "id");
        String thumbnailString = in.getAttributeValue(null, "thumbnail");
        Uri thumbnail = (thumbnailString != null) ? Uri.parse(thumbnailString) : null;
        CharSequence title = fromHtml(in.getAttributeValue(null, "title"));
        String contextUriString = in.getAttributeValue(null, "contexturi");
        Uri contextUri = (contextUriString != null) ? Uri.parse(contextUriString) : null;
        CharSequence contextDescription = fromHtml(
                in.getAttributeValue(null, "contextdescription"));

        SparseArray<Rect> cropHints = new SparseArray<>();
        screenDimensionPairs().forEach(pair -> {
            @ScreenOrientation int orientation = pair.first;
            String attrName = pair.second;
            Rect crop = new Rect(
                    in.getAttributeInt(null, "cropLeft" + attrName, 0),
                    in.getAttributeInt(null, "cropTop" + attrName, 0),
                    in.getAttributeInt(null, "cropRight" + attrName, 0),
                    in.getAttributeInt(null, "cropBottom" + attrName, 0));
            if (!crop.isEmpty()) cropHints.put(orientation, crop);
        });
        float sampleSize = in.getAttributeFloat(null, "sampleSize", 1f);

        List<CharSequence> description = new ArrayList<>();
        PersistableBundle content = null;
        int type;
        while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || in.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String name = in.getName();
            if (XML_TAG_DESCRIPTION.equals(name)) {
                for (int i = 0; i < in.getAttributeCount(); i++) {
                    description.add(fromHtml(in.getAttributeValue(i)));
                }
            } else if (XML_TAG_CONTENT.equals(name)) {
                content = PersistableBundle.restoreFromXml(in);
            }
        }

        return new WallpaperDescription(componentName, id, thumbnail, title, description,
                contextUri, contextDescription, content, cropHints, sampleSize);
    }

    private static String toHtml(@NonNull CharSequence c) {
        Spanned s = (c instanceof Spanned) ? (Spanned) c : new SpannedString(c);
        return Html.toHtml(s, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL);
    }

    private static CharSequence fromHtml(@Nullable String text) {
        if (text == null) {
            return  null;
        } else {
            return removeTrailingWhitespace(Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT));
        }
    }

    // Html.fromHtml and toHtml add a trailing line. This removes it. See
    // https://stackoverflow.com/q/9589381
    private static CharSequence removeTrailingWhitespace(CharSequence s) {
        if (s == null) return null;

        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }

        return s.subSequence(0, end);
    }

    ////// Parcelable implementation

    WallpaperDescription(@NonNull Parcel in) {
        mComponent = ComponentName.readFromParcel(in);
        mId = in.readString8();
        mThumbnail = Uri.CREATOR.createFromParcel(in);
        mTitle = in.readCharSequence();
        mDescription = Arrays.stream(in.readCharSequenceArray()).toList();
        mContextUri = Uri.CREATOR.createFromParcel(in);
        mContextDescription = in.readCharSequence();
        mContent = PersistableBundle.CREATOR.createFromParcel(in);
        mCropHints = new SparseArray<>();
        screenDimensionPairs().forEach(pair -> {
            int orientation = pair.first;
            Rect crop = in.readTypedObject(Rect.CREATOR);
            if (crop != null) mCropHints.put(orientation, crop);
        });
        mSampleSize = in.readFloat();
    }

    @NonNull
    public static final Creator<WallpaperDescription> CREATOR = new Creator<>() {
        @Override
        public WallpaperDescription createFromParcel(Parcel source) {
            return new WallpaperDescription(source);
        }

        @Override
        public WallpaperDescription[] newArray(int size) {
            return new WallpaperDescription[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        ComponentName.writeToParcel(mComponent, dest);
        dest.writeString8(mId);
        Uri.writeToParcel(dest, mThumbnail);
        dest.writeCharSequence(mTitle);
        dest.writeCharSequenceArray(mDescription.toArray(new CharSequence[0]));
        Uri.writeToParcel(dest, mContextUri);
        dest.writeCharSequence(mContextDescription);
        dest.writePersistableBundle(mContent);
        screenDimensionPairs().forEach(pair -> {
            int orientation = pair.first;
            dest.writeTypedObject(mCropHints.get(orientation), flags);
        });
        dest.writeFloat(mSampleSize);
    }

    ////// Builder

    /**
     * Convert the current description to a {@link Builder}.
     * @return the Builder representing this description
     */
    @NonNull
    public Builder toBuilder() {
        return new Builder()
                .setComponent(mComponent)
                .setId(mId)
                .setThumbnail(mThumbnail)
                .setTitle(mTitle)
                .setDescription(mDescription)
                .setContextUri(mContextUri)
                .setContextDescription(mContextDescription)
                .setContent(mContent)
                .setCropHints(mCropHints)
                .setSampleSize(mSampleSize);
    }

    /** Builder for the immutable {@link WallpaperDescription} class */
    public static final class Builder {
        @Nullable private ComponentName mComponent;
        @Nullable private String mId;
        @Nullable private Uri mThumbnail;
        @Nullable private CharSequence mTitle;
        @NonNull private List<CharSequence> mDescription = new ArrayList<>();
        @Nullable private Uri mContextUri;
        @Nullable private CharSequence mContextDescription;
        @NonNull private PersistableBundle mContent = new PersistableBundle();
        @NonNull
        private SparseArray<Rect> mCropHints = new SparseArray<>();
        private float mSampleSize = 1f;

        /** Creates a new, empty {@link Builder}. */
        public Builder() {}

        /**
         * Specify the component for this wallpaper.
         *
         * <p>This method is hidden because only trusted apps should be able to specify the
         * component, which names a wallpaper service to be started by the system.
         * </p>
         *
         * @param component component name, or {@code null} for static wallpaper
         * @hide
         */
        @NonNull
        public Builder setComponent(@Nullable ComponentName component) {
            mComponent = component;
            return this;
        }

        /**
         * Set the id for this wallpaper.
         *
         * <p>IDs are used to distinguish among different instances of wallpapers rendered by the
         * same component, and should be unique among all wallpapers for that component.
         * </p>
         *
         * @param id the id, or {@code null} for none
         */
        @NonNull
        public Builder setId(@Nullable String id) {
            mId = id;
            return this;
        }

        /**
         * Set the thumbnail Uri for this wallpaper.
         *
         * @param thumbnail the thumbnail Uri, or {@code null} for none
         */
        @NonNull
        public Builder setThumbnail(@Nullable Uri thumbnail) {
            mThumbnail = thumbnail;
            return this;
        }

        /**
         * Set the title for this wallpaper.
         *
         * @param title the title, or {@code null} for none
         */
        @NonNull
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Set the description for this wallpaper. Each array element should be shown on a
         * different line.
         *
         * @param description the description, or an empty list for none
         */
        @NonNull
        public Builder setDescription(@NonNull List<CharSequence> description) {
            mDescription = description;
            return this;
        }

        /**
         * Set the Uri for the action associated with this wallpaper, to be shown as a link with the
         * wallpaper information.
         *
         * @param contextUri the action Uri, or {@code null} for no action
         */
        @NonNull
        public Builder setContextUri(@Nullable Uri contextUri) {
            mContextUri = contextUri;
            return this;
        }

        /**
         * Set the link text for the action associated with this wallpaper.
         *
         * @param contextDescription the link text, or {@code null} for default text
         */
        @NonNull
        public Builder setContextDescription(@Nullable CharSequence contextDescription) {
            mContextDescription = contextDescription;
            return this;
        }

        /**
         * Set the additional content required to render this wallpaper.
         *
         * <p>When setting additional content (asset id, etc.), best practice is to set an ID as
         * well. This allows WallpaperManager and other code to distinguish between different
         * wallpapers handled by this component.
         * </p>
         *
         * @param content additional content, or an empty bundle for none
         */
        @NonNull
        public Builder setContent(@NonNull PersistableBundle content) {
            mContent = content;
            return this;
        }

        /**
         * Defines which part of the source wallpaper image is in the stored crop file.
         *
         * @param cropHints map from screen dimensions to a sub-region of the image to display
         *                  for those dimensions. The {@code Rect} sub-region may have a larger
         *                  width/height ratio than the screen dimensions to apply a horizontal
         *                  parallax effect. If the map is empty or some entries are missing, the
         *                  system will apply a default strategy to position the wallpaper for
         *                  any unspecified screen dimensions.
         * @hide
         */
        @NonNull
        @TestApi
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setCropHints(@NonNull Map<Point, Rect> cropHints) {
            mCropHints = new SparseArray<>();
            cropHints.forEach(
                    (point, rect) -> mCropHints.put(WallpaperManager.getOrientation(point), rect));
            return this;
        }

        /**
         * Defines which part of the source wallpaper image is in the stored crop file.
         *
         * @param cropHints map from {@link ScreenOrientation} to a sub-region of the image to
         *                  display for that screen orientation.
         * @hide
         */
        @NonNull
        @TestApi
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setCropHints(@NonNull SparseArray<Rect> cropHints) {
            mCropHints = cropHints;
            return this;
        }

        /**
         * How much the crop is sub-sampled. A value > 1 means that the image quality was reduced.
         * This is the ratio between the cropHint height and the actual stored crop file height.
         * height.
         *
         * @param sampleSize Sub-sampling value
         * @hide
         */
        @NonNull
        public Builder setSampleSize(float sampleSize) {
            mSampleSize = sampleSize;
            return this;
        }

        /** Creates and returns the {@link WallpaperDescription} represented by this builder. */
        @NonNull
        public WallpaperDescription build() {
            return new WallpaperDescription(mComponent, mId, mThumbnail, mTitle, mDescription,
                    mContextUri, mContextDescription, mContent, mCropHints, mSampleSize);
        }
    }

    private static List<Pair<Integer, String>> screenDimensionPairs() {
        return List.of(
                new Pair<>(WallpaperManager.ORIENTATION_PORTRAIT, "Portrait"),
                new Pair<>(WallpaperManager.ORIENTATION_LANDSCAPE, "Landscape"),
                new Pair<>(WallpaperManager.ORIENTATION_SQUARE_PORTRAIT, "SquarePortrait"),
                new Pair<>(WallpaperManager.ORIENTATION_SQUARE_LANDSCAPE, "SquareLandscape"));
    }
}
