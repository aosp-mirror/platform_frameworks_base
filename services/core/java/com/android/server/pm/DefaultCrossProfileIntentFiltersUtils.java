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

import static com.android.server.pm.CrossProfileIntentFilter.FLAG_ALLOW_CHAINED_RESOLUTION;
import static com.android.server.pm.CrossProfileIntentFilter.FLAG_IS_PACKAGE_FOR_FILTER;

import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;

import java.util.ArrayList;
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

    /** Dial intent with mime type exclusively handled by managed profile. */
    private static final DefaultCrossProfileIntentFilter DIAL_MIME_MANAGED_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    SKIP_CURRENT_PROFILE,
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

    /** Dial intent with data scheme exclusively handled by managed profile. */
    private static final DefaultCrossProfileIntentFilter DIAL_DATA_MANAGED_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    SKIP_CURRENT_PROFILE,
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

    /**
     * Dial intent with no data scheme or type exclusively handled by managed profile.
     */
    private static final DefaultCrossProfileIntentFilter DIAL_RAW_MANAGED_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    SKIP_CURRENT_PROFILE,
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

    /** SMS and MMS intent exclusively handled by the managed profile. */
    private static final DefaultCrossProfileIntentFilter SMS_MMS_MANAGED_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
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
                    /* flags= */ ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(ACTION_RECOGNIZE_SPEECH)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /** Media capture can be performed by primary user. */
    private static final DefaultCrossProfileIntentFilter MEDIA_CAPTURE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ ONLY_IF_NO_MATCH_FOUND,
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
        List<DefaultCrossProfileIntentFilter> filters =
                new ArrayList<DefaultCrossProfileIntentFilter>();
        filters.addAll(Arrays.asList(
                EMERGENCY_CALL_MIME,
                EMERGENCY_CALL_DATA,
                CALL_BUTTON,
                SET_ALARM,
                MEDIA_CAPTURE,
                RECOGNIZE_SPEECH,
                ACTION_PICK_RAW,
                ACTION_PICK_DATA,
                ACTION_PICK_IMAGES,
                ACTION_PICK_IMAGES_WITH_DATA_TYPES,
                OPEN_DOCUMENT,
                GET_CONTENT,
                USB_DEVICE_ATTACHED,
                ACTION_SEND,
                HOME,
                MOBILE_NETWORK_SETTINGS));
        filters.addAll(getDefaultCrossProfileTelephonyIntentFilters(false));
        return filters;
    }

    /** Call intent with tel scheme exclusively handled my managed profile.
     * Note that work profile telephony relies on this intent filter to redirect intents to
     * the IntentForwarderActivity. Work profile telephony error handling must be updated in
     * the Telecomm package CallsManager if this filter is changed.
     */
    private static final DefaultCrossProfileIntentFilter CALL_MANAGED_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    SKIP_CURRENT_PROFILE,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_CALL)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataScheme("tel")
                    .build();

    /**
     * Returns default telephony related intent filters for managed profile.
     */
    public static List<DefaultCrossProfileIntentFilter>
            getDefaultCrossProfileTelephonyIntentFilters(boolean telephonyOnlyInManagedProfile) {
        if (telephonyOnlyInManagedProfile) {
            return Arrays.asList(
                    DIAL_DATA_MANAGED_PROFILE,
                    DIAL_MIME_MANAGED_PROFILE,
                    DIAL_RAW_MANAGED_PROFILE,
                    CALL_MANAGED_PROFILE,
                    SMS_MMS_MANAGED_PROFILE);
        } else {
            return Arrays.asList(
                    DIAL_DATA,
                    DIAL_MIME,
                    DIAL_RAW,
                    SMS_MMS);
        }
    }

    /**
     * Clone profile's DefaultCrossProfileIntentFilter
     */

    /*
     Allowing media capture from clone to parent profile as clone profile would not have camera
     */
    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_MEDIA_CAPTURE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                                            // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(MediaStore.ACTION_IMAGE_CAPTURE)
                    .addAction(MediaStore.ACTION_IMAGE_CAPTURE_SECURE)
                    .addAction(MediaStore.ACTION_VIDEO_CAPTURE)
                    .addAction(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
                    .addAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    .addAction(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
                    .addAction(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_PHOTOPICKER_SELECTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(MediaStore.ACTION_USER_SELECT_IMAGES_FOR_APP)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    /*
     Allowing send action from clone to parent profile to share content from clone apps to parent
     apps
     */
    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_SEND_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_SEND)
                    .addAction(Intent.ACTION_SEND_MULTIPLE)
                    .addAction(Intent.ACTION_SENDTO)
                    .addDataType("*/*")
                    .build();

    /*
     Allowing send action from parent to clone profile to share content from parent apps to clone
     apps
     */
    private static final DefaultCrossProfileIntentFilter PARENT_TO_CLONE_SEND_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                                            // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_SEND)
                    .addAction(Intent.ACTION_SEND_MULTIPLE)
                    .addAction(Intent.ACTION_SENDTO)
                    .addDataType("*/*")
                    .build();

    /*
     Allowing view action from clone to parent profile to open any app-links or web links
     */
    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_WEB_VIEW_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_VIEW)
                    .addDataScheme("https")
                    .addDataScheme("http")
                    .build();

    /*
     Allowing view action from parent to clone profile to open any app-links or web links
     */
    private static final DefaultCrossProfileIntentFilter PARENT_TO_CLONE_WEB_VIEW_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                                            // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_VIEW)
                    .addDataScheme("https")
                    .addDataScheme("http")
                    .build();

    /*
     Allowing view action from clone to parent profile to any data type e.g. pdf, including custom
     content providers.
     */
    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_VIEW_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_VIEW)
                    .addDataType("*/*")
                    .build();


    /*
     Allowing pick,insert and edit action from clone to parent profile to open picker or contacts
     insert/edit.
     */
    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_PICK_INSERT_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                                            // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_PICK)
                    .addAction(Intent.ACTION_GET_CONTENT)
                    .addAction(Intent.ACTION_EDIT)
                    .addAction(Intent.ACTION_INSERT)
                    .addAction(Intent.ACTION_INSERT_OR_EDIT)
                    .addAction(Intent.ACTION_OPEN_DOCUMENT)
                    .addDataType("*/*")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .build();

    /*
     Allowing pick,insert and edit action from parent to clone profile to open picker
     */
    private static final DefaultCrossProfileIntentFilter PARENT_TO_CLONE_PICK_INSERT_ACTION =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                                            // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_PICK)
                    .addAction(Intent.ACTION_GET_CONTENT)
                    .addAction(Intent.ACTION_EDIT)
                    .addAction(Intent.ACTION_INSERT)
                    .addAction(Intent.ACTION_INSERT_OR_EDIT)
                    .addAction(Intent.ACTION_OPEN_DOCUMENT)
                    .addDataType("*/*")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .build();

    private static final DefaultCrossProfileIntentFilter PARENT_TO_CLONE_DIAL_DATA =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PROFILE,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataScheme("tel")
                    .addDataScheme("sip")
                    .addDataScheme("voicemail")
                    .build();

    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_DIAL_DATA =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addDataScheme("tel")
                    .addDataScheme("sip")
                    .addDataScheme("voicemail")
                    .build();

    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_SMS_MMS =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ 0x00000018, // 0x00000018 means FLAG_IS_PACKAGE_FOR_FILTER
                    // and FLAG_ALLOW_CHAINED_RESOLUTION set
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

    private static final DefaultCrossProfileIntentFilter CLONE_TO_PARENT_ACTION_PICK_IMAGES =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ FLAG_IS_PACKAGE_FOR_FILTER | FLAG_ALLOW_CHAINED_RESOLUTION,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .build();

    private static final DefaultCrossProfileIntentFilter
            CLONE_TO_PARENT_ACTION_PICK_IMAGES_WITH_DATA_TYPES =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    /* flags= */ FLAG_IS_PACKAGE_FOR_FILTER | FLAG_ALLOW_CHAINED_RESOLUTION,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(MediaStore.ACTION_PICK_IMAGES)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addDataType("image/*")
                    .addDataType("video/*")
                    .build();

    public static List<DefaultCrossProfileIntentFilter> getDefaultCloneProfileFilters() {
        return Arrays.asList(
                PARENT_TO_CLONE_SEND_ACTION,
                PARENT_TO_CLONE_WEB_VIEW_ACTION,
                PARENT_TO_CLONE_PICK_INSERT_ACTION,
                PARENT_TO_CLONE_DIAL_DATA,
                CLONE_TO_PARENT_MEDIA_CAPTURE,
                CLONE_TO_PARENT_SEND_ACTION,
                CLONE_TO_PARENT_WEB_VIEW_ACTION,
                CLONE_TO_PARENT_VIEW_ACTION,
                CLONE_TO_PARENT_PICK_INSERT_ACTION,
                CLONE_TO_PARENT_DIAL_DATA,
                CLONE_TO_PARENT_SMS_MMS,
                CLONE_TO_PARENT_PHOTOPICKER_SELECTION,
                CLONE_TO_PARENT_ACTION_PICK_IMAGES,
                CLONE_TO_PARENT_ACTION_PICK_IMAGES_WITH_DATA_TYPES
        );
    }

    /** Dial intent with mime type can be handled by either private profile or its parent user. */
    private static final DefaultCrossProfileIntentFilter DIAL_MIME_PRIVATE_PROFILE =
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

    /** Dial intent with data scheme can be handled by either private profile or its parent user. */
    private static final DefaultCrossProfileIntentFilter DIAL_DATA_PRIVATE_PROFILE =
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
     * Dial intent with no data scheme or type can be handled by either private profile or its
     * parent user.
     */
    private static final DefaultCrossProfileIntentFilter DIAL_RAW_PRIVATE_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
                    /* letsPersonalDataIntoProfile= */ false)
                    .addAction(Intent.ACTION_DIAL)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .build();

    /** SMS and MMS can be handled by the private profile or by the parent user. */
    private static final DefaultCrossProfileIntentFilter SMS_MMS_PRIVATE_PROFILE =
            new DefaultCrossProfileIntentFilter.Builder(
                    DefaultCrossProfileIntentFilter.Direction.TO_PARENT,
                    ONLY_IF_NO_MATCH_FOUND,
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

    public static List<DefaultCrossProfileIntentFilter> getDefaultPrivateProfileFilters() {
        return Arrays.asList(
                DIAL_MIME_PRIVATE_PROFILE,
                DIAL_DATA_PRIVATE_PROFILE,
                DIAL_RAW_PRIVATE_PROFILE,
                SMS_MMS_PRIVATE_PROFILE
        );
    }
}
