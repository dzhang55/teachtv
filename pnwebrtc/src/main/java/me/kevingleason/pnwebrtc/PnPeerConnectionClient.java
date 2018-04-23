package me.kevingleason.pnwebrtc;

import android.util.Log;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.callbacks.SubscribeCallback;
import com.pubnub.api.PubNub;
import com.pubnub.api.enums.PNStatusCategory;
import com.pubnub.api.models.consumer.PNPublishResult;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.pubsub.PNMessageResult;
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult;

import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * <h1>PeerConnection manager for {@link me.kevingleason.pnwebrtc.PnRTCClient}</h1>
 * <pre>
 * Author:  Kevin Gleason - Boston College '16
 * File:    PnPeerConnectionClient.java
 * Date:    7/20/15
 * Use:     WebRTC PeerConnection Manager
 * &copy; 2009 - 2015 PubNub, Inc.
 * </pre>
 *
 * {@link PnPeerConnectionClient} is used to manage peer connections.
 */
public class PnPeerConnectionClient {
    private SessionDescription localSdp  = null; // either offer or answer SDP
    private MediaStream localMediaStream = null;
    PeerConnectionFactory pcFactory;
    PnRTCListener mRtcListener;
    PnSignalingParams signalingParams;
    int MAX_CONNECTIONS = Integer.MAX_VALUE;

    private PubNub mPubNub;
    private CallListener mSubscribeReceiver;
    private Map<String,PnAction> actionMap;
    private Map<String,PnPeer> peers;
    private String id;
    private String sessionID;

    public PnPeerConnectionClient(PubNub pubnub, PnSignalingParams signalingParams, me.kevingleason.pnwebrtc.PnRTCListener rtcListener){
        this.mPubNub = pubnub;
        this.signalingParams = signalingParams;
        this.mRtcListener = rtcListener;
        this.pcFactory = new PeerConnectionFactory(); // TODO: Check it allowed, else extra param
        this.peers = new HashMap<String, PnPeer>();
        sessionID = java.util.UUID.randomUUID().toString();
        init();
    }

    private void init(){
        this.actionMap = new HashMap<String, PnAction>();
        this.actionMap.put(CreateOfferAction.TRIGGER,     new CreateOfferAction());
        this.actionMap.put(CreateAnswerAction.TRIGGER,    new CreateAnswerAction());
        this.actionMap.put(SetRemoteSDPAction.TRIGGER,    new SetRemoteSDPAction());
        this.actionMap.put(AddIceCandidateAction.TRIGGER, new AddIceCandidateAction());
        this.actionMap.put(PnUserHangupAction.TRIGGER,    new PnUserHangupAction());
        this.actionMap.put(PnUserMessageAction.TRIGGER,   new PnUserMessageAction());
        mSubscribeReceiver = new CallListener();
//        this.mPubNub.addListener(this.mSubscribeReceiver);
    }

    boolean listenOn(String myId){  // Todo: return success?
        //TODO(dz): Make sure we can remove this
//        if (localMediaStream==null){       // Not true for streaming?
//            mRtcListener.onDebug(new PnRTCMessage("Need to add media stream before you can connect."));
//            return false;
//        }
        if (this.id != null){  // Prevent listening on multiple channels.
            mRtcListener.onDebug(new PnRTCMessage("Already listening on " + this.id + ". Cannot have multiple connections."));
            return false;
        }
        this.id = myId;
        subscribe(myId);
        return true;
    }

    /**TODO: Add a max user threshold.
     * Connect with another user by their ID.
     * @param userId The user to establish a WebRTC connection with
     * @return boolean value of success
     */
    boolean connect(String userId, boolean dialed) {
        if (!peers.containsKey(userId)) { // Prevents duplicate dials.
            if (peers.size() < MAX_CONNECTIONS) {
                addPeer(userId);
                if (!dialed) {
                    Log.d("Teachtv-PnPeerConClient", "connect to: " + userId + " dialed: " + dialed);
                    actionMap.get(CreateOfferAction.TRIGGER).execute(userId, new ObjectMapper().createObjectNode());
                }
                return true;
            }
        }
        this.mRtcListener.onDebug(new PnRTCMessage("CONNECT FAILED. Duplicate dial or max peer " +
                "connections exceeded. Max: " + MAX_CONNECTIONS + " Current: " + this.peers.size()));
        return false;
    }

    public void setRTCListener(PnRTCListener listener){
        this.mRtcListener = listener;
    }

    private void subscribe(String channel){
        Log.d("Teachtv-PnPeerConClient", "subscribe: " + channel);
        this.mPubNub.addListener(this.mSubscribeReceiver);
        this.mPubNub.subscribe().channels(Arrays.asList(channel)).execute();
    }

