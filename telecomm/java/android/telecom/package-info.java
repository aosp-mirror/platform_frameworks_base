/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

/**
 * The Android Telecom framework is responsible for managing calls on an Android device.  This can
 * include SIM-based calls using the {@code Telephony} framework, VOIP calls using SIP (e.g. the
 * {@code SipConnectionService}), or via a third-party VOIP
 * {@link android.telecom.ConnectionService}.  Telecom acts as a switchboard, routing calls and
 * audio focus between {@link android.telecom.Connection}s provided by
 * {@link android.telecom.ConnectionService} implementations, and
 * {@link android.telecom.InCallService} implementations which provide a user interface for calls.
 * <p>
 * Android supports the following calling use cases (with increasing level of complexity):
 * <ul>
 *     <li>Implement the self-managed {@link android.telecom.ConnectionService} API - this is ideal
 *     for developers of standalone calling apps which do not wish to show their calls within the
 *     default phone app, and do not wish to have other calls shown in their user interface.  Using
 *     a self-managed {@link android.telecom.ConnectionService} implementation within your
 *     standalone calling app helps you ensure that your app will interoperate not only with native
 *     telephony calling on the device, but also other standalone calling apps implementing this
 *     API.  It also manages audio routing and focus for you.</li>
 *     <li>Implement the managed {@link android.telecom.ConnectionService} API - facilitates
 *     development of a calling solution that relies on the existing device phone application (see
 *     {@link android.telecom.TelecomManager#getDefaultDialerPackage()}) to provide the user
 *     interface for calls.  An example might be a third party implementation of SIP calling, or a
 *     VOIP calling service.  A {@link android.telecom.ConnectionService} alone provides only the
 *     means of connecting calls, but has no associated user interface.</li>
 *     <li>Implement the {@link android.telecom.InCallService} API - facilitates development of a
 *     replacement for the device's default Phone/Dialer app.  The
 *     {@link android.telecom.InCallService} alone does not have any calling capability and consists
 *     of the user-interface side of calling only.  An {@link android.telecom.InCallService} must
 *     handle all Calls the Telecom framework is aware of.  It must not make assumptions about the
 *     nature of the calls (e.g. assuming calls are SIM-based telephony calls), and should not
 *     implement calling restrictions based on any one {@link android.telecom.ConnectionService}
 *     (e.g. it should not enforce Telephony restrictions for video calls).</li>
 *     <li>Implement both the {@link android.telecom.InCallService} and
 *     {@link android.telecom.ConnectionService} API - ideal if you wish to create your own
 *     {@link android.telecom.ConnectionService} based calling solution, complete with its own
 *     full user interface, while showing all other Android calls in the same user interface.  Using
 *     this approach, you must still ensure that your {@link android.telecom.InCallService} makes
 *     no assumption about the source of the calls it displays.  You must also ensure that your
 *     {@link android.telecom.ConnectionService} implementation can still function without the
 *     default phone app being set to your custom {@link android.telecom.InCallService}.</li>
 * </ul>
 */
package android.telecom;