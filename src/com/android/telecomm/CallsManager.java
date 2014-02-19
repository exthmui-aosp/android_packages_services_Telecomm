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

package com.android.telecomm;

import android.telecomm.CallServiceDescriptor;
import android.telecomm.CallState;
import android.util.Log;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Singleton.
 *
 * NOTE(gilad): by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.telecomm package boundary.
 */
public final class CallsManager {

    private static final String TAG = CallsManager.class.getSimpleName();

    private static final CallsManager INSTANCE = new CallsManager();

    private final Switchboard mSwitchboard;

    /** Used to control the in-call app. */
    private final InCallController mInCallController;

    /**
     * The main call repository. Keeps an instance of all live calls keyed by call ID. New incoming
     * and outgoing calls are added to the map and removed when the calls move to the disconnected
     * state.
     * TODO(santoscordon): Add new CallId class and use it in place of String.
     */
    private final Map<String, Call> mCalls = Maps.newHashMap();

    /**
     * May be unnecessary per off-line discussions (between santoscordon and gilad) since the set
     * of CallsManager APIs that need to be exposed to the dialer (or any application firing call
     * intents) may be empty.
     */
    private DialerAdapter mDialerAdapter;

    private InCallAdapter mInCallAdapter;

    private CallLogManager mCallLogManager;

    private VoicemailManager mVoicemailManager;

    private List<OutgoingCallValidator> mOutgoingCallValidators = Lists.newArrayList();

    private List<IncomingCallValidator> mIncomingCallValidators = Lists.newArrayList();

    /**
     * Initializes the required Telecomm components.
     */
    private CallsManager() {
        mSwitchboard = new Switchboard(this);
        mInCallController = new InCallController(this);
    }

