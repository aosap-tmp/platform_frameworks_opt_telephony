/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;


import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkCapabilities;
import android.net.LinkProperties;
import android.net.ProxyProperties;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.telephony.Rlog;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@hide}
 *
 * DataConnectionBase StateMachine.
 *
 * This is an abstract base class for representing a single data connection.
 * Instances of this class such represent a connection via the cellular network.
 * There may be multiple data connections and all of them are managed by the
 * <code>DataConnectionTracker</code>.
 *
 * Instances are asynchronous state machines and have two primary entry points
 * <code>connect()</code> and <code>disconnect</code>. The message a parameter will be returned
 * hen the operation completes. The <code>msg.obj</code> will contain an AsyncResult
 * object and <code>AsyncResult.userObj</code> is the original <code>msg.obj</code>. if successful
 * with the <code>AsyncResult.result == null</code> and <code>AsyncResult.exception == null</code>.
 * If an error <code>AsyncResult.result = FailCause</code> and
 * <code>AsyncResult.exception = new Exception()</code>.
 *
 * The other public methods are provided for debugging.
 */
public abstract class DataConnectionBase extends StateMachine {
    protected static final String LOG_TAG = "DCBase";
    protected static final boolean DBG = true;
    protected static final boolean VDBG = true;
    protected static final boolean DBG_FAILURE = SystemProperties.getInt("ro.debuggable", 0) == 1;

    protected static AtomicInteger mCount = new AtomicInteger(0);
    protected AsyncChannel mAc;

    protected List<ApnContext> mApnList = null;
    PendingIntent mReconnectIntent = null;

    private DataConnectionTrackerBase mDataConnectionTracker = null;

    /**
     * Used internally for saving connecting parameters.
     */
    protected static class ConnectionParams {
        ConnectionParams(ApnContext apnContext, Message onCompletedMsg) {
            mApnContext = apnContext;
            mOnCompletedMsg = onCompletedMsg;
        }

        int mTheTag;
        ApnContext mApnContext;
        Message mOnCompletedMsg;
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    protected static class DisconnectParams {
        DisconnectParams(ApnContext apnContext, String reason, Message onCompletedMsg) {
            mApnContext = apnContext;
            mReason = reason;
            mOnCompletedMsg = onCompletedMsg;
        }

        int mTheTag;
        ApnContext mApnContext;
        String mReason;
        Message mOnCompletedMsg;
    }

    /**
     * Returned as the reason for a connection failure as defined
     * by RIL_DataCallFailCause in ril.h and some local errors.
     */
    public enum FailCause {
        NONE(0),

        // This series of errors as specified by the standards
        // specified in ril.h
        OPERATOR_BARRED(0x08),
        INSUFFICIENT_RESOURCES(0x1A),
        MISSING_UNKNOWN_APN(0x1B),
        UNKNOWN_PDP_ADDRESS_TYPE(0x1C),
        USER_AUTHENTICATION(0x1D),
        ACTIVATION_REJECT_GGSN(0x1E),
        ACTIVATION_REJECT_UNSPECIFIED(0x1F),
        SERVICE_OPTION_NOT_SUPPORTED(0x20),
        SERVICE_OPTION_NOT_SUBSCRIBED(0x21),
        SERVICE_OPTION_OUT_OF_ORDER(0x22),
        NSAPI_IN_USE(0x23),
        ONLY_IPV4_ALLOWED(0x32),
        ONLY_IPV6_ALLOWED(0x33),
        ONLY_SINGLE_BEARER_ALLOWED(0x34),
        PROTOCOL_ERRORS(0x6F),

        // Local errors generated by Vendor RIL
        // specified in ril.h
        REGISTRATION_FAIL(-1),
        GPRS_REGISTRATION_FAIL(-2),
        SIGNAL_LOST(-3),
        PREF_RADIO_TECH_CHANGED(-4),
        RADIO_POWER_OFF(-5),
        TETHERED_CALL_ACTIVE(-6),
        ERROR_UNSPECIFIED(0xFFFF),

        // Errors generated by the Framework
        // specified here
        UNKNOWN(0x10000),
        RADIO_NOT_AVAILABLE(0x10001),
        UNACCEPTABLE_NETWORK_PARAMETER(0x10002),
        CONNECTION_TO_DATACONNECTIONAC_BROKEN(0x10003);

        private final int mErrorCode;
        private static final HashMap<Integer, FailCause> sErrorCodeToFailCauseMap;
        static {
            sErrorCodeToFailCauseMap = new HashMap<Integer, FailCause>();
            for (FailCause fc : values()) {
                sErrorCodeToFailCauseMap.put(fc.getErrorCode(), fc);
            }
        }

        FailCause(int errorCode) {
            mErrorCode = errorCode;
        }

        public int getErrorCode() {
            return mErrorCode;
        }

