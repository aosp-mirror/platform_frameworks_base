/**
 * Copyright (c) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.ArrayList;

/**
 * Meta-data describing the contents of a {@link ClipData}.  Provides enough
 * information to know if you can handle the ClipData, but not the data
 * itself.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using the clipboard framework, read the
 * <a href="{@docRoot}guide/topics/clipboard/copy-paste.html">Copy and Paste</a>
 * developer guide.</p>
 * </div>
 */
public class ClipDescription implements Parcelable {
    /**
     * The MIME type for a clip holding plain text.
     */
    public static final String MIMETYPE_TEXT_PLAIN = "text/plain";

    /**
     * The MIME type for a clip holding one or more URIs.  This should be
     * used for URIs that are meaningful to a user (such as an http: URI).
     * It should <em>not</em> be used for a content: URI that references some
     * other piece of data; in that case the MIME type should be the type
     * of the referenced data.
     */
    public static final String MIMETYPE_TEXT_URILIST = "text/uri-list";

    /**
     * The MIME type for a clip holding an Intent.
     */
    public static final String MIMETYPE_TEXT_INTENT = "text/vnd.android.intent";

    final CharSequence mLabel;
    final String[] mMimeTypes;

    /**
     * Create a new clip.
     *
     * @param label Label to show to the user describing this clip.
     * @param mimeTypes An array of MIME types this data is available as.
     */
    public ClipDescription(CharSequence label, String[] mimeTypes) {
        if (mimeTypes == null) {
            throw new NullPointerException("mimeTypes is null");
        }
        mLabel = label;
        mMimeTypes = mimeTypes;
    }

    /**
     * Create a copy of a ClipDescription.
     */
    public ClipDescription(ClipDescription o) {
        mLabel = o.mLabel;
        mMimeTypes = o.mMimeTypes;
    }

    /**
     * Helper to compare two MIME types, where one may be a pattern.
     * @param concreteType A fully-specified MIME type.
     * @param desiredType A desired MIME type that may be a pattern such as *\/*.
     * @return Returns true if the two MIME types match.
     */
    public static boolean compareMimeTypes(String concreteType, String desiredType) {
        final int typeLength = desiredType.length();
        if (typeLength == 3 && desiredType.equals("*/*")) {
            return true;
        }

        final int slashpos = desiredType.indexOf('/');
        if (slashpos > 0) {
            if (typeLength == slashpos+2 && desiredType.charAt(slashpos+1) == '*') {
                if (desiredType.regionMatches(0, concreteType, 0, slashpos+1)) {
                    return true;
                }
            } else if (desiredType.equals(concreteType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Return the label for this clip.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Check whether the clip description contains the given MIME type.
     *
     * @param mimeType The desired MIME type.  May be a pattern.
     * @return Returns true if one of the MIME types in the clip description
     * matches the desired MIME type, else false.
     */
    public boolean hasMimeType(String mimeType) {
        for (int i=0; i<mMimeTypes.length; i++) {
            if (compareMimeTypes(mMimeTypes[i], mimeType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter the clip description MIME types by the given MIME type.  Returns
     * all MIME types in the clip that match the given MIME type.
     *
     * @param mimeType The desired MIME type.  May be a pattern.
     * @return Returns an array of all matching MIME types.  If there are no
     * matching MIME types, null is returned.
     */
    public String[] filterMimeTypes(String mimeType) {
        ArrayList<String> array = null;
        for (int i=0; i<mMimeTypes.length; i++) {
            if (compareMimeTypes(mMimeTypes[i], mimeType)) {
                if (array == null) {
                    array = new ArrayList<String>();
                }
                array.add(mMimeTypes[i]);
            }
        }
        if (array == null) {
            return null;
        }
        String[] rawArray = new String[array.size()];
        array.toArray(rawArray);
        return rawArray;
    }

    /**
     * Return the number of MIME types the clip is available in.
     */
    public int getMimeTypeCount() {
        return mMimeTypes.length;
    }

    /**
     * Return one of the possible clip MIME types.
     */
    public String getMimeType(int index) {
        return mMimeTypes[index];
    }

    /** @hide */
    public void validate() {
        if (mMimeTypes == null) {
            throw new NullPointerException("null mime types");
        }
        if (mMimeTypes.length <= 0) {
            throw new IllegalArgumentException("must have at least 1 mime type");
        }
        for (int i=0; i<mMimeTypes.length; i++) {
            if (mMimeTypes[i] == null) {
                throw new NullPointerException("mime type at " + i + " is null");
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        TextUtils.writeToParcel(mLabel, dest, flags);
        dest.writeStringArray(mMimeTypes);
    }

    ClipDescription(Parcel in) {
        mLabel = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mMimeTypes = in.createStringArray();
    }

    public static final Parcelable.Creator<ClipDescription> CREATOR =
        new Parcelable.Creator<ClipDescription>() {

            public ClipDescription createFromParcel(Parcel source) {
                return new ClipDescription(source);
            }

            public ClipDescription[] newArray(int size) {
                return new ClipDescription[size];
            }
        };
}