    public void setLocalMediaStream(MediaStream localStream){
        this.localMediaStream = localStream;
        mRtcListener.onLocalStream(localStream);
    }

    public MediaStream getLocalMediaStream(){
        return this.localMediaStream;
    }

    private PnPeer addPeer(String id) {
        PnPeer peer = new PnPeer(id, this);
        peers.put(id, peer);
        return peer;
    }

    PnPeer removePeer(String id) {
        PnPeer peer = peers.get(id);
        peer.pc.close();
        return peers.remove(peer.id);
    }

    List<PnPeer> getPeers(){
        return new ArrayList<PnPeer>(this.peers.values());
    }

    /**
     * Close connection (hangup) no a certain peer.
     * @param id PnPeer id to close connection with
     */
    public void closeConnection(String id){
        ObjectNode packet = new ObjectMapper().createObjectNode();
        if (!this.peers.containsKey(id)) return;
        PnPeer peer = this.peers.get(id);
        peer.hangup();
        packet.put(PnRTCMessage.JSON_HANGUP, true);
        transmitMessage(id, packet);
        mRtcListener.onPeerConnectionClosed(peer);
    }

    /**
     * Close connections (hangup) on all open connections.
     */
    public void closeAllConnections() {
        Iterator<String> peerIds = this.peers.keySet().iterator();
        while (peerIds.hasNext()) {
            closeConnection(peerIds.next());
        }
    }

    /**
     * Send SDP Offers/Answers nd ICE candidates to peers.
     * @param toID The id or "number" that you wish to transmit a message to.
     * @param packet The JSON data to be transmitted
     */
    void transmitMessage(String toID, JsonNode packet){
        if (this.id==null){ // Not logged in. Put an error in the debug cb.
            mRtcListener.onDebug(new PnRTCMessage("Cannot transmit before calling Client.connect"));
        }
        ObjectNode message = new ObjectMapper().createObjectNode();
        message.set(PnRTCMessage.JSON_PACKET, packet);
        message.put(PnRTCMessage.JSON_ID, sessionID); //Todo: session id, unused in js SDK?
        message.put(PnRTCMessage.JSON_NUMBER, this.id);
        Log.d("Teachtv-transmitMessage", "publish: channel: " + toID + " message: " + message.toString());
        this.mPubNub.publish().message(message).channel(toID).async(new PNCallback<PNPublishResult>() {
            @Override
            public void onResponse(PNPublishResult result, PNStatus status) {
                if (status.isError()) {
                    Log.e("Teachtv-transmitMessage", "publish error: " + status.getCategory().name());
                    return;
                }
                Log.d("Teachtv-transmitMessage", "transmit success: " + result.toString());
            }
        });;
    }

    private interface PnAction{
        void execute(String peerId, JsonNode payload);
    }

