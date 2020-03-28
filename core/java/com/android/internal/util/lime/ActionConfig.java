/*
* Copyright (C) 2014 SlimRoms Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package com.android.internal.util.lime;

public class ActionConfig {

    private String mClickAction;
    private String mClickActionDescription;
    private String mLongpressAction;
    private String mLongpressActionDescription;
    private String mIconUri;

    public ActionConfig(String clickAction, String clickActionDescription,
                    String longpressAction, String longpressActionDescription, String iconUri) {
        mClickAction = clickAction;
        mClickActionDescription = clickActionDescription;
        mLongpressAction = longpressAction;
        mLongpressActionDescription = longpressActionDescription;
        mIconUri = iconUri;
    }

    @Override
    public String toString() {
        return mClickActionDescription;
    }

    public String getClickAction() {
        return mClickAction;
    }

    public String getClickActionDescription() {
        return mClickActionDescription;
    }

    public String getLongpressAction() {
        return mLongpressAction;
    }

    public String getLongpressActionDescription() {
        return mLongpressActionDescription;
    }

    public String getIcon() {
        return mIconUri;
    }

    public void setClickAction(String action) {
        mClickAction = action;
    }

    public void setClickActionDescription(String description) {
        mClickActionDescription = description;
    }

    public void setLongpressAction(String action) {
        mLongpressAction = action;
    }

    public void setLongpressActionDescription(String description) {
        mLongpressActionDescription = description;
    }

    public void setIcon(String iconUri) {
        mIconUri = iconUri;
    }

}
