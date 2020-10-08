/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.appsearch;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Structure for transmitting a page of search results across binder.
 * @hide
 */
public final class SearchResults implements Parcelable {
    final List<SearchResult> mResults;
    final long mNextPageToken;

    public SearchResults(@NonNull List<SearchResult> results, long nextPageToken) {
        mResults = results;
        mNextPageToken = nextPageToken;
    }

    private SearchResults(@NonNull Parcel in) {
        List<Bundle> resultBundles = in.readArrayList(/*loader=*/ null);
        mResults = new ArrayList<>(resultBundles.size());
        for (int i = 0; i < resultBundles.size(); i++) {
            SearchResult searchResult = new SearchResult(resultBundles.get(i));
            mResults.add(searchResult);
        }
        mNextPageToken = in.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        List<Bundle> resultBundles = new ArrayList<>(mResults.size());
        for (int i = 0; i < mResults.size(); i++) {
            resultBundles.add(mResults.get(i).getBundle());
        }
        dest.writeList(resultBundles);
        dest.writeLong(mNextPageToken);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<SearchResults> CREATOR = new Creator<SearchResults>() {
        @NonNull
        @Override
        public SearchResults createFromParcel(@NonNull Parcel in) {
            return new SearchResults(in);
        }

        @NonNull
        @Override
        public SearchResults[] newArray(int size) {
            return new SearchResults[size];
        }
    };
}
