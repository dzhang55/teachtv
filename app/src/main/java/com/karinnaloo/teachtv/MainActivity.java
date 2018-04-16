package com.karinnaloo.teachtv;

import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubnub.api.PNConfiguration;
import com.pubnub.api.PubNub;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import com.karinnaloo.teachtv.adapters.HistoryAdapter;
import com.karinnaloo.teachtv.adt.HistoryItem;
import com.karinnaloo.teachtv.util.Constants;
import com.pubnub.api.PubNubError;
import com.pubnub.api.PubNubException;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.callbacks.SubscribeCallback;
import com.pubnub.api.enums.PNStatusCategory;
import com.pubnub.api.models.consumer.PNPublishResult;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.presence.PNHereNowResult;
import com.pubnub.api.models.consumer.presence.PNSetStateResult;
import com.pubnub.api.models.consumer.pubsub.PNMessageResult;
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult;


public class MainActivity extends ListActivity {
    private SharedPreferences mSharedPreferences;
    private String username;
    private String stdByChannel;
    private PubNub mPubNub;

    private ListView mHistoryList;
    private HistoryAdapter mHistoryAdapter;
    private EditText mCallNumET;
    private TextView mUsernameTV;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.mSharedPreferences = getSharedPreferences(Constants.SHARED_PREFS, MODE_PRIVATE);
        if (!this.mSharedPreferences.contains(Constants.USER_NAME)){
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        this.username     = this.mSharedPreferences.getString(Constants.USER_NAME, "");
        this.stdByChannel = this.username + Constants.STDBY_SUFFIX;

        this.mHistoryList = getListView();
        this.mCallNumET   = (EditText) findViewById(R.id.call_num);
        this.mUsernameTV  = (TextView) findViewById(R.id.main_username);

        this.mUsernameTV.setText(this.username);
        initPubNub();

        this.mHistoryAdapter = new HistoryAdapter(this, new ArrayList<HistoryItem>(), this.mPubNub, this.username);
        this.mHistoryList.setAdapter(this.mHistoryAdapter);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch(id){
            case R.id.action_settings:
                return true;
            case R.id.action_sign_out:
                signOut();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if(this.mPubNub!=null){
            // TODO: kloo unsubscribe all
            this.mPubNub.unsubscribe();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if(this.mPubNub==null){
            initPubNub();
        } else {
            subscribeStdBy();
        }
    }

    /**
     * Subscribe to standby channel so that it doesn't interfere with the WebRTC Signaling.
     */
    public void initPubNub(){
        PNConfiguration config = new PNConfiguration()
                .setPublishKey(Constants.PUB_KEY)
                .setSubscribeKey(Constants.SUB_KEY)
                .setUuid(this.username)
                .setSecure(true);
        this.mPubNub = new PubNub(config);
        subscribeStdBy();
    }

    /**
     * Subscribe to standby channel
     */
    private void subscribeStdBy(){
        this.mPubNub.addListener(new SubscribeCallback() {
            @Override
            public void status(PubNub pubnub, PNStatus status) {
                if (status.getCategory() == PNStatusCategory.PNConnectedCategory) {
                    setUserStatus(Constants.STATUS_AVAILABLE);
                }
            }

            @Override
            public void message(PubNub pubnub, PNMessageResult message) {
                Log.d("MA-iPN", "MESSAGE: " + message.toString());
                JsonNode jsonMsg = message.getMessage();
                if (!jsonMsg.has(Constants.JSON_CALL_USER)) return;     //Ignore Signaling messages.
                String user = jsonMsg.get(Constants.JSON_CALL_USER).textValue();
                dispatchIncomingCall(user);
            }

            @Override
            public void presence(PubNub pubnub, PNPresenceEventResult presence) {

            }
        });

        this.mPubNub.subscribe().channels(Arrays.asList(this.stdByChannel)).withPresence().execute();
    }

    /**
     * Take the user to a video screen. USER_NAME is a required field.
     * @param view button that is clicked to trigger toVideo
     */
    public void makeCall(View view){
        String callNum = mCallNumET.getText().toString();
        if (callNum.isEmpty() || callNum.equals(this.username)){
            showToast("Enter a valid user ID to call.");
            return;
        }
        dispatchCall(callNum);
    }

    /**TODO: Debate who calls who. Should one be on standby? Or use State API for busy/available
     * Check that user is online. If they are, dispatch the call by publishing to their standby
     *   channel. If the publish was successful, then change activities over to the video chat.
     * The called user will then have the option to accept of decline the call. If they accept,
     *   they will be brought to the video chat activity as well, to connect video/audio. If
     *   they decline, a hangup will be issued, and the VideoChat adapter's onHangup callback will
     *   be invoked.
     * @param callNum Number to publish a call to.
     */
    public void dispatchCall(final String callNum){
        final String callNumStdBy = callNum + Constants.STDBY_SUFFIX;
        this.mPubNub.hereNow().channels(Arrays.asList(callNumStdBy)).async(new PNCallback<PNHereNowResult>() {
            @Override
            public void onResponse(PNHereNowResult result, PNStatus status) {
                Log.d("MA-dC", "HERE_NOW: " +" CH - " + callNumStdBy + " " + result.toString());
                int occupancy = result.getTotalOccupancy();
                if (occupancy == 0) {
                    showToast("User is not online!");
                    return;
                }
                ObjectNode jsonCall = new ObjectMapper().createObjectNode();
                jsonCall.put(Constants.JSON_CALL_USER, username);
                jsonCall.put(Constants.JSON_CALL_TIME, System.currentTimeMillis());
                mPubNub.publish().message(jsonCall).channel(callNumStdBy).async(new PNCallback<PNPublishResult>() {
                    @Override
                    public void onResponse(PNPublishResult result, PNStatus status) {
                        Log.d("MA-dC", "SUCCESS: " + result.toString());
                        Intent intent = new Intent(MainActivity.this, StudentClassroomActivity.class);
                        intent.putExtra(Constants.USER_NAME, username);
                        intent.putExtra(Constants.CALL_USER, callNum);  // Only accept from this number?
                        intent.putExtra("dialed", true);
                        startActivity(intent);
                    }
                });
            }
        });
    }

    /**
     * Handle incoming calls. TODO: Implement an accept/reject functionality.
     * TODO(dz): Remove this functionality.
     * @param userId
     */
    private void dispatchIncomingCall(String userId){
        showToast("Call from: " + userId);
        Intent intent = new Intent(MainActivity.this, IncomingCallActivity.class);
        intent.putExtra(Constants.USER_NAME, username);
        intent.putExtra(Constants.CALL_USER, userId);
        startActivity(intent);
    }

    private void setUserStatus(String status){
        this.mPubNub.setPresenceState()
                .channels(Arrays.asList(this.stdByChannel))
                .uuid(status) // Sets state for key uuid to be value status.
                .async(new PNCallback<PNSetStateResult>() {
            @Override
            public void onResponse(PNSetStateResult result, PNStatus status) {
                Log.d("MA-sUS","State Set: " + status.toString());
            }
        });
    }

    private void getUserStatus(String userId){
        String stdByUser = userId + Constants.STDBY_SUFFIX;
        this.mPubNub.getPresenceState().channels(Arrays.asList(stdByUser)).uuid(userId);
    }

    /**
     * Ensures that toast is run on the UI thread.
     * @param message
     */
    private void showToast(final String message){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Log out, remove username from SharedPreferences, unsubscribe from PubNub, and send user back
     *   to the LoginActivity
     */
    public void signOut(){
        // TODO: kloo unsub all
        this.mPubNub.unsubscribe();
        SharedPreferences.Editor edit = this.mSharedPreferences.edit();
        edit.remove(Constants.USER_NAME);
        edit.apply();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("oldUsername", this.username);
        startActivity(intent);
    }
}
