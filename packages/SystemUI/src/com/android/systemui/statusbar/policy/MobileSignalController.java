/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.CellLocation;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NetworkController.IconState;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallbackExtended;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.Config;
import com.android.systemui.statusbar.policy.NetworkControllerImpl.SubscriptionDefaults;

import java.io.PrintWriter;
import java.util.BitSet;
import java.util.Objects;
import java.util.List;

public class MobileSignalController extends SignalController<
        MobileSignalController.MobileState, MobileSignalController.MobileIconGroup> {
    private final TelephonyManager mPhone;
    private final SubscriptionDefaults mDefaults;
    private final String mNetworkNameDefault;
    private final String mNetworkNameSeparator;
    @VisibleForTesting
    final PhoneStateListener mPhoneStateListener;
    // Save entire info for logging, we only use the id.
    final SubscriptionInfo mSubscriptionInfo;

    // @VisibleForDemoMode
    final SparseArray<MobileIconGroup> mNetworkToIconLookup;

    private boolean mLastShowSpn;
    private String mLastSpn;
    private String mLastDataSpn;
    private boolean mLastShowPlmn;
    private String mLastPlmn;
    private boolean mIsDataSignalControlEnabled;


    // Since some pieces of the phone state are interdependent we store it locally,
    // this could potentially become part of MobileState for simplification/complication
    // of code.
    private int mDataNetType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private int mDataState = TelephonyManager.DATA_DISCONNECTED;
    private ServiceState mServiceState;
    private SignalStrength mSignalStrength;
    private MobileIconGroup mDefaultIcons;
    private Config mConfig;
    private int mNewCellIdentity = Integer.MAX_VALUE;
    private CallbackHandler mCallbackHandler;

    private final int STATUS_BAR_STYLE_ANDROID_DEFAULT = 0;
    private final int STATUS_BAR_STYLE_CDMA_1X_COMBINED = 1;
    private final int STATUS_BAR_STYLE_DEFAULT_DATA = 2;
    private final int STATUS_BAR_STYLE_DATA_VOICE = 3;
    private final int STATUS_BAR_STYLE_EXTENDED = 4;
    private int mStyle = STATUS_BAR_STYLE_ANDROID_DEFAULT;
    private DataEnabledSettingObserver mDataEnabledSettingObserver;

    private int[] mCarrierOneThresholdValues = null;
    private boolean mIsCarrierOneNetwork = false;
    private String[] mCarrieroneMccMncs = null;

    // TODO: Reduce number of vars passed in, if we have the NetworkController, probably don't
    // need listener lists anymore.
    public MobileSignalController(Context context, Config config, boolean hasMobileData,
            TelephonyManager phone, CallbackHandler callbackHandler,
            NetworkControllerImpl networkController, SubscriptionInfo info,
            SubscriptionDefaults defaults, Looper receiverLooper) {
        super("MobileSignalController(" + info.getSubscriptionId() + ")", context,
                NetworkCapabilities.TRANSPORT_CELLULAR, callbackHandler,
                networkController);

        mCallbackHandler = callbackHandler;
        mNetworkToIconLookup = new SparseArray<>();
        mConfig = config;
        mPhone = phone;
        mDefaults = defaults;
        mSubscriptionInfo = info;
        mPhoneStateListener = new MobilePhoneStateListener(info.getSubscriptionId(),
                receiverLooper);
        mNetworkNameSeparator = getStringIfExists(R.string.status_bar_network_name_separator);
        mNetworkNameDefault = getStringIfExists(
                com.android.internal.R.string.lockscreen_carrier_default);
        mIsDataSignalControlEnabled = mContext.getResources()
                .getBoolean(R.bool.config_data_signal_control);
        if (mIsDataSignalControlEnabled) {
            mDataEnabledSettingObserver =
                    new DataEnabledSettingObserver(new Handler(), context);
            mLastState.isForbidden = mCurrentState.isForbidden =
                  !(isMobileDataEnabled(mSubscriptionInfo.getSubscriptionId()));
        }

        if (config.readIconsFromXml) {
            TelephonyIcons.readIconsFromXml(context);
            mDefaultIcons = !mConfig.showAtLeast3G ? TelephonyIcons.G : TelephonyIcons.THREE_G;
        } else {
            mapIconSets();
        }

        mStyle = context.getResources().getInteger(R.integer.status_bar_style);
        //TODO - Remove this when status_bar_style is set to 4 for carrier one in carrier config
        if (isCarrierOneSupported()) {
            mStyle = STATUS_BAR_STYLE_EXTENDED;
        }

        String networkName = info.getCarrierName() != null ? info.getCarrierName().toString()
                : mNetworkNameDefault;
        mLastState.networkName = mCurrentState.networkName = networkName;
        mLastState.networkNameData = mCurrentState.networkNameData = networkName;
        mLastState.enabled = mCurrentState.enabled = hasMobileData;
        mLastState.iconGroup = mCurrentState.iconGroup = mDefaultIcons;
        // Get initial data sim state.
        updateDataSim();
        mCarrieroneMccMncs = mContext.getResources().getStringArray(
                R.array.config_carrier_one_networks);
        mCarrierOneThresholdValues = mContext.getResources().getIntArray(
                R.array.carrier_one_strength_threshold_values);
    }

    //TODO - Remove this when carrier pack is enabled for carrier one
    public static boolean isCarrierOneSupported() {
        String property = SystemProperties.get("persist.radio.atel.carrier");
        return "405854".equals(property);
    }

    public void setConfiguration(Config config) {
        mConfig = config;
        if (!config.readIconsFromXml) {
            mapIconSets();
        }
        updateTelephony();
    }

    public int getDataContentDescription() {
        return getIcons().mDataContentDescription;
    }

    public void setForbiddenState(boolean isForbidden) {
        mCurrentState.isForbidden = isForbidden;
        notifyListenersIfNecessary();
    }

    public void setAirplaneMode(boolean airplaneMode) {
        mCurrentState.airplaneMode = airplaneMode;
        notifyListenersIfNecessary();
    }

    public void setUserSetupComplete(boolean userSetup) {
        mCurrentState.userSetup = userSetup;
        notifyListenersIfNecessary();
    }

    @Override
    public void updateConnectivity(BitSet connectedTransports, BitSet validatedTransports) {
        boolean isValidated = validatedTransports.get(mTransportType);
        mCurrentState.isDefault = connectedTransports.get(mTransportType);
        // Only show this as not having connectivity if we are default.
        mCurrentState.inetCondition = (isValidated || !mCurrentState.isDefault) ? 1 : 0;
        notifyListenersIfNecessary();
    }

    public void setCarrierNetworkChangeMode(boolean carrierNetworkChangeMode) {
        mCurrentState.carrierNetworkChangeMode = carrierNetworkChangeMode;
        updateTelephony();
    }

    /**
     * Start listening for phone state changes.
     */
    public void registerListener() {
        mPhone.listen(mPhoneStateListener,
                PhoneStateListener.LISTEN_SERVICE_STATE
                        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                        | PhoneStateListener.LISTEN_CALL_STATE
                        | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE
                        | PhoneStateListener.LISTEN_DATA_ACTIVITY
                        | PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE);
        if (mIsDataSignalControlEnabled) {
            mDataEnabledSettingObserver.register();
        }
    }

    /**
     * Stop listening for phone state changes.
     */
    public void unregisterListener() {
        mPhone.listen(mPhoneStateListener, 0);
        if (mIsDataSignalControlEnabled) {
            mDataEnabledSettingObserver.unregister();
       }
    }
    /**
     * Produce a mapping of data network types to icon groups for simple and quick use in
     * updateTelephony.
     */
    private void mapIconSets() {
        mNetworkToIconLookup.clear();

        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_UMTS, TelephonyIcons.THREE_G);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_TD_SCDMA, TelephonyIcons.THREE_G);

        if (!mConfig.showAtLeast3G) {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyIcons.UNKNOWN);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EDGE, TelephonyIcons.E);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_CDMA, TelephonyIcons.ONE_X);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyIcons.ONE_X);

            mDefaultIcons = TelephonyIcons.G;
        } else {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_UNKNOWN,
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyIcons.THREE_G);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_1xRTT,
                    TelephonyIcons.THREE_G);
            mDefaultIcons = TelephonyIcons.THREE_G;
        }
        if (mContext.getResources().getBoolean(R.bool.show_network_indicators)) {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_EDGE, TelephonyIcons.E);
        }
        MobileIconGroup hGroup = TelephonyIcons.THREE_G;
        if (mConfig.hspaDataDistinguishable) {
            hGroup = TelephonyIcons.H;
        }
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSDPA, hGroup);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSUPA, hGroup);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPA, hGroup);
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPAP, hGroup);
        if (mContext.getResources().getBoolean(R.bool.config_show4gForHspap)) {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyIcons.FOUR_G);
        } else {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPAP, hGroup);
        }
        if (mContext.getResources().getBoolean(R.bool.show_network_indicators)) {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyIcons.THREE_G_PLUS);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyIcons.THREE_G_PLUS);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyIcons.THREE_G_PLUS);
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyIcons.H_PLUS);
        }

        if (mConfig.show4gForLte) {
            if (mContext.getResources().getBoolean(R.bool.show_4glte_icon_for_lte)) {
                mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE,
                        TelephonyIcons.FOUR_G_LTE);
            } else if (mContext.getResources().getBoolean(R.bool.show_network_indicators)) {
                mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE, TelephonyIcons.LTE);
            } else {
                mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE, TelephonyIcons.FOUR_G);
                if (mConfig.hideLtePlus) {
                    mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                            TelephonyIcons.FOUR_G);
                } else {
                    mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                            TelephonyIcons.FOUR_G_PLUS);
                }
            }
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                TelephonyIcons.FOUR_G_PLUS);
        } else {
            mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE, TelephonyIcons.LTE);
            if (mContext.getResources().getBoolean(R.bool.show_network_indicators)){
                mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                        TelephonyIcons.LTE_PLUS);
            } else {
                if (mConfig.hideLtePlus) {
                    mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                            TelephonyIcons.LTE);
                } else {
                    mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_LTE_CA,
                        TelephonyIcons.LTE_PLUS);
                }
            }
        }
        mNetworkToIconLookup.put(TelephonyManager.NETWORK_TYPE_IWLAN, TelephonyIcons.WFC);
    }

    @Override
    public void notifyListeners(SignalCallback callback) {
        if (mConfig.readIconsFromXml) {
            generateIconGroup();
        }
        MobileIconGroup icons = getIcons();

        String contentDescription = getStringIfExists(getContentDescription());
        String dataContentDescription = getStringIfExists(icons.mDataContentDescription);
        final boolean dataDisabled = mCurrentState.iconGroup == TelephonyIcons.DATA_DISABLED
                && mCurrentState.userSetup;

        // Show icon in QS when we are connected or data is disabled.
        boolean showDataIcon = false;

        showDataIcon = mCurrentState.dataConnected
                || dataDisabled;

        IconState statusIcon = new IconState(mCurrentState.enabled && !mCurrentState.airplaneMode,
                getCurrentIconId(), contentDescription);

        int qsTypeIcon = 0;
        IconState qsIcon = null;
        String description = null;
        // Only send data sim callbacks to QS.
        if (mCurrentState.dataSim) {
            qsTypeIcon = showDataIcon ? icons.mQsDataType : 0;
            qsIcon = new IconState(mCurrentState.enabled
                    && !mCurrentState.isEmergency, getQsCurrentIconId(), contentDescription);
            description = mCurrentState.isEmergency ? null : mCurrentState.networkName;
        }
        boolean activityIn = mCurrentState.dataConnected
                        && !mCurrentState.carrierNetworkChangeMode
                        && mCurrentState.activityIn;
        boolean activityOut = mCurrentState.dataConnected
                        && !mCurrentState.carrierNetworkChangeMode
                        && mCurrentState.activityOut;
        showDataIcon &= mCurrentState.isDefault
                || dataDisabled;

        showDataIcon &= (mStyle == STATUS_BAR_STYLE_ANDROID_DEFAULT
                || mStyle == STATUS_BAR_STYLE_EXTENDED);
        int typeIcon = showDataIcon ? icons.mDataType : 0;
        int dataActivityId = showMobileActivity() ? 0 : icons.mActivityId;
        int mobileActivityId = showMobileActivity() ? icons.mActivityId : 0;
        int dataNetworkTypeId = 0;
        if (mStyle == STATUS_BAR_STYLE_EXTENDED) {
            dataNetworkTypeId = showDataIcon ? icons.mDataType : 0;
            typeIcon = 0;
        }
        if( callback instanceof SignalCallbackExtended ) {
            ((SignalCallbackExtended)callback).setMobileDataIndicators(statusIcon, qsIcon, typeIcon,
                    qsTypeIcon, activityIn, activityOut, dataActivityId, mobileActivityId,
                    icons.mStackedDataIcon, icons.mStackedVoiceIcon,
                    dataContentDescription, description, icons.mIsWide,
                    mSubscriptionInfo.getSubscriptionId(), dataNetworkTypeId,
                    getEmbmsIconId(), isMobileIms(), isImsRegisteredInWifi(), mCurrentState.roaming);
        } else {
            callback.setMobileDataIndicators(statusIcon, qsIcon, typeIcon, qsTypeIcon,
                    activityIn, activityOut, dataActivityId, mobileActivityId,
                    icons.mStackedDataIcon, icons.mStackedVoiceIcon,
                    dataContentDescription, description, icons.mIsWide,
                    mSubscriptionInfo.getSubscriptionId(), mCurrentState.roaming, isMobileIms());
        }
        mCallbackHandler.post(new Runnable() {
            @Override
            public void run() {
                mNetworkController.updateNetworkLabelView();
            }
        });
    }

    private int getEmbmsIconId() {
        if (mStyle == STATUS_BAR_STYLE_EXTENDED
                && isEmbmsActiveOnDataSim()) {
            return R.drawable.lte_embms_services_all_brackets;
        } else {
            return 0;
        }
    }

    private boolean isEmbmsActiveOnDataSim() {
        return mNetworkController.isEmbmsActive()
                && mCurrentState.dataSim;
    }

    private boolean isImsRegisteredInWifi() {
        if (mStyle != STATUS_BAR_STYLE_EXTENDED) {
            return false;
        }

        List<SubscriptionInfo> subInfos = SubscriptionManager.from(mContext)
                        .getActiveSubscriptionInfoList();
        if (subInfos != null) {
            for (SubscriptionInfo subInfo: subInfos) {
                int subId = subInfo.getSubscriptionId();
                if (mPhone != null
                        && (mPhone.isVoWifiCallingAvailableForSubscriber(subId)
                        || mPhone.isVideoTelephonyWifiCallingAvailableForSubscriber(subId))) {
                    return true;
                }
            }
        } else {
            Log.e(mTag, "Invalid SubscriptionInfo");
        }
        return false;
    }

    private boolean isMobileIms() {

        List<SubscriptionInfo> subInfos = SubscriptionManager.from(mContext)
                        .getActiveSubscriptionInfoList();
        if (subInfos != null) {
            for (SubscriptionInfo subInfo: subInfos) {
                int subId = subInfo.getSubscriptionId();
                if (mPhone != null
                        && mPhone.isImsRegisteredForSubscriber(subId)
                        && (!(isImsRegisteredInWifi()))) {
                    return true;
                }
            }
        } else {
            Log.e(mTag, "Invalid SubscriptionInfo");
        }
        return false;
    }

    @Override
    protected MobileState cleanState() {
        return new MobileState();
    }

    @Override
    public int getCurrentIconId() {
        if (mConfig.readIconsFromXml && mCurrentState.connected) {
            return getIcons().mSingleSignalIcon;
        } else {
            return super.getCurrentIconId();
        }
    }

    private boolean hasService() {
        if (mServiceState != null) {
            // Consider the device to be in service if either voice or data
            // service is available. Some SIM cards are marketed as data-only
            // and do not support voice service, and on these SIM cards, we
            // want to show signal bars for data service as well as the "no
            // service" or "emergency calls only" text that indicates that voice
            // is not available.
            switch (mServiceState.getVoiceRegState()) {
                case ServiceState.STATE_POWER_OFF:
                    return false;
                case ServiceState.STATE_IN_SERVICE:
                    if (mServiceState.getVoiceNetworkType() == TelephonyManager.NETWORK_TYPE_IWLAN
                            && (mServiceState.getDataNetworkType() ==
                            TelephonyManager.NETWORK_TYPE_IWLAN ||
                            mServiceState.getDataRegState() != ServiceState.STATE_IN_SERVICE)) {
                        return false;
                    } else {
                        return true;
                    }
                case ServiceState.STATE_OUT_OF_SERVICE:
                case ServiceState.STATE_EMERGENCY_ONLY:
                    if (mContext.getResources().getBoolean(R.bool.config_showSignalForIWlan)) {
                        return mServiceState.getDataRegState() == ServiceState.STATE_IN_SERVICE;
                    } else {
                        return ((mServiceState.getDataRegState() ==
                                      ServiceState.STATE_IN_SERVICE)&&
                                (mServiceState.getDataNetworkType() !=
                                      TelephonyManager.NETWORK_TYPE_IWLAN));
                    }
                default:
                    return true;
            }
        } else {
            return false;
        }
    }

    private boolean isCdma() {
        return (mSignalStrength != null) && !mSignalStrength.isGsm();
    }

    public boolean isEmergencyOnly() {
        return (mServiceState != null && mServiceState.isEmergencyOnly());
    }

    private boolean isRoaming() {
        if (isCdma()) {
            final int iconMode = mServiceState.getCdmaEriIconMode();
            return mServiceState.getCdmaEriIconIndex() != EriInfo.ROAMING_INDICATOR_OFF
                    && (iconMode == EriInfo.ROAMING_ICON_MODE_NORMAL
                        || iconMode == EriInfo.ROAMING_ICON_MODE_FLASH);
        } else {
            return mServiceState != null && mServiceState.getRoaming();
        }
    }

    private boolean isCarrierNetworkChangeActive() {
        return mCurrentState.carrierNetworkChangeMode;
    }

    public void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action.equals(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION)) {
            updateNetworkName(intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false),
                    intent.getStringExtra(TelephonyIntents.EXTRA_SPN),
                    intent.getStringExtra(TelephonyIntents.EXTRA_DATA_SPN),
                    intent.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false),
                    intent.getStringExtra(TelephonyIntents.EXTRA_PLMN));
            notifyListenersIfNecessary();
        } else if (action.equals(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)) {
            updateDataSim();
            notifyListenersIfNecessary();
        } else if (action.equals(Intent.ACTION_LOCALE_CHANGED)) {
            if (mConfig.showLocale) {
                updateNetworkName(mLastShowSpn, mLastSpn, mLastDataSpn, mLastShowPlmn, mLastPlmn);
                notifyListenersIfNecessary();
            }
        }
    }

    private void updateDataSim() {
        int defaultDataSub = mDefaults.getDefaultDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(defaultDataSub)) {
            mCurrentState.dataSim = defaultDataSub == mSubscriptionInfo.getSubscriptionId();
        } else {
            // There doesn't seem to be a data sim selected, however if
            // there isn't a MobileSignalController with dataSim set, then
            // QS won't get any callbacks and will be blank.  Instead
            // lets just assume we are the data sim (which will basically
            // show one at random) in QS until one is selected.  The user
            // should pick one soon after, so we shouldn't be in this state
            // for long.
            mCurrentState.dataSim = true;
        }
    }

    private String getLocalString(String originalString) {
        return android.util.NativeTextHelper.getLocalString(mContext, originalString,
                          com.android.internal.R.array.origin_carrier_names,
                          com.android.internal.R.array.locale_carrier_names);
    }

    private String getNetworkClassString(ServiceState state) {
        if (state != null && (state.getDataRegState() == ServiceState.STATE_IN_SERVICE ||
                state.getVoiceRegState() == ServiceState.STATE_IN_SERVICE)) {
            int voiceNetType = state.getVoiceNetworkType();
            int dataNetType =  state.getDataNetworkType();
            int chosenNetType =
                    ((dataNetType == TelephonyManager.NETWORK_TYPE_UNKNOWN)
                    ? voiceNetType : dataNetType);
            return networkClassToString(TelephonyManager.getNetworkClass(chosenNetType));
        } else {
            return "";
        }
    }

    private String networkClassToString (int networkClass) {
        final int[] classIds =
            {com.android.internal.R.string.config_rat_unknown, // TelephonyManager.NETWORK_CLASS_UNKNOWN
            com.android.internal.R.string.config_rat_2g,
            com.android.internal.R.string.config_rat_3g,
            com.android.internal.R.string.config_rat_4g };
        String classString = null;
        if (networkClass < classIds.length) {
            classString = mContext.getResources().getString(classIds[networkClass]);
        }
        return (classString == null) ? "" : classString;
    }

    /**
     * Updates the network's name based on incoming spn and plmn.
     */
    void updateNetworkName(boolean showSpn, String spn, String dataSpn,
            boolean showPlmn, String plmn) {
        mLastShowSpn = showSpn;
        mLastSpn = spn;
        mLastDataSpn = dataSpn;
        mLastShowPlmn = showPlmn;
        mLastPlmn = plmn;
        if (CHATTY) {
            Log.d("CarrierLabel", "updateNetworkName showSpn=" + showSpn
                    + " spn=" + spn + " dataSpn=" + dataSpn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        if (mConfig.showLocale) {
            if (showSpn && !TextUtils.isEmpty(spn)) {
                spn = getLocalString(spn);
            }
            if (showSpn && !TextUtils.isEmpty(dataSpn)) {
                dataSpn = getLocalString(dataSpn);
            }
            if (showPlmn && !TextUtils.isEmpty(plmn)) {
                plmn = getLocalString(plmn);
            }
        }
        if (showPlmn && showSpn && !TextUtils.isEmpty(spn) && !TextUtils.isEmpty(plmn)
                && plmn.equals(spn)) {
            showSpn = false;
        }
        boolean showRat = mConfig.showRat;
        int[] subId = SubscriptionManager.getSubId(getSimSlotIndex());
        if (subId != null && subId.length >= 1) {
            showRat = SubscriptionManager.getResourcesForSubId(mContext,
                    subId[0]).getBoolean(com.android.internal.R.bool.config_display_rat);
        }
        String networkClass = getNetworkClassString(mServiceState);
        Log.d(mTag, "networkClass=" + networkClass + " showRat=" + showRat +
                " slot=" + getSimSlotIndex());
        StringBuilder str = new StringBuilder();
        StringBuilder strData = new StringBuilder();
        if (showPlmn && plmn != null) {
            str.append(plmn);
            strData.append(plmn);
            if (showRat) {
                str.append(" ").append(networkClass);
                strData.append(" ").append(networkClass);
            }
        }
        if (showSpn && spn != null) {
            if (str.length() != 0) {
                str.append(mNetworkNameSeparator);
            }
            str.append(spn);
            if (showRat) str.append(" ").append(networkClass);
        }
        if (str.length() != 0) {
            mCurrentState.networkName = str.toString();
        } else {
            mCurrentState.networkName = mNetworkNameDefault;
        }
        if (showSpn && dataSpn != null) {
            if (strData.length() != 0) {
                strData.append(mNetworkNameSeparator);
            }
            strData.append(dataSpn);
            if (showRat) strData.append(" ").append(networkClass);
        }
        if (strData.length() != 0) {
            mCurrentState.networkNameData = strData.toString();
        } else {
            mCurrentState.networkNameData = mNetworkNameDefault;
        }
    }

    /**
     * Updates the current state based on mServiceState, mSignalStrength, mDataNetType,
     * mDataState, and mSimState.  It should be called any time one of these is updated.
     * This will call listeners if necessary.
     */
    private final void updateTelephony() {
        if (DEBUG) {
            Log.d(mTag, "updateTelephony: hasService=" + hasService()
                    + " ss=" + mSignalStrength);
        }
        mCurrentState.connected = hasService() && mSignalStrength != null;
        if (mCurrentState.connected) {
            if (!mSignalStrength.isGsm() && mConfig.alwaysShowCdmaRssi) {
                mCurrentState.level = mSignalStrength.getCdmaLevel();
            } else {
                mCurrentState.level = mSignalStrength.getLevel();
                if (mConfig.showRsrpSignalLevelforLTE) {
                    int dataType = mServiceState.getDataNetworkType();
                    if (dataType == TelephonyManager.NETWORK_TYPE_LTE ||
                            dataType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
                        mCurrentState.level = getAlternateLteLevel(mSignalStrength);
                    }
                }
            }
        }
        if (mNetworkToIconLookup.indexOfKey(mDataNetType) >= 0) {
            mCurrentState.iconGroup = mNetworkToIconLookup.get(mDataNetType);
        } else {
            mCurrentState.iconGroup = mDefaultIcons;
        }
        mCurrentState.dataConnected = mCurrentState.connected
                && mDataState == TelephonyManager.DATA_CONNECTED;
        
        mCurrentState.roaming = isRoaming();

        if (isCarrierNetworkChangeActive()) {
            mCurrentState.iconGroup = TelephonyIcons.CARRIER_NETWORK_CHANGE;
        } else if (isDataDisabled()) {
            mCurrentState.iconGroup = TelephonyIcons.DATA_DISABLED;
        }
        if (isEmergencyOnly() != mCurrentState.isEmergency) {
            mCurrentState.isEmergency = isEmergencyOnly();
            mNetworkController.recalculateEmergency();
        }
        // Fill in the network name if we think we have it.
        if (mCurrentState.networkName == mNetworkNameDefault && mServiceState != null
                && !TextUtils.isEmpty(mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkName = mServiceState.getOperatorAlphaShort();
        }

        if (!showLongOperatorName() && mServiceState != null && !TextUtils.isEmpty(
                mServiceState.getOperatorAlphaShort())) {
            mCurrentState.networkNameData = mServiceState.getOperatorAlphaShort()
                            + " " + getNetworkClassString(mServiceState);
        }

        if (mConfig.readIconsFromXml) {
            mCurrentState.voiceLevel = getVoiceSignalLevel();
        }

        if (mStyle == STATUS_BAR_STYLE_EXTENDED) {
            mCurrentState.imsRadioTechnology = getImsRadioTechnology();
        }

        mCurrentState.dataNetType = getDataNetworkType();

        if (getDataRegState() != mCurrentState.dataRegState){
            mCurrentState.dataRegState = getDataRegState();
        }

        notifyListenersIfNecessary();
    }

    private boolean isDataDisabled() {
        return !mPhone.getDataEnabled(mSubscriptionInfo.getSubscriptionId());
    }

    private boolean showLongOperatorName() {
        if (mContext.getResources().getBoolean(R.bool.config_show_long_operator_name) || (mContext.
                getResources().getBoolean(R.bool.config_show_long_operator_name_when_roaming) &&
                isRoaming())) {
            return true;
        }
        return false;
    }

    private void generateIconGroup() {
        final int level = mCurrentState.level;
        final int voiceLevel = mCurrentState.voiceLevel;
        final int inet = mCurrentState.inetCondition;
        final boolean dataConnected = mCurrentState.dataConnected;
        final boolean roaming = isRoaming();
        final int voiceType = getVoiceNetworkType();
        final int dataType =  mDataNetType;
        int[][] sbIcons = TelephonyIcons.TELEPHONY_SIGNAL_STRENGTH;
        int[][] qsIcons = TelephonyIcons.QS_TELEPHONY_SIGNAL_STRENGTH;
        int[] contentDesc = AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH;
        int sbDiscState = TelephonyIcons.TELEPHONY_NO_NETWORK;
        int qsDiscState = TelephonyIcons.QS_TELEPHONY_NO_NETWORK;
        int discContentDesc = AccessibilityContentDescriptions.PHONE_SIGNAL_STRENGTH[0];
        int dataContentDesc, dataTypeIcon, qsDataTypeIcon, dataActivityId;
        int singleSignalIcon, stackedDataIcon = 0, stackedVoiceIcon = 0;

        final int slotId = getSimSlotIndex();
        if (slotId < 0 || slotId > mPhone.getPhoneCount()) {
            Log.e(mTag, "generateIconGroup invalid slotId:" + slotId);
            return;
        }

        if (DEBUG) Log.d(mTag, "generateIconGroup slot:" + slotId + " style:" + mStyle
                + " connected:" + mCurrentState.connected + " inetCondition:" + inet
                + " roaming:" + roaming + " level:" + level + " voiceLevel:" + voiceLevel
                + " dataConnected:" + dataConnected
                + " dataActivity:" + mCurrentState.dataActivity
                + " CS:" + voiceType
                + "/" + TelephonyManager.getNetworkTypeName(voiceType)
                + ", PS:" + dataType
                + "/" + TelephonyManager.getNetworkTypeName(dataType));

        // Update data icon set
        int chosenNetworkType = ((dataType == TelephonyManager.NETWORK_TYPE_UNKNOWN)
                ? voiceType : dataType);
        TelephonyIcons.updateDataType(slotId, chosenNetworkType, mConfig.showAtLeast3G,
                mConfig.show4gForLte, mConfig.hspaDataDistinguishable, inet);

        // Update signal strength icons
        singleSignalIcon = TelephonyIcons.getSignalStrengthIcon(slotId, inet, level, roaming);
        if (DEBUG) {
            Log.d(mTag, "singleSignalIcon:" + getResourceName(singleSignalIcon));
        }

        if (mIsDataSignalControlEnabled) {
            dataActivityId = (mCurrentState.dataConnected && slotId >= 0) ?
                    TelephonyIcons.getDataActivity(slotId, mCurrentState.dataActivity) :
                    getCustomStatusBarIcon(slotId);
        } else {
            dataActivityId = (mCurrentState.dataConnected && slotId >= 0) ?
                    TelephonyIcons.getDataActivity(slotId, mCurrentState.dataActivity) : 0;
        }
        // Convert the icon to unstacked if necessary.
        int unstackedSignalIcon = TelephonyIcons.convertMobileStrengthIcon(singleSignalIcon);
        if (DEBUG) {
            Log.d(mTag, "unstackedSignalIcon:" + getResourceName(unstackedSignalIcon));
        }
        if (singleSignalIcon != unstackedSignalIcon) {
            stackedDataIcon = singleSignalIcon;
            singleSignalIcon = unstackedSignalIcon;
        }

        if (mStyle == STATUS_BAR_STYLE_CDMA_1X_COMBINED) {
            if (!roaming && showDataAndVoice()) {
                stackedVoiceIcon = TelephonyIcons.getStackedVoiceIcon(voiceLevel);
            } else if (roaming && dataActivityId != 0) {
                // Remove data type indicator if already shown in data activity icon.
                singleSignalIcon = TelephonyIcons.getRoamingSignalIconId(level, inet);
            }
        }

        // Clear satcked data icon if no satcked voice icon.
        if (stackedVoiceIcon == 0) stackedDataIcon = 0;

        contentDesc = TelephonyIcons.getSignalStrengthDes(slotId);
        sbDiscState = TelephonyIcons.getSignalNullIcon(slotId);
        if (DEBUG) {
            Log.d(mTag, "singleSignalIcon=" + getResourceName(singleSignalIcon)
                    + " dataActivityId=" + getResourceName(dataActivityId)
                    + " stackedDataIcon=" + getResourceName(stackedDataIcon)
                    + " stackedVoiceIcon=" + getResourceName(stackedVoiceIcon));
        }

        // Update data net type icons
        if (dataType == TelephonyManager.NETWORK_TYPE_IWLAN &&
                mContext.getResources().getBoolean(R.bool.config_show4gForIWlan)) {
            // wimax is a special 4g network not handled by telephony
            dataTypeIcon = TelephonyIcons.ICON_4G;
            qsDataTypeIcon = TelephonyIcons.QS_DATA_4G;
            dataContentDesc = R.string.accessibility_data_connection_4g;
        } else {
            dataTypeIcon = TelephonyIcons.getDataTypeIcon(slotId);
            dataContentDesc = TelephonyIcons.getDataTypeDesc(slotId);
            qsDataTypeIcon = TelephonyIcons.getQSDataTypeIcon(slotId);
        }

        if (DEBUG) {
            Log.d(mTag, "updateDataNetType, dataTypeIcon=" + getResourceName(dataTypeIcon)
                    + " qsDataTypeIcon=" + getResourceName(qsDataTypeIcon)
                    + " dataContentDesc=" + dataContentDesc);
        }
        boolean isWide = roaming && mStyle == STATUS_BAR_STYLE_EXTENDED;
        mCurrentState.iconGroup = new MobileIconGroup(
                TelephonyManager.getNetworkTypeName(dataType),
                sbIcons, qsIcons, contentDesc, 0, 0, sbDiscState, qsDiscState, discContentDesc,
                dataContentDesc, dataTypeIcon, isWide, qsDataTypeIcon,
                singleSignalIcon, stackedDataIcon, stackedVoiceIcon, dataActivityId);
    }

    private boolean isMobileDataEnabled(int subId) {
         //Checks for data enabled or not from the given subId.
         return TelephonyManager.getDefault().getDataEnabled(subId);
      }

    private int getCustomStatusBarIcon(int slotId) {
        int dataTypeIcon = 0 ;
        if (!mCurrentState.dataConnected &&  mCurrentState.isForbidden) {
            // Show Forbidden icon incase both data is Disabled and
            // disconnected
            dataTypeIcon = TelephonyIcons.getForbiddenDataIcon(slotId);
        } else {
            // Show greyed icon incase data is enabled and not preferred
            dataTypeIcon = TelephonyIcons.getDataDisconnectedIcon(slotId);
        }
        return dataTypeIcon;
    }

    private int getSimSlotIndex() {
        int slotId = -1;
        if (mSubscriptionInfo != null) {
            slotId = mSubscriptionInfo.getSimSlotIndex();
        }
        if (DEBUG) Log.d(mTag, "getSimSlotIndex, slotId: " + slotId);
        return slotId;
    }

    private boolean showMobileActivity() {
        return (mStyle == STATUS_BAR_STYLE_DEFAULT_DATA)
                || (mStyle == STATUS_BAR_STYLE_ANDROID_DEFAULT)
                || (mStyle == STATUS_BAR_STYLE_EXTENDED);
    }

    private int getVoiceNetworkType() {
        if (mServiceState == null) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
        return mServiceState.getVoiceNetworkType();
    }

    private int getDataNetworkType() {
        if (mServiceState == null) {
            return TelephonyManager.NETWORK_TYPE_UNKNOWN;
        }
        return mServiceState.getDataNetworkType();
    }

    private int getImsRadioTechnology() {
        if (mServiceState == null || (mServiceState.getVoiceRegState() !=
                ServiceState.STATE_IN_SERVICE)) {
            return ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN;
        }
        return mServiceState.getRilImsRadioTechnology();
    }

    private int getVoiceSignalLevel() {
        if (mSignalStrength == null) {
            return SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        }
        boolean isCdma = TelephonyManager.PHONE_TYPE_CDMA == TelephonyManager.getDefault()
                .getCurrentPhoneType(mSubscriptionInfo.getSubscriptionId());
        return isCdma ? mSignalStrength.getCdmaLevel() : mSignalStrength.getGsmLevel();
    }

    private boolean showDataAndVoice() {
        if (mStyle != STATUS_BAR_STYLE_CDMA_1X_COMBINED) {
            return false;
        }
        int dataType = getDataNetworkType();
        int voiceType = getVoiceNetworkType();
        if ((dataType == TelephonyManager.NETWORK_TYPE_EVDO_0
                || dataType == TelephonyManager.NETWORK_TYPE_EVDO_0
                || dataType == TelephonyManager.NETWORK_TYPE_EVDO_A
                || dataType == TelephonyManager.NETWORK_TYPE_EVDO_B
                || dataType == TelephonyManager.NETWORK_TYPE_EHRPD
                || dataType == TelephonyManager.NETWORK_TYPE_LTE
                || dataType == TelephonyManager.NETWORK_TYPE_LTE_CA)
                && (voiceType == TelephonyManager.NETWORK_TYPE_GSM
                    || voiceType == TelephonyManager.NETWORK_TYPE_1xRTT
                    || voiceType == TelephonyManager.NETWORK_TYPE_CDMA)) {
            return true;
        }
        return false;
    }

    private boolean show1xOnly() {
        int dataType = getDataNetworkType();
        int voiceType = getVoiceNetworkType();
        if (dataType == TelephonyManager.NETWORK_TYPE_1xRTT
                || dataType == TelephonyManager.NETWORK_TYPE_CDMA) {
            return true;
        }
        return false;
    }

    private int getAlternateLteLevel(SignalStrength signalStrength) {
        int lteRsrp = signalStrength.getLteDbm();
        int rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (lteRsrp > -44) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        else if (lteRsrp >= -97) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GREAT;
        else if (lteRsrp >= -105) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_GOOD;
        else if (lteRsrp >= -113) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_MODERATE;
        else if (lteRsrp >= -120) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_POOR;
        else if (lteRsrp >= -140) rsrpLevel = SignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        if (DEBUG) {
            Log.d(mTag, "getAlternateLteLevel lteRsrp:" + lteRsrp + " rsrpLevel = " + rsrpLevel);
        }
        return rsrpLevel;
    }

    protected String getResourceName(int resId) {
        if (resId != 0) {
            final Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private int getDataRegState() {
        if (mServiceState == null) {
            if (DEBUG) {
                Log.d(mTag, "getDataRegState dataRegState:STATE_OUT_OF_SERVICE");
            }
            return ServiceState.STATE_OUT_OF_SERVICE;
        }
        return mServiceState.getDataRegState();
    }

    @VisibleForTesting
    void setActivity(int activity) {
        mCurrentState.activityIn = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_IN;
        mCurrentState.activityOut = activity == TelephonyManager.DATA_ACTIVITY_INOUT
                || activity == TelephonyManager.DATA_ACTIVITY_OUT;
        if (mConfig.readIconsFromXml) {
            mCurrentState.dataActivity = activity;
        }
        notifyListenersIfNecessary();
    }

    @Override
    public void dump(PrintWriter pw) {
        super.dump(pw);
        pw.println("  mSubscription=" + mSubscriptionInfo + ",");
        pw.println("  mServiceState=" + mServiceState + ",");
        pw.println("  mSignalStrength=" + mSignalStrength + ",");
        pw.println("  mDataState=" + mDataState + ",");
        pw.println("  mDataNetType=" + mDataNetType + ",");
    }

    class MobilePhoneStateListener extends PhoneStateListener {
        public MobilePhoneStateListener(int subId, Looper looper) {
            super(subId, looper);
        }

        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            if (DEBUG) {
                Log.d(mTag, "onSignalStrengthsChanged signalStrength=" + signalStrength +
                        ((signalStrength == null) ? "" : (" level=" + signalStrength.getLevel())));
            }
            mSignalStrength = signalStrength;

            if (mIsCarrierOneNetwork && mSignalStrength != null &&
                    mCarrierOneThresholdValues != null) {
                mSignalStrength.setThreshRsrp(mCarrierOneThresholdValues);
            }
            updateTelephony();
        }

        private boolean isCarrierOneOperatorRegistered(ServiceState state) {
            String operatornumeric = state.getOperatorNumeric();
            if (mCarrieroneMccMncs == null || mCarrieroneMccMncs.length == 0 ||
                    TextUtils.isEmpty(operatornumeric)) {
                return false;
            }
            for (String numeric : mCarrieroneMccMncs) {
                if (operatornumeric.equals(numeric)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            if (DEBUG) {
                Log.d(mTag, "onServiceStateChanged voiceState=" + state.getVoiceRegState()
                        + " dataState=" + state.getDataRegState());
            }
            mServiceState = state;
            mDataNetType = state.getDataNetworkType();

            mIsCarrierOneNetwork = isCarrierOneOperatorRegistered(mServiceState);
            Log.d(mTag, "onServiceStateChanged mIsCarrierOneNetwork =" +
                    mIsCarrierOneNetwork);

            updateNetworkName(mLastShowSpn, mLastSpn, mLastDataSpn, mLastShowPlmn, mLastPlmn);

            if (mDataNetType == TelephonyManager.NETWORK_TYPE_LTE && mServiceState != null &&
                    mServiceState.isUsingCarrierAggregation()) {
                mDataNetType = TelephonyManager.NETWORK_TYPE_LTE_CA;
            }

            updateTelephony();
        }

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (DEBUG) {
                Log.d(mTag, "onDataConnectionStateChanged: state=" + state
                        + " type=" + networkType);
            }
            if (mContext.getResources().getBoolean(R.bool.show_network_indicators)) {
                CellLocation cl = mPhone.getCellLocation();
                if (cl instanceof GsmCellLocation) {
                    GsmCellLocation cellLocation = (GsmCellLocation)cl;
                    mNewCellIdentity = cellLocation.getCid();
                    Log.d(mTag, "onDataConnectionStateChanged, mNewCellIdentity = "
                            + mNewCellIdentity);
                }
                Log.d(mTag, "onDataConnectionStateChanged "
                        + ", mNewCellIdentity = " + mNewCellIdentity
                        + ", mDataNetType = " + mDataNetType
                        + ", networkType = " + networkType);
                if (Integer.MAX_VALUE != mNewCellIdentity) {
                    mDataNetType = networkType;
                } else {
                    if (networkType > mDataNetType) {
                        mDataNetType = networkType;
                    }
                }
                mDataState = state;
                updateTelephony();
            } else {
                mDataState = state;
                mDataNetType = networkType;
                if (mDataNetType == TelephonyManager.NETWORK_TYPE_LTE && mServiceState != null &&
                        mServiceState.isUsingCarrierAggregation()) {
                    mDataNetType = TelephonyManager.NETWORK_TYPE_LTE_CA;
                }				
                updateTelephony();
            }
        }

        @Override
        public void onDataActivity(int direction) {
            if (DEBUG) {
                Log.d(mTag, "onDataActivity: direction=" + direction);
            }
            setActivity(direction);
        }

        @Override
        public void onCarrierNetworkChange(boolean active) {
            if (DEBUG) {
                Log.d(mTag, "onCarrierNetworkChange: active=" + active);
            }
            mCurrentState.carrierNetworkChangeMode = active;

            updateTelephony();
        }
    };

    static class MobileIconGroup extends SignalController.IconGroup {
        final int mDataContentDescription; // mContentDescriptionDataType
        final int mDataType;
        final boolean mIsWide;
        final int mQsDataType;
        final int mSingleSignalIcon;
        final int mStackedDataIcon;
        final int mStackedVoiceIcon;
        final int mActivityId;

        public MobileIconGroup(String name, int[][] sbIcons, int[][] qsIcons, int[] contentDesc,
                int sbNullState, int qsNullState, int sbDiscState, int qsDiscState,
                int discContentDesc, int dataContentDesc, int dataType, boolean isWide,
                int qsDataType) {
                this(name, sbIcons, qsIcons, contentDesc, sbNullState, qsNullState, sbDiscState,
                        qsDiscState, discContentDesc, dataContentDesc, dataType, isWide,
                        qsDataType, 0, 0, 0, 0);
        }

        public MobileIconGroup(String name, int[][] sbIcons, int[][] qsIcons, int[] contentDesc,
                int sbNullState, int qsNullState, int sbDiscState, int qsDiscState,
                int discContentDesc, int dataContentDesc, int dataType, boolean isWide,
                int qsDataType, int singleSignalIcon, int stackedDataIcon,
                int stackedVoicelIcon, int activityId) {
            super(name, sbIcons, qsIcons, contentDesc, sbNullState, qsNullState, sbDiscState,
                    qsDiscState, discContentDesc);
            mDataContentDescription = dataContentDesc;
            mDataType = dataType;
            mIsWide = isWide;
            mQsDataType = qsDataType;
            mSingleSignalIcon = singleSignalIcon;
            mStackedDataIcon = stackedDataIcon;
            mStackedVoiceIcon = stackedVoicelIcon;
            mActivityId = activityId;
        }
    }

    static class MobileState extends SignalController.State {
        String networkName;
        String networkNameData;
        boolean dataSim;
        boolean dataConnected;
        boolean isEmergency;
        boolean airplaneMode;
        boolean carrierNetworkChangeMode;
        boolean isDefault;
        boolean isForbidden;
        boolean userSetup;
        int dataActivity;
        int voiceLevel;
        int imsRadioTechnology;
        int dataNetType;
        int dataRegState;
        boolean roaming;

        @Override
        public void copyFrom(State s) {
            super.copyFrom(s);
            MobileState state = (MobileState) s;
            dataSim = state.dataSim;
            networkName = state.networkName;
            networkNameData = state.networkNameData;
            dataConnected = state.dataConnected;
            isDefault = state.isDefault;
            isEmergency = state.isEmergency;
            isForbidden = state.isForbidden;
            airplaneMode = state.airplaneMode;
            carrierNetworkChangeMode = state.carrierNetworkChangeMode;
            userSetup = state.userSetup;
            dataActivity = state.dataActivity;
            voiceLevel = state.voiceLevel;
            imsRadioTechnology = state.imsRadioTechnology;
            dataNetType = state.dataNetType;
            dataRegState = state.dataRegState;
            roaming = state.roaming;
        }

        @Override
        protected void toString(StringBuilder builder) {
            super.toString(builder);
            builder.append(',');
            builder.append("dataSim=").append(dataSim).append(',');
            builder.append("networkName=").append(networkName).append(',');
            builder.append("networkNameData=").append(networkNameData).append(',');
            builder.append("dataConnected=").append(dataConnected).append(',');
            builder.append("roaming=").append(roaming).append(',');
            builder.append("isDefault=").append(isDefault).append(',');
            builder.append("isEmergency=").append(isEmergency).append(',');
            builder.append("isForbidden= ").append(isForbidden).append(',');
            builder.append("airplaneMode=").append(airplaneMode).append(',');
            builder.append("carrierNetworkChangeMode=").append(carrierNetworkChangeMode)
                    .append(',');
            builder.append("userSetup=").append(userSetup);
            builder.append("voiceLevel=").append(voiceLevel).append(',');
            builder.append("carrierNetworkChangeMode=").append(carrierNetworkChangeMode);
            builder.append("imsRadioTechnology=").append(imsRadioTechnology);
            builder.append("dataNetType=").append(dataNetType);
            builder.append("dataRegState=").append(dataRegState);
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o)
                    && Objects.equals(((MobileState) o).networkName, networkName)
                    && Objects.equals(((MobileState) o).networkNameData, networkNameData)
                    && ((MobileState) o).dataSim == dataSim
                    && ((MobileState) o).dataConnected == dataConnected
                    && ((MobileState) o).isEmergency == isEmergency
                    && ((MobileState) o).isForbidden ==  isForbidden
                    && ((MobileState) o).airplaneMode == airplaneMode
                    && ((MobileState) o).carrierNetworkChangeMode == carrierNetworkChangeMode
                    && ((MobileState) o).userSetup == userSetup
                    && ((MobileState) o).voiceLevel == voiceLevel
                    && ((MobileState) o).isDefault == isDefault
                    && ((MobileState) o).imsRadioTechnology == imsRadioTechnology
                    && ((MobileState) o).dataNetType == dataNetType
                    && ((MobileState) o).dataRegState == dataRegState
                    && ((MobileState) o).roaming == roaming;
        }
    }

   //Observer to moniter enabling and disabling of MobileData
    private class DataEnabledSettingObserver extends ContentObserver {
        ContentResolver mResolver;
        public DataEnabledSettingObserver(Handler handler, Context context) {
            super(handler);
            mResolver = context.getContentResolver();
        }

        public void register() {
            String contentUri;
            if (TelephonyManager.getDefault().getSimCount() == 1) {
                contentUri = Settings.Global.MOBILE_DATA;
            } else {
                contentUri = Settings.Global.MOBILE_DATA + mSubscriptionInfo.getSubscriptionId();
            }
            mResolver.registerContentObserver(Settings.Global.getUriFor(contentUri), false, this);
        }

        public void unregister() {
            mResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            setForbiddenState(!isMobileDataEnabled(mSubscriptionInfo.getSubscriptionId()));
        }
    }
}
