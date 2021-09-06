/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.ONLY_IF_NO_MATCH_FOUND;
import static android.content.pm.PackageManager.SKIP_CURRENT_PROFILE;
import static android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;

import java.util.Arrays;
import java.util.List;

/**
 * Utility Class for {@link DefaultCrossProfileIntentFilter}.
 */
public class DefaultCrossProfileIntentFiltersUtils {

    private DefaultCrossProfileIntentFiltersUtils() {
    }

    // Intents from profile to parent user
    /** Emergency call intent with mime type is always resolved by primary user. */
    private static final DefaultCrossProfileIntentFilter
            EMERGENCY_CALL_MIME =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_CALL_EMERGENCY)
                    .addAction(Intent.ACTION_CALL_PRIVILEGED)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataType("vnd.android.cursor.item/phone")
                    .addDataType("vnd.android.cursor.item/phone_v2")
                    .addDataType("vnd.android.cursor.item/person")
                    .addDataType("vnd.android.cursor.dir/calls")
                    .addDataType("vnd.android.cursor.item/calls")
                    .build();

    /** Emergency call intent with data schemes is always resolved by primary user. */
    private static final DefaultCrossProfileIntentFilter
            EMERGENCY_CALL_DATA =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_CALL_EMERGENCY)
                    .addAction(Intent.ACTION_CALL_PRIVILEGED)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataScheme("tel")
                    .addDataScheme("sip")
                    .addDataScheme("voicemail")
                    .build();

    /** Dial intent with mime type can be handled by either managed profile or its parent user. */
    private static final DefaultCrossProfileIntentFilter DIAL_MIME =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataType("vnd.android.cursor.item/phone")
                    .addDataType("vnd.android.cursor.item/phone_v2")
                    .addDataType("vnd.android.cursor.item/person")
                    .addDataType("vnd.android.cursor.dir/calls")
                    .addDataType("vnd.android.cursor.item/calls")
                    .build();

    /** Dial intent with data scheme can be handled by either managed profile or its parent user. */
    private static final DefaultCrossProfileIntentFilter DIAL_DATA =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataScheme("tel")
                    .addDataScheme("sip")
                    .addDataScheme("voicemail")
                    .build();

    /**
     * Dial intent with no data scheme or type can be handled by either managed profile or its
     * parent user.
     */
    private static final DefaultCrossProfileIntentFilter DIAL_RAW =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .build();

    /** Pressing the call button can be handled by either managed profile or its parent user. */
    private static final DefaultCrossProfileIntentFilter CALL_BUTTON =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_CALL_BUTTON)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** SMS and MMS are exclusively handled by the primary user. */
    private static final DefaultCrossProfileIntentFilter SMS_MMS =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_VIEW)
                    .addAction(Intent.ACTION_SENDTO)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataScheme("sms")
                    .addDataScheme("smsto")
                    .addDataScheme("mms")
                    .addDataScheme("mmsto")
                    .build();

    /** Mobile network settings is always shown in the primary user. */
    private static final DefaultCrossProfileIntentFilter
            MOBILE_NETWORK_SETTINGS =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS)
                    .addAction(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** HOME intent is always resolved by the primary user. */
    static final DefaultCrossProfileIntentFilter HOME =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_HOME)
                    .build();

    /** Get content can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter GET_CONTENT =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addDataType("*/*")
                    .build();

    /** Pick images can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter ACTION_PICK_IMAGES =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();
    /** Pick images can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter ACTION_PICK_IMAGES_WITH_DATA_TYPES =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataType("image/*")
                    .addDataType("video/*")
                    .build();

    // TODO(b/199068419): Remove once GEM enables the intent for Googlers
    /** Pick images can be forwarded to work profile. */
    private static final DefaultCrossProfileIntentFilter ACTION_PICK_IMAGES_TO_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();
    // TODO(b/199068419): Remove once GEM enables the intent for Googlers
    /** Pick images can be forwarded to work profile. */
    private static final DefaultCrossProfileIntentFilter
            ACTION_PICK_IMAGES_WITH_DATA_TYPES_TO_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataType("image/*")
                    .addDataType("video/*")
                    .build();

    /** Open document intent can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter OPEN_DOCUMENT =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .addDataType("*/*")
                    .build();

    /** Pick for any data type can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter ACTION_PICK_DATA =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(Intent.ACTION_PICK)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataType("*/*")
                    .build();

    /** Pick without data type can be forwarded to parent user. */
    private static final DefaultCrossProfileIntentFilter ACTION_PICK_RAW =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(Intent.ACTION_PICK)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** Speech recognition can be performed by primary user. */
    private static final DefaultCrossProfileIntentFilter RECOGNIZE_SPEECH =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(ACTION_RECOGNIZE_SPEECH)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** Media capture can be performed by primary user. */
    private static final DefaultCrossProfileIntentFilter MEDIA_CAPTURE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(MediaStore.ACTION_IMAGE_CAPTURE)
                    .addAction(MediaStore.ACTION_IMAGE_CAPTURE_SECURE)
                    .addAction(MediaStore.ACTION_VIDEO_CAPTURE)
                    .addAction(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    .addAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addAction(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** Alarm setting can be performed by primary user. */
    private static final DefaultCrossProfileIntentFilter SET_ALARM =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(AlarmClock.ACTION_SET_ALARM)
                    .addAction(AlarmClock.ACTION_SHOW_ALARMS)
                    .addAction(AlarmClock.ACTION_SET_TIMER)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    // Intents from parent to profile user

    /** ACTION_SEND can be forwarded to the managed profile on user's choice. */
    private static final DefaultCrossProfileIntentFilter ACTION_SEND =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ true)
                    .addAction(Intent.ACTION_SEND)
                    .addAction(Intent.ACTION_SEND_MULTIPLE)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataType("*/*")
                    .build();

    /** USB devices attached can get forwarded to the profile. */
    private static final DefaultCrossProfileIntentFilter
            USB_DEVICE_ATTACHED =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */0,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    .addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    public static List<DefaultCrossProfileIntentFilter> getDefaultManagedProfileFilters() {
        return Arrays.asList(
                EMERGENCY_CALL_MIME,
                EMERGENCY_CALL_DATA,
                DIAL_MIME,
                DIAL_DATA,
                DIAL_RAW,
                CALL_BUTTON,
                SMS_MMS,
                SET_ALARM,
                MEDIA_CAPTURE,
                RECOGNIZE_SPEECH,
                ACTION_PICK_RAW,
                ACTION_PICK_DATA,
                ACTION_PICK_IMAGES,
                ACTION_PICK_IMAGES_WITH_DATA_TYPES,
                ACTION_PICK_IMAGES_TO_PROFILE,
                ACTION_PICK_IMAGES_WITH_DATA_TYPES_TO_PROFILE,
                OPEN_DOCUMENT,
                GET_CONTENT,
                USB_DEVICE_ATTACHED,
                ACTION_SEND,
                HOME,
                MOBILE_NETWORK_SETTINGS);
    }
}
