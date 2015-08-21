/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2015 Sony Mobile Communications Inc.
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
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/

package com.gsma.rcs.core.ims.network;

import com.gsma.rcs.core.Core;
import com.gsma.rcs.core.ims.ImsModule;
import com.gsma.rcs.core.ims.network.ImsNetworkInterface.DnsResolvedFields;
import com.gsma.rcs.core.ims.protocol.sip.SipNetworkException;
import com.gsma.rcs.core.ims.protocol.sip.SipPayloadException;
import com.gsma.rcs.core.ims.service.ImsServiceSession.TerminationReason;
import com.gsma.rcs.platform.AndroidFactory;
import com.gsma.rcs.platform.network.NetworkFactory;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.provider.settings.RcsSettingsData.NetworkAccessType;
import com.gsma.rcs.service.LauncherUtils;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.CommonServiceConfiguration.MinimumBatteryLevel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.telephony.TelephonyManager;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Random;

/**
 * IMS connection manager
 * 
 * @author JM. Auffret
 * @author Deutsche Telekom
 */
public class ImsConnectionManager implements Runnable {

    /**
     * Core
     */
    private final Core mCore;

    /**
     * IMS module
     */
    private ImsModule mImsModule;

    /**
     * Network interfaces
     */
    private ImsNetworkInterface[] mNetworkInterfaces = new ImsNetworkInterface[2];

    /**
     * IMS network interface
     */
    private ImsNetworkInterface mCurrentNetworkInterface;

    /**
     * IMS polling thread
     */
    private Thread mImsPollingThread;

    /**
     * IMS polling thread Id
     */
    private long mImsPollingThreadId = -1;

    /**
     * Connectivity manager
     */
    private ConnectivityManager mCnxManager;

    /**
     * Network access type
     */
    private NetworkAccessType mNetwork;

    /**
     * Operator
     */
    private String mOperator;

    /**
     * DNS resolved fields
     */
    private DnsResolvedFields mDnsResolvedFields;

    /**
     * Battery level state
     */
    private boolean mDisconnectedByBattery = false;

    /**
     * IMS services already started
     */
    private boolean mImsServicesStarted = false;

    /**
     * The logger
     */
    private static final Logger sLogger = Logger.getLogger(ImsConnectionManager.class.getName());

    private final RcsSettings mRcsSettings;

    /**
     * Constructor
     * 
     * @param core Core
     * @param rcsSettings RcsSettings instance
     */
    public ImsConnectionManager(ImsModule imsModule, RcsSettings rcsSettings) {
        mImsModule = imsModule;
        mCore = imsModule.getCore();

        mRcsSettings = rcsSettings;

        // Get network access parameters
        mNetwork = rcsSettings.getNetworkAccess();

        // Get network operator parameters
        mOperator = rcsSettings.getNetworkOperator();

        Context appContext = AndroidFactory.getApplicationContext();
        // Set the connectivity manager
        mCnxManager = (ConnectivityManager) appContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);

        // Instantiates the IMS network interfaces
        mNetworkInterfaces[0] = new MobileNetworkInterface(imsModule, rcsSettings);
        mNetworkInterfaces[1] = new WifiNetworkInterface(imsModule, rcsSettings);

        // Set the mobile network interface by default
        mCurrentNetworkInterface = getMobileNetworkInterface();

        // Load the user profile
        loadUserProfile();

        // Register network state listener
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        appContext.registerReceiver(mNetworkStateListener, intentFilter);

        // Battery management
        appContext.registerReceiver(mBatteryLevelListener, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
    }

    /**
     * Returns the current network interface
     * 
     * @return Current network interface
     */
    public ImsNetworkInterface getCurrentNetworkInterface() {
        return mCurrentNetworkInterface;
    }

    /**
     * Returns the mobile network interface
     * 
     * @return Mobile network interface
     */
    public ImsNetworkInterface getMobileNetworkInterface() {
        return mNetworkInterfaces[0];
    }

    /**
     * Returns the Wi-Fi network interface
     * 
     * @return Wi-Fi network interface
     */
    public ImsNetworkInterface getWifiNetworkInterface() {
        return mNetworkInterfaces[1];
    }

    /**
     * Is connected to Wi-Fi
     * 
     * @return Boolean
     */
    public boolean isConnectedToWifi() {
        return mCurrentNetworkInterface == getWifiNetworkInterface();
    }

