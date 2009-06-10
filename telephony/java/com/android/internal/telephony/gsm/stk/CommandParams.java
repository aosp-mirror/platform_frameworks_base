/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.graphics.Bitmap;

/**
 * Container class for proactive command parameters.
 *
 */
class CommandParams {
    CommandDetails cmdDet;

    CommandParams(CommandDetails cmdDet) {
        this.cmdDet = cmdDet;
    }

    AppInterface.CommandType getCommandType() {
        return AppInterface.CommandType.fromInt(cmdDet.typeOfCommand);
    }

    boolean setIcon(Bitmap icon) { return true; }
}

class DisplayTextParams extends CommandParams {
    TextMessage textMsg;

    DisplayTextParams(CommandDetails cmdDet, TextMessage textMsg) {
        super(cmdDet);
        this.textMsg = textMsg;
    }

    boolean setIcon(Bitmap icon) {
        if (icon != null && textMsg != null) {
            textMsg.icon = icon;
            return true;
        }
        return false;
    }
}

class LaunchBrowserParams extends CommandParams {
    TextMessage confirmMsg;
    LaunchBrowserMode mode;
    String url;

    LaunchBrowserParams(CommandDetails cmdDet, TextMessage confirmMsg,
            String url, LaunchBrowserMode mode) {
        super(cmdDet);
        this.confirmMsg = confirmMsg;
        this.mode = mode;
        this.url = url;
    }

    boolean setIcon(Bitmap icon) {
        if (icon != null && confirmMsg != null) {
            confirmMsg.icon = icon;
            return true;
        }
        return false;
    }
}

class PlayToneParams extends CommandParams {
    TextMessage textMsg;
    ToneSettings settings;

    PlayToneParams(CommandDetails cmdDet, TextMessage textMsg,
            Tone tone, Duration duration, boolean vibrate) {
        super(cmdDet);
        this.textMsg = textMsg;
        this.settings = new ToneSettings(duration, tone, vibrate);
    }

    boolean setIcon(Bitmap icon) {
        if (icon != null && textMsg != null) {
            textMsg.icon = icon;
            return true;
        }
        return false;
    }
}

class CallSetupParams extends CommandParams {
    TextMessage confirmMsg;
    TextMessage callMsg;

    CallSetupParams(CommandDetails cmdDet, TextMessage confirmMsg,
            TextMessage callMsg) {
        super(cmdDet);
        this.confirmMsg = confirmMsg;
        this.callMsg = callMsg;
    }

    boolean setIcon(Bitmap icon) {
        if (icon == null) {
            return false;
        }
        if (confirmMsg != null && confirmMsg.icon == null) {
            confirmMsg.icon = icon;
            return true;
        } else if (callMsg != null && callMsg.icon == null) {
            callMsg.icon = icon;
            return true;
        }
        return false;
    }
}

class SelectItemParams extends CommandParams {
    Menu menu = null;
    boolean loadTitleIcon = false;

    SelectItemParams(CommandDetails cmdDet, Menu menu, boolean loadTitleIcon) {
        super(cmdDet);
        this.menu = menu;
        this.loadTitleIcon = loadTitleIcon;
    }

    boolean setIcon(Bitmap icon) {
        if (icon != null && menu != null) {
            if (loadTitleIcon && menu.titleIcon == null) {
                menu.titleIcon = icon;
            } else {
                for (Item item : menu.items) {
                    if (item.icon != null) {
                        continue;
                    }
                    item.icon = icon;
                    break;
                }
            }
            return true;
        }
        return false;
    }
}

class GetInputParams extends CommandParams {
    Input input = null;

    GetInputParams(CommandDetails cmdDet, Input input) {
        super(cmdDet);
        this.input = input;
    }

    boolean setIcon(Bitmap icon) {
        if (icon != null && input != null) {
            input.icon = icon;
        }
        return true;
    }
}


