/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextUtils;

/**
 * A SpellCheckSpan is an internal data structure created by the TextView's SpellChecker to
 * annotate portions of the text that are about to or currently being spell checked. They are
 * automatically removed once the spell check is completed.
 *
 * @hide
 */
public class SpellCheckSpan implements ParcelableSpan {

    private boolean mSpellCheckInProgress;

    public SpellCheckSpan() {
        mSpellCheckInProgress = false;
    }

    public SpellCheckSpan(Parcel src) {
        mSpellCheckInProgress = (src.readInt() != 0);
    }

    public void setSpellCheckInProgress(boolean inProgress) {
        mSpellCheckInProgress = inProgress;
    }

    public boolean isSpellCheckInProgress() {
        return mSpellCheckInProgress;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSpellCheckInProgress ? 1 : 0);
    }

    @Override
    public int getSpanTypeId() {
        return TextUtils.SPELL_CHECK_SPAN;
    }
}
