/*
 * Copyright (C) 2015 TeamEos project
 * Author Randall Rushing aka bigrushdog, randall.rushing@gmail.com
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
 * 
 * Widgets may implement this interface to interact with action configurations
 * 
 */

package com.android.internal.util.hwkeys;

import com.android.internal.util.hwkeys.ActionConstants.ConfigMap;
import com.android.internal.util.hwkeys.ActionConstants.Defaults;
import com.android.internal.util.hwkeys.Config.ActionConfig;
import com.android.internal.util.hwkeys.Config.ButtonConfig;

public interface ActionHolder {
   public String getTag();
   public void setTag(String tag);
   public Defaults getDefaults();
   public void setDefaults(Defaults defaults);
   public ConfigMap getConfigMap();
   public void setConfigMap(ConfigMap map);
   public ButtonConfig getButtonConfig();
   public void setButtonConfig(ButtonConfig button);
   public ActionConfig getActionConfig();
   public void setActionConfig(ActionConfig action);
   public ButtonConfig getDefaultButtonConfig();
   public void setDefaultButtonConfig(ButtonConfig button);
   public ActionConfig getDefaultActionConfig();
   public void setDefaultActionConfig(ActionConfig action);
}