        public boolean isPermanentFail() {
            return (this == OPERATOR_BARRED) || (this == MISSING_UNKNOWN_APN) ||
                   (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                   (this == ACTIVATION_REJECT_GGSN) || (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                   (this == SERVICE_OPTION_NOT_SUBSCRIBED) || (this == NSAPI_IN_USE) ||
                   (this == ONLY_IPV4_ALLOWED) || (this == ONLY_IPV6_ALLOWED) ||
                   (this == PROTOCOL_ERRORS) || (this == SIGNAL_LOST) ||
                   (this == RADIO_POWER_OFF) || (this == TETHERED_CALL_ACTIVE);
        }

        public boolean isEventLoggable() {
            return (this == OPERATOR_BARRED) || (this == INSUFFICIENT_RESOURCES) ||
                    (this == UNKNOWN_PDP_ADDRESS_TYPE) || (this == USER_AUTHENTICATION) ||
                    (this == ACTIVATION_REJECT_GGSN) || (this == ACTIVATION_REJECT_UNSPECIFIED) ||
                    (this == SERVICE_OPTION_NOT_SUBSCRIBED) ||
                    (this == SERVICE_OPTION_NOT_SUPPORTED) ||
                    (this == SERVICE_OPTION_OUT_OF_ORDER) || (this == NSAPI_IN_USE) ||
                    (this == ONLY_IPV4_ALLOWED) || (this == ONLY_IPV6_ALLOWED) ||
                    (this == PROTOCOL_ERRORS) || (this == SIGNAL_LOST) ||
                    (this == RADIO_POWER_OFF) || (this == TETHERED_CALL_ACTIVE) ||
                    (this == UNACCEPTABLE_NETWORK_PARAMETER);
        }

        public static FailCause fromInt(int errorCode) {
            FailCause fc = sErrorCodeToFailCauseMap.get(errorCode);
            if (fc == null) {
                fc = UNKNOWN;
            }
            return fc;
        }
    }

    /**
     * Static logging for DataConnection
     */
    private static void sDcLog(String s) {
        Rlog.d(LOG_TAG, "[DC] " + s);
    }

    // Debugging INTENT with are two targets, com.android.internal.telephony.DC which
    // is for all DataConnections and com.android.internal.telephony.<NameDC-X> where
    // NameDc-X is a particular DC such as GsmDC-1.
    protected static final String INTENT_BASE = DataConnectionBase.class.getPackage().getName();
    protected static String sActionFailBringUp;
    protected String mActionFailBringUp;

    // The FailBringUp class
    public static class FailBringUp {
        protected static final String ACTION_FAIL_BRINGUP = "action_fail_bringup";

        // counter with its --ei option name and default value
        public static final String COUNTER = "counter";
        public static final int DEFAULT_COUNTER = 1;
        public int counter;

        // failCause with its --ei option name and default value
        public static final String FAIL_CAUSE = "fail_cause";
        public static final FailCause DEFAULT_FAIL_CAUSE = FailCause.ERROR_UNSPECIFIED;
        public FailCause failCause;

        // suggestedRetryTime with its --ei option name and default value
        public static final String SUGGESTED_RETRY_TIME = "suggested_retry_time";
        public static final int DEFAULT_SUGGESTED_RETRY_TIME = -1;
        public int suggestedRetryTime;

        // Get the Extra Intent parameters
        public void getEiParameters(Intent intent, String s) {
            if (DBG) sDcLog(s + ".getEiParameters: action=" + intent.getAction());
            counter = intent.getIntExtra(FailBringUp.COUNTER,
                    FailBringUp.DEFAULT_COUNTER);
            failCause = FailCause.fromInt(
                    intent.getIntExtra(FailBringUp.FAIL_CAUSE,
                            FailBringUp.DEFAULT_FAIL_CAUSE.getErrorCode()));
            suggestedRetryTime =
                    intent.getIntExtra(FailBringUp.SUGGESTED_RETRY_TIME,
                            FailBringUp.DEFAULT_SUGGESTED_RETRY_TIME);
            if (DBG) {
                sDcLog(s + ".getEiParameters: " + this);
            }
        }

        @Override
        public String toString() {
            return "{counter=" + counter +
                    " failCause=" + failCause +
                    " suggestedRetryTime=" + suggestedRetryTime + "}";

        }
    }

    // This is the static FailBringUp used to cause all DC's to "fail" a bringUp.
    // Here is an example that sets counter to 2 and cause to -3 for all instances:
    //
    // adb shell am broadcast \
    //  -a com.android.internal.telephony.DC.action_fail_bringup \
    //  --ei counter 2 --ei fail_cause -3
    //
    // Also you can add a suggested retry time if desired:
    //  --ei suggested_retry_time 5000
    protected static FailBringUp sFailBringUp = new FailBringUp();

    // The static intent receiver one for all instances.
    protected static BroadcastReceiver sIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (DBG) sDcLog("sIntentReceiver.onReceive: action=" + action);
            if (action.equals(sActionFailBringUp)) {
                sFailBringUp.getEiParameters(intent, "sFailBringUp");
            } else {
                if (DBG) sDcLog("onReceive: unknown action=" + action);
            }
        }
    };

