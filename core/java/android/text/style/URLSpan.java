/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.style;

import android.annotation.NonNull;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.provider.Browser;
import android.text.ParcelableSpan;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

/**
 * Implementation of the {@link ClickableSpan} that allows setting a url string. When
 * selecting and clicking on the text to which the span is attached, the <code>URLSpan</code>
 * will try to open the url, by launching an an Activity with an {@link Intent#ACTION_VIEW} intent.
 * <p>
 * For example, a <code>URLSpan</code> can be used like this:
 * <pre>
 * SpannableString string = new SpannableString("Text with a url span");
 * string.setSpan(new URLSpan("http://www.developer.android.com"), 12, 15, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
 * </pre>
 * <img src="{@docRoot}reference/android/images/text/style/urlspan.png" />
 * <figcaption>Text with <code>URLSpan</code>.</figcaption>
 */
public class URLSpan extends ClickableSpan implements ParcelableSpan {

    private final String mURL;

    /**
     * Constructs a {@link URLSpan} from a url string.
     *
     * @param url the url string
     */
    public URLSpan(String url) {
        mURL = url;
    }

    /**
     * Constructs a {@link URLSpan} from a parcel.
     */
    public URLSpan(@NonNull Parcel src) {
        mURL = src.readString();
    }

    @Override
    public int getSpanTypeId() {
        return getSpanTypeIdInternal();
    }

    /** @hide */
    @Override
    public int getSpanTypeIdInternal() {
        return TextUtils.URL_SPAN;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeToParcelInternal(dest, flags);
    }

    /** @hide */
    @Override
    public void writeToParcelInternal(@NonNull Parcel dest, int flags) {
        dest.writeString(mURL);
    }

    /**
     * Get the url string for this span.
     *
     * @return the url string.
     */
    public String getURL() {
        return mURL;
    }

    @Override
    public void onClick(View widget) {
        Uri uri = Uri.parse(getURL());
        Context context = widget.getContext();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w("URLSpan", "Actvity was not found for intent, " + intent.toString());
        }
    }

    @Override
    public String toString() {
        return "URLSpan{" + "URL='" + getURL() + '\'' + '}';
    }
}
