/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2014 ParanoidAndroid Project.
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

package com.android.systemui.statusbar.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAdapter.BluetoothStateChangeCallback;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.util.paranoid.DeviceUtils;
import com.android.internal.util.paranoid.LightbulbConstants;
import com.android.systemui.R;
import com.android.systemui.settings.BrightnessController.BrightnessStateChangeCallback;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.LocationController.LocationSettingsChangeCallback;
import com.android.systemui.statusbar.policy.NetworkController.NetworkSignalChangedCallback;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

import java.util.List;

public class QuickSettingsModel implements BluetoothStateChangeCallback,
        NetworkSignalChangedCallback,
        BatteryStateChangeCallback,
        BrightnessStateChangeCallback,
        RotationLockControllerCallback,
        LocationSettingsChangeCallback {
    // Sett InputMethoManagerService
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";

    /** Represents the state of a given attribute. */
    static class State {
        int iconId;
        String label;
        boolean enabled = false;
    }
    static class BatteryState extends State {
        int batteryLevel;
        boolean pluggedIn;
    }
    static class ActivityState extends State {
        boolean activityIn;
        boolean activityOut;
    }
    static class RSSIState extends ActivityState {
        int signalIconId;
        String signalContentDescription;
        int dataTypeIconId;
        String dataContentDescription;
    }
    static class WifiState extends ActivityState {
        String signalContentDescription;
        boolean connected;
    }
    static class UserState extends State {
        Drawable avatar;
    }
    static class BrightnessState extends State {
        boolean autoBrightness;
    }
    public static class BluetoothState extends State {
        boolean connected = false;
        String stateContentDescription;
    }
    public static class RotationLockState extends State {
        boolean visible = false;
    }

    /** The callback to update a given tile. */
    interface RefreshCallback {
        public void refreshView(QuickSettingsTileView view, State state);
    }

    // General basic tiles
    public static class BasicRefreshCallback implements RefreshCallback {
        private final QuickSettingsBasicTile mView;
        private boolean mShowWhenEnabled;

        public BasicRefreshCallback(QuickSettingsBasicTile v) {
            mView = v;
        }
        public void refreshView(QuickSettingsTileView ignored, State state) {
            if (mShowWhenEnabled) {
                mView.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
            if (state.iconId != 0) {
                mView.setImageDrawable(null); // needed to flush any cached IDs
                mView.setImageResource(state.iconId);
            }
            if (state.label != null) {
                mView.setText(state.label);
            }
        }
        public BasicRefreshCallback setShowWhenEnabled(boolean swe) {
            mShowWhenEnabled = swe;
            return this;
        }
    }

    /** Broadcast receive to determine if there is an alarm set. */
    private BroadcastReceiver mAlarmIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_ALARM_CHANGED)) {
                onAlarmChanged(intent);
                onNextAlarmChanged();
            }
        }
    };

    /** Broadcast receive to determine if device boot is complete*/
    private BroadcastReceiver mBootReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ContentResolver cr = mContext.getContentResolver();
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
                mHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        mUsesAospDialer = Settings.System
                                .getInt(cr, Settings.System.AOSP_DIALER, 0) == 1;
                        if (deviceHasMobileData()) {
                            if (mUsesAospDialer) {
                                refreshMobileNetworkTile();
                            } else {
                                mMobileNetworkState.label =
                                    mContext.getResources()
                                            .getString(R.string.quick_settings_network_disabled);
                                mMobileNetworkState.iconId =
                                    R.drawable.ic_qs_unexpected_network;
                                mMobileNetworkCallback.refreshView(mMobileNetworkTile,
                                                                    mMobileNetworkState);
                            }
                        }
                    }
                }, 200);
            }
            context.unregisterReceiver(mBootReceiver);
        }
    };

    /** Broadcast receive to catch device shutdown */
    private BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final ContentResolver cr = mContext.getContentResolver();
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SHUTDOWN)) {
                Settings.System.putInt(cr, Settings.System.AOSP_DIALER, 0);
            }
        }
    };

    /** Broadcast receive to determine lightbulb state. */
    private BroadcastReceiver mLightbulbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mLightbulbActive = intent.getIntExtra(LightbulbConstants.EXTRA_CURRENT_STATE, 0) != 0;
            onLightbulbChanged();
        }
    };

    /** Broadcast receive to determine ringer mode. */
    private BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int ringerMode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, 0);
            onRingerModeChanged(ringerMode);
        }
    };

    /** Broadcast receive to determine usb tether. */
    private BroadcastReceiver mUsbIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
            }

            if (intent.getAction().equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
            }

            onUsbChanged();
        }
    };

    /** ContentObserver to determine the next alarm */
    private class NextAlarmObserver extends ContentObserver {
        public NextAlarmObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onNextAlarmChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NEXT_ALARM_FORMATTED), false, this,
                    UserHandle.USER_ALL);
        }
    }

    /** ContentObserver to watch adb */
    private class BugreportObserver extends ContentObserver {
        public BugreportObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onBugreportChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.BUGREPORT_IN_POWER_MENU), false, this);
        }
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {
        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onBrightnessLevelChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false, this, mUserTracker.getCurrentUserId());
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                    false, this, mUserTracker.getCurrentUserId());
        }
    }

    /** ContentObserver to watch Network State */
    private class NetworkObserver extends ContentObserver {
        public NetworkObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onMobileNetworkChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.PREFERRED_NETWORK_MODE), false, this);
        }
    }

    /** ContentObserver to determine the Sleep Time */
    private class SleepTimeObserver extends ContentObserver {
        public SleepTimeObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            refreshSleepTimeTile();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.SCREEN_OFF_TIMEOUT), false, this);
        }
    }

    /** ContentObserver to watch immersive **/
    private class ImmersiveObserver extends ContentObserver {
        public ImmersiveObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.PIE_STATE))) {
                boolean enablePie = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.PIE_STATE, 0) != 0;
                if (enablePie) switchImmersiveGlobal();
            }
            onImmersiveGlobalChanged();
            onImmersiveModeChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.IMMERSIVE_MODE), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.PIE_STATE), false, this);
        }
    }

    /** ContentObserver to determine the Ringer mode */
    private class RingerObserver extends ContentObserver {
        public RingerObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onRingerModeChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.VIBRATE_WHEN_RINGING), false, this);
        }
    }

    /** Callback for changes to remote display routes. */
    private class RemoteDisplayRouteCallback extends MediaRouter.SimpleCallback {
        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo route) {
            updateRemoteDisplays();
        }
    }

    /** Broadcast receiver for NFC State */
    private final BroadcastReceiver mNfcReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,
                        NfcAdapter.STATE_OFF);
                boolean nfcEnabled = state == NfcAdapter.STATE_ON ||
                        state == NfcAdapter.STATE_TURNING_ON;
                onNfcSettingsChanged(nfcEnabled);
            }
        }
    };

    /** Broadcast receive to determine wifi ap state. */
    private BroadcastReceiver mWifiApStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                onWifiApChanged();
            }
        }
    };

    /** ContentObserver observing changes of adb over network */
    private class AdbObserver extends ContentObserver {
        public AdbObserver(Handler handler) {
            super(handler);
        }

        @Override public void onChange(boolean selfChange) {
            onAdbChanged();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.ADB_PORT), false, this,
                    UserHandle.USER_ALL);
            cr.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.QUICK_SETTINGS_ADB_TILE), false, this,
                    UserHandle.USER_ALL);
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final CurrentUserTracker mUserTracker;
    private final NextAlarmObserver mNextAlarmObserver;
    private final BugreportObserver mBugreportObserver;
    private final BrightnessObserver mBrightnessObserver;
    private final AdbObserver mAdbObserver;
    private final NetworkObserver mLteObserver;

    private final NetworkObserver mMobileNetworkObserver;
    private final SleepTimeObserver mSleepTimeObserver;
    private final ImmersiveObserver mImmersiveObserver;
    private final RingerObserver mRingerObserver;
    private boolean mUsbTethered = false;
    private boolean mUsbConnected = false;
    private boolean mMassStorageActive = false;
    protected boolean mUsesAospDialer = false;
    private String[] mUsbRegexs;
    private ConnectivityManager mCM;

    private int mLastImmersiveMode = 1;

    private final AudioManager mAudioManager;
    private final Vibrator mVibrator;

    private final MediaRouter mMediaRouter;
    private final RemoteDisplayRouteCallback mRemoteDisplayRouteCallback;

    private final boolean mHasMobileData;
    protected boolean mLightbulbActive;

    private QuickSettingsTileView mUserTile;
    private RefreshCallback mUserCallback;
    private UserState mUserState = new UserState();

    private QuickSettingsTileView mTimeTile;
    private RefreshCallback mTimeCallback;
    private State mTimeState = new State();

    private QuickSettingsTileView mAlarmTile;
    private RefreshCallback mAlarmCallback;
    private State mAlarmState = new State();

    private QuickSettingsTileView mAirplaneModeTile;
    private RefreshCallback mAirplaneModeCallback;
    private State mAirplaneModeState = new State();

    private QuickSettingsTileView mUsbModeTile;
    private RefreshCallback mUsbModeCallback;
    private State mUsbModeState = new State();

    private QuickSettingsTileView mWifiTile;
    private RefreshCallback mWifiCallback;
    private WifiState mWifiState = new WifiState();

    private QuickSettingsTileView mWifiApTile;
    private RefreshCallback mWifiApCallback;
    private State mWifiApState = new State();

    private QuickSettingsTileView mRemoteDisplayTile;
    private RefreshCallback mRemoteDisplayCallback;
    private State mRemoteDisplayState = new State();

    private QuickSettingsTileView mRSSITile;
    private RefreshCallback mRSSICallback;
    private RSSIState mRSSIState = new RSSIState();

    private QuickSettingsTileView mBluetoothTile;
    private RefreshCallback mBluetoothCallback;
    private BluetoothState mBluetoothState = new BluetoothState();

    private QuickSettingsTileView mBluetoothExtraTile;
    private RefreshCallback mBluetoothExtraCallback;
    private BluetoothState mBluetoothExtraState = new BluetoothState();

    private QuickSettingsTileView mBatteryTile;
    private RefreshCallback mBatteryCallback;
    private BatteryState mBatteryState = new BatteryState();

    private QuickSettingsTileView mLocationTile;
    private RefreshCallback mLocationCallback;
    private State mLocationState = new State();

    private QuickSettingsTileView mLocationExtraTile;
    private RefreshCallback mLocationExtraCallback;
    private State mLocationExtraState = new State();

    private QuickSettingsTileView mImeTile;
    private RefreshCallback mImeCallback = null;
    private State mImeState = new State();

    private QuickSettingsTileView mRotationLockTile;
    private RefreshCallback mRotationLockCallback;
    private RotationLockState mRotationLockState = new RotationLockState();

    private QuickSettingsTileView mBrightnessTile;
    private RefreshCallback mBrightnessCallback;
    private BrightnessState mBrightnessState = new BrightnessState();

    private QuickSettingsTileView mBugreportTile;
    private RefreshCallback mBugreportCallback;
    private State mBugreportState = new State();

    private QuickSettingsTileView mSettingsTile;
    private RefreshCallback mSettingsCallback;
    private State mSettingsState = new State();

    private QuickSettingsTileView mSslCaCertWarningTile;
    private RefreshCallback mSslCaCertWarningCallback;
    private State mSslCaCertWarningState = new State();

    private QuickSettingsTileView mNfcTile;
    private RefreshCallback mNfcCallback;
    private State mNfcState = new State();

    private QuickSettingsTileView mAdbTile;
    private RefreshCallback mAdbCallback;
    private State mAdbState = new State();

    protected QuickSettingsTileView mLteTile;
    private RefreshCallback mLteCallback;
    protected State mLteState = new State();

    private QuickSettingsTileView mMobileNetworkTile;
    private RefreshCallback mMobileNetworkCallback;
    private State mMobileNetworkState = new State();

    private QuickSettingsTileView mLightbulbTile;
    private RefreshCallback mLightbulbCallback;
    private State mLightbulbState = new State();

    private QuickSettingsTileView mSleepTimeTile;
    private RefreshCallback mSleepTimeCallback;
    private State mSleepTimeState = new State();

    private QuickSettingsTileView mImmersiveGlobalTile;
    private RefreshCallback mImmersiveGlobalCallback;
    private State mImmersiveGlobalState = new State();

    private QuickSettingsTileView mImmersiveModeTile;
    private RefreshCallback mImmersiveModeCallback;
    private State mImmersiveModeState = new State();

    private QuickSettingsTileView mImmersiveExtraTile;
    private RefreshCallback mImmersiveExtraCallback;
    private State mImmersiveExtraState = new State();

    private QuickSettingsTileView mRingerModeTile;
    private RefreshCallback mRingerModeCallback;
    private State mRingerModeState = new State();

    private RotationLockController mRotationLockController;
    private LocationController mLocationController;

    public QuickSettingsModel(Context context) {
        mContext = context;
        mHandler = new Handler();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBrightnessObserver.startObserving();
                mSleepTimeObserver.startObserving();
                mImmersiveObserver.startObserving();
                mRingerObserver.startObserving();
                refreshRotationLockTile();
                onBrightnessLevelChanged();
                onNextAlarmChanged();
                onBugreportChanged();
                rebindMediaRouterAsCurrentUser();
                onUsbChanged();
                onRingerModeChanged();
            }
        };
        mNextAlarmObserver = new NextAlarmObserver(mHandler);
        mNextAlarmObserver.startObserving();
        mBugreportObserver = new BugreportObserver(mHandler);
        mBugreportObserver.startObserving();
        mBrightnessObserver = new BrightnessObserver(mHandler);
        mBrightnessObserver.startObserving();

        mAdbObserver = new AdbObserver(mHandler);
        mAdbObserver.startObserving();

        mLteObserver = new NetworkObserver(mHandler);
        mLteObserver.startObserving();

        mMobileNetworkObserver = new NetworkObserver(mHandler);
        mMobileNetworkObserver.startObserving();
        mSleepTimeObserver = new SleepTimeObserver(mHandler);
        mSleepTimeObserver.startObserving();
        mImmersiveObserver = new ImmersiveObserver(mHandler);
        mImmersiveObserver.startObserving();
        mRingerObserver = new RingerObserver(mHandler);
        mRingerObserver.startObserving();

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        mMediaRouter = (MediaRouter)context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
        rebindMediaRouterAsCurrentUser();

        mRemoteDisplayRouteCallback = new RemoteDisplayRouteCallback();

        mCM = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHasMobileData = mCM.isNetworkSupported(ConnectivityManager.TYPE_MOBILE);

        IntentFilter alarmIntentFilter = new IntentFilter();
        alarmIntentFilter.addAction(Intent.ACTION_ALARM_CHANGED);
        context.registerReceiver(mAlarmIntentReceiver, alarmIntentFilter);

        IntentFilter nfcIntentFilter = new IntentFilter();
        nfcIntentFilter.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        context.registerReceiver(mNfcReceiver, nfcIntentFilter);

        IntentFilter bootFilter = new IntentFilter();
        bootFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        context.registerReceiver(mBootReceiver, bootFilter);

        IntentFilter shutdownFilter = new IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        context.registerReceiver(mShutdownReceiver, shutdownFilter);

        IntentFilter lightbulbFilter = new IntentFilter();
        lightbulbFilter.addAction(LightbulbConstants.ACTION_STATE_CHANGED);
        context.registerReceiver(mLightbulbReceiver, lightbulbFilter);

        IntentFilter wifiApStateFilter = new IntentFilter();
        wifiApStateFilter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        context.registerReceiver(mWifiApStateReceiver, wifiApStateFilter);

        IntentFilter ringerFilter = new IntentFilter();
        ringerFilter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        context.registerReceiver(mRingerReceiver, ringerFilter);

        // Only register for devices that support usb tethering
        if (DeviceUtils.deviceSupportsUsbTether(context)) {
            IntentFilter usbIntentFilter = new IntentFilter();
            usbIntentFilter.addAction(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
            usbIntentFilter.addAction(UsbManager.ACTION_USB_STATE);
            usbIntentFilter.addAction(Intent.ACTION_MEDIA_SHARED);
            usbIntentFilter.addAction(Intent.ACTION_MEDIA_UNSHARED);
            context.registerReceiver(mUsbIntentReceiver, usbIntentFilter);
        }

        mUsesAospDialer = Settings.System.getInt(context.getContentResolver(),
                Settings.System.AOSP_DIALER, 0) == 1;
    }

    void updateResources() {
        refreshSettingsTile();
        refreshBatteryTile();
        refreshBluetoothTile();
        refreshBrightnessTile();
        refreshRotationLockTile();
        refreshRssiTile();
        refreshLocationTile();
        refreshMobileNetworkTile();
        refreshSleepTimeTile();
        refreshLocationExtraTile();
        refreshImmersiveGlobalTile();
        refreshImmersiveModeTile();
        refreshWifiApTile();
        refreshRingerModeTile();
    }

    // Settings
    void addSettingsTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSettingsTile = view;
        mSettingsCallback = cb;
        refreshSettingsTile();
    }
    void refreshSettingsTile() {
        Resources r = mContext.getResources();
        mSettingsState.label = r.getString(R.string.quick_settings_settings_label);
        mSettingsCallback.refreshView(mSettingsTile, mSettingsState);
    }

    // User
    void addUserTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUserTile = view;
        mUserCallback = cb;
        mUserCallback.refreshView(mUserTile, mUserState);
    }
    void setUserTileInfo(String name, Drawable avatar) {
        mUserState.label = name;
        mUserState.avatar = avatar;
        mUserCallback.refreshView(mUserTile, mUserState);
    }

    // Time
    void addTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mTimeTile = view;
        mTimeCallback = cb;
        mTimeCallback.refreshView(view, mTimeState);
    }

    // Alarm
    void addAlarmTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAlarmTile = view;
        mAlarmCallback = cb;
        mAlarmCallback.refreshView(view, mAlarmState);
    }
    void onAlarmChanged(Intent intent) {
        mAlarmState.enabled = intent.getBooleanExtra("alarmSet", false);
        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }
    void onNextAlarmChanged() {
        final String alarmText = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.NEXT_ALARM_FORMATTED,
                UserHandle.USER_CURRENT);
        mAlarmState.label = alarmText;

        // When switching users, this is the only clue we're going to get about whether the
        // alarm is actually set, since we won't get the ACTION_ALARM_CHANGED broadcast
        mAlarmState.enabled = ! TextUtils.isEmpty(alarmText);

        mAlarmCallback.refreshView(mAlarmTile, mAlarmState);
    }

    // Usb Mode
    void addUsbModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mUsbModeTile = view;
        mUsbModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mUsbConnected) {
                    setUsbTethering(!mUsbTethered);
                }
            }
        });
        mUsbModeCallback = cb;
        onUsbChanged();
    }

    void onUsbChanged() {
        updateState();
        if (mUsbConnected && !mMassStorageActive) {
            if (mUsbTethered) {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_on;
                mUsbModeState.label =
                        mContext.getString(R.string.quick_settings_usb_tether_on_label);
            } else {
                mUsbModeState.iconId = R.drawable.ic_qs_usb_tether_connected;
                mUsbModeState.label =
                        mContext.getString(R.string.quick_settings_usb_tether_connected_label);
            }
            mUsbModeState.enabled = true;
        } else {
            mUsbModeState.enabled = false;
        }
        mUsbModeCallback.refreshView(mUsbModeTile, mUsbModeState);
    }

    // Airplane Mode
    void addAirplaneModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAirplaneModeTile = view;
        mAirplaneModeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAirplaneModeState.enabled) {
                    setAirplaneModeState(false);
                } else {
                    setAirplaneModeState(true);
                }
            }
        });
        mAirplaneModeCallback = cb;
        int airplaneMode = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0);
        onAirplaneModeChanged(airplaneMode != 0);
    }
    private void setAirplaneModeState(boolean enabled) {
        // TODO: Sets the view to be "awaiting" if not already awaiting

        // Change the system setting
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON,
                enabled ? 1 : 0);

        // Post the intent
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", enabled);
        mContext.sendBroadcast(intent);
    }
    // NetworkSignalChanged callback
    @Override
    public void onAirplaneModeChanged(boolean enabled) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mAirplaneModeState.enabled = enabled;
        mAirplaneModeState.iconId = (enabled ?
                R.drawable.ic_qs_airplane_on :
                R.drawable.ic_qs_airplane_off);
        mAirplaneModeState.label = r.getString(R.string.quick_settings_airplane_mode_label);
        mAirplaneModeCallback.refreshView(mAirplaneModeTile, mAirplaneModeState);
    }

    // Wifi
    void addWifiTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiTile = view;
        mWifiCallback = cb;
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }
    // Remove the double quotes that the SSID may contain
    public static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
    }
    // Remove the period from the network name
    public static String removeTrailingPeriod(String string) {
        if (string == null) return null;
        final int length = string.length();
        if (string.endsWith(".")) {
            return string.substring(0, length - 1);
        }
        return string;
    }
    // NetworkSignalChanged callback
    @Override
    public void onWifiSignalChanged(boolean enabled, int wifiSignalIconId,
            boolean activityIn, boolean activityOut,
            String wifiSignalContentDescription, String enabledDesc) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();

        boolean wifiConnected = enabled && (wifiSignalIconId > 0) && (enabledDesc != null);
        boolean wifiNotConnected = (wifiSignalIconId > 0) && (enabledDesc == null);
        mWifiState.enabled = enabled;
        mWifiState.connected = wifiConnected;
        mWifiState.activityIn = enabled && activityIn;
        mWifiState.activityOut = enabled && activityOut;
        if (wifiConnected) {
            mWifiState.iconId = wifiSignalIconId;
            mWifiState.label = removeDoubleQuotes(enabledDesc);
            mWifiState.signalContentDescription = wifiSignalContentDescription;
        } else if (wifiNotConnected) {
            mWifiState.iconId = R.drawable.ic_qs_wifi_0;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_no_wifi);
        } else {
            mWifiState.iconId = R.drawable.ic_qs_wifi_no_network;
            mWifiState.label = r.getString(R.string.quick_settings_wifi_off_label);
            mWifiState.signalContentDescription = r.getString(R.string.accessibility_wifi_off);
        }
        mWifiCallback.refreshView(mWifiTile, mWifiState);
    }

    boolean deviceHasMobileData() {
        return mHasMobileData;
    }

    boolean deviceSupportsLTE() {
        return DeviceUtils.deviceSupportsLte(mContext);
    }

    boolean deviceHasCameraFlash() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_device_has_camera_flash);
    }

    // RSSI
    void addRSSITile(QuickSettingsTileView view, RefreshCallback cb) {
        mRSSITile = view;
        mRSSICallback = cb;
        refreshRssiTile();
    }
    // NetworkSignalChanged callback
    @Override
    public void onMobileDataSignalChanged(
            boolean enabled, int mobileSignalIconId, String signalContentDescription,
            int dataTypeIconId, boolean activityIn, boolean activityOut,
            String dataContentDescription,String enabledDesc) {
        if (deviceHasMobileData()) {
            // TODO: If view is in awaiting state, disable
            Resources r = mContext.getResources();
            mRSSIState.signalIconId = enabled && (mobileSignalIconId > 0)
                    ? mobileSignalIconId
                    : R.drawable.ic_qs_signal_no_signal;
            mRSSIState.signalContentDescription = enabled && (mobileSignalIconId > 0)
                    ? signalContentDescription
                    : r.getString(R.string.accessibility_no_signal);
            mRSSIState.dataTypeIconId = enabled && (dataTypeIconId > 0) && !mWifiState.connected
                    ? dataTypeIconId
                    : 0;
            mRSSIState.activityIn = enabled && activityIn;
            mRSSIState.activityOut = enabled && activityOut;
            mRSSIState.dataContentDescription = enabled && (dataTypeIconId > 0) && !mWifiState.connected
                    ? dataContentDescription
                    : r.getString(R.string.accessibility_no_data);
            mRSSIState.label = enabled
                    ? removeTrailingPeriod(enabledDesc)
                    : r.getString(R.string.quick_settings_rssi_emergency_only);
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
    }

    void refreshRssiTile() {
        if (mRSSITile != null) {
            mRSSICallback.refreshView(mRSSITile, mRSSIState);
        }
    }

    // Mobile Network
    void addMobileNetworkTile(QuickSettingsTileView view, RefreshCallback cb) {
        mMobileNetworkTile = view;
        mMobileNetworkCallback = cb;
        onMobileNetworkChanged();
    }

    void onMobileNetworkChanged() {
        if (deviceHasMobileData() && mUsesAospDialer) {
            mMobileNetworkState.label = getNetworkType(mContext.getResources());
            mMobileNetworkState.iconId = getNetworkTypeIcon();
            mMobileNetworkCallback.refreshView(mMobileNetworkTile, mMobileNetworkState);
        }
    }

    void refreshMobileNetworkTile() {
        onMobileNetworkChanged();
    }

    protected void toggleMobileNetworkState() {
        TelephonyManager tm = (TelephonyManager)
            mContext.getSystemService(Context.TELEPHONY_SERVICE);
        boolean usesQcLte = SystemProperties.getBoolean(
                        "ro.config.qc_lte_network_modes", false);
        int network = getCurrentPreferredNetworkMode(mContext);
        switch(network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                break;
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
                // 2G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA_NO_EVDO);
                break;
            case Phone.NT_MODE_GSM_ONLY:
                // 3G Only
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_ONLY);
                break;
            case Phone.NT_MODE_WCDMA_ONLY:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_WCDMA_PREF);
                break;
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
                // 2G/3G
                tm.toggleMobileNetwork(Phone.NT_MODE_CDMA);
                break;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
                // LTE
                if (deviceSupportsLTE()) {
                    if (usesQcLte) {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                    } else {
                        tm.toggleMobileNetwork(Phone.NT_MODE_LTE_GSM_WCDMA);
                    }
                } else {
                    tm.toggleMobileNetwork(Phone.NT_MODE_GSM_ONLY);
                }
                break;
            case Phone.NT_MODE_CDMA:
                tm.toggleMobileNetwork(Phone.NT_MODE_LTE_CDMA_AND_EVDO);
                break;
        }
    }

    private String getNetworkType(Resources r) {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_WCDMA_ONLY:
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_EVDO_NO_CDMA:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_CDMA:
                return r.getString(R.string.quick_settings_network_type);
        }
        return r.getString(R.string.quick_settings_network_unknown);
    }

    private int getNetworkTypeIcon() {
        int network = getCurrentPreferredNetworkMode(mContext);
        switch (network) {
            case Phone.NT_MODE_GLOBAL:
            case Phone.NT_MODE_LTE_CDMA_AND_EVDO:
            case Phone.NT_MODE_LTE_GSM_WCDMA:
            case Phone.NT_MODE_LTE_CMDA_EVDO_GSM_WCDMA:
            case Phone.NT_MODE_LTE_ONLY:
            case Phone.NT_MODE_LTE_WCDMA:
                return R.drawable.ic_qs_lte_on;
            case Phone.NT_MODE_WCDMA_ONLY:
                return R.drawable.ic_qs_3g_on;
            case Phone.NT_MODE_GSM_ONLY:
            case Phone.NT_MODE_CDMA_NO_EVDO:
            case Phone.NT_MODE_EVDO_NO_CDMA:
                return R.drawable.ic_qs_2g_on;
            case Phone.NT_MODE_WCDMA_PREF:
            case Phone.NT_MODE_GSM_UMTS:
            case Phone.NT_MODE_CDMA:
                return R.drawable.ic_qs_2g3g_on;
        }
        return R.drawable.ic_qs_unexpected_network;
    }

    public static int getCurrentPreferredNetworkMode(Context context) {
        int network = Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.PREFERRED_NETWORK_MODE, -1);
        return network;
    }

    public boolean isMobileDataEnabled(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm.getMobileDataEnabled();
    }

    // Bluetooth
    void addBluetoothTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothTile = view;
        mBluetoothCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothState.enabled = adapter.isEnabled();
        mBluetoothState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothState);
    }
    void addBluetoothExtraTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBluetoothExtraTile = view;
        mBluetoothExtraCallback = cb;

        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothExtraState.enabled = adapter.isEnabled();
        mBluetoothExtraState.connected =
                (adapter.getConnectionState() == BluetoothAdapter.STATE_CONNECTED);
        onBluetoothStateChange(mBluetoothExtraState);
    }
    boolean deviceSupportsBluetooth() {
        return (BluetoothAdapter.getDefaultAdapter() != null);
    }
    // BluetoothController callback
    @Override
    public void onBluetoothStateChange(boolean on) {
        mBluetoothState.enabled = on;
        onBluetoothStateChange(mBluetoothState);
    }
    public void onBluetoothStateChange(BluetoothState bluetoothStateIn) {
        // TODO: If view is in awaiting state, disable
        Resources r = mContext.getResources();
        mBluetoothState.enabled = bluetoothStateIn.enabled;
        mBluetoothState.connected = bluetoothStateIn.connected;
        if (mBluetoothState.enabled) {
            if (mBluetoothState.connected) {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_on;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_connected);
            } else {
                mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_not_connected;
                mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_on);
            }
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_label);
        } else {
            mBluetoothState.iconId = R.drawable.ic_qs_bluetooth_off;
            mBluetoothState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            mBluetoothState.stateContentDescription = r.getString(R.string.accessibility_desc_off);
        }

        if (mBluetoothExtraTile != null) {
            final BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (mBluetoothAdapter.getScanMode()
                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                mBluetoothExtraState.iconId = R.drawable.ic_qs_bluetooth_discoverable;
                mBluetoothExtraState.label = r.getString(R.string.quick_settings_bluetooth_label);
            } else {
                mBluetoothExtraState.iconId = R.drawable.ic_qs_bluetooth_discoverable_off;
                mBluetoothExtraState.label = r.getString(R.string.quick_settings_bluetooth_off_label);
            }
        }

        mBluetoothCallback.refreshView(mBluetoothTile, mBluetoothState);

        if (mBluetoothExtraTile != null) {
            mBluetoothExtraCallback.refreshView(mBluetoothExtraTile, mBluetoothExtraState);
        }
    }
    void refreshBluetoothTile() {
        if (mBluetoothTile != null) {
            onBluetoothStateChange(mBluetoothState.enabled);
        }
    }

    // Battery
    void addBatteryTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBatteryTile = view;
        mBatteryCallback = cb;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    // BatteryController callback
    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn) {
        mBatteryState.batteryLevel = level;
        mBatteryState.pluggedIn = pluggedIn;
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }
    void refreshBatteryTile() {
        if (mBatteryCallback == null) {
            return;
        }
        mBatteryCallback.refreshView(mBatteryTile, mBatteryState);
    }

    // Location
    void addLocationTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLocationTile = view;
        mLocationCallback = cb;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
    }

    void refreshLocationTile() {
        if (mLocationTile != null) {
            onLocationSettingsChanged(mLocationState.enabled);
        }
    }

    @Override
    public void onLocationSettingsChanged(boolean locationEnabled) {
        int textResId = locationEnabled ? R.string.quick_settings_location_label
                : R.string.quick_settings_location_off_label;
        String label = mContext.getText(textResId).toString();
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_on : R.drawable.ic_qs_location_off;
        mLocationState.enabled = locationEnabled;
        mLocationState.label = label;
        mLocationState.iconId = locationIconId;
        mLocationCallback.refreshView(mLocationTile, mLocationState);
        refreshLocationExtraTile();
    }

    void addLocationExtraTile(QuickSettingsTileView view, LocationController controller, RefreshCallback cb) {
        mLocationExtraTile = view;
        mLocationController = controller;
        mLocationExtraCallback = cb;
        mLocationExtraCallback.refreshView(mLocationExtraTile, mLocationExtraState);
    }

    void refreshLocationExtraTile() {
        if (mLocationExtraTile != null) {
            onLocationExtraSettingsChanged(mLocationController.locationMode(), mLocationState.enabled);
        }
    }

    private void onLocationExtraSettingsChanged(int mode, boolean locationEnabled) {
        int locationIconId = locationEnabled
                ? R.drawable.ic_qs_location_accuracy_on : R.drawable.ic_qs_location_accuracy_off;
        mLocationExtraState.enabled = locationEnabled;
        mLocationExtraState.label = getLocationMode(mContext.getResources(), mode);
        mLocationExtraState.iconId = locationIconId;
        mLocationExtraCallback.refreshView(mLocationExtraTile, mLocationExtraState);
    }

    private String getLocationMode(Resources r, int location) {
        switch (location) {
            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                return r.getString(R.string.quick_settings_location_mode_sensors_label);
            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                return r.getString(R.string.quick_settings_location_mode_battery_label);
            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                return r.getString(R.string.quick_settings_location_mode_high_label);
        }
        return r.getString(R.string.quick_settings_location_off_label);
    }

    // NFC
    void addNfcTile(QuickSettingsTileView view, RefreshCallback cb) {
        mNfcTile = view;
        mNfcCallback = cb;
        mNfcCallback.refreshView(view, mNfcState);
    }
    void onNfcSettingsChanged(boolean nfcEnabled) {
        int textResId = nfcEnabled ? R.string.quick_settings_nfc_label
                : R.string.quick_settings_nfc_off_label;
        String label = mContext.getText(textResId).toString();
        int nfcIconId = nfcEnabled
                ? R.drawable.ic_qs_nfc_on : R.drawable.ic_qs_nfc_off;
        mNfcState.enabled = nfcEnabled;
        mNfcState.label = label;
        mNfcState.iconId = nfcIconId;
        mNfcCallback.refreshView(mNfcTile, mNfcState);
    }

    //Adb
    void addAdbTile(QuickSettingsTileView view, RefreshCallback cb) {
        mAdbTile = view;
        mAdbCallback = cb;
        mAdbCallback.refreshView(view, mAdbState);

    }
    void onAdbChanged() {
        if (mAdbTile != null) {
            boolean enabled = Settings.Secure.getInt(mContext.getContentResolver(),
                    Settings.Secure.ADB_PORT, -1) != -1;
            mAdbState.enabled = enabled;
            int textResId = enabled ? R.string.quick_settings_adb_label
                    : R.string.quick_settings_adb_off_label;
            String label = mContext.getText(textResId).toString();
            int iconId = enabled
                    ? R.drawable.ic_qs_adb_on : R.drawable.ic_qs_adb_off;
            mAdbState.label = label;
            mAdbState.iconId = iconId;
            mAdbCallback.refreshView(mAdbTile, mAdbState);
        }
    }

    // Bug report
    void addBugreportTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBugreportTile = view;
        mBugreportCallback = cb;
        onBugreportChanged();
    }
    // SettingsObserver callback
    public void onBugreportChanged() {
        final ContentResolver cr = mContext.getContentResolver();
        boolean enabled = false;
        try {
            enabled = (Settings.Global.getInt(cr, Settings.Global.BUGREPORT_IN_POWER_MENU) != 0);
        } catch (SettingNotFoundException e) {
        }

        mBugreportState.enabled = enabled && mUserTracker.isCurrentUserOwner();
        mBugreportCallback.refreshView(mBugreportTile, mBugreportState);
    }

    // Remote Display
    void addRemoteDisplayTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRemoteDisplayTile = view;
        mRemoteDisplayCallback = cb;
        final int[] count = new int[1];
        mRemoteDisplayTile.setOnPrepareListener(new QuickSettingsTileView.OnPrepareListener() {
            @Override
            public void onPrepare() {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mRemoteDisplayRouteCallback,
                        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                updateRemoteDisplays();
            }
            @Override
            public void onUnprepare() {
                mMediaRouter.removeCallback(mRemoteDisplayRouteCallback);
            }
        });

        updateRemoteDisplays();
    }

    private void rebindMediaRouterAsCurrentUser() {
        mMediaRouter.rebindAsUser(mUserTracker.getCurrentUserId());
    }

    private void updateRemoteDisplays() {
        MediaRouter.RouteInfo connectedRoute = mMediaRouter.getSelectedRoute(
                MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean enabled = connectedRoute != null
                && connectedRoute.matchesTypes(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY);
        boolean connecting;
        if (enabled) {
            connecting = connectedRoute.isConnecting();
        } else {
            connectedRoute = null;
            connecting = false;
            enabled = mMediaRouter.isRouteAvailable(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
        }

        mRemoteDisplayState.enabled = enabled;
        if (connectedRoute != null) {
            mRemoteDisplayState.label = connectedRoute.getName().toString();
            mRemoteDisplayState.iconId = connecting ?
                    R.drawable.ic_qs_cast_connecting : R.drawable.ic_qs_cast_connected;
        } else {
            mRemoteDisplayState.label = mContext.getString(
                    R.string.quick_settings_remote_display_no_connection_label);
            mRemoteDisplayState.iconId = R.drawable.ic_qs_cast_available;
        }
        mRemoteDisplayCallback.refreshView(mRemoteDisplayTile, mRemoteDisplayState);
    }

    // IME
    void addImeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImeTile = view;
        mImeCallback = cb;
        mImeCallback.refreshView(mImeTile, mImeState);
    }
    /* This implementation is taken from
       InputMethodManagerService.needsToShowImeSwitchOngoingNotification(). */
    private boolean needsToShowImeSwitchOngoingNotification(InputMethodManager imm) {
        List<InputMethodInfo> imis = imm.getEnabledInputMethodList();
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes = imm.getEnabledInputMethodSubtypeList(imi,
                    true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                            || auxSubtype.overridesImplicitlyEnabledSubtype()
                            || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }
    void onImeWindowStatusChanged(boolean visible) {
        InputMethodManager imm =
                (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> imis = imm.getInputMethodList();

        mImeState.enabled = (visible && needsToShowImeSwitchOngoingNotification(imm));
        mImeState.label = getCurrentInputMethodName(mContext, mContext.getContentResolver(),
                imm, imis, mContext.getPackageManager());
        if (mImeCallback != null) {
            mImeCallback.refreshView(mImeTile, mImeState);
        }
    }
    private static String getCurrentInputMethodName(Context context, ContentResolver resolver,
            InputMethodManager imm, List<InputMethodInfo> imis, PackageManager pm) {
        if (resolver == null || imis == null) return null;
        final String currentInputMethodId = Settings.Secure.getString(resolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (TextUtils.isEmpty(currentInputMethodId)) return null;
        for (InputMethodInfo imi : imis) {
            if (currentInputMethodId.equals(imi.getId())) {
                final InputMethodSubtype subtype = imm.getCurrentInputMethodSubtype();
                final CharSequence summary = subtype != null
                        ? subtype.getDisplayName(context, imi.getPackageName(),
                                imi.getServiceInfo().applicationInfo)
                        : context.getString(R.string.quick_settings_ime_label);
                return summary.toString();
            }
        }
        return null;
    }

    // Rotation lock
    void addRotationLockTile(QuickSettingsTileView view,
            RotationLockController rotationLockController,
            RefreshCallback cb) {
        mRotationLockTile = view;
        mRotationLockCallback = cb;
        mRotationLockController = rotationLockController;
        onRotationLockChanged();
    }
    void onRotationLockChanged() {
        onRotationLockStateChanged(mRotationLockController.isRotationLocked(),
                mRotationLockController.isRotationLockAffordanceVisible());
    }
    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        mRotationLockState.visible = affordanceVisible;
        mRotationLockState.enabled = rotationLocked;
        mRotationLockState.iconId = rotationLocked
                ? R.drawable.ic_qs_rotation_locked
                : R.drawable.ic_qs_auto_rotate;
        mRotationLockState.label = rotationLocked
                ? mContext.getString(R.string.quick_settings_rotation_locked_label)
                : mContext.getString(R.string.quick_settings_rotation_unlocked_label);
        mRotationLockCallback.refreshView(mRotationLockTile, mRotationLockState);
    }
    void refreshRotationLockTile() {
        if (mRotationLockTile != null) {
            onRotationLockChanged();
        }
    }

    // Brightness
    void addBrightnessTile(QuickSettingsTileView view, RefreshCallback cb) {
        mBrightnessTile = view;
        mBrightnessCallback = cb;
        onBrightnessLevelChanged();
    }
    @Override
    public void onBrightnessLevelChanged() {
        Resources r = mContext.getResources();
        int mode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                mUserTracker.getCurrentUserId());
        mBrightnessState.autoBrightness =
                (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mBrightnessState.iconId = mBrightnessState.autoBrightness
                ? R.drawable.ic_qs_brightness_auto_on
                : R.drawable.ic_qs_brightness_auto_off;
        mBrightnessState.label = r.getString(R.string.quick_settings_brightness_label);
        mBrightnessCallback.refreshView(mBrightnessTile, mBrightnessState);
    }
    void refreshBrightnessTile() {
        onBrightnessLevelChanged();
    }

    // SSL CA Cert warning.
    public void addSslCaCertWarningTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSslCaCertWarningTile = view;
        mSslCaCertWarningCallback = cb;
        // Set a sane default while we wait for the AsyncTask to finish (no cert).
        setSslCaCertWarningTileInfo(false, true);
    }
    public void setSslCaCertWarningTileInfo(boolean hasCert, boolean isManaged) {
        Resources r = mContext.getResources();
        mSslCaCertWarningState.enabled = hasCert;
        if (isManaged) {
            mSslCaCertWarningState.iconId = R.drawable.ic_qs_certificate_info;
        } else {
            mSslCaCertWarningState.iconId = android.R.drawable.stat_notify_error;
        }
        mSslCaCertWarningState.label = r.getString(R.string.ssl_ca_cert_warning);
        mSslCaCertWarningCallback.refreshView(mSslCaCertWarningTile, mSslCaCertWarningState);
    }

    // Lightbulb
    void addLightbulbTile(QuickSettingsTileView view, RefreshCallback cb) {
        mLightbulbTile = view;
        mLightbulbCallback = cb;
        onLightbulbChanged();
    }

    void onLightbulbChanged() {
        if (mLightbulbActive) {
            mLightbulbState.iconId = R.drawable.ic_qs_lightbulb_on;
            mLightbulbState.label = mContext.getString(R.string.quick_settings_lightbulb_label);
        } else {
            mLightbulbState.iconId = R.drawable.ic_qs_lightbulb_off;
            mLightbulbState.label = mContext.getString(R.string.quick_settings_lightbulb_off_label);
        }
        mLightbulbState.enabled = mLightbulbActive;
        mLightbulbCallback.refreshView(mLightbulbTile, mLightbulbState);
    }

    private void updateState() {
        mUsbRegexs = mCM.getTetherableUsbRegexs();

        String[] available = mCM.getTetherableIfaces();
        String[] tethered = mCM.getTetheredIfaces();
        String[] errored = mCM.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
    }

    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {

        mUsbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) mUsbTethered = true;
            }
        }

    }

    private void setUsbTethering(boolean enabled) {
        if (mCM.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            return;
        }
    }

    // Sleep: Screen timeout sub-tile (sleep time tile)
    private static final int SCREEN_TIMEOUT_15     =   15000;
    private static final int SCREEN_TIMEOUT_30     =   30000;
    private static final int SCREEN_TIMEOUT_60     =   60000;
    private static final int SCREEN_TIMEOUT_120    =  120000;
    private static final int SCREEN_TIMEOUT_300    =  300000;
    private static final int SCREEN_TIMEOUT_600    =  600000;
    private static final int SCREEN_TIMEOUT_1800   = 1800000;

    void addSleepTimeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mSleepTimeTile = view;
        mSleepTimeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenTimeoutChangeState();
                refreshSleepTimeTile();
            }
        });
        mSleepTimeCallback = cb;
        refreshSleepTimeTile();
    }

    private void refreshSleepTimeTile() {
        mSleepTimeState.enabled = true;
        mSleepTimeState.iconId = R.drawable.ic_qs_sleep_time;
        mSleepTimeState.label = screenTimeoutGetLabel(getScreenTimeout());
        mSleepTimeCallback.refreshView(mSleepTimeTile, mSleepTimeState);
    }

    protected int getScreenTimeout() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_TIMEOUT_30);
    }

    protected void screenTimeoutChangeState() {
        final int currentScreenTimeout = getScreenTimeout();
        int screenTimeout = currentScreenTimeout;

        switch(currentScreenTimeout) {
            case SCREEN_TIMEOUT_15:
                screenTimeout = SCREEN_TIMEOUT_30;
                break;
            case SCREEN_TIMEOUT_30:
                screenTimeout = SCREEN_TIMEOUT_60;
                break;
            case SCREEN_TIMEOUT_60:
                screenTimeout = SCREEN_TIMEOUT_120;
                break;
            case SCREEN_TIMEOUT_120:
                screenTimeout = SCREEN_TIMEOUT_300;
                break;
            case SCREEN_TIMEOUT_300:
                screenTimeout = SCREEN_TIMEOUT_600;
                break;
            case SCREEN_TIMEOUT_600:
                screenTimeout = SCREEN_TIMEOUT_1800;
                break;
            case SCREEN_TIMEOUT_1800:
                screenTimeout = SCREEN_TIMEOUT_15;
                break;
        }

        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, screenTimeout);
        }

    protected String screenTimeoutGetLabel(int currentTimeout) {
        switch(currentTimeout) {
            case SCREEN_TIMEOUT_15:
                return mContext.getString(R.string.quick_settings_sleep_time_15_label);
            case SCREEN_TIMEOUT_30:
                return mContext.getString(R.string.quick_settings_sleep_time_30_label);
            case SCREEN_TIMEOUT_60:
                return mContext.getString(R.string.quick_settings_sleep_time_60_label);
            case SCREEN_TIMEOUT_120:
                return mContext.getString(R.string.quick_settings_sleep_time_120_label);
            case SCREEN_TIMEOUT_300:
                return mContext.getString(R.string.quick_settings_sleep_time_300_label);
            case SCREEN_TIMEOUT_600:
                return mContext.getString(R.string.quick_settings_sleep_time_600_label);
            case SCREEN_TIMEOUT_1800:
                return mContext.getString(R.string.quick_settings_sleep_time_1800_label);
        }
        return mContext.getString(R.string.quick_settings_sleep_time_unknown_label);
    }

    // Immersive mode
    private int immersiveModeLastState = 1;

    public static final int IMMERSIVE_MODE_OFF = 0;
    public static final int IMMERSIVE_MODE_FULL = 1;
    public static final int IMMERSIVE_MODE_HIDE_ONLY_NAVBAR = 2;
    public static final int IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR = 3;

    void addImmersiveGlobalTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveGlobalTile = view;
        mImmersiveGlobalCallback = cb;
        onImmersiveGlobalChanged();
    }

    void addImmersiveModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mImmersiveModeTile = view;
        mImmersiveModeCallback = cb;
        onImmersiveModeChanged();
    }

    private void onImmersiveGlobalChanged() {
        Resources r = mContext.getResources();
        final int mode = getImmersiveMode();
        final boolean enabled = isPieEnabled();
        if (mode == IMMERSIVE_MODE_OFF) {
            mImmersiveGlobalState.iconId = enabled ?
                    R.drawable.ic_qs_pie_global_off : R.drawable.ic_qs_immersive_global_off;
            mImmersiveGlobalState.label = r.getString(R.string.quick_settings_immersive_global_off_label);
        } else {
            mImmersiveGlobalState.iconId = enabled ?
                    R.drawable.ic_qs_pie_global_on : R.drawable.ic_qs_immersive_global_on;
            mImmersiveGlobalState.label = r.getString(R.string.quick_settings_immersive_global_on_label);
        }
        mImmersiveGlobalCallback.refreshView(mImmersiveGlobalTile, mImmersiveGlobalState);
    }

    private void onImmersiveModeChanged() {
        Resources r = mContext.getResources();
        final int mode = getImmersiveMode();
        switch(mode) {
            case IMMERSIVE_MODE_OFF:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_off;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_off_label);
                break;
            case IMMERSIVE_MODE_FULL:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_full;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_full_label);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_navigation_bar_off;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_no_status_bar_label);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                mImmersiveModeState.iconId =
                        R.drawable.ic_qs_immersive_status_bar_off;
                mImmersiveModeState.label =
                        r.getString(R.string.quick_settings_immersive_mode_no_navigation_bar_label);
                break;
        }
        mImmersiveModeCallback.refreshView(mImmersiveModeTile, mImmersiveModeState);
    }

    void refreshImmersiveGlobalTile() {
        onImmersiveGlobalChanged();
    }

    void refreshImmersiveModeTile() {
        onImmersiveModeChanged();
    }

    void refreshImmersiveExtraTile() {
        onImmersiveModeChanged();
    }

    protected int getImmersiveMode() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, 0);
    }

    protected boolean isPieEnabled() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.PIE_STATE, 0) == 1;
    }

    private void setImmersiveMode(int style) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.IMMERSIVE_MODE, style);
        if (style != 0) {
            immersiveModeLastState = style;
        }
    }

    protected void switchImmersiveGlobal() {
        final int current = getImmersiveMode();
        switch(current) {
            case IMMERSIVE_MODE_OFF:
                setImmersiveMode(immersiveModeLastState);
                break;
            case IMMERSIVE_MODE_FULL:
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                setImmersiveMode(IMMERSIVE_MODE_OFF);
                break;
        }
    }

    protected void switchImmersiveMode() {
        final int current = getImmersiveMode();
        switch(current) {
            case IMMERSIVE_MODE_FULL:
                setImmersiveMode(IMMERSIVE_MODE_HIDE_ONLY_NAVBAR);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_NAVBAR:
                setImmersiveMode(IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR);
                break;
            case IMMERSIVE_MODE_HIDE_ONLY_STATUSBAR:
                setImmersiveMode(IMMERSIVE_MODE_FULL);
                break;
        }
    }

    // Wifi Ap
    void addWifiApTile(QuickSettingsTileView view, RefreshCallback cb) {
        mWifiApTile = view;
        mWifiApCallback = cb;
        onWifiApChanged();
    }

    void onWifiApChanged() {
        if (isWifiApEnabled()) {
            mWifiApState.iconId = R.drawable.ic_qs_wifi_ap_on;
            mWifiApState.label = mContext.getString(R.string.quick_settings_wifi_ap_label);
        } else {
            mWifiApState.iconId = R.drawable.ic_qs_wifi_ap_off;
            mWifiApState.label = mContext.getString(R.string.quick_settings_wifi_ap_off_label);
        }
        mWifiApState.enabled = isWifiApEnabled();
        mWifiApCallback.refreshView(mWifiApTile, mWifiApState);
    }

    void refreshWifiApTile() {
        onWifiApChanged();
    }

    protected void toggleWifiApState() {
        setWifiApEnabled(isWifiApEnabled() ? false : true);
    }

    protected boolean isWifiApEnabled() {
        WifiManager mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        int state = mWifiManager.getWifiApState();
        boolean active;
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                active = true;
                break;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
            default:
                active = false;
                break;
        }
        return active;
    }

    protected void setWifiApEnabled(boolean enable) {
        WifiManager mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        final ContentResolver cr = mContext.getContentResolver();
        /**
         * Disable Wifi if enabling tethering
         */
        int wifiState = mWifiManager.getWifiState();
        if (enable && ((wifiState == WifiManager.WIFI_STATE_ENABLING) ||
                (wifiState == WifiManager.WIFI_STATE_ENABLED))) {
            mWifiManager.setWifiEnabled(false);
            Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 1);
        }

        // Turn on the Wifi AP
        mWifiManager.setWifiApEnabled(null, enable);

        /**
         *  If needed, restore Wifi on tether disable
         */
        if (!enable) {
            int wifiSavedState = 0;
            try {
                wifiSavedState = Settings.Global.getInt(cr, Settings.Global.WIFI_SAVED_STATE);
            } catch (Settings.SettingNotFoundException e) {
                // Do nothing here
            }
            if (wifiSavedState == 1) {
                mWifiManager.setWifiEnabled(true);
                Settings.Global.putInt(cr, Settings.Global.WIFI_SAVED_STATE, 0);
            }
        }
    }

    // Ringer mode
    private static final int RINGER_MODE_SILENT = 0;
    private static final int RINGER_MODE_VIBRATE = 1;
    private static final int RINGER_MODE_NORMAL = 2;

    void addRingerModeTile(QuickSettingsTileView view, RefreshCallback cb) {
        mRingerModeTile = view;
        mRingerModeCallback = cb;
        onRingerModeChanged();
    }

    void onRingerModeChanged() {
        onRingerModeChanged(getRingerMode());
    }

    private void onRingerModeChanged(int ringerMode) {
        Resources r = mContext.getResources();
        switch (ringerMode) {
            case RINGER_MODE_SILENT:
                updateRingerModeTile(R.drawable.ic_qs_ringer_silent,
                        r.getString(R.string.quick_settings_ringer_mode_silent_label));
                break;
            case RINGER_MODE_VIBRATE:
                updateRingerModeTile(R.drawable.ic_qs_ringer_vibrate,
                        r.getString(R.string.quick_settings_ringer_mode_vibrate_label));
                break;
            case RINGER_MODE_NORMAL:
                if (vibrateWhenRinging()) {
                    updateRingerModeTile(R.drawable.ic_qs_ringer_normal_vibrate,
                            r.getString(R.string.quick_settings_ringer_mode_normal_vibrate_label));
                } else {
                    updateRingerModeTile(R.drawable.ic_qs_ringer_normal,
                            r.getString(R.string.quick_settings_ringer_mode_normal_label));
                }
                break;
        }
        mRingerModeCallback.refreshView(mRingerModeTile, mRingerModeState);
    }

    void updateRingerModeTile(int icon, String label) {
        mRingerModeState.iconId = icon;
        mRingerModeState.label = label;
    }

    void refreshRingerModeTile() {
        onRingerModeChanged();
    }

    protected int getRingerMode() {
        return mAudioManager.getRingerMode();
    }

    private void setRingerMode(int mode) {
        mAudioManager.setRingerMode(mode);
    }

    protected boolean vibrateWhenRinging() {
        return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }

    protected void switchRingerMode() {
        int ringerMode = getRingerMode();
        switch (ringerMode) {
            case RINGER_MODE_SILENT:
                if (mVibrator.hasVibrator()) {
                    setRingerMode(RINGER_MODE_VIBRATE);
                    mVibrator.vibrate(150);
                } else {
                    setRingerMode(RINGER_MODE_NORMAL);
                }
                break;
            case RINGER_MODE_VIBRATE:
                setRingerMode(RINGER_MODE_NORMAL);
                if(vibrateWhenRinging()) mVibrator.vibrate(150);
                break;
            case RINGER_MODE_NORMAL:
                setRingerMode(RINGER_MODE_SILENT);
                break;
        }
    }
}