    // This is the per instance FailBringUP used to cause one DC to "fail" a bringUp.
    // Here is an example that sets counter to 2 and cause to -3 for GsmDC-2:
    //
    // adb shell am broadcast \
    //  -a com.android.internal.telephony.GsmDC-2.action_fail_bringup \
    //  --ei counter 2 --ei fail_cause -3
    //
    // Also you can add a suggested retry time if desired:
    //  --ei suggested_retry_time 5000
    protected FailBringUp mFailBringUp = new FailBringUp();

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver ()
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            if (DBG) log("mIntentReceiver.onReceive: action=" + action);
            if (DBG_FAILURE && action.equals(mActionFailBringUp)) {
                mFailBringUp.getEiParameters(intent, "mFailBringUp");
            } else {
                if (DBG) log("onReceive: unknown action=" + action);
            }
        }
    };

    /**
     * Do the on connect or fake it if an error
     */
    protected void doOnConnect(ConnectionParams cp) {
        // Check if we should fake an error.
        if (sFailBringUp.counter  > 0) {
            DataCallState response = new DataCallState();
            response.version = mPhone.mCi.getRilVersion();
            response.status = sFailBringUp.failCause.getErrorCode();
            response.cid = 0;
            response.active = 0;
            response.type = "";
            response.ifname = "";
            response.addresses = new String[0];
            response.dnses = new String[0];
            response.gateways = new String[0];
            response.suggestedRetryTime = sFailBringUp.suggestedRetryTime;

            Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
            AsyncResult.forMessage(msg, response, null);
            sendMessage(msg);
            if (DBG) {
                log("doOnConnect: sFailBringUp.counter=" + sFailBringUp.counter +
                        " send error response=" + response);
            }
            sFailBringUp.counter -= 1;
            return;
        }
        if (mFailBringUp.counter > 0) {
            DataCallState response = new DataCallState();
            response.version = mPhone.mCi.getRilVersion();
            response.status = mFailBringUp.failCause.getErrorCode();
            response.cid = 0;
            response.active = 0;
            response.type = "";
            response.ifname = "";
            response.addresses = new String[0];
            response.dnses = new String[0];
            response.gateways = new String[0];
            response.suggestedRetryTime = mFailBringUp.suggestedRetryTime;

            Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
            AsyncResult.forMessage(msg, response, null);
            sendMessage(msg);
            if (DBG) {
                log("doOnConnect: mFailBringUp.counter=" + mFailBringUp.counter +
                        " send error response=" + response);
            }
            mFailBringUp.counter -= 1;
            return;
        }

        // Else do the normal onConnection
        onConnect(cp);
    }

    public static class CallSetupException extends Exception {
        private int mRetryOverride = -1;

        CallSetupException (int retryOverride) {
            mRetryOverride = retryOverride;
        }

        public int getRetryOverride() {
            return mRetryOverride;
        }
    }

    // ***** Event codes for driving the state machine
    protected static final int BASE = Protocol.BASE_DATA_CONNECTION;
    protected static final int EVENT_CONNECT = BASE + 0;
    protected static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    protected static final int EVENT_GET_LAST_FAIL_DONE = BASE + 2;
    protected static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    protected static final int EVENT_DISCONNECT = BASE + 4;
    protected static final int EVENT_RIL_CONNECTED = BASE + 5;
    protected static final int EVENT_DISCONNECT_ALL = BASE + 6;

    private static final int CMD_TO_STRING_COUNT = EVENT_DISCONNECT_ALL - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_CONNECT - BASE] = "EVENT_CONNECT";
        sCmdToString[EVENT_SETUP_DATA_CONNECTION_DONE - BASE] =
                "EVENT_SETUP_DATA_CONNECTION_DONE";
        sCmdToString[EVENT_GET_LAST_FAIL_DONE - BASE] = "EVENT_GET_LAST_FAIL_DONE";
        sCmdToString[EVENT_DEACTIVATE_DONE - BASE] = "EVENT_DEACTIVATE_DONE";
        sCmdToString[EVENT_DISCONNECT - BASE] = "EVENT_DISCONNECT";
        sCmdToString[EVENT_RIL_CONNECTED - BASE] = "EVENT_RIL_CONNECTED";
        sCmdToString[EVENT_DISCONNECT_ALL - BASE] = "EVENT_DISCONNECT_ALL";
    }
    protected static String cmdToString(int cmd) {
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            return sCmdToString[cmd];
        } else {
            return null;
        }
    }

    //***** Tag IDs for EventLog
    protected static final int EVENT_LOG_BAD_DNS_ADDRESS = 50100;

    //***** Member Variables
    protected ApnSetting mApn;
    protected int mTag;
    protected PhoneBase mPhone;
    protected int mRilVersion = -1;
    protected int mCid;
    protected LinkProperties mLinkProperties = new LinkProperties();
    protected LinkCapabilities mCapabilities = new LinkCapabilities();
    protected long mCreateTime;
    protected long mLastFailTime;
    protected FailCause mLastFailCause;
    protected int mRetryOverride = -1;
    protected static final String NULL_IP = "0.0.0.0";
    Object mUserData;

    //***** Abstract methods
    @Override
    public abstract String toString();

    // A Non recursive toString
    public String toStringSimple() {
        return toString();
    }

    protected abstract void onConnect(ConnectionParams cp);

    protected abstract boolean isDnsOk(String[] domainNameServers);

   //***** Constructor
    protected DataConnectionBase(PhoneBase phone, String name, int id, RetryManager rm,
            DataConnectionTrackerBase dct) {
        super(name);
        setLogRecSize(300);
        setLogOnlyTransitions(true);
        if (DBG) log("DataConnectionBase constructor E");
        mPhone = phone;
        mDataConnectionTracker = dct;
        mId = id;
        mRetryMgr = rm;
        mCid = -1;

        if (DBG_FAILURE) {
            IntentFilter filter;

            synchronized (DataConnectionBase.class) {
                // Register the static Intent receiver once
                if (sActionFailBringUp == null) {
                    sActionFailBringUp = INTENT_BASE + ".DC." +
                            FailBringUp.ACTION_FAIL_BRINGUP;

                    filter = new IntentFilter();
                    filter.addAction(sActionFailBringUp);
                    phone.getContext().registerReceiver(sIntentReceiver, filter, null, phone);
                    log("DataConnectionBase: register sActionFailBringUp=" + sActionFailBringUp);
                }
            }

            // Register the per instance Intent receiver
            mActionFailBringUp = INTENT_BASE + "." + getName() + "." +
                    FailBringUp.ACTION_FAIL_BRINGUP;
            filter = new IntentFilter();
            filter.addAction(mActionFailBringUp);
            phone.getContext().registerReceiver(mIntentReceiver, filter, null, phone);
            log("DataConnectionBase: register mActionFailBringUp=" + mActionFailBringUp);
        }

        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mActiveState, mDefaultState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);

        mApnList = new ArrayList<ApnContext>();
        if (DBG) log("DataConnectionBase constructor X");
    }

    /**
     * Shut down this instance and its state machine.
     */
    private void shutDown() {
        if (DBG) log("shutDown");

        if (mAc != null) {
            mAc.disconnected();
            mAc = null;
        }
        mApnList = null;
        mReconnectIntent = null;
        mDataConnectionTracker = null;
        mApn = null;
        mPhone = null;
        mLinkProperties = null;
        mCapabilities = null;
        mLastFailCause = null;
        mUserData = null;
    }

    /**
     * TearDown the data connection.
     *
     * @param o will be returned in AsyncResult.userObj
     *          and is either a DisconnectParams or ConnectionParams.
     */
    private void tearDownData(Object o) {
        int discReason = RILConstants.DEACTIVATE_REASON_NONE;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams)o;
            Message m = dp.mOnCompletedMsg;
            if (TextUtils.equals(dp.mReason, Phone.REASON_RADIO_TURNED_OFF)) {
                discReason = RILConstants.DEACTIVATE_REASON_RADIO_OFF;
            } else if (TextUtils.equals(dp.mReason, Phone.REASON_PDP_RESET)) {
                discReason = RILConstants.DEACTIVATE_REASON_PDP_RESET;
            }
        }
        if (mPhone.mCi.getRadioState().isOn()) {
            if (DBG) log("tearDownData radio is on, call deactivateDataCall");
            mPhone.mCi.deactivateDataCall(mCid, discReason, obtainMessage(EVENT_DEACTIVATE_DONE, o));
        } else {
            if (DBG) log("tearDownData radio is off sendMessage EVENT_DEACTIVATE_DONE immediately");
            AsyncResult ar = new AsyncResult(o, null, null);
            sendMessage(obtainMessage(EVENT_DEACTIVATE_DONE, ar));
        }
    }

    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause
     */
    private void notifyConnectCompleted(ConnectionParams cp, FailCause cause) {
        Message connectionCompletedMsg = cp.mOnCompletedMsg;
        if (connectionCompletedMsg == null) {
            return;
        }

        long timeStamp = System.currentTimeMillis();
        connectionCompletedMsg.arg1 = mCid;

        if (cause == FailCause.NONE) {
            mCreateTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg);
        } else {
            mLastFailCause = cause;
            mLastFailTime = timeStamp;
            AsyncResult.forMessage(connectionCompletedMsg, cause,
                                   new CallSetupException(mRetryOverride));
        }
        if (DBG) log("notifyConnectionCompleted at " + timeStamp + " cause=" + cause);

        connectionCompletedMsg.sendToTarget();
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    private void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp.mOnCompletedMsg != null) {
            // Get ApnContext, but only valid on GSM devices this is a string on CDMA devices.
            Message msg = dp.mOnCompletedMsg;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext)msg.obj;
            }
            reason = dp.mReason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            for (ApnContext a : mApnList) {
                if (a == alreadySent) continue;
                if (reason != null) a.setReason(reason);
                Message msg = mDataConnectionTracker.obtainMessage(
                        DctConstants.EVENT_DISCONNECT_DONE, a);
                AsyncResult.forMessage(msg);
                msg.sendToTarget();
            }
        }

        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    protected int getRilRadioTechnology() {
        int rilRadioTechnology;
        if (mApn.bearer > 0) {
            rilRadioTechnology = mApn.bearer + 2;
        } else {
            rilRadioTechnology = mPhone.getServiceState().getRilDataRadioTechnology() + 2;
        }
        return rilRadioTechnology;
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnectionBase because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    private int mId;

    /**
     * Get the DataConnection ID
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * The retry manager is currently owned by the DataConnectionTracker but is stored
     * in the DataConnection because there is one per connection. These methods
     * should only be used by the DataConnectionTracker although someday the retrying
     * maybe managed by the DataConnection itself and these methods could disappear.
     */
    private RetryManager mRetryMgr;

    /**
     * @return retry manager retryCount
     */
    public int getRetryCount() {
        return mRetryMgr.getRetryCount();
    }

    /**
     * set retry manager retryCount
     */
    public void setRetryCount(int retryCount) {
        if (DBG) log("setRetryCount: " + retryCount);
        mRetryMgr.setRetryCount(retryCount);
    }

    /**
     * @return retry manager retryTimer
     */
    public int getRetryTimer() {
        return mRetryMgr.getRetryTimer();
    }

    /**
     * increaseRetryCount of retry manager
     */
    public void increaseRetryCount() {
        mRetryMgr.increaseRetryCount();
    }

    /**
     * @return retry manager isRetryNeeded
     */
    public boolean isRetryNeeded() {
        return mRetryMgr.isRetryNeeded();
    }

    /**
     * resetRetryCount of retry manager
     */
    public void resetRetryCount() {
        mRetryMgr.resetRetryCount();
    }

    /**
     * set retryForeverUsingLasttimeout of retry manager
     */
    public void retryForeverUsingLastTimeout() {
        mRetryMgr.retryForeverUsingLastTimeout();
    }

    /**
     * @return retry manager isRetryForever
     */
    public boolean isRetryForever() {
        return mRetryMgr.isRetryForever();
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(int maxRetryCount, int retryTime, int randomizationTime) {
        return mRetryMgr.configure(maxRetryCount, retryTime, randomizationTime);
    }

    /**
     * @return whether the retry config is set successfully or not
     */
    public boolean configureRetry(String configStr) {
        return mRetryMgr.configure(configStr);
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    protected void clearSettings() {
        if (DBG) log("clearSettings");

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = FailCause.NONE;
        mRetryOverride = -1;
        mCid = -1;

        mLinkProperties = new LinkProperties();
        mApnList.clear();
        mApn = null;
    }

    /**
     * Process setup completion.
     *
     * @param ar is the result
     * @return SetupResult.
     */
    private DataCallState.SetupResult onSetupConnectionCompleted(AsyncResult ar) {
        DataCallState response = (DataCallState) ar.result;
        ConnectionParams cp = (ConnectionParams) ar.userObj;
        DataCallState.SetupResult result;

        if (ar.exception != null) {
            if (DBG) {
                log("onSetupConnectionCompleted failed, ar.exception=" + ar.exception +
                    " response=" + response);
            }

            if (ar.exception instanceof CommandException
                    && ((CommandException) (ar.exception)).getCommandError()
                    == CommandException.Error.RADIO_NOT_AVAILABLE) {
                result = DataCallState.SetupResult.ERR_BadCommand;
                result.mFailCause = FailCause.RADIO_NOT_AVAILABLE;
            } else if ((response == null) || (response.version < 4)) {
                result = DataCallState.SetupResult.ERR_GetLastErrorFromRil;
            } else {
                result = DataCallState.SetupResult.ERR_RilError;
                result.mFailCause = FailCause.fromInt(response.status);
            }
        } else if (cp.mTheTag != mTag) {
            if (DBG) {
                log("BUG: onSetupConnectionCompleted is stale cp.tag=" + cp.mTheTag + ", mtag=" + mTag);
            }
            result = DataCallState.SetupResult.ERR_Stale;
        } else if (response.status != 0) {
            result = DataCallState.SetupResult.ERR_RilError;
            result.mFailCause = FailCause.fromInt(response.status);
        } else {
            if (DBG) log("onSetupConnectionCompleted received DataCallState: " + response);
            mCid = response.cid;
            result = updateLinkProperty(response).setupResult;
        }

        return result;
    }

    private int getSuggestedRetryTime(AsyncResult ar) {
        int retry = -1;
        if (ar.exception == null) {
            DataCallState response = (DataCallState) ar.result;
            retry =  response.suggestedRetryTime;
        }
        return retry;
    }

    private DataCallState.SetupResult setLinkProperties(DataCallState response,
            LinkProperties lp) {
        // Check if system property dns usable
        boolean okToUseSystemPropertyDns = false;
        String propertyPrefix = "net." + response.ifname + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        okToUseSystemPropertyDns = isDnsOk(dnsServers);

        // set link properties based on data call response
        return response.setLinkProperties(lp, okToUseSystemPropertyDns);
    }

    public static class UpdateLinkPropertyResult {
        public DataCallState.SetupResult setupResult = DataCallState.SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    private UpdateLinkPropertyResult updateLinkProperty(DataCallState newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        DataCallState.SetupResult setupResult;
        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != DataCallState.SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallState.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            log("updateLinkProperty old LP=" + result.oldLp);
            log("updateLinkProperty new LP=" + result.newLp);
        }
        mLinkProperties = result.newLp;

        return result;
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            mPhone.mCi.registerForRilConnected(getHandler(), EVENT_RIL_CONNECTED, null);
        }
        @Override
        public void exit() {
            mPhone.mCi.unregisterForRilConnected(getHandler());
            shutDown();
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED;
            AsyncResult ar;

            if (VDBG) {
                log("DcDefault msg=0x" + Integer.toHexString(msg.what)
                        + " RefCount=" + mApnList.size());
            }
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECTED");
                    quit();
                    break;
                }
                case DataConnectionAc.REQ_IS_INACTIVE: {
                    boolean val = getCurrentState() == mInactiveState;
                    if (VDBG) log("REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DataConnectionAc.REQ_GET_CID: {
                    if (VDBG) log("REQ_GET_CID  cid=" + mCid);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_CID, mCid);
                    break;
                }
                case DataConnectionAc.REQ_GET_APNSETTING: {
                    if (VDBG) log("REQ_GET_APNSETTING  apnSetting=" + mApn);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNSETTING, mApn);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_PROPERTIES: {
                    LinkProperties lp = new LinkProperties(mLinkProperties);
                    if (VDBG) log("REQ_GET_LINK_PROPERTIES linkProperties" + lp);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_PROPERTIES, lp);
                    break;
                }
                case DataConnectionAc.REQ_SET_LINK_PROPERTIES_HTTP_PROXY: {
                    ProxyProperties proxy = (ProxyProperties) msg.obj;
                    if (VDBG) log("REQ_SET_LINK_PROPERTIES_HTTP_PROXY proxy=" + proxy);
                    mLinkProperties.setHttpProxy(proxy);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_LINK_PROPERTIES_HTTP_PROXY);
                    break;
                }
                case DataConnectionAc.REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE: {
                    DataCallState newState = (DataCallState) msg.obj;
                    UpdateLinkPropertyResult result =
                                             updateLinkProperty(newState);
                    if (VDBG) {
                        log("REQ_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE result="
                            + result + " newState=" + newState);
                    }
                    mAc.replyToMessage(msg,
                                   DataConnectionAc.RSP_UPDATE_LINK_PROPERTIES_DATA_CALL_STATE,
                                   result);
                    break;
                }
                case DataConnectionAc.REQ_GET_LINK_CAPABILITIES: {
                    LinkCapabilities lc = new LinkCapabilities(mCapabilities);
                    if (VDBG) log("REQ_GET_LINK_CAPABILITIES linkCapabilities" + lc);
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_LINK_CAPABILITIES, lc);
                    break;
                }
                case DataConnectionAc.REQ_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    transitionTo(mInactiveState);
                    break;
                case DataConnectionAc.REQ_GET_REFCOUNT: {
                    if (VDBG) log("REQ_GET_REFCOUNT  RefCount=" + mApnList.size());
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_REFCOUNT, mApnList.size());
                    break;
                }
                case DataConnectionAc.REQ_GET_APNCONTEXT_LIST: {
                    if (VDBG) log("REQ_GET_APNCONTEXT_LIST num in list=" + mApnList.size());
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_APNCONTEXT_LIST,
                                       new ArrayList<ApnContext>(mApnList));
                    break;
                }
                case DataConnectionAc.REQ_SET_RECONNECT_INTENT: {
                    PendingIntent intent = (PendingIntent) msg.obj;
                    if (VDBG) log("REQ_SET_RECONNECT_INTENT");
                    mReconnectIntent = intent;
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_SET_RECONNECT_INTENT);
                    break;
                }
                case DataConnectionAc.REQ_GET_RECONNECT_INTENT: {
                    if (VDBG) log("REQ_GET_RECONNECT_INTENT");
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_GET_RECONNECT_INTENT,
                                       mReconnectIntent);
                    break;
                }
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, FailCause.UNKNOWN);
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT RefCount="
                                + mApnList.size());
                    }
                    deferMessage(msg);
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT_ALL RefCount="
                                + mApnList.size());
                    }
                    deferMessage(msg);
                    break;

                case EVENT_RIL_CONNECTED:
                    ar = (AsyncResult)msg.obj;
                    if (ar.exception == null) {
                        mRilVersion = (Integer)ar.result;
                        if (DBG) {
                            log("DcDefaultState: msg.what=EVENT_RIL_CONNECTED mRilVersion=" +
                                mRilVersion);
                        }
                    } else {
                        log("Unexpected exception on EVENT_RIL_CONNECTED");
                        mRilVersion = -1;
                    }
                    break;

                default:
                    if (DBG) {
                        log("DcDefaultState: shouldn't happen but ignore msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    break;
            }

            return retVal;
        }
    }
    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    private class DcInactiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;
        private DisconnectParams mDisconnectParams = null;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause,
                                               int retryOverride) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
            mRetryOverride = retryOverride;
        }

        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams dp");
            mDisconnectParams = dp;
        }

        @Override
        public void enter() {
            mTag += 1;

            /**
             * Now that we've transitioned to Inactive state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isInactive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcInactiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
            if (mDisconnectParams != null) {
                if (VDBG) log("DcInactiveState: enter notifyDisconnectCompleted");
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            clearSettings();
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
            mDisconnectParams = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DataConnectionAc.REQ_RESET:
                    if (DBG) {
                        log("DcInactiveState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    mAc.replyToMessage(msg, DataConnectionAc.RSP_RESET);
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECT:
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    mApnList.add(cp.mApnContext);
                    cp.mTheTag = mTag;
                    if (DBG) {
                        log("DcInactiveState msg.what=EVENT_CONNECT " + "RefCount="
                                + mApnList.size());
                    }
                    doOnConnect(cp);
                    transitionTo(mActivatingState);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams)msg.obj, false);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcInactiveState nothandled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) {
                        log("DcActivatingState deferring msg.what=EVENT_CONNECT RefCount="
                                + mApnList.size());
                    }
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    if (DBG) {
                        log("DcActivatingState msg.what=EVENT_SETUP_DATA_CONNECTION_DONE"
                                + " RefCount=" + mApnList.size());
                    }

                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;

                    DataCallState.SetupResult result = onSetupConnectionCompleted(ar);
                    if (DBG) log("DcActivatingState onSetupConnectionCompleted result=" + result);
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mActiveState.setEnterNotificationParams(cp, FailCause.NONE);
                            transitionTo(mActiveState);
                            break;
                        case ERR_BadCommand:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause, -1);
                            transitionTo(mInactiveState);
                            break;
                        case ERR_UnacceptableParameter:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERR_GetLastErrorFromRil:
                            // Request failed and this is an old RIL
                            mPhone.mCi.getLastDataCallFailCause(
                                    obtainMessage(EVENT_GET_LAST_FAIL_DONE, cp));
                            break;
                        case ERR_RilError:
                            // Request failed and mFailCause has the reason
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause,
                                                                      getSuggestedRetryTime(ar));
                            transitionTo(mInactiveState);
                            break;
                        case ERR_Stale:
                            // Request is stale, ignore.
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_GET_LAST_FAIL_DONE:
                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;
                    FailCause cause = FailCause.UNKNOWN;

                    if (cp.mTheTag == mTag) {
                        if (DBG) {
                            log("DcActivatingState msg.what=EVENT_GET_LAST_FAIL_DONE"
                                    + " RefCount=" + mApnList.size());
                        }
                        if (ar.exception == null) {
                            int rilFailCause = ((int[]) (ar.result))[0];
                            cause = FailCause.fromInt(rilFailCause);
                        }
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp, cause, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcActivatingState EVENT_GET_LAST_FAIL_DONE is stale cp.tag="
                                + cp.mTheTag + ", mTag=" + mTag + " RefCount=" + mApnList.size());
                        }
                    }

                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what) + " RefCount=" + mApnList.size());
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {
        private ConnectionParams mConnectionParams = null;
        private FailCause mFailCause = null;

        public void setEnterNotificationParams(ConnectionParams cp, FailCause cause) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mFailCause = cause;
        }

        @Override public void enter() {
            /**
             * Now that we've transitioned to Active state we
             * can send notifications. Previously we sent the
             * notifications in the processMessage handler but
             * that caused a race condition because the synchronous
             * call to isActive.
             */
            if ((mConnectionParams != null) && (mFailCause != null)) {
                if (VDBG) log("DcActiveState: enter notifyConnectCompleted");
                notifyConnectCompleted(mConnectionParams, mFailCause);
            }
        }

        @Override
        public void exit() {
            // clear notifications
            mConnectionParams = null;
            mFailCause = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT: {
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (mApnList.contains(cp.mApnContext)) {
                        log("DcActiveState ERROR already added apnContext=" + cp.mApnContext
                                    + " to this DC=" + this);
                    } else {
                        mApnList.add(cp.mApnContext);
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_CONNECT RefCount=" + mApnList.size());
                        }
                    }
                    notifyConnectCompleted(cp, FailCause.NONE);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT: {
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    if (mApnList.contains(dp.mApnContext)) {
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_DISCONNECT RefCount="
                                    + mApnList.size());
                        }

                        if (mApnList.size() == 1) {
                            mApnList.clear();
                            dp.mTheTag = mTag;
                            tearDownData(dp);
                            transitionTo(mDisconnectingState);
                        } else {
                            mApnList.remove(dp.mApnContext);
                            notifyDisconnectCompleted(dp, false);
                        }
                    } else {
                        log("DcActiveState ERROR no such apnContext=" + dp.mApnContext
                                + " in this DC=" + this);
                        notifyDisconnectCompleted(dp, false);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DcActiveState msg.what=EVENT_DISCONNECT_ALL RefCount="
                                + mApnList.size() + " clearing apn contexts");
                    }
                    mApnList.clear();
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    dp.mTheTag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mApnList.size());
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE RefCount="
                            + mApnList.size());
                    AsyncResult ar = (AsyncResult) msg.obj;
                    DisconnectParams dp = (DisconnectParams) ar.userObj;
                    if (dp.mTheTag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams((DisconnectParams) ar.userObj);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState EVENT_DEACTIVATE_DONE stale dp.tag="
                                + dp.mTheTag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what=0x" +
                                Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;
                    if (cp.mTheTag == mTag) {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE");
                        }

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                FailCause.UNACCEPTABLE_NETWORK_PARAMETER, -1);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection EVENT_DEACTIVATE_DONE" +
                                    " stale dp.tag=" + cp.mTheTag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what=0x"
                                + Integer.toHexString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();

    // ******* public interface

    /**
     * Bring up a connection to the apn and return an AsyncResult in onCompletedMsg.
     * Used for cellular networks that use Acesss Point Names (APN) such
     * as GSM networks.
     *
     * @param apnContext is the Access Point Name to bring up a connection to
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj,
     *        AsyncResult.result = FailCause and AsyncResult.exception = Exception().
     */
    public void bringUp(ApnContext apnContext, Message onCompletedMsg) {
        if (DBG) log("bringUp: apnContext=" + apnContext + " onCompletedMsg=" + onCompletedMsg);
        sendMessage(obtainMessage(EVENT_CONNECT, new ConnectionParams(apnContext, onCompletedMsg)));
    }

    /**
     * Tear down the connection through the apn on the network.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDown(ApnContext apnContext, String reason, Message onCompletedMsg) {
        if (DBG) {
            log("tearDown: apnContext=" + apnContext
                    + " reason=" + reason + " onCompletedMsg=" + onCompletedMsg);
        }
        sendMessage(obtainMessage(EVENT_DISCONNECT,
                        new DisconnectParams(apnContext, reason, onCompletedMsg)));
    }

    /**
     * Tear down the connection through the apn on the network.  Ignores refcount and
     * and always tears down.
     *
     * @param onCompletedMsg is sent with its msg.obj as an AsyncResult object.
     *        With AsyncResult.userObj set to the original msg.obj.
     */
    public void tearDownAll(String reason, Message onCompletedMsg) {
        if (DBG) log("tearDownAll: reason=" + reason + " onCompletedMsg=" + onCompletedMsg);
        sendMessage(obtainMessage(EVENT_DISCONNECT_ALL,
                new DisconnectParams(null, reason, onCompletedMsg)));
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        String info = null;
        info = cmdToString(what);
        if (info == null) {
            info = DataConnectionAc.cmdToString(what);
        }
        return info;
    }

    /**
     * Dump the current state.
     *
     * @param fd
     * @param pw
     * @param args
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("DataConnectionBase ");
        super.dump(fd, pw, args);
        pw.println(" mApnContexts.size=" + mApnList.size());
        pw.println(" mApnContexts=" + mApnList);
        pw.flush();
        pw.println(" mDataConnectionTracker=" + mDataConnectionTracker);
        pw.println(" mApn=" + mApn);
        pw.println(" mTag=" + mTag);
        pw.flush();
        pw.println(" mPhone=" + mPhone);
        pw.println(" mRilVersion=" + mRilVersion);
        pw.println(" mCid=" + mCid);
        pw.flush();
        pw.println(" mLinkProperties=" + mLinkProperties);
        pw.flush();
        pw.println(" mCapabilities=" + mCapabilities);
        pw.println(" mCreateTime=" + TimeUtils.logTimeOfDay(mCreateTime));
        pw.println(" mLastFailTime=" + TimeUtils.logTimeOfDay(mLastFailTime));
        pw.println(" mLastFailCause=" + mLastFailCause);
        pw.flush();
        pw.println(" mRetryOverride=" + mRetryOverride);
        pw.println(" mUserData=" + mUserData);
        if (mRetryMgr != null) pw.println(" " + mRetryMgr);
        pw.flush();
    }
}
