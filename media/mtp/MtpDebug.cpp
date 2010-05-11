/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "MtpDebug.h"


struct OperationCodeEntry {
    const char* name;
    MtpOperationCode code;
};

static const OperationCodeEntry sOperationCodes[] = {
    { "MTP_OPERATION_GET_DEVICE_INFO",              0x1001 },
    { "MTP_OPERATION_OPEN_SESSION",                 0x1002 },
    { "MTP_OPERATION_CLOSE_SESSION",                0x1003 },
    { "MTP_OPERATION_GET_STORAGE_IDS",              0x1004 },
    { "MTP_OPERATION_GET_STORAGE_INFO",             0x1005 },
    { "MTP_OPERATION_GET_NUM_OBJECTS",              0x1006 },
    { "MTP_OPERATION_GET_OBJECT_HANDLES",           0x1007 },
    { "MTP_OPERATION_GET_OBJECT_INFO",              0x1008 },
    { "MTP_OPERATION_GET_OBJECT",                   0x1009 },
    { "MTP_OPERATION_GET_THUMB",                    0x100A },
    { "MTP_OPERATION_DELETE_OBJECT",                0x100B },
    { "MTP_OPERATION_SEND_OBJECT_INFO",             0x100C },
    { "MTP_OPERATION_SEND_OBJECT",                  0x100D },
    { "MTP_OPERATION_INITIATE_CAPTURE",             0x100E },
    { "MTP_OPERATION_FORMAT_STORE",                 0x100F },
    { "MTP_OPERATION_RESET_DEVICE",                 0x1010 },
    { "MTP_OPERATION_SELF_TEST",                    0x1011 },
    { "MTP_OPERATION_SET_OBJECT_PROTECTION",        0x1012 },
    { "MTP_OPERATION_POWER_DOWN",                   0x1013 },
    { "MTP_OPERATION_GET_DEVICE_PROP_DESC",         0x1014 },
    { "MTP_OPERATION_GET_DEVICE_PROP_VALUE",        0x1015 },
    { "MTP_OPERATION_SET_DEVICE_PROP_VALUE",        0x1016 },
    { "MTP_OPERATION_RESET_DEVICE_PROP_VALUE",      0x1017 },
    { "MTP_OPERATION_TERMINATE_OPEN_CAPTURE",       0x1018 },
    { "MTP_OPERATION_MOVE_OBJECT",                  0x1019 },
    { "MTP_OPERATION_COPY_OBJECT",                  0x101A },
    { "MTP_OPERATION_GET_PARTIAL_OBJECT",           0x101B },
    { "MTP_OPERATION_INITIATE_OPEN_CAPTURE",        0x101C },
    { "MTP_OPERATION_GET_OBJECT_PROPS_SUPPORTED",   0x9801 },
    { "MTP_OPERATION_GET_OBJECT_PROP_DESC",         0x9802 },
    { "MTP_OPERATION_GET_OBJECT_PROP_VALUE",        0x9803 },
    { "MTP_OPERATION_SET_OBJECT_PROP_VALUE",        0x9804 },
    { "MTP_OPERATION_GET_OBJECT_REFERENCES",        0x9810 },
    { "MTP_OPERATION_SET_OBJECT_REFERENCES",        0x9811 },
    { "MTP_OPERATION_SKIP",                         0x9820 },
    { 0,                                            0      },
};


const char* MtpDebug::getOperationCodeName(MtpOperationCode code) {
    const OperationCodeEntry* entry = sOperationCodes;
    while (entry->name) {
        if (entry->code == code)
            return entry->name;
        entry++;
    }
    return "*** UNKNOWN OPERATION ***";
}