    /**
     * Is connected to mobile
     * 
     * @return Boolean
     */
    public boolean isConnectedToMobile() {
        return mCurrentNetworkInterface == getMobileNetworkInterface();
    }

    /**
     * Is disconnected by battery
     * 
     * @return Returns true if disconnected by battery, else returns false
     */
    public boolean isDisconnectedByBattery() {
        return mDisconnectedByBattery;
    }

    /**
     * Load the user profile associated to the network interface
     */
    private void loadUserProfile() {
        ImsModule.IMS_USER_PROFILE = mCurrentNetworkInterface.getUserProfile();
        if (sLogger.isActivated()) {
            sLogger.debug("User profile has been reloaded");
        }
    }

    /**
     * Terminate the connection manager
     * 
     * @throws SipPayloadException
     * @throws SipNetworkException
     */
    public void terminate() throws SipPayloadException, SipNetworkException {
        if (sLogger.isActivated()) {
            sLogger.info("Terminate the IMS connection manager");
        }

        // Unregister battery listener
        try {
            AndroidFactory.getApplicationContext().unregisterReceiver(mBatteryLevelListener);
        } catch (IllegalArgumentException e) {
            // Nothing to do
        }

        // Unregister network state listener
        try {
            AndroidFactory.getApplicationContext().unregisterReceiver(mNetworkStateListener);
        } catch (IllegalArgumentException e) {
            // Nothing to do
        }

        // Stop the IMS connection manager
        stopImsConnection(TerminationReason.TERMINATION_BY_SYSTEM);

        // Unregister from the IMS
        mCurrentNetworkInterface.unregister();

        if (sLogger.isActivated()) {
            sLogger.info("IMS connection manager has been terminated");
        }
    }

