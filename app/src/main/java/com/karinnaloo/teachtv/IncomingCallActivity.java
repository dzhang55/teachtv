package com.karinnaloo.teachtv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonObject;
import com.pubnub.api.PNConfiguration;
import com.pubnub.api.PubNub;

import org.json.JSONObject;

import com.karinnaloo.teachtv.util.Constants;
import me.kevingleason.pnwebrtc.PnPeerConnectionClient;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.models.consumer.PNPublishResult;
import com.pubnub.api.models.consumer.PNStatus;


public class IncomingCallActivity extends Activity {
    private SharedPreferences mSharedPreferences;
    private String username;
    private String callUser;

    private PubNub mPubNub;
    private TextView mCallerID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        this.mSharedPreferences = getSharedPreferences(Constants.SHARED_PREFS, MODE_PRIVATE);
        if (!this.mSharedPreferences.contains(Constants.USER_NAME)){
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        this.username = this.mSharedPreferences.getString(Constants.USER_NAME, "");

        Bundle extras = getIntent().getExtras();
        if (extras==null || !extras.containsKey(Constants.CALL_USER)){
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            Toast.makeText(this, "Need to pass username to IncomingCallActivity in intent extras (Constants.CALL_USER).",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        this.callUser = extras.getString(Constants.CALL_USER, "");
        this.mCallerID = (TextView) findViewById(R.id.caller_id);
        this.mCallerID.setText(this.callUser);

        PNConfiguration config = new PNConfiguration()
                .setPublishKey(Constants.PUB_KEY)
                .setSubscribeKey(Constants.SUB_KEY)
                .setUuid(this.username)
                .setSecure(true);
        this.mPubNub = new PubNub(config);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_incoming_call, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void acceptCall(View view){
        Intent intent = new Intent(IncomingCallActivity.this, TeacherClassroomActivity.class);
        intent.putExtra(Constants.USER_NAME, this.username);
        intent.putExtra(Constants.CALL_USER, this.callUser);
        startActivity(intent);
    }

    /**
     * Publish a hangup command if rejecting call.
     * @param view
     */
    public void rejectCall(View view){
        JsonNode hangupMsg = PnPeerConnectionClient.generateHangupPacket(this.username);
        Log.d("Teachtv-MainActivity", "publish: " + this.callUser + "message: " + hangupMsg.toString());
        this.mPubNub.publish().message(hangupMsg).channel(this.callUser).async(new PNCallback<PNPublishResult>() {
            @Override
            public void onResponse(PNPublishResult result, PNStatus status) {
                Intent intent = new Intent(IncomingCallActivity.this, MainActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if(this.mPubNub!=null){
            // TODO: kloo unsubscribe all
            this.mPubNub.unsubscribe();
        }
    }
}
