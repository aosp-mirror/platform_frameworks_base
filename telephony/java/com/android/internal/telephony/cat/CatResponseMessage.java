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

public class StkResponseMessage {
        CommandDetails cmdDet = null;
        ResultCode resCode  = ResultCode.OK;
        int usersMenuSelection = 0;
        String usersInput  = null;
        boolean usersYesNoSelection = false;
        boolean usersConfirm = false;

        public StkResponseMessage(StkCmdMessage cmdMsg) {
            this.cmdDet = cmdMsg.mCmdDet;
        }

        public void setResultCode(ResultCode resCode) {
            this.resCode = resCode;
        }

        public void setMenuSelection(int selection) {
            this.usersMenuSelection = selection;
        }

        public void setInput(String input) {
            this.usersInput = input;
        }

        public void setYesNo(boolean yesNo) {
            usersYesNoSelection = yesNo;
        }

        public void setConfirmation(boolean confirm) {
            usersConfirm = confirm;
        }

        CommandDetails getCmdDetails() {
            return cmdDet;
        }
    }