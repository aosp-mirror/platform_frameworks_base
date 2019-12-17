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

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;

/**
 * @hide
 */
public final class StatelessTemplate extends ControlTemplate {

    @Override
    public int getTemplateType() {
        return TYPE_STATELESS;
    }

    public StatelessTemplate(@NonNull Bundle b) {
        super(b);
    }

    public StatelessTemplate(@NonNull String templateId) {
        super(templateId);
    }

    public static final Creator<StatelessTemplate> CREATOR = new Creator<StatelessTemplate>() {
        @Override
        public StatelessTemplate createFromParcel(Parcel source) {
            return new StatelessTemplate(source.readBundle());
        }

        @Override
        public StatelessTemplate[] newArray(int size) {
            return new StatelessTemplate[size];
        }
    };
}
