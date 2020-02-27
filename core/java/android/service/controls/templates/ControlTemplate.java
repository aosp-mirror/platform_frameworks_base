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

package android.service.controls.templates;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.actions.ControlAction;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An abstract input template for a {@link Control}.
 *
 * Specifies what layout is presented to the user for a given {@link Control}.
 * <p>
 * Some instances of {@link Control} can originate actions (via user interaction) to modify its
 * associated state. The actions available to a given {@link Control} are determined by its
 * {@link ControlTemplate}.
 * @see ControlAction
 */
public abstract class ControlTemplate {

    private static final String TAG = "ControlTemplate";

    private static final String KEY_TEMPLATE_ID = "key_template_id";
    private static final String KEY_TEMPLATE_TYPE = "key_template_type";

    /**
     * Singleton representing a {@link Control} with no input.
     */
    public static final @NonNull ControlTemplate NO_TEMPLATE = new ControlTemplate("") {
        @Override
        public int getTemplateType() {
            return TYPE_NONE;
        }
    };

    /**
     * Object returned when there is an unparcelling error.
     */
    public static final @NonNull ControlTemplate ERROR_TEMPLATE = new ControlTemplate("") {
        @Override
        public int getTemplateType() {
            return TYPE_ERROR;
        }
    };

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            TYPE_ERROR,
            TYPE_NONE,
            TYPE_TOGGLE,
            TYPE_RANGE,
            TYPE_THUMBNAIL,
            TYPE_TOGGLE_RANGE,
            TYPE_TEMPERATURE,
            TYPE_STATELESS
    })
    public @interface TemplateType {}

    /**
     * Type identifier of {@link #ERROR_TEMPLATE}.
     */
    public static final @TemplateType int TYPE_ERROR = -1;

    /**
     * Type identifier of {@link ControlTemplate#NO_TEMPLATE}.
     */
    public static final @TemplateType int TYPE_NONE = 0;

    /**
     * Type identifier of {@link ToggleTemplate}.
     */
    public static final @TemplateType int TYPE_TOGGLE = 1;

    /**
     * Type identifier of {@link RangeTemplate}.
     */
    public static final @TemplateType int TYPE_RANGE = 2;

    /**
     * Type identifier of {@link ThumbnailTemplate}.
     */
    public static final @TemplateType int TYPE_THUMBNAIL = 3;

    /**
     * Type identifier of {@link ToggleRangeTemplate}.
     */
    public static final @TemplateType int TYPE_TOGGLE_RANGE = 6;

    /**
     * Type identifier of {@link TemperatureControlTemplate}.
     */
    public static final @TemplateType int TYPE_TEMPERATURE = 7;

    /**
     * Type identifier of {@link StatelessTemplate}.
     */
    public static final @TemplateType int TYPE_STATELESS = 8;

    private @NonNull final String mTemplateId;

    /**
     * @return the identifier for this object.
     */
    @NonNull
    public String getTemplateId() {
        return mTemplateId;
    }

    /**
     * The {@link TemplateType} associated with this class.
     */
    public abstract @TemplateType int getTemplateType();

    /**
     * Obtain a {@link Bundle} describing this object populated with data.
     * @return a {@link Bundle} containing the data that represents this object.
     * @hide
     */
    @CallSuper
    @NonNull
    Bundle getDataBundle() {
        Bundle b = new Bundle();
        b.putInt(KEY_TEMPLATE_TYPE, getTemplateType());
        b.putString(KEY_TEMPLATE_ID, mTemplateId);
        return b;
    }

    private ControlTemplate() {
        mTemplateId = "";
    }

    /**
     * @param b
     * @hide
     */
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

    /**
     *
     * @param bundle
     * @return
     * @hide
     */
    @NonNull
    static ControlTemplate createTemplateFromBundle(@Nullable Bundle bundle) {
        if (bundle == null) {
            Log.e(TAG, "Null bundle");
            return ERROR_TEMPLATE;
        }
        int type = bundle.getInt(KEY_TEMPLATE_TYPE, TYPE_ERROR);
        try {
            switch (type) {
                case TYPE_TOGGLE:
                    return new ToggleTemplate(bundle);
                case TYPE_RANGE:
                    return new RangeTemplate(bundle);
                case TYPE_THUMBNAIL:
                    return new ThumbnailTemplate(bundle);
                case TYPE_TOGGLE_RANGE:
                    return new ToggleRangeTemplate(bundle);
                case TYPE_TEMPERATURE:
                    return new TemperatureControlTemplate(bundle);
                case TYPE_STATELESS:
                    return new StatelessTemplate(bundle);
                case TYPE_NONE:
                    return NO_TEMPLATE;
                case TYPE_ERROR:
                default:
                    return ERROR_TEMPLATE;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating template", e);
            return ERROR_TEMPLATE;
        }
    }
}
