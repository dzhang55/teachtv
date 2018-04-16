package com.karinnaloo.teachtv.adapters;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.fasterxml.jackson.databind.JsonNode;
import com.pubnub.api.PubNub;


import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.karinnaloo.teachtv.MainActivity;
import com.karinnaloo.teachtv.R;
import com.karinnaloo.teachtv.adt.ChatUser;
import com.karinnaloo.teachtv.adt.HistoryItem;
import com.karinnaloo.teachtv.util.Constants;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.history.PNHistoryItemResult;
import com.pubnub.api.models.consumer.history.PNHistoryResult;
import com.pubnub.api.models.consumer.presence.PNGetStateResult;

/**
 * Created by GleasonK on 7/31/15.
 */
public class HistoryAdapter extends ArrayAdapter<HistoryItem> {
    private final Context context;
    private PubNub mPubNub;
    private LayoutInflater inflater;
    private List<HistoryItem> values;
    private Map<String, ChatUser> users;
    private String uuid;


    public HistoryAdapter(Context context, List<HistoryItem> values, PubNub pubnub, String uuid) {
        super(context, R.layout.history_row_layout, android.R.id.text1, values);
        this.context  = context;
        this.inflater = LayoutInflater.from(context);
        this.mPubNub  = pubnub;
        this.values   = values;
        this.users    = new HashMap<String, ChatUser>();
        this.uuid     = uuid;
        updateHistory();
    }

    class ViewHolder {
        TextView    user;
        TextView    status;
        TextView    time;
        ImageButton callBtn;
        HistoryItem histItem;
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {
        final HistoryItem hItem = this.values.get(position);
        ViewHolder holder;
        if (convertView == null) {
            holder = new ViewHolder();
            convertView    = inflater.inflate(R.layout.history_row_layout, parent, false);
            holder.user    = (TextView) convertView.findViewById(R.id.history_name);
            holder.status  = (TextView) convertView.findViewById(R.id.history_status);
            holder.time    = (TextView) convertView.findViewById(R.id.history_time);
            holder.callBtn = (ImageButton) convertView.findViewById(R.id.history_call);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }
        holder.user.setText(hItem.getUser().getUserId());
        holder.time.setText(formatTimeStamp(hItem.getTimeStamp()));
        holder.status.setText(hItem.getUser().getStatus());
        if (hItem.getUser().getStatus().equals(Constants.STATUS_OFFLINE))
            getUserStatus(hItem.getUser(), holder.status);
        holder.callBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((MainActivity)context).dispatchCall(hItem.getUser().getUserId());
            }
        });
        holder.histItem=hItem;
        return convertView;
    }

    @Override
    public int getCount() {
        return this.values.size();
    }

    public void removeButton(int loc){
        this.values.remove(loc);
        notifyDataSetChanged();
    }

    private void getUserStatus(final ChatUser user, final TextView statusView){
        String stdByUser = user.getUserId() + Constants.STDBY_SUFFIX;
        this.mPubNub.getPresenceState().channels(Arrays.asList(stdByUser)).uuid(user.getUserId()).async(new PNCallback<PNGetStateResult>() {
            @Override
            public void onResponse(PNGetStateResult result, PNStatus status) {
                // TODO: kloo figure out status
//                final String userStatus = result.getStateByUUID().get(Constants.JSON_STATUS).toString();
//                user.setStatus(userStatus);
                user.setStatus("Poop");
                ((Activity)getContext()).runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
//                        statusView.setText(userStatus);
                        user.setStatus("Poop");
                    }
                });
            }
        });
    }

    public void updateHistory(){
        final List<HistoryItem> rtcHistory = new LinkedList<HistoryItem>();
        String usrStdBy = this.uuid + Constants.STDBY_SUFFIX;
        this.mPubNub.history().channel(usrStdBy).count(25).async(new PNCallback<PNHistoryResult>() {
            @Override
            public void onResponse(PNHistoryResult result, PNStatus status) {
                Log.d("HA-uH", "HISTORY: " + result.toString());
                for (PNHistoryItemResult itemResult : result.getMessages()) {
                    JsonNode entry = itemResult.getEntry();
                    String userName = entry.get(Constants.JSON_CALL_USER).toString();
                    long timeStamp = entry.get(Constants.JSON_CALL_TIME).asLong();
                    ChatUser cUser = new ChatUser(userName);
                    if (users.containsKey(userName)) {
                        cUser = users.get(userName);
                    } else {
                        users.put(userName, cUser);
                    }
                    rtcHistory.add(0, new HistoryItem(cUser, timeStamp));
                }
                values = rtcHistory;
                updateAdapter();
            }
        });
    }

    private void updateAdapter(){
        ((Activity)context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                notifyDataSetChanged();
            }
        });
    }

    /**
     * Format the long System.currentTimeMillis() to a better looking timestamp. Uses a calendar
     *   object to format with the user's current time zone.
     * @param timeStamp
     * @return
     */
    public static String formatTimeStamp(long timeStamp){
        // Create a DateFormatter object for displaying date in specified format.
        SimpleDateFormat formatter = new SimpleDateFormat("MMM d, h:mm a");

        // Create a calendar object that will convert the date and time value in milliseconds to date.
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timeStamp);
        return formatter.format(calendar.getTime());
    }
}

