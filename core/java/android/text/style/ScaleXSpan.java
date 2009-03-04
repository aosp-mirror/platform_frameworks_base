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

import android.os.Parcel;
import android.text.ParcelableSpan;
import android.text.TextPaint;
import android.text.TextUtils;

public class ScaleXSpan extends MetricAffectingSpan implements ParcelableSpan {

	private final float mProportion;

	public ScaleXSpan(float proportion) {
		mProportion = proportion;
	}

    public ScaleXSpan(Parcel src) {
        mProportion = src.readFloat();
    }
    
    public int getSpanTypeId() {
        return TextUtils.SCALE_X_SPAN;
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(mProportion);
    }

	public float getScaleX() {
		return mProportion;
	}

	@Override
	public void updateDrawState(TextPaint ds) {
		ds.setTextScaleX(ds.getTextScaleX() * mProportion);
	}

	@Override
	public void updateMeasureState(TextPaint ds) {
		ds.setTextScaleX(ds.getTextScaleX() * mProportion);
	}
}