    /**
     * Network state listener
     */
    private BroadcastReceiver mNetworkStateListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            mCore.scheduleForBackgroundExecution(new Runnable() {
                @Override
                public void run() {

                    try {
                        connectionEvent(intent);
                    } catch (SipPayloadException e) {
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    } catch (SipNetworkException e) {
                        if (sLogger.isActivated()) {
                            sLogger.debug(e.getMessage());
                        }
                    } catch (CertificateException e) {
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    } catch (RuntimeException e) {
                        /*
                         * Normally we are not allowed to catch runtime exceptions as these are
                         * genuine bugs which should be handled/fixed within the code. However the
                         * cases when we are executing operations on a thread unhandling such
                         * exceptions will eventually lead to exit the system and thus can bring the
                         * whole system down, which is not intended.
                         */
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    }
                }
            });
        }
    };

    /**
     * Connection event
     * 
     * @param intent Intent
     * @throws SipPayloadException
     * @throws CertificateException
     * @throws SipNetworkException
     */
    // @FIXME: This method is doing so many things at this moment and has become too complex thus
    // needs a complete refactor, However at this moment due to other prior tasks the refactoring
    // task has been kept in backlog.
    private void connectionEvent(Intent intent) throws SipPayloadException, CertificateException,
            SipNetworkException {
        try {
            if (mDisconnectedByBattery) {
                return;
            }

            if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                return;
            }

            boolean connectivity = intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            boolean failover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
            if (sLogger.isActivated()) {
                sLogger.debug("Connectivity event change: failover=" + failover + ", connectivity="
                        + !connectivity + ", reason=" + reason);
            }
            NetworkInfo networkInfo = mCnxManager.getActiveNetworkInfo();
            if (networkInfo == null) {
                if (sLogger.isActivated()) {
                    sLogger.debug("Disconnect from IMS: no network (e.g. air plane mode)");
                }
                disconnectFromIms();
                return;
            }
            if (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                String lastUserAccount = LauncherUtils.getLastUserAccount(AndroidFactory
                        .getApplicationContext());
                String currentUserAccount = LauncherUtils.getCurrentUserAccount(AndroidFactory
                        .getApplicationContext());
                if (lastUserAccount != null) {
                    if ((currentUserAccount == null)
                            || !currentUserAccount.equalsIgnoreCase(lastUserAccount)) {
                        mImsModule.getCoreListener().handleSimHasChanged();
                        return;
                    }
                }
            }
            String localIpAddr = null;
            if (networkInfo.getType() != mCurrentNetworkInterface.getType()) {
                if (sLogger.isActivated()) {
                    sLogger.info("Data connection state: NETWORK ACCESS CHANGED");
                }
                if (sLogger.isActivated()) {
                    sLogger.debug("Disconnect from IMS: network access has changed");
                }
                disconnectFromIms();

                if (networkInfo.getType() == ConnectivityManager.TYPE_MOBILE) {
                    if (sLogger.isActivated()) {
                        sLogger.debug("Change the network interface to mobile");
                    }
                    mCurrentNetworkInterface = getMobileNetworkInterface();
                } else if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
                    if (sLogger.isActivated()) {
                        sLogger.debug("Change the network interface to Wi-Fi");
                    }
                    mCurrentNetworkInterface = getWifiNetworkInterface();
                }

                loadUserProfile();

                try {
                    mDnsResolvedFields = mCurrentNetworkInterface.getDnsResolvedFields();
                } catch (UnknownHostException e) {
                    /*
                     * Even if we are not able to resolve host name , we should still continue to
                     * get local IP as this is a very obvious case, Specially for networks
                     * supporting IPV4 protocol.
                     */
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                }
                localIpAddr = NetworkFactory.getFactory().getLocalIpAddress(mDnsResolvedFields,
                        networkInfo.getType());
            } else {
                /* Check if the IP address has changed */
                try {
                    if (mDnsResolvedFields == null) {
                        mDnsResolvedFields = mCurrentNetworkInterface.getDnsResolvedFields();
                    }
                } catch (UnknownHostException e) {
                    /*
                     * Even if we are not able to resolve host name , we should still continue to
                     * get local IP as this is a very obvious case, Specially for networks
                     * supporting IPV4 protocol.
                     */
                    if (sLogger.isActivated()) {
                        sLogger.debug(e.getMessage());
                    }
                }
                localIpAddr = NetworkFactory.getFactory().getLocalIpAddress(mDnsResolvedFields,
                        networkInfo.getType());
                String lastIpAddr = mCurrentNetworkInterface.getNetworkAccess().getIpAddress();
                if (!localIpAddr.equals(lastIpAddr)) {
                    // Changed by Deutsche Telekom
                    if (lastIpAddr != null) {
                        if (sLogger.isActivated()) {
                            sLogger.debug("Disconnect from IMS: IP address has changed");
                        }
                        disconnectFromIms();
                    } else {
                        if (sLogger.isActivated()) {
                            sLogger.debug("IP address available (again)");
                        }
                    }
                } else {
                    // Changed by Deutsche Telekom
                    if (sLogger.isActivated()) {
                        sLogger.debug("Neither interface nor IP address has changed; nothing to do.");
                    }
                    return;
                }
            }
            if (networkInfo.isConnected()) {
                String remoteAddress;
                if (mDnsResolvedFields != null) {
                    remoteAddress = mDnsResolvedFields.mIpAddress;
                } else {
                    remoteAddress = new String("unresolved");
                }

                if (sLogger.isActivated()) {
                    sLogger.info("Data connection state: CONNECTED to " + networkInfo.getTypeName()
                            + " with local IP " + localIpAddr + " valid for " + remoteAddress);
                }

                if (!NetworkAccessType.ANY.equals(mNetwork)
                        && (mNetwork.toInt() != networkInfo.getType())) {
                    if (sLogger.isActivated()) {
                        sLogger.warn("Network access " + networkInfo.getTypeName()
                                + " is not authorized");
                    }
                    return;
                }

                TelephonyManager tm = (TelephonyManager) AndroidFactory.getApplicationContext()
                        .getSystemService(Context.TELEPHONY_SERVICE);
                String currentOpe = tm.getSimOperatorName();
                if ((mOperator.length() > 0) && !currentOpe.equalsIgnoreCase(mOperator)) {
                    if (sLogger.isActivated()) {
                        sLogger.warn("Operator not authorized");
                    }
                    return;
                }

                if (!mCurrentNetworkInterface.isInterfaceConfigured()) {
                    if (sLogger.isActivated()) {
                        sLogger.warn("IMS network interface not well configured");
                    }
                    return;
                }

                if (sLogger.isActivated()) {
                    sLogger.debug("Connect to IMS");
                }
                connectToIms(localIpAddr);
            }
        } catch (SocketException e) {
            if (sLogger.isActivated()) {
                sLogger.debug(e.getMessage());
            }
            disconnectFromIms();
        } catch (IOException e) {
            if (sLogger.isActivated()) {
                sLogger.debug(e.getMessage());
            }
            disconnectFromIms();
        }
    }

    /**
     * Connect to IMS network interface
     * 
     * @param ipAddr IP address
     * @throws CertificateException
     * @throws IOException
     */
    private void connectToIms(String ipAddr) throws CertificateException, IOException {
        // Connected to the network access
        mCurrentNetworkInterface.getNetworkAccess().connect(ipAddr);

        // Start the IMS connection
        startImsConnection();
    }

    /**
     * Disconnect from IMS network interface
     * 
     * @throws SipPayloadException
     * @throws SipNetworkException
     */
    private void disconnectFromIms() throws SipPayloadException, SipNetworkException {
        // Stop the IMS connection
        stopImsConnection(TerminationReason.TERMINATION_BY_CONNECTION_LOST);

        // Registration terminated
        mCurrentNetworkInterface.registrationTerminated();

        // Disconnect from the network access
        mCurrentNetworkInterface.getNetworkAccess().disconnect();
    }

    /**
     * Start the IMS connection
     */
    private synchronized void startImsConnection() {
        if (mImsPollingThreadId >= 0) {
            return;
        }
        if (sLogger.isActivated()) {
            sLogger.info("Start the IMS connection manager");
        }
        mImsPollingThread = new Thread(this);
        mImsPollingThreadId = mImsPollingThread.getId();
        mImsPollingThread.start();
    }

    /**
     * Stop the IMS connection
     * 
     * @throws SipPayloadException
     * @throws SipNetworkException
     */
    private synchronized void stopImsConnection(TerminationReason reasonCode)
            throws SipPayloadException, SipNetworkException {
        if (mImsPollingThreadId == -1) {
            return;
        }
        if (sLogger.isActivated()) {
            sLogger.info("Stop the IMS connection manager");
        }
        mImsPollingThreadId = -1;
        mImsPollingThread.interrupt();
        mImsPollingThread = null;

        if (mImsServicesStarted) {
            mImsModule.stopImsServices(reasonCode);
            mImsServicesStarted = false;
        }
    }

    /**
     * Background processing
     */
    public void run() {
        if (sLogger.isActivated()) {
            sLogger.debug("Start polling of the IMS connection");
        }

        long servicePollingPeriod = mRcsSettings.getImsServicePollingPeriod();
        long regBaseTime = mRcsSettings.getRegisterRetryBaseTime();
        long regMaxTime = mRcsSettings.getRegisterRetryMaxTime();
        Random random = new Random();
        int nbFailures = 0;

        while (mImsPollingThreadId == Thread.currentThread().getId()) {
            if (sLogger.isActivated()) {
                sLogger.debug("Polling: check IMS connection");
            }

            // Connection management
            try {
                // Test IMS registration
                if (!mCurrentNetworkInterface.isRegistered()) {
                    if (sLogger.isActivated()) {
                        sLogger.debug("Not yet registered to IMS: try registration");
                    }

                    // Try to register to IMS
                    mCurrentNetworkInterface.register(mDnsResolvedFields);

                    // InterruptedException thrown by stopImsConnection() may be caught by one
                    // of the methods used in currentNetworkInterface.register() above
                    if (mImsPollingThreadId != Thread.currentThread().getId()) {
                        if (sLogger.isActivated()) {
                            sLogger.debug("IMS connection polling thread race condition");
                        }
                        break;
                    } else {
                        if (sLogger.isActivated()) {
                            sLogger.debug("Registered to the IMS with success: start IMS services");
                        }
                        if (mImsModule.isInitializationFinished() && !mImsServicesStarted) {
                            mImsModule.startImsServices();
                            mImsServicesStarted = true;
                        }

                        // Reset number of failures
                        nbFailures = 0;
                    }

                } else {
                    if (mImsModule.isInitializationFinished()) {
                        if (!mImsServicesStarted) {
                            if (sLogger.isActivated()) {
                                sLogger.debug("Already registered to IMS: start IMS services");
                            }
                            mImsModule.startImsServices();
                            mImsServicesStarted = true;
                        } else {
                            if (sLogger.isActivated()) {
                                sLogger.debug("Already registered to IMS: check IMS services");
                            }
                            mImsModule.checkImsServices();
                        }
                    } else {
                        if (sLogger.isActivated()) {
                            sLogger.debug("Already registered to IMS: IMS services not yet started");
                        }
                    }
                }
            } catch (SipPayloadException e) {
                sLogger.error("Can't register to the IMS!", e);
                mCurrentNetworkInterface.getSipManager().closeStack();
                /* Increment number of failures */
                nbFailures++;
                /* Force to perform a new DNS lookup */
                mDnsResolvedFields = null;
            } catch (SipNetworkException e) {
                if (sLogger.isActivated()) {
                    sLogger.debug(e.getMessage());
                }
                mCurrentNetworkInterface.getSipManager().closeStack();
                /* Increment number of failures */
                nbFailures++;
                /* Force to perform a new DNS lookup */
                mDnsResolvedFields = null;
            }

            // InterruptedException thrown by stopImsConnection() may be caught by one
            // of the methods used in currentNetworkInterface.register() above
            if (mImsPollingThreadId != Thread.currentThread().getId()) {
                sLogger.debug("IMS connection polling thread race condition");
                break;
            }

            // Make a pause before the next polling
            try {
                if (!mCurrentNetworkInterface.isRegistered()) {
                    final long retryAfterHeaderDuration = mCurrentNetworkInterface
                            .getRetryAfterHeaderDuration();
                    if (retryAfterHeaderDuration > 0) {
                        Thread.sleep(retryAfterHeaderDuration);
                    } else {
                        // Pause before the next register attempt
                        double w = Math.min(regMaxTime, (regBaseTime * Math.pow(2, nbFailures)));
                        double coeff = (random.nextInt(51) + 50) / 100.0; // Coeff between 50% and
                                                                          // 100%
                        long retryPeriod = (long) (coeff * w);
                        if (sLogger.isActivated()) {
                            sLogger.debug(new StringBuilder("Wait ").append(retryPeriod)
                                    .append("ms before retry registration (failures=")
                                    .append(nbFailures).append(", coeff=").append(coeff)
                                    .append(')').toString());
                        }
                        Thread.sleep(retryPeriod);
                    }
                } else if (!mImsServicesStarted) {
                    long retryPeriod = 5000;
                    if (sLogger.isActivated()) {
                        sLogger.debug(new StringBuilder("Wait ").append(retryPeriod)
                                .append("ms before retry to start services").toString());
                    }
                    Thread.sleep(retryPeriod);
                } else {
                    // Pause before the next service check
                    Thread.sleep(servicePollingPeriod);
                }
            } catch (InterruptedException e) {
                break;
            }
        }

        if (sLogger.isActivated()) {
            sLogger.debug("IMS connection polling is terminated");
        }
    }

    /**
     * Battery level listener
     */
    private BroadcastReceiver mBatteryLevelListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, final Intent intent) {
            mCore.scheduleForBackgroundExecution(new Runnable() {
                @Override
                public void run() {
                    try {
                        MinimumBatteryLevel batteryLimit = mRcsSettings.getMinBatteryLevel();
                        if (MinimumBatteryLevel.NEVER_STOP == batteryLimit) {
                            mDisconnectedByBattery = false;
                            return;

                        }
                        int batteryLevel = intent.getIntExtra("level", 0);
                        int batteryPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1);
                        if (sLogger.isActivated()) {
                            sLogger.info(new StringBuilder("Battery level: ").append(batteryLevel)
                                    .append("% plugged: ").append(batteryPlugged).toString());
                        }
                        if (batteryLevel <= batteryLimit.toInt() && batteryPlugged == 0) {
                            if (!mDisconnectedByBattery) {
                                mDisconnectedByBattery = true;

                                // Disconnect
                                disconnectFromIms();
                            }
                        } else {
                            if (mDisconnectedByBattery) {
                                mDisconnectedByBattery = false;

                                // Reconnect with a connection event
                                connectionEvent(new Intent(ConnectivityManager.CONNECTIVITY_ACTION));
                            }
                        }
                    } catch (SipPayloadException e) {
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    } catch (SipNetworkException e) {
                        if (sLogger.isActivated()) {
                            sLogger.debug(e.getMessage());
                        }
                    } catch (CertificateException e) {
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    } catch (RuntimeException e) {
                        /*
                         * Normally we are not allowed to catch runtime exceptions as these are
                         * genuine bugs which should be handled/fixed within the code. However the
                         * cases when we are executing operations on a thread unhandling such
                         * exceptions will eventually lead to exit the system and thus can bring the
                         * whole system down, which is not intended.
                         */
                        sLogger.error("Unable to handle connection event for intent action : "
                                .concat(intent.getAction()), e);
                    }
                }
            });
        }
    };

    /**
     * @return true is device is in roaming
     */
    public boolean isInRoaming() {
        if (mCnxManager != null && mCnxManager.getActiveNetworkInfo() != null) {
            return mCnxManager.getActiveNetworkInfo().isRoaming();
        }
        return false;
    }
}