    private class CreateOfferAction implements PnAction{
        public static final String TRIGGER = "init";
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-COAction","CreateOfferAction");
            PnPeer peer = peers.get(peerId);
            peer.setDialed(true);
            peer.setType(PnPeer.TYPE_ANSWER);
            peer.pc.createOffer(peer, signalingParams.pcConstraints);
        }
    }

    private class CreateAnswerAction implements PnAction{
        public static final String TRIGGER = "offer";
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-CAAction","CreateAnswerAction");
            PnPeer peer = peers.get(peerId);
            peer.setType(PnPeer.TYPE_OFFER);
            peer.setStatus(PnPeer.STATUS_CONNECTED);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.get("type").textValue()),
                    payload.get("sdp").textValue()
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, signalingParams.pcConstraints);
        }
    }

    private class SetRemoteSDPAction implements PnAction{
        public static final String TRIGGER = "answer";
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-SRSAction","SetRemoteSDPAction");
            PnPeer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.get("type").textValue()),
                    payload.get("sdp").textValue()
            );
            peer.pc.setRemoteDescription(peer, sdp);
        }
    }

    private class AddIceCandidateAction implements PnAction{
        public static final String TRIGGER = "candidate";
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-AICAction","AddIceCandidateAction");
            PeerConnection pc = peers.get(peerId).pc;
            if (pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        payload.get("sdpMid").textValue(),
                        payload.get("sdpMLineIndex").intValue(),
                        payload.get("candidate").textValue()
                );
                pc.addIceCandidate(candidate);
            }
        }
    }

    private class PnUserHangupAction implements PnAction{
        public static final String TRIGGER = PnRTCMessage.JSON_HANGUP;
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-PnUserHangup","PnUserHangupAction");
            PnPeer peer = peers.get(peerId);
            peer.hangup();
            mRtcListener.onPeerConnectionClosed(peer);
            // Todo: Consider Callback?
        }
    }

    private class PnUserMessageAction implements PnAction{
        public static final String TRIGGER = PnRTCMessage.JSON_USERMSG;
        public void execute(String peerId, JsonNode payload) {
            Log.d("Teachtv-PnUserMessage","PnUserMessageAction");
            JsonNode msgJson = payload.get(PnRTCMessage.JSON_USERMSG);
            PnPeer peer = peers.get(peerId);
            mRtcListener.onMessage(peer, msgJson);
        }
    }


    /**
     * @param userId Your id. Used to tag the message before publishing it to another user.
     * @return
     */
    public static JsonNode generateHangupPacket(String userId){
        ObjectNode json = new ObjectMapper().createObjectNode();
        ObjectNode packet = new ObjectMapper().createObjectNode();
        packet.put(PnRTCMessage.JSON_HANGUP, true);
        json.set(PnRTCMessage.JSON_PACKET, packet);
        json.put(PnRTCMessage.JSON_ID, ""); //Todo: session id, unused in js SDK?
        json.put(PnRTCMessage.JSON_NUMBER, userId);
        return json;
    }

    /**
     * Static method to generate the proper JSON for a user message. Use this when you don't have
     *   a {@link me.kevingleason.pnwebrtc.PnRTCClient} instantiated. Simply send a publish with the
     *   returned JsonNode to the ID that a user is subscribed to.
     * @param userId Your UserID, needed to tag the message
     * @param message The message you with to send some other user
     * @return JsonNode properly formatted for the PubNub WebRTC API
     */
    public static JsonNode generateUserMessage(String userId, JsonNode message){
        ObjectNode json = new ObjectMapper().createObjectNode();
        ObjectNode packet = new ObjectMapper().createObjectNode();
        packet.set(PnRTCMessage.JSON_USERMSG, message);
        json.set(PnRTCMessage.JSON_PACKET, packet);
        json.put(PnRTCMessage.JSON_ID, ""); //Todo: session id, unused in js SDK?
        json.put(PnRTCMessage.JSON_NUMBER, userId);
        return json;
    }

    private class CallListener extends SubscribeCallback {

        // TODO: kloo delete? on connected
//        @Override
//        public void connectCallback(String channel, Object message) {
//            mRtcListener.onDebug(new PnRTCMessage(((JSONArray) message).toString()));
//            mRtcListener.onConnected(channel);
//        }

        @Override
        public void status(PubNub pubnub, PNStatus status) {
            if (status.getCategory() == PNStatusCategory.PNConnectedCategory) {
                Log.d("Teachtv-SubCallback", "connected");
                String myId = pubnub.getConfiguration().getUuid();
                mRtcListener.onConnected(myId);
//                actionMap.get(CreateAnswerAction.TRIGGER).execute(myId, new ObjectMapper().createObjectNode());
            }
        }

        @Override
        public void message(PubNub pubnub, PNMessageResult message) {
            Log.d("Teachtv-SubCallback", message.toString());
            JsonNode jsonMessage = message.getMessage();
            mRtcListener.onDebug(new PnRTCMessage(jsonMessage.toString()));
            String peerId = jsonMessage.get(PnRTCMessage.JSON_NUMBER).textValue();
            JsonNode packet = jsonMessage.get(PnRTCMessage.JSON_PACKET);
            PnPeer peer;
            if (!peers.containsKey(peerId)){
                // Possibly threshold number of allowed users
                peer = addPeer(peerId);
            } else {
                peer = peers.get(peerId);
            }
            if (peer.getStatus().equals(PnPeer.STATUS_DISCONNECTED)) return; // Do nothing if disconnected.
            if (packet.has(PnRTCMessage.JSON_USERMSG)) {
                actionMap.get(PnUserMessageAction.TRIGGER).execute(peerId,packet);
                return;
            }
            if (packet.has(PnRTCMessage.JSON_HANGUP)){
                actionMap.get(PnUserHangupAction.TRIGGER).execute(peerId,packet);
                return;
            }
            if (packet.has(PnRTCMessage.JSON_THUMBNAIL)) {
                return;   // No handler for thumbnail or hangup yet, will be separate controller callback
            }
            if (packet.has(PnRTCMessage.JSON_SDP)) {
                if(!peer.received) {
                    peer.setReceived(true);
                    mRtcListener.onDebug(new PnRTCMessage("SDP - " + peer.toString()));
                    // Todo: reveivercb(peer);
                }
                String type = packet.get(PnRTCMessage.JSON_TYPE).textValue();
                actionMap.get(type).execute(peerId, packet);
                return;
            }

            if (packet.has(PnRTCMessage.JSON_ICE)){
                actionMap.get(AddIceCandidateAction.TRIGGER).execute(peerId,packet);
                return;
            }
        }

        @Override
        public void presence(PubNub pubnub, PNPresenceEventResult presence) {
            Log.d("Teachtv-SubCallback", "presence: " + presence.getEvent());

        }
    }
}
