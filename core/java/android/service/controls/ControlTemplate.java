/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An abstract input template for a {@link Control}.
 *
 * Specifies what layout is presented to the user when a {@link ControlState} is assigned to a
 * particular {@link Control}.
 * <p>
 * Some instances of {@link Control} can originate actions (via user interaction) to modify its
 * associated state. The actions available to a given {@link Control} in a particular
 * {@link ControlState} are determined by its {@link ControlTemplate}.
 * @see ControlAction
 * @hide
 */
public abstract class ControlTemplate implements Parcelable {

    private static final String KEY_TEMPLATE_ID = "key_template_id";

    /**
     * Singleton representing a {@link Control} with no input.
     */
    public static final ControlTemplate NO_TEMPLATE = new ControlTemplate("") {
        @Override
        public int getTemplateType() {
            return TYPE_NONE;
        }
    };

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_NONE,
            TYPE_TOGGLE,
            TYPE_RANGE,
            TYPE_THUMBNAIL,
            TYPE_DISCRETE_TOGGLE,
            TYPE_COORD_RANGE
    })
    public @interface TemplateType {}

    /**
     * Type identifier of {@link ControlTemplate#NO_TEMPLATE}.
     */
    public static final int TYPE_NONE = 0;

    /**
     * Type identifier of {@link ToggleTemplate}.
     */
    public static final int TYPE_TOGGLE = 1;

    /**
     * Type identifier of {@link RangeTemplate}.
     */
    public static final int TYPE_RANGE = 2;

    /**
     * Type identifier of {@link ThumbnailTemplate}.
     */
    public static final int TYPE_THUMBNAIL = 3;

    /**
     * Type identifier of {@link DiscreteToggleTemplate}.
     */
    public static final int TYPE_DISCRETE_TOGGLE = 4;

    /**
     * @hide
     */
    public static final int TYPE_COORD_RANGE = 5;

    private @NonNull final String mTemplateId;

    /**
     * @return the identifier for this object.
     */
    public String getTemplateId() {
        return mTemplateId;
    }

    /**
     * The {@link TemplateType} associated with this class.
     */
    public abstract @TemplateType int getTemplateType();

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public final void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(getTemplateType());
        dest.writeBundle(getDataBundle());
    }

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     * @return a {@link Bundle} containing the data that represents this object.
     */
    @CallSuper
    protected Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putString(KEY_TEMPLATE_ID, mTemplateId);
        return b;
    }

    private ControlTemplate() {
        mTemplateId = "";
    }

    ControlTemplate(@NonNull Bundle b) {
        mTemplateId = b.getString(KEY_TEMPLATE_ID);
    }

    /**
     * @hide
     */
    ControlTemplate(@NonNull String templateId) {
        Preconditions.checkNotNull(templateId);
        mTemplateId = templateId;
    }

    public static final Creator<ControlTemplate> CREATOR = new Creator<ControlTemplate>() {
        @Override
        public ControlTemplate createFromParcel(Parcel source) {
            int type = source.readInt();
            return createTemplateFromType(type, source);
        }

        @Override
        public ControlTemplate[] newArray(int size) {
            return new ControlTemplate[size];
        }
    };


    private static ControlTemplate createTemplateFromType(@TemplateType int type, Parcel source) {
        switch(type) {
            case TYPE_TOGGLE:
                return ToggleTemplate.CREATOR.createFromParcel(source);
            case TYPE_RANGE:
                return RangeTemplate.CREATOR.createFromParcel(source);
            case TYPE_THUMBNAIL:
                return ThumbnailTemplate.CREATOR.createFromParcel(source);
            case TYPE_DISCRETE_TOGGLE:
                return DiscreteToggleTemplate.CREATOR.createFromParcel(source);
            case TYPE_NONE:
            default:
                source.readBundle();
                return NO_TEMPLATE;
        }
    }
}
