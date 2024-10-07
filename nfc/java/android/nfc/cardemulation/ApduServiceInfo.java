/*
 * Copyright (C) 2013 The Android Open Source Project
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

/**********************************************************************
 * This file is not a part of the NFC mainline module                 *
 * *******************************************************************/

package android.nfc.cardemulation;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.nfc.Flags;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Class holding APDU service info.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
public final class ApduServiceInfo implements Parcelable {
    private static final String TAG = "ApduServiceInfo";

    /**
     * Component level {@link android.content.pm.PackageManager.Property PackageManager
     * .Property} for a system application to change its icon and label
     * on the default applications page. This property should be added to an
     * {@link HostApduService} declaration in the manifest.
     *
     * <p>For example:
     * <pre>
     * &lt;service
     *     android:apduServiceBanner="@drawable/product_logo"
     *     android:label="@string/service_label"&gt
     *      &lt;property
     *          android:name="android.content.PROPERTY_WALLET_ICON_AND_LABEL_HOLDER"
     *          android:value="true"/&gt;
     * &lt/service&gt;
     * </pre>
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.permission.flags.Flags.FLAG_WALLET_ROLE_ICON_PROPERTY_ENABLED)
    public static final String PROPERTY_WALLET_PREFERRED_BANNER_AND_LABEL =
            "android.nfc.cardemulation.PROPERTY_WALLET_PREFERRED_BANNER_AND_LABEL";

    /**
     * The service that implements this
     */
    private final ResolveInfo mService;

    /**
     * Description of the service
     */
    private final String mDescription;

    /**
     * Whether this service represents AIDs running on the host CPU
     */
    private final boolean mOnHost;

    /**
     * Offhost reader name.
     * eg: SIM, eSE etc
     */
    private String mOffHostName;

    /**
     * Offhost reader name from manifest file.
     * Used for resetOffHostSecureElement()
     */
    private final String mStaticOffHostName;

    /**
     * Mapping from category to static AID group
     */
    private final HashMap<String, AidGroup> mStaticAidGroups;

    /**
     * Mapping from category to dynamic AID group
     */
    private final HashMap<String, AidGroup> mDynamicAidGroups;


    private final Map<String, Boolean> mAutoTransact;

    private final Map<Pattern, Boolean> mAutoTransactPatterns;

    /**
     * Whether this service should only be started when the device is unlocked.
     */
    private final boolean mRequiresDeviceUnlock;

    /**
     * Whether this service should only be started when the device is screen on.
     */
    private final boolean mRequiresDeviceScreenOn;

    /**
     * The id of the service banner specified in XML.
     */
    private final int mBannerResourceId;

    /**
     * The uid of the package the service belongs to
     */
    private final int mUid;

    /**
     * Settings Activity for this service
     */
    private final String mSettingsActivityName;

    /**
     * State of the service for CATEGORY_OTHER selection
     */
    private boolean mCategoryOtherServiceEnabled;

    /**
     * Whether the NFC stack should default to Observe Mode when this preferred service.
     */
    private boolean mShouldDefaultToObserveMode;

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public ApduServiceInfo(ResolveInfo info, boolean onHost, String description,
            ArrayList<AidGroup> staticAidGroups, ArrayList<AidGroup> dynamicAidGroups,
            boolean requiresUnlock, int bannerResource, int uid,
            String settingsActivityName, String offHost, String staticOffHost) {
        this(info, onHost, description, staticAidGroups, dynamicAidGroups,
                requiresUnlock, bannerResource, uid, settingsActivityName,
                offHost, staticOffHost, false);
    }

    /**
     * @hide
     */
    public ApduServiceInfo(ResolveInfo info, boolean onHost, String description,
            ArrayList<AidGroup> staticAidGroups, ArrayList<AidGroup> dynamicAidGroups,
            boolean requiresUnlock, int bannerResource, int uid,
            String settingsActivityName, String offHost, String staticOffHost,
            boolean isEnabled) {
        this(info, onHost, description, staticAidGroups, dynamicAidGroups,
                requiresUnlock, onHost ? true : false, bannerResource, uid,
                settingsActivityName, offHost, staticOffHost, isEnabled);
    }

    /**
     * @hide
     */
    public ApduServiceInfo(ResolveInfo info, boolean onHost, String description,
            List<AidGroup> staticAidGroups, List<AidGroup> dynamicAidGroups,
            boolean requiresUnlock, boolean requiresScreenOn, int bannerResource, int uid,
            String settingsActivityName, String offHost, String staticOffHost, boolean isEnabled) {
        this(info, onHost, description, staticAidGroups, dynamicAidGroups,
                requiresUnlock, requiresScreenOn, bannerResource, uid,
                settingsActivityName, offHost, staticOffHost, isEnabled,
                new HashMap<String, Boolean>(), new HashMap<Pattern, Boolean>());
    }

    /**
     * @hide
     */
    public ApduServiceInfo(ResolveInfo info, boolean onHost, String description,
            List<AidGroup> staticAidGroups, List<AidGroup> dynamicAidGroups,
            boolean requiresUnlock, boolean requiresScreenOn, int bannerResource, int uid,
            String settingsActivityName, String offHost, String staticOffHost, boolean isEnabled,
            Map<String, Boolean> autoTransact, Map<Pattern, Boolean> autoTransactPatterns) {
        this.mService = info;
        this.mDescription = description;
        this.mStaticAidGroups = new HashMap<String, AidGroup>();
        this.mDynamicAidGroups = new HashMap<String, AidGroup>();
        this.mAutoTransact = autoTransact;
        this.mAutoTransactPatterns = autoTransactPatterns;
        this.mOffHostName = offHost;
        this.mStaticOffHostName = staticOffHost;
        this.mOnHost = onHost;
        this.mRequiresDeviceUnlock = requiresUnlock;
        this.mRequiresDeviceScreenOn = requiresScreenOn;
        for (AidGroup aidGroup : staticAidGroups) {
            this.mStaticAidGroups.put(aidGroup.getCategory(), aidGroup);
        }
        for (AidGroup aidGroup : dynamicAidGroups) {
            this.mDynamicAidGroups.put(aidGroup.getCategory(), aidGroup);
        }
        this.mBannerResourceId = bannerResource;
        this.mUid = uid;
        this.mSettingsActivityName = settingsActivityName;
        this.mCategoryOtherServiceEnabled = isEnabled;
    }

    /**
     * Creates a new ApduServiceInfo object.
     *
     * @param pm packageManager instance
     * @param info app component info
     * @param onHost whether service is on host or not (secure element)
     * @throws XmlPullParserException If an error occurs parsing the element.
     * @throws IOException If an error occurs reading the element.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public ApduServiceInfo(@NonNull PackageManager pm, @NonNull ResolveInfo info, boolean onHost)
            throws XmlPullParserException, IOException {
        ServiceInfo si = info.serviceInfo;
        XmlResourceParser parser = null;
        try {
            if (onHost) {
                parser = si.loadXmlMetaData(pm, HostApduService.SERVICE_META_DATA);
                if (parser == null) {
                    throw new XmlPullParserException("No " + HostApduService.SERVICE_META_DATA +
                            " meta-data");
                }
            } else {
                parser = si.loadXmlMetaData(pm, OffHostApduService.SERVICE_META_DATA);
                if (parser == null) {
                    throw new XmlPullParserException("No " + OffHostApduService.SERVICE_META_DATA +
                            " meta-data");
                }
            }

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }

            String tagName = parser.getName();
            if (onHost && !"host-apdu-service".equals(tagName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with <host-apdu-service> tag");
            } else if (!onHost && !"offhost-apdu-service".equals(tagName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with <offhost-apdu-service> tag");
            }

            Resources res = pm.getResourcesForApplication(si.applicationInfo);
            AttributeSet attrs = Xml.asAttributeSet(parser);
            if (onHost) {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.HostApduService);
                mService = info;
                mDescription = sa.getString(
                        com.android.internal.R.styleable.HostApduService_description);
                mRequiresDeviceUnlock = sa.getBoolean(
                        com.android.internal.R.styleable.HostApduService_requireDeviceUnlock,
                        false);
                mRequiresDeviceScreenOn = sa.getBoolean(
                        com.android.internal.R.styleable.HostApduService_requireDeviceScreenOn,
                        true);
                mBannerResourceId = sa.getResourceId(
                        com.android.internal.R.styleable.HostApduService_apduServiceBanner, -1);
                mSettingsActivityName = sa.getString(
                        com.android.internal.R.styleable.HostApduService_settingsActivity);
                mOffHostName = null;
                mStaticOffHostName = mOffHostName;
                mShouldDefaultToObserveMode = sa.getBoolean(
                        R.styleable.HostApduService_shouldDefaultToObserveMode,
                        false);
                sa.recycle();
            } else {
                TypedArray sa = res.obtainAttributes(attrs,
                        com.android.internal.R.styleable.OffHostApduService);
                mService = info;
                mDescription = sa.getString(
                        com.android.internal.R.styleable.OffHostApduService_description);
                mRequiresDeviceUnlock = sa.getBoolean(
                        com.android.internal.R.styleable.OffHostApduService_requireDeviceUnlock,
                        false);
                mRequiresDeviceScreenOn = sa.getBoolean(
                        com.android.internal.R.styleable.OffHostApduService_requireDeviceScreenOn,
                        false);
                mBannerResourceId = sa.getResourceId(
                        com.android.internal.R.styleable.OffHostApduService_apduServiceBanner, -1);
                mSettingsActivityName = sa.getString(
                        com.android.internal.R.styleable.HostApduService_settingsActivity);
                mOffHostName = sa.getString(
                        com.android.internal.R.styleable.OffHostApduService_secureElementName);
                mShouldDefaultToObserveMode = sa.getBoolean(
                        R.styleable.HostApduService_shouldDefaultToObserveMode,
                        false);
                if (mOffHostName != null) {
                    if (mOffHostName.equals("eSE")) {
                        mOffHostName = "eSE1";
                    } else if (mOffHostName.equals("SIM")) {
                        mOffHostName = "SIM1";
                    }
                }
                mStaticOffHostName = mOffHostName;
                sa.recycle();
            }

            mStaticAidGroups = new HashMap<String, AidGroup>();
            mDynamicAidGroups = new HashMap<String, AidGroup>();
            mAutoTransact = new HashMap<String, Boolean>();
            mAutoTransactPatterns = new HashMap<Pattern, Boolean>();
            mOnHost = onHost;

            final int depth = parser.getDepth();
            AidGroup currentGroup = null;

            // Parsed values for the current AID group
            while (((eventType = parser.next()) != XmlPullParser.END_TAG || parser.getDepth() > depth)
                    && eventType != XmlPullParser.END_DOCUMENT) {
                tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG && "aid-group".equals(tagName) &&
                        currentGroup == null) {
                    final TypedArray groupAttrs = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidGroup);
                    // Get category of AID group
                    String groupCategory = groupAttrs.getString(
                            com.android.internal.R.styleable.AidGroup_category);
                    String groupDescription = groupAttrs.getString(
                            com.android.internal.R.styleable.AidGroup_description);
                    if (!CardEmulation.CATEGORY_PAYMENT.equals(groupCategory)) {
                        groupCategory = CardEmulation.CATEGORY_OTHER;
                    }
                    currentGroup = mStaticAidGroups.get(groupCategory);
                    if (currentGroup != null) {
                        if (!CardEmulation.CATEGORY_OTHER.equals(groupCategory)) {
                            Log.e(TAG, "Not allowing multiple aid-groups in the " +
                                    groupCategory + " category");
                            currentGroup = null;
                        }
                    } else {
                        currentGroup = new AidGroup(groupCategory, groupDescription);
                    }
                    groupAttrs.recycle();
                } else if (eventType == XmlPullParser.END_TAG && "aid-group".equals(tagName) &&
                        currentGroup != null) {
                    if (currentGroup.getAids().size() > 0) {
                        if (!mStaticAidGroups.containsKey(currentGroup.getCategory())) {
                            mStaticAidGroups.put(currentGroup.getCategory(), currentGroup);
                        }
                    } else {
                        Log.e(TAG, "Not adding <aid-group> with empty or invalid AIDs");
                    }
                    currentGroup = null;
                } else if (eventType == XmlPullParser.START_TAG && "aid-filter".equals(tagName) &&
                        currentGroup != null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidFilter);
                    String aid = a.getString(com.android.internal.R.styleable.AidFilter_name).
                            toUpperCase();
                    if (isValidAid(aid) && !currentGroup.getAids().contains(aid)) {
                        currentGroup.getAids().add(aid);
                    } else {
                        Log.e(TAG, "Ignoring invalid or duplicate aid: " + aid);
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG &&
                        "aid-prefix-filter".equals(tagName) && currentGroup != null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidFilter);
                    String aid = a.getString(com.android.internal.R.styleable.AidFilter_name).
                            toUpperCase();
                    // Add wildcard char to indicate prefix
                    aid = aid.concat("*");
                    if (isValidAid(aid) && !currentGroup.getAids().contains(aid)) {
                        currentGroup.getAids().add(aid);
                    } else {
                        Log.e(TAG, "Ignoring invalid or duplicate aid: " + aid);
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG &&
                        tagName.equals("aid-suffix-filter") && currentGroup != null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.AidFilter);
                    String aid = a.getString(com.android.internal.R.styleable.AidFilter_name).
                            toUpperCase();
                    // Add wildcard char to indicate suffix
                    aid = aid.concat("#");
                    if (isValidAid(aid) && !currentGroup.getAids().contains(aid)) {
                        currentGroup.getAids().add(aid);
                    } else {
                        Log.e(TAG, "Ignoring invalid or duplicate aid: " + aid);
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG
                        && "polling-loop-filter".equals(tagName) && currentGroup == null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.PollingLoopFilter);
                    String plf =
                            a.getString(com.android.internal.R.styleable.PollingLoopFilter_name)
                            .toUpperCase(Locale.ROOT);
                    boolean autoTransact = a.getBoolean(
                            com.android.internal.R.styleable.PollingLoopFilter_autoTransact,
                            false);
                    if (!mOnHost && !autoTransact) {
                        Log.e(TAG, "Ignoring polling-loop-filter " + plf
                                + " for offhost service that isn't autoTransact");
                    } else {
                        mAutoTransact.put(plf, autoTransact);
                    }
                    a.recycle();
                } else if (eventType == XmlPullParser.START_TAG
                        && "polling-loop-pattern-filter".equals(tagName) && currentGroup == null) {
                    final TypedArray a = res.obtainAttributes(attrs,
                            com.android.internal.R.styleable.PollingLoopPatternFilter);
                    String plf = a.getString(
                            com.android.internal.R.styleable.PollingLoopPatternFilter_name)
                                    .toUpperCase(Locale.ROOT);
                    boolean autoTransact = a.getBoolean(
                            com.android.internal.R.styleable.PollingLoopFilter_autoTransact,
                            false);
                    if (!mOnHost && !autoTransact) {
                        Log.e(TAG, "Ignoring polling-loop-filter " + plf
                                + " for offhost service that isn't autoTransact");
                    } else {
                        mAutoTransactPatterns.put(Pattern.compile(plf), autoTransact);
                    }
                    a.recycle();
                }
            }
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException("Unable to create context for: " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
        // Set uid
        mUid = si.applicationInfo.uid;

        mCategoryOtherServiceEnabled = true;    // support other category

    }

    /**
     * Returns the app component corresponding to this APDU service.
     *
     * @return app component for this service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public ComponentName getComponent() {
        return new ComponentName(mService.serviceInfo.packageName,
                mService.serviceInfo.name);
    }

    /**
     * Returns the offhost secure element name (if the service is offhost).
     *
     * @return offhost secure element name for offhost services
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @Nullable
    public String getOffHostSecureElement() {
        return mOffHostName;
    }

    /**
     * Returns a consolidated list of AIDs from the AID groups
     * registered by this service. Note that if a service has both
     * a static (manifest-based) AID group for a category and a dynamic
     * AID group, only the dynamically registered AIDs will be returned
     * for that category.
     * @return List of AIDs registered by the service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public List<String> getAids() {
        final ArrayList<String> aids = new ArrayList<String>();
        for (AidGroup group : getAidGroups()) {
            aids.addAll(group.getAids());
        }
        return aids;
    }

    /**
     * Returns the current polling loop filters for this service.
     * @return List of polling loop filters.
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    @NonNull
    public List<String> getPollingLoopFilters() {
        return new ArrayList<>(mAutoTransact.keySet());
    }

    /**
     * Returns whether this service would like to automatically transact for a given plf.
     *
     * @param plf the polling loop filter to query.
     * @return {@code true} indicating to auto transact, {@code false} indicating to not.
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public boolean getShouldAutoTransact(@NonNull String plf) {
        if (mAutoTransact.getOrDefault(plf.toUpperCase(Locale.ROOT), false)) {
            return true;
        }
        List<Pattern> patternMatches = mAutoTransactPatterns.keySet().stream()
                .filter(p -> p.matcher(plf).matches()).toList();
        if (patternMatches == null || patternMatches.size() == 0) {
            return false;
        }
        for (Pattern patternMatch : patternMatches) {
            if (mAutoTransactPatterns.get(patternMatch)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the current polling loop pattern filters for this service.
     * @return List of polling loop pattern filters.
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    @NonNull
    public List<Pattern> getPollingLoopPatternFilters() {
        return new ArrayList<>(mAutoTransactPatterns.keySet());
    }

    /**
     * Returns a consolidated list of AIDs with prefixes from the AID groups
     * registered by this service. Note that if a service has both
     * a static (manifest-based) AID group for a category and a dynamic
     * AID group, only the dynamically registered AIDs will be returned
     * for that category.
     * @return List of prefix AIDs registered by the service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public List<String> getPrefixAids() {
        final ArrayList<String> prefixAids = new ArrayList<String>();
        for (AidGroup group : getAidGroups()) {
            for (String aid : group.getAids()) {
                if (aid.endsWith("*")) {
                    prefixAids.add(aid);
                }
            }
        }
        return prefixAids;
    }

    /**
     * Returns a consolidated list of AIDs with subsets from the AID groups
     * registered by this service. Note that if a service has both
     * a static (manifest-based) AID group for a category and a dynamic
     * AID group, only the dynamically registered AIDs will be returned
     * for that category.
     * @return List of prefix AIDs registered by the service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public List<String> getSubsetAids() {
        final ArrayList<String> subsetAids = new ArrayList<String>();
        for (AidGroup group : getAidGroups()) {
            for (String aid : group.getAids()) {
                if (aid.endsWith("#")) {
                    subsetAids.add(aid);
                }
            }
        }
        return subsetAids;
    }

    /**
     * Returns the registered AID group for this category.
     *
     * @param category category name
     * @return {@link AidGroup} instance for the provided category
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public AidGroup getDynamicAidGroupForCategory(@NonNull String category) {
        return mDynamicAidGroups.get(category);
    }

    /**
     * Removes the registered AID group for this category.
     *
     * @param category category name
     * @return {@code true} if an AID group existed
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public boolean removeDynamicAidGroupForCategory(@NonNull String category) {
        return (mDynamicAidGroups.remove(category) != null);
    }

    /**
     * Returns a consolidated list of AID groups
     * registered by this service. Note that if a service has both
     * a static (manifest-based) AID group for a category and a dynamic
     * AID group, only the dynamically registered AID group will be returned
     * for that category.
     * @return List of AIDs registered by the service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public List<AidGroup> getAidGroups() {
        final ArrayList<AidGroup> groups = new ArrayList<AidGroup>();
        for (Map.Entry<String, AidGroup> entry : mDynamicAidGroups.entrySet()) {
            groups.add(entry.getValue());
        }
        for (Map.Entry<String, AidGroup> entry : mStaticAidGroups.entrySet()) {
            if (!mDynamicAidGroups.containsKey(entry.getKey())) {
                // Consolidate AID groups - don't return static ones
                // if a dynamic group exists for the category.
                groups.add(entry.getValue());
            }
        }
        return groups;
    }

    /**
     * Returns the category to which this service has attributed the AID that is passed in,
     * or null if we don't know this AID.
     * @param aid AID to lookup for
     * @return category name corresponding to this AID
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public String getCategoryForAid(@NonNull String aid) {
        List<AidGroup> groups = getAidGroups();
        for (AidGroup group : groups) {
            if (group.getAids().contains(aid.toUpperCase())) {
                return group.getCategory();
            }
        }
        return null;
    }

    /**
     * Returns whether there is any AID group for this category.
     * @param category category name
     * @return {@code true} if an AID group exists
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public boolean hasCategory(@NonNull String category) {
        return (mStaticAidGroups.containsKey(category) || mDynamicAidGroups.containsKey(category));
    }

    /**
     * Returns whether the service is on host or not.
     * @return true if the service is on host (not secure element)
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public boolean isOnHost() {
        return mOnHost;
    }

    /**
     * Returns whether the service requires device unlock.
     * @return whether the service requires device unlock
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public boolean requiresUnlock() {
        return mRequiresDeviceUnlock;
    }

    /**
     * Returns whether this service should only be started when the device is screen on.
     * @return whether the service requires screen on
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public boolean requiresScreenOn() {
        return mRequiresDeviceScreenOn;
    }

    /**
     * Returns whether the NFC stack should default to observe mode when this service is preferred.
     * @return whether the NFC stack should default to observe mode when this service is preferred
     */
    @FlaggedApi(Flags.FLAG_NFC_OBSERVE_MODE)
    public boolean shouldDefaultToObserveMode() {
        return mShouldDefaultToObserveMode;
    }

    /**
     * Sets whether the NFC stack should default to observe mode when this service is preferred.
     * @param shouldDefaultToObserveMode whether the NFC stack should default to observe mode when
     *                                  this service is preferred
     */
    @FlaggedApi(Flags.FLAG_NFC_OBSERVE_MODE)
    public void setShouldDefaultToObserveMode(boolean shouldDefaultToObserveMode) {
        mShouldDefaultToObserveMode = shouldDefaultToObserveMode;
    }

    /**
     * Returns description of service.
     * @return user readable description of service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public String getDescription() {
        return mDescription;
    }

    /**
     * Returns uid of service.
     * @return uid of the service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public int getUid() {
        return mUid;
    }

    /**
     * Add or replace an AID group to this service.
     * @param aidGroup instance of aid group to set or replace
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void setDynamicAidGroup(@NonNull AidGroup aidGroup) {
        mDynamicAidGroups.put(aidGroup.getCategory(), aidGroup);
    }

    /**
     * Add a Polling Loop Filter. Custom NFC polling frames that match this filter will be
     * delivered to {@link HostApduService#processPollingFrames(List)}. Adding a key with this
     * multiple times will cause the value to be overwritten each time.
     * @param pollingLoopFilter the polling loop filter to add, must be a valid hexadecimal string
     * @param autoTransact when true, disable observe mode when this filter matches, when false,
     *                     matching does not change the observe mode state
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void addPollingLoopFilter(@NonNull String pollingLoopFilter,
            boolean autoTransact) {
        if (!mOnHost && !autoTransact) {
            return;
        }
        mAutoTransact.put(pollingLoopFilter, autoTransact);
    }

    /**
     * Remove a Polling Loop Filter. Custom NFC polling frames that match this filter will no
     * longer be delivered to {@link HostApduService#processPollingFrames(List)}.
     * @param pollingLoopFilter this polling loop filter to add.
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void removePollingLoopFilter(@NonNull String pollingLoopFilter) {
        mAutoTransact.remove(pollingLoopFilter.toUpperCase(Locale.ROOT));
    }

    /**
     * Add a Polling Loop Pattern Filter. Custom NFC polling frames that match this filter will be
     * delivered to {@link HostApduService#processPollingFrames(List)}. Adding a key with this
     * multiple times will cause the value to be overwritten each time.
     * @param pollingLoopPatternFilter the polling loop pattern filter to add, must be a valid
     *                                regex to match a hexadecimal string
     * @param autoTransact when true, disable observe mode when this filter matches, when false,
     *                     matching does not change the observe mode state
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void addPollingLoopPatternFilter(@NonNull String pollingLoopPatternFilter,
            boolean autoTransact) {
        if (!mOnHost && !autoTransact) {
            return;
        }
        mAutoTransactPatterns.put(Pattern.compile(pollingLoopPatternFilter), autoTransact);
    }

    /**
     * Remove a Polling Loop Pattern Filter. Custom NFC polling frames that match this filter will
     * no longer be delivered to {@link HostApduService#processPollingFrames(List)}.
     * @param pollingLoopPatternFilter this polling loop filter to add.
     */
    @FlaggedApi(Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void removePollingLoopPatternFilter(@NonNull String pollingLoopPatternFilter) {
        mAutoTransactPatterns.remove(
                Pattern.compile(pollingLoopPatternFilter.toUpperCase(Locale.ROOT)));
    }

    /**
     * Sets the off host Secure Element.
     * @param  offHost  Secure Element to set. Only accept strings with prefix SIM or prefix eSE.
     *                  Ref: GSMA TS.26 - NFC Handset Requirements
     *                  TS26_NFC_REQ_069: For UICC, Secure Element Name SHALL be SIM[smartcard slot]
     *                                    (e.g. SIM/SIM1, SIM2… SIMn).
     *                  TS26_NFC_REQ_070: For embedded SE, Secure Element Name SHALL be eSE[number]
     *                                    (e.g. eSE/eSE1, eSE2, etc.).
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void setOffHostSecureElement(@NonNull String offHost) {
        mOffHostName = offHost;
    }

    /**
     * Resets the off host Secure Element to statically defined
     * by the service in the manifest file.
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void resetOffHostSecureElement() {
        mOffHostName = mStaticOffHostName;
    }

    /**
     * Load label for this service.
     * @param pm packagemanager instance
     * @return label name corresponding to service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public CharSequence loadLabel(@NonNull PackageManager pm) {
        return mService.loadLabel(pm);
    }

    /**
     * Load application label for this service.
     * @param pm packagemanager instance
     * @return app label name corresponding to service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public CharSequence loadAppLabel(@NonNull PackageManager pm) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(
                    mService.resolvePackageName, PackageManager.GET_META_DATA));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Load application icon for this service.
     * @param pm packagemanager instance
     * @return app icon corresponding to service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public Drawable loadIcon(@NonNull PackageManager pm) {
        return mService.loadIcon(pm);
    }

    /**
     * Load application banner for this service.
     * @param pm packagemanager instance
     * @return app banner corresponding to service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public Drawable loadBanner(@NonNull PackageManager pm) {
        Resources res;
        try {
            res = pm.getResourcesForApplication(mService.serviceInfo.packageName);
            Drawable banner = res.getDrawable(mBannerResourceId);
            return banner;
        } catch (NotFoundException e) {
            Log.e(TAG, "Could not load banner.");
            return null;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not load banner.");
            return null;
        }
    }

    /**
     * Load activity name for this service.
     * @return activity name for this service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @NonNull
    public String getSettingsActivityName() { return mSettingsActivityName; }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("ApduService: ");
        out.append(getComponent());
        out.append(", UID: " + mUid);
        out.append(", description: " + mDescription);
        out.append(", Static AID Groups: ");
        for (AidGroup aidGroup : mStaticAidGroups.values()) {
            out.append(aidGroup.toString());
        }
        out.append(", Dynamic AID Groups: ");
        for (AidGroup aidGroup : mDynamicAidGroups.values()) {
            out.append(aidGroup.toString());
        }
        return out.toString();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof ApduServiceInfo)) return false;
        ApduServiceInfo thatService = (ApduServiceInfo) o;

        return thatService.getComponent().equals(this.getComponent())
                && thatService.getUid() == this.getUid();
    }

    @Override
    public int hashCode() {
        return getComponent().hashCode();
    }

    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @Override
    public int describeContents() {
        return 0;
    }

    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mService.writeToParcel(dest, flags);
        dest.writeString(mDescription);
        dest.writeInt(mOnHost ? 1 : 0);
        dest.writeString(mOffHostName);
        dest.writeString(mStaticOffHostName);
        dest.writeInt(mStaticAidGroups.size());
        if (mStaticAidGroups.size() > 0) {
            dest.writeTypedList(new ArrayList<AidGroup>(mStaticAidGroups.values()));
        }
        dest.writeInt(mDynamicAidGroups.size());
        if (mDynamicAidGroups.size() > 0) {
            dest.writeTypedList(new ArrayList<AidGroup>(mDynamicAidGroups.values()));
        }
        dest.writeInt(mRequiresDeviceUnlock ? 1 : 0);
        dest.writeInt(mRequiresDeviceScreenOn ? 1 : 0);
        dest.writeInt(mBannerResourceId);
        dest.writeInt(mUid);
        dest.writeString(mSettingsActivityName);

        dest.writeInt(mCategoryOtherServiceEnabled ? 1 : 0);
        dest.writeInt(mAutoTransact.size());
        dest.writeMap(mAutoTransact);
        dest.writeInt(mAutoTransactPatterns.size());
        dest.writeMap(mAutoTransactPatterns);
    };

    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public static final @NonNull Parcelable.Creator<ApduServiceInfo> CREATOR =
            new Parcelable.Creator<ApduServiceInfo>() {
                @Override
                public ApduServiceInfo createFromParcel(Parcel source) {
                    ResolveInfo info = ResolveInfo.CREATOR.createFromParcel(source);
                    String description = source.readString();
                    boolean onHost = source.readInt() != 0;
                    String offHostName = source.readString();
                    String staticOffHostName = source.readString();
                    ArrayList<AidGroup> staticAidGroups = new ArrayList<AidGroup>();
                    int numStaticGroups = source.readInt();
                    if (numStaticGroups > 0) {
                        source.readTypedList(staticAidGroups, AidGroup.CREATOR);
                    }
                    ArrayList<AidGroup> dynamicAidGroups = new ArrayList<AidGroup>();
                    int numDynamicGroups = source.readInt();
                    if (numDynamicGroups > 0) {
                        source.readTypedList(dynamicAidGroups, AidGroup.CREATOR);
                    }
                    boolean requiresUnlock = source.readInt() != 0;
                    boolean requiresScreenOn = source.readInt() != 0;
                    int bannerResource = source.readInt();
                    int uid = source.readInt();
                    String settingsActivityName = source.readString();
                    boolean isEnabled = source.readInt() != 0;
                    int autoTransactSize = source.readInt();
                    HashMap<String, Boolean> autoTransact =
                            new HashMap<String, Boolean>(autoTransactSize);
                    source.readMap(autoTransact, getClass().getClassLoader(),
                            String.class, Boolean.class);
                    int autoTransactPatternSize = source.readInt();
                    HashMap<Pattern, Boolean> autoTransactPatterns =
                            new HashMap<Pattern, Boolean>(autoTransactSize);
                    source.readMap(autoTransactPatterns, getClass().getClassLoader(),
                            Pattern.class, Boolean.class);
                    return new ApduServiceInfo(info, onHost, description, staticAidGroups,
                            dynamicAidGroups, requiresUnlock, requiresScreenOn, bannerResource, uid,
                            settingsActivityName, offHostName, staticOffHostName,
                            isEnabled, autoTransact, autoTransactPatterns);
                }

                @Override
                public ApduServiceInfo[] newArray(int size) {
                    return new ApduServiceInfo[size];
                }
            };

    /**
     * Dump contents for debugging.
     * @param fd parcelfiledescriptor instance
     * @param pw printwriter instance
     * @param args args for dumping
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void dump(@NonNull ParcelFileDescriptor fd, @NonNull PrintWriter pw,
                     @NonNull String[] args) {
        pw.println("    " + getComponent()
                + " (Description: " + getDescription() + ")"
                + " (UID: " + getUid() + ")");
        if (mOnHost) {
            pw.println("    On Host Service");
        } else {
            pw.println("    Off-host Service");
            pw.println("        " + "Current off-host SE:" + mOffHostName
                    + " static off-host SE:" + mStaticOffHostName);
        }
        pw.println("    Static AID groups:");
        for (AidGroup group : mStaticAidGroups.values()) {
            pw.println("        Category: " + group.getCategory()
                    + "(enabled: " + mCategoryOtherServiceEnabled + ")");
            for (String aid : group.getAids()) {
                pw.println("            AID: " + aid);
            }
        }
        pw.println("    Dynamic AID groups:");
        for (AidGroup group : mDynamicAidGroups.values()) {
            pw.println("        Category: " + group.getCategory()
                    + "(enabled: " + mCategoryOtherServiceEnabled + ")");
            for (String aid : group.getAids()) {
                pw.println("            AID: " + aid);
            }
        }
        pw.println("    Settings Activity: " + mSettingsActivityName);
        pw.println("    Requires Device Unlock: " + mRequiresDeviceUnlock);
        pw.println("    Requires Device ScreenOn: " + mRequiresDeviceScreenOn);
        pw.println("    Should Default to Observe Mode: " + mShouldDefaultToObserveMode);
        pw.println("    Auto-Transact Mapping: " + mAutoTransact);
        pw.println("    Auto-Transact Patterns: " + mAutoTransactPatterns);
    }


    /**
     * Enable or disable this CATEGORY_OTHER service.
     *
     * @param enabled true to indicate if user has enabled this service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void setCategoryOtherServiceEnabled(boolean enabled) {
        mCategoryOtherServiceEnabled = enabled;
    }


    /**
     * Returns whether this CATEGORY_OTHER service is enabled or not.
     *
     * @return true to indicate if user has enabled this service
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public boolean isCategoryOtherServiceEnabled() {
        return mCategoryOtherServiceEnabled;
    }

    /**
     * Dump debugging info as ApduServiceInfoProto.
     *
     * If the output belongs to a sub message, the caller is responsible for wrapping this function
     * between {@link ProtoOutputStream#start(long)} and {@link ProtoOutputStream#end(long)}.
     * See proto definition in frameworks/base/core/proto/android/nfc/apdu_service_info.proto
     *
     * @param proto the ProtoOutputStream to write to
     */
    @FlaggedApi(Flags.FLAG_ENABLE_NFC_MAINLINE)
    public void dumpDebug(@NonNull ProtoOutputStream proto) {
        getComponent().dumpDebug(proto, ApduServiceInfoProto.COMPONENT_NAME);
        proto.write(ApduServiceInfoProto.DESCRIPTION, getDescription());
        proto.write(ApduServiceInfoProto.ON_HOST, mOnHost);
        if (!mOnHost) {
            proto.write(ApduServiceInfoProto.OFF_HOST_NAME, mOffHostName);
            proto.write(ApduServiceInfoProto.STATIC_OFF_HOST_NAME, mStaticOffHostName);
        }
        for (AidGroup group : mStaticAidGroups.values()) {
            long token = proto.start(ApduServiceInfoProto.STATIC_AID_GROUPS);
            group.dump(proto);
            proto.end(token);
        }
        for (AidGroup group : mDynamicAidGroups.values()) {
            long token = proto.start(ApduServiceInfoProto.STATIC_AID_GROUPS);
            group.dump(proto);
            proto.end(token);
        }
        proto.write(ApduServiceInfoProto.SETTINGS_ACTIVITY_NAME, mSettingsActivityName);
        proto.write(ApduServiceInfoProto.SHOULD_DEFAULT_TO_OBSERVE_MODE,
                mShouldDefaultToObserveMode);
        {
            long token = proto.start(ApduServiceInfoProto.AUTO_TRANSACT_MAPPING);
            for (Map.Entry<String, Boolean> entry : mAutoTransact.entrySet()) {
                proto.write(ApduServiceInfoProto.AutoTransactMapping.AID, entry.getKey());
                proto.write(ApduServiceInfoProto.AutoTransactMapping.SHOULD_AUTO_TRANSACT,
                        entry.getValue());
            }
            proto.end(token);
        }
        {
            long token = proto.start(ApduServiceInfoProto.AUTO_TRANSACT_PATTERNS);
            for (Map.Entry<Pattern, Boolean> entry : mAutoTransactPatterns.entrySet()) {
                proto.write(ApduServiceInfoProto.AutoTransactPattern.REGEXP_PATTERN,
                        entry.getKey().pattern());
                proto.write(ApduServiceInfoProto.AutoTransactPattern.SHOULD_AUTO_TRANSACT,
                        entry.getValue());
            }
            proto.end(token);
        }
    }

    private static final Pattern AID_PATTERN = Pattern.compile("[0-9A-Fa-f]{10,32}\\*?\\#?");
    /**
     * Copied over from {@link CardEmulation#isValidAid(String)}
     * @hide
     */
    private static boolean isValidAid(String aid) {
        if (aid == null)
            return false;

        // If a prefix/subset AID, the total length must be odd (even # of AID chars + '*')
        if ((aid.endsWith("*") || aid.endsWith("#")) && ((aid.length() % 2) == 0)) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        // If not a prefix/subset AID, the total length must be even (even # of AID chars)
        if ((!(aid.endsWith("*") || aid.endsWith("#"))) && ((aid.length() % 2) != 0)) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        // Verify hex characters
        if (!AID_PATTERN.matcher(aid).matches()) {
            Log.e(TAG, "AID " + aid + " is not a valid AID.");
            return false;
        }

        return true;
    }
}