    static CallsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Starts the incoming call sequence by having switchboard gather more information about the
     * specified call; using the specified call service descriptor. Upon success, execution returns
     * to {@link #handleSuccessfulIncomingCall} to start the in-call UI.
     *
     * @param descriptor The descriptor of the call service to use for this incoming call.
     */
    void processIncomingCallIntent(CallServiceDescriptor descriptor) {
        Log.d(TAG, "processIncomingCallIntent");
        // Create a call with no handle. Eventually, switchboard will update the call with
        // additional information from the call service, but for now we just need one to pass around
        // with a unique call ID.
        Call call = new Call();

        mSwitchboard.retrieveIncomingCall(call, descriptor);
    }

    /**
     * Validates the specified call and, upon no objection to connect it, adds the new call to the
     * list of live calls. Also notifies the in-call app so the user can answer or reject the call.
     *
     * @param call The new incoming call.
     */
    void handleSuccessfulIncomingCall(Call call) {
        Log.d(TAG, "handleSuccessfulIncomingCall");
        Preconditions.checkState(call.getState() == CallState.RINGING);

        String handle = call.getHandle();
        ContactInfo contactInfo = call.getContactInfo();
        for (IncomingCallValidator validator : mIncomingCallValidators) {
            if (!validator.isValid(handle, contactInfo)) {
                // TODO(gilad): Consider displaying an error message.
                Log.i(TAG, "Dropping restricted incoming call");
                return;
            }
        }

        // No objection to accept the incoming call, proceed with potentially connecting it (based
        // on the user's action, or lack thereof).
        addCall(call);
    }

    /**
     * Attempts to issue/connect the specified call.  From an (arbitrary) application standpoint,
     * all that is required to initiate this flow is to fire either of the CALL, CALL_PRIVILEGED,
     * and CALL_EMERGENCY intents. These are listened to by CallActivity.java which then invokes
     * this method.
     *
     * @param handle The handle to dial.
     * @param contactInfo Information about the entity being called.
     */
    void processOutgoingCallIntent(String handle, ContactInfo contactInfo) {
        for (OutgoingCallValidator validator : mOutgoingCallValidators) {
            if (!validator.isValid(handle, contactInfo)) {
                // TODO(gilad): Display an error message.
                Log.i(TAG, "Dropping restricted outgoing call.");
                return;
            }
        }

        // No objection to issue the call, proceed with trying to put it through.
        Call call = new Call(handle, contactInfo);
        mSwitchboard.placeOutgoingCall(call);
    }

    /**
     * Adds a new outgoing call to the list of live calls and notifies the in-call app.
     *
     * @param call The new outgoing call.
     */
    void handleSuccessfulOutgoingCall(Call call) {
        // OutgoingCallProcessor sets the call state to DIALING when it receives confirmation of the
        // placed call from the call service so there is no need to set it here. Instead, check that
        // the state is appropriate.
        Preconditions.checkState(call.getState() == CallState.DIALING);
        addCall(call);
    }

    /**
     * Instructs Telecomm to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param callId The ID of the call.
     */
    void answerCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.i(TAG, "Request to answer a non-existent call " + callId);
        } else {
            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}. However, if we ever change that to look more responsive,
            // then we need to make sure we add a timeout for the answer() in case the call never
            // comes out of RINGING.
            call.answer();
        }
    }

    /**
     * Instructs Telecomm to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecomm notifies it of an incoming call followed by
     * the user opting to reject said call.
     *
     * @param callId The ID of the call.
     */
    void rejectCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.i(TAG, "Request to reject a non-existent call " + callId);
        } else {
            call.reject();
        }
    }

    /**
     * Instructs Telecomm to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     *
     * @param callId The ID of the call.
     */
    void disconnectCall(String callId) {
        Call call = mCalls.get(callId);
        if (call == null) {
            Log.e(TAG, "Unknown call (" + callId + ") asked to disconnect");
        } else {
            call.disconnect();
        }

    }

    void markCallAsRinging(String callId) {
        setCallState(callId, CallState.RINGING);
    }

    void markCallAsDialing(String callId) {
        setCallState(callId, CallState.DIALING);
    }

    void markCallAsActive(String callId) {
        setCallState(callId, CallState.ACTIVE);
        mInCallController.markCallAsActive(callId);
    }

    /**
     * Marks the specified call as DISCONNECTED and notifies the in-call app. If this was the last
     * live call, then also disconnect from the in-call controller.
     *
     * @param callId The ID of the call.
     */
    void markCallAsDisconnected(String callId) {
        setCallState(callId, CallState.DISCONNECTED);

        Call call = mCalls.remove(callId);
        // At this point the call service has confirmed that the call is disconnected to it is
        // safe to disassociate the call from its call service.
        call.clearCallService();

        // Notify the in-call UI
        mInCallController.markCallAsDisconnected(callId);
        if (mCalls.isEmpty()) {
            mInCallController.unbind();
        }
    }

    /**
     * Sends all the live calls to the in-call app if any exist. If there are no live calls, then
     * tells the in-call controller to unbind since it is not needed.
     */
    void updateInCall() {
        if (mCalls.isEmpty()) {
            mInCallController.unbind();
        } else {
            for (Call call : mCalls.values()) {
                addInCallEntry(call);
            }
        }
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        mCalls.put(call.getId(), call);
        addInCallEntry(call);
    }

    /**
     * Notifies the in-call app of the specified (new) call.
     */
    private void addInCallEntry(Call call) {
        mInCallController.addCall(call.toCallInfo());
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param callId The ID of the call to update.
     * @param state The new state of the call.
     */
    private void setCallState(String callId, CallState state) {
        Preconditions.checkState(!Strings.isNullOrEmpty(callId));
        Preconditions.checkNotNull(state);

        Call call = mCalls.get(callId);
        if (call == null) {
            Log.e(TAG, "Call " + callId + " was not found while attempting to upda the state to " +
                    state + ".");
        } else {
            // Unfortunately, in the telephony world, the radio is king. So if the call notifies us
            // that the call is in a particular state, we allow it even if it doesn't make sense
            // (e.g., ACTIVE -> RINGING).
            // TODO(santoscordon): Consider putting a stop to the above and turning CallState into
            // a well-defined state machine.
            // TODO(santoscordon): Define expected state transitions here, and log when an
            // unexpected transition occurs.
            call.setState(state);
            // TODO(santoscordon): Notify the in-call app whenever a call changes state.
        }
    }
}
