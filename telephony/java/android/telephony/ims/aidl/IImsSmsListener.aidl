/*
 * Copyright (c) 2017 The Android Open Source Project
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

package android.telephony.ims.aidl;

/**
 * See SmsImplBase for more information.
 * {@hide}
 */
oneway interface IImsSmsListener {
    void onSendSmsResult(int token, int messageRef, int status, int reason);
    void onSmsStatusReportReceived(int token, in String format, in byte[] pdu);
    void onSmsReceived(int token, in String format, in byte[] pdu);
}
