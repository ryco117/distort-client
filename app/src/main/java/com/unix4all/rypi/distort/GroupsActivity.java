package com.unix4all.rypi.distort;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.JsonReader;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

public class GroupsActivity extends AppCompatActivity implements NewGroupFragment.NewGroupListener, TimedRemoveFragment.OnFragmentFinishedListener {
    private final GroupsActivity mActivity = this;

    private DistortAuthParams mLoginParams;
    private @Nullable DistortAccount mAccount;

    private RecyclerView mGroupsView;
    private GroupAdapter mGroupsAdapter;

    private AddGroupTask mAddGroupTask;
    private RemoveGroupTask mRemoveGroupTask;

    private GroupServiceBroadcastReceiver mGroupServiceReceiver;
    private AccountServiceBroadcastReceiver mAccountServiceReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_groups);

        // Use shared preferences to fetch authorization params
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.account_credentials_preferences_key), Context.MODE_PRIVATE);
        mLoginParams = new DistortAuthParams();
        mLoginParams.setHomeserverAddress(sharedPref.getString(DistortAuthParams.EXTRA_HOMESERVER, null));
        mLoginParams.setHomeserverProtocol(sharedPref.getString(DistortAuthParams.EXTRA_HOMESERVER_PROTOCOL, null));
        mLoginParams.setPeerId(sharedPref.getString(DistortAuthParams.EXTRA_PEER_ID, null));
        mLoginParams.setAccountName(sharedPref.getString(DistortAuthParams.EXTRA_ACCOUNT_NAME, null));
        mLoginParams.setCredential(sharedPref.getString(DistortAuthParams.EXTRA_CREDENTIAL, null));

        // Init toolbar
        Toolbar toolbar = findViewById(R.id.groupToolbar);
        toolbar.setTitle(R.string.title_activity_groups);
        setSupportActionBar(toolbar);

        // Setup list of groups-list properties
        mGroupsView = (RecyclerView) findViewById(R.id.groupsView);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(GroupsActivity.this, LinearLayoutManager.VERTICAL, false);
        mGroupsView.setLayoutManager(linearLayoutManager);
        mGroupsView.addItemDecoration(new DividerItemDecoration(GroupsActivity.this, DividerItemDecoration.VERTICAL));

        // Determine account
        String activeGroupId = null;
        mAccount = DistortBackgroundService.getLocalAccount(this);
        if(mAccount != null) {
            activeGroupId = mAccount.getActiveGroupId();
        }

        // Prepare for datasets
        HashMap<String, DistortGroup> groupSet = DistortBackgroundService.getLocalGroups(this);
        ArrayList<DistortGroup> groups = new ArrayList<DistortGroup>();
        for(Map.Entry<String, DistortGroup> groupEntry : groupSet.entrySet()) {
            DistortGroup group = groupEntry.getValue();

            // Set if group is active
            if(group.getId().equals(activeGroupId)) {
                group.setIsActive(true);
            }

            // Add group
            groups.add(group);
        }
        mGroupsAdapter = new GroupAdapter(this, groups);
        mGroupsView.setAdapter(mGroupsAdapter);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showJoinNewGroup();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater findMenuItems = getMenuInflater();
        findMenuItems.inflate(R.menu.options_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.aboutOption:
                FragmentManager fm = getSupportFragmentManager();
                AboutFragment aboutFragment = AboutFragment.newInstance();
                aboutFragment.show(fm, "fragment_aboutLayout");
                return true;
            case R.id.settingsOption:
                Intent intent = new Intent(this, SettingsActivity.class);
                this.startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void showJoinNewGroup() {
        FragmentManager fm = getSupportFragmentManager();
        NewGroupFragment newGroupFragment = NewGroupFragment.newInstance();
        newGroupFragment.show(fm, "fragment_newGroupLayout");
    }

    public void showRemoveGroup(Integer groupIndex) {
        FragmentManager fm = getSupportFragmentManager();

        String title = getResources().getString(R.string.title_remove_group);
        String description = getResources().getString(R.string.description_remove_group);

        TimedRemoveFragment timedRemoveGroupFragment = TimedRemoveFragment.newInstance(this, title, description, groupIndex);
        timedRemoveGroupFragment.show(fm, "fragment_removeGroupLayout");
    }

    @Override
    public void onFinishGroupFieldInputs(String groupName, Integer subgroupLevel) {
        mAddGroupTask = new AddGroupTask(this, groupName, subgroupLevel);
        mAddGroupTask.execute();
    }

    @Override
    public void onFragmentFinished(Boolean removeChoice, @Nullable Integer groupIndex) {
        if(removeChoice) {
            String groupName = mGroupsAdapter.getItem(groupIndex).getName();
            mRemoveGroupTask = new RemoveGroupTask(this, groupName, groupIndex);
            mRemoveGroupTask.execute();
        }
    }

    // Handle successful retrieval of groups
    @Override
    protected void onStart() {
        mGroupServiceReceiver = new GroupsActivity.GroupServiceBroadcastReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DistortBackgroundService.ACTION_FETCH_GROUPS);
        LocalBroadcastManager.getInstance(this).registerReceiver(mGroupServiceReceiver, intentFilter);

        mAccountServiceReceiver = new GroupsActivity.AccountServiceBroadcastReceiver();
        intentFilter = new IntentFilter();
        intentFilter.addAction(DistortBackgroundService.ACTION_FETCH_ACCOUNT);
        LocalBroadcastManager.getInstance(this).registerReceiver(mAccountServiceReceiver, intentFilter);

        DistortBackgroundService.startActionFetchAccount(getApplicationContext());

        super.onStart();
    }
    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mGroupServiceReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mAccountServiceReceiver);
        super.onStop();
    }

    public class GroupServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final HashMap<String, DistortGroup> allGroups = DistortBackgroundService.getLocalGroups(getApplicationContext());
            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    for(Map.Entry<String, DistortGroup> group : allGroups.entrySet()) {
                        mGroupsAdapter.addOrUpdateGroup(group.getValue());
                    }
                }
            });
        }
    }

    public class AccountServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final Context appContext = getApplicationContext();
            final DistortAccount originalAccount = mAccount;
            final DistortAccount account = DistortBackgroundService.getLocalAccount(appContext);

            final HashMap<String, DistortGroup> allGroups = DistortBackgroundService.getLocalGroups(appContext);
            final boolean activeGroupIsSet = account.getActiveGroupId() != null && !account.getActiveGroupId().isEmpty();
            final boolean activeGroupWasSet = originalAccount.getActiveGroupId() != null && !originalAccount.getActiveGroupId().isEmpty();

            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (activeGroupWasSet) {
                        DistortGroup originalGroup = allGroups.get(originalAccount.getActiveGroupId());
                        if(originalGroup != null) {
                            originalGroup.setIsActive(false);
                            mGroupsAdapter.addOrUpdateGroup(originalGroup);
                        }
                    }
                    if (activeGroupIsSet) {
                        DistortGroup group = allGroups.get(account.getActiveGroupId());
                        if(group != null) {
                            group.setIsActive(true);
                            mGroupsAdapter.addOrUpdateGroup(group);
                        }
                    }
                }
            });
            mAccount = account;
        }
    }

    /**
     * Represents an asynchronous task used to add a group to account
     */
    public class AddGroupTask extends AsyncTask<Void, Void, Boolean> {

        private Activity mActivity;
        private String mGroupName;
        private Integer mSubgroupLevel;
        private int mErrorCode;

        AddGroupTask(Activity activity, String groupName, Integer subgroupLevel) {
            mActivity = activity;
            mGroupName = groupName;
            mSubgroupLevel = subgroupLevel;
            mErrorCode = 0;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            mErrorCode = 0;

            // Attempt authentication against a network service.
            try {
                JsonReader response = null;
                String url = mLoginParams.getHomeserverAddress() + "groups";

                HashMap<String, String> bodyParams = new HashMap<>();
                bodyParams.put("name", mGroupName);
                bodyParams.put("subgroupLevel", String.valueOf(mSubgroupLevel));

                URL homeserverEndpoint = new URL(url);
                if(DistortAuthParams.PROTOCOL_HTTPS.equals(mLoginParams.getHomeserverProtocol())) {
                    HttpsURLConnection myConnection;
                    myConnection = (HttpsURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.PostBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                } else {
                    HttpURLConnection myConnection;
                    myConnection = (HttpURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.PostBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                }

                // Read new group response
                final DistortGroup newGroup = DistortGroup.readJson(response);
                response.close();

                // If new group is the only group, then it will be active
                if(mGroupsAdapter.getItemCount() == 0) {
                    newGroup.setIsActive(true);
                }

                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mGroupsAdapter.addOrUpdateGroup(newGroup);
                    }
                });

                return true;
            } catch (DistortJson.DistortException e) {
                mErrorCode = -1;
                e.printStackTrace();
                Log.e("ADD-GROUP", e.getMessage() + " : " + String.valueOf(e.getResponseCode()));

                return false;
            } catch (IOException e) {
                mErrorCode = -2;
                e.printStackTrace();

                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            mAddGroupTask = null;
            Log.d("ADD-GROUP", String.valueOf(mErrorCode));

            // TODO: Handle errors here
            if (success) {
                // Invalidated local groups cache, refetch
                DistortBackgroundService.startActionFetchGroups(getApplicationContext());
            } else {

            }
        }
    }


    /**
     * Represents an asynchronous task used to remove a group
     */
    public class RemoveGroupTask extends AsyncTask<Void, Void, Boolean> {

        private Activity mActivity;
        private String mGroupName;
        private int mIndex;
        private int mErrorCode;

        RemoveGroupTask(Activity activity, String groupName, int groupIndex) {
            mActivity = activity;
            mGroupName = groupName;
            mIndex = groupIndex;
            mErrorCode = 0;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            mErrorCode = 0;

            // Attempt authentication against a network service.
            try {
                JsonReader response = null;
                String url = mLoginParams.getHomeserverAddress() + "groups/" + URLEncoder.encode(mGroupName, "UTF-8");

                HashMap<String, String> bodyParams = new HashMap<>();

                URL homeserverEndpoint = new URL(url);
                if(DistortAuthParams.PROTOCOL_HTTPS.equals(mLoginParams.getHomeserverProtocol())) {
                    HttpsURLConnection myConnection;
                    myConnection = (HttpsURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.DeleteBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                } else {
                    HttpURLConnection myConnection;
                    myConnection = (HttpURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.DeleteBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                }

                response.close();

                mActivity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mGroupsAdapter.removeItem(mIndex);
                    }
                });

                return true;
            } catch (DistortJson.DistortException e) {
                mErrorCode = -1;
                e.printStackTrace();
                Log.e("REMOVE-GROUP", e.getMessage() + " : " + String.valueOf(e.getResponseCode()));

                return false;
            } catch (IOException e) {
                mErrorCode = -2;
                e.printStackTrace();

                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            mRemoveGroupTask = null;
            Log.d("REMOVE-GROUP", String.valueOf(mErrorCode));

            // TODO: Handle errors here
            if (success) {
                // Invalidated local groups cache, refetch
                DistortBackgroundService.startActionFetchGroups(getApplicationContext());
            } else {

            }
        }
    }
}