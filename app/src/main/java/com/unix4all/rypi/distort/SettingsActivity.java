package com.unix4all.rypi.distort;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;

import com.twitter.sdk.android.core.Callback;
import com.twitter.sdk.android.core.Result;
import com.twitter.sdk.android.core.TwitterAuthConfig;
import com.twitter.sdk.android.core.TwitterAuthToken;
import com.twitter.sdk.android.core.TwitterException;
import com.twitter.sdk.android.core.TwitterSession;
import com.twitter.sdk.android.core.identity.TwitterLoginButton;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import javax.net.ssl.HttpsURLConnection;

import com.unix4all.rypi.distort.SocialMediaJSON.TwitterPlatform;

public class SettingsActivity extends AppCompatActivity implements ChangePasswordFragment.ChangePasswordListener {
    private final Activity mActivity = this;

    private DistortAuthParams mLoginParams;

    private ArrayList<DistortGroup> mGroups;
    private ArrayList<String> mGroupNames;
    private DistortAccount mAccount;

    private Switch mEnabledToggle;
    private Spinner mActiveGroupSpinner;
    private Button mChangePswdButton;
    private TwitterLoginButton mLinkTwitterButton;

    private UpdateAccountTask mUpdateAccountTask;
    private LinkTask mLinkTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mLoginParams = DistortAuthParams.getAuthenticationParams(this);
        mAccount = DistortBackgroundService.getLocalAccount(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);

        mEnabledToggle = findViewById(R.id.settingsEnabledSwitch);
        mEnabledToggle.setChecked(mAccount.getEnabled());
        mEnabledToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                HashMap<String, String> p = new HashMap<>();
                p.put("accountName", mLoginParams.getAccountName());
                p.put("enabled", String.valueOf(mEnabledToggle.isChecked()));
                mUpdateAccountTask = new UpdateAccountTask(p);
                mUpdateAccountTask.execute();
            }
        });
        if(mAccount.getAccountName().equals("root")) {
            // Can't disable root
            mEnabledToggle.setVisibility(View.GONE);
        } else {
            mEnabledToggle.setVisibility(View.VISIBLE);
        }


        int selectedItem = 0;
        HashMap<String, DistortGroup> groupMap = DistortBackgroundService.getLocalGroups(this, mAccount.getFullAddress());
        mGroups = new ArrayList<>();
        mGroups.add(null);
        mGroupNames = new ArrayList<>();
        mGroupNames.add("");
        int i = 1;
        for(HashMap.Entry<String, DistortGroup> groupEntry : groupMap.entrySet()) {
            mGroups.add(groupEntry.getValue());
            mGroupNames.add(groupEntry.getValue().getName());

            if(groupEntry.getValue().getName().equals(mAccount.getActiveGroup())) {
                selectedItem = i;
            }
            i++;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, mGroupNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mActiveGroupSpinner = findViewById(R.id.activeGroupSpinner);
        mActiveGroupSpinner.setAdapter(adapter);
        mActiveGroupSpinner.setSelection(selectedItem);
        mActiveGroupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View view,
                                       int position, long id) {
                HashMap<String, String> p = new HashMap<>();
                p.put("accountName", mLoginParams.getAccountName());
                if(mGroups.get(position) == null) {
                    // Remove active group
                    p.put("activeGroup", "");
                } else {
                    p.put("activeGroup", mGroups.get(position).getName());
                }
                mUpdateAccountTask = new UpdateAccountTask(p);
                mUpdateAccountTask.execute();
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) {}
        });

        mChangePswdButton = findViewById(R.id.changePasswordButton);
        mChangePswdButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChangePassword();
            }
        });

        mLinkTwitterButton = findViewById(R.id.twitterLoginButton);
        mLinkTwitterButton.setCallback(new Callback<TwitterSession>() {
            @Override
            public void success(Result<TwitterSession> result) {
                TwitterAuthToken auth = result.data.getAuthToken();
                TwitterPlatform twitter = new TwitterPlatform(result.data.getUserName(), auth.token, auth.secret,
                        getString(R.string.com_twitter_sdk_android_CONSUMER_KEY),
                        getString(R.string.com_twitter_sdk_android_CONSUMER_SECRET));

                SharedPreferences sharedPref = getSharedPreferences(
                        getString(R.string.linked_accounts_preferences_keys), Context.MODE_PRIVATE);
                SocialMediaJSON socialMedia = SocialMediaJSON.readPreferences(sharedPref, mAccount.getFullAddress());
                socialMedia.put(twitter);
                socialMedia.writePreferences(sharedPref, mAccount.getFullAddress());
                Log.d("LINK-ACCOUNTS", "*Prepare to run async task*");

                mLinkTask = new LinkTask(twitter);
                mLinkTask.execute();
            }

            @Override
            public void failure(TwitterException exception) {
                Snackbar.make(findViewById(R.id.settingsConstraintLayout), exception.getLocalizedMessage(),
                        Snackbar.LENGTH_LONG)
                        .show();
            }
        });
    }

    public void showChangePassword() {
        FragmentManager fm = getSupportFragmentManager();
        ChangePasswordFragment changePasswordFragment = ChangePasswordFragment.newInstance(mLoginParams.getPeerId());
        changePasswordFragment.show(fm, "fragment_changePassword");
    }

    @Override
    public void onChangePasswordFinished(String authToken) {
        HashMap<String, String> p = new HashMap<>();
        p.put("accountName", mLoginParams.getAccountName());
        p.put("authToken", authToken);
        mUpdateAccountTask = new UpdateAccountTask(p);
        mUpdateAccountTask.execute();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == TwitterAuthConfig.DEFAULT_AUTH_REQUEST_CODE) {
            // Pass the activity result to the login button.
            mLinkTwitterButton.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Represents an asynchronous task used to update account
     */
    public class UpdateAccountTask extends AsyncTask<Void, Void, Boolean> {

        private String mErrorString;
        private HashMap<String, String> mBodyParams;

        UpdateAccountTask(HashMap<String, String> bodyParams) {
            mErrorString = "";
            mBodyParams = bodyParams;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            mErrorString = "";

            // Attempt authentication against a network service.
            try {
                JsonReader response;
                String url = mLoginParams.getHomeserverAddress() + "account";

                URL homeserverEndpoint = new URL(url);
                if(DistortAuthParams.PROTOCOL_HTTPS.equals(mLoginParams.getHomeserverProtocol())) {
                    HttpsURLConnection myConnection;
                    myConnection = (HttpsURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.putBodyGetJSONFromURL(myConnection, mLoginParams, mBodyParams);
                } else {
                    HttpURLConnection myConnection;
                    myConnection = (HttpURLConnection) homeserverEndpoint.openConnection();
                    response = DistortJson.putBodyGetJSONFromURL(myConnection, mLoginParams, mBodyParams);
                }
                response.close();


                return true;
            } catch (DistortJson.DistortException e) {
                if (e.getResponseCode() == 403) {           // Not authorized to update specified account
                    mErrorString = e.getMessage();
                } else if (e.getResponseCode() == 404) {    // Account to update does not exist
                    mErrorString = e.getMessage();
                } else if (e.getResponseCode() == 500) {
                    Log.w("UPDATE-ACCOUNT", e.getMessage());
                    mErrorString = getString(R.string.error_server_error);
                } else {
                    mErrorString = e.getMessage();
                }
                return false;
            } catch (IOException e) {
                mErrorString = e.getMessage();
                return false;
            }
        }

        @SuppressLint("ApplySharedPref")
        @Override
        protected void onPostExecute(final Boolean success) {
            mUpdateAccountTask = null;
            if (success) {
                // Check if password was changed
                if(mBodyParams.containsKey("authToken")) {
                    String token = mBodyParams.get("authToken");
                    mLoginParams.setCredential(token);

                    SharedPreferences sp = getSharedPreferences(
                            getString(R.string.account_credentials_preferences_key), Context.MODE_PRIVATE);

                    // Wait for write since background needs updated token
                    sp.edit().putString(DistortAuthParams.EXTRA_CREDENTIAL, token).commit();

                    // Display success message (since updating password has the least intuitive feedback)
                    Snackbar.make(findViewById(R.id.settingsConstraintLayout),
                        getString(R.string.success_update_password),
                        Snackbar.LENGTH_LONG)
                        .show();
                }

                Context context = getApplicationContext();
                DistortBackgroundService.startActionFetchAccount(context);
                DistortBackgroundService.startActionFetchGroups(context);
            } else {
                Log.e("UPDATE-ACCOUNT", mErrorString);
                // TODO: Reset relevant fields on error
                // Reset enable-status on error
                mEnabledToggle.setChecked(mAccount.getEnabled());

                Snackbar.make(findViewById(R.id.settingsConstraintLayout), mErrorString,
                    Snackbar.LENGTH_LONG)
                    .show();
            }
        }
    }

    /**
     * Represents an asynchronous task used to add link to media account
     */
    public class LinkTask extends AsyncTask<Void, Void, Boolean> {

        private String mErrorString;
        private SocialMediaJSON.Platform mPlatform;

        LinkTask(SocialMediaJSON.Platform platform) {
            Log.d("LINK-ACCOUNT-CONSTRUCTOR", "HERE");
            mErrorString = "";
            mPlatform = platform;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            mErrorString = "";

            // Attempt authentication against a network service.
            try {
                JsonReader response;
                String url = mLoginParams.getHomeserverAddress() + "social-media";

                HashMap<String, String> bodyParams = new HashMap<>();
                bodyParams.put("platform", mPlatform.getPlatform());
                bodyParams.put("handle", mPlatform.getHandle());
                bodyParams.put("key", mPlatform.getKey());

                URL homeserverEndpoint = new URL(url);
                if(DistortAuthParams.PROTOCOL_HTTPS.equals(mLoginParams.getHomeserverProtocol())) {
                    HttpsURLConnection myConnection;
                    myConnection = (HttpsURLConnection) homeserverEndpoint.openConnection();

                    Log.d("LINK-ACCOUNT-START", "THERE");
                    response = DistortJson.putBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                } else {
                    HttpURLConnection myConnection;
                    myConnection = (HttpURLConnection) homeserverEndpoint.openConnection();

                    Log.d("LINK-ACCOUNT-START", "THERE");
                    response = DistortJson.putBodyGetJSONFromURL(myConnection, mLoginParams, bodyParams);
                }

                Log.d("LINK-ACCOUNT-MID", "THERE");

                response.beginObject();
                while(response.hasNext()) {
                    String key = response.nextName();
                    if(key.equals("message")) {
                        Log.d("LINK-ACCOUNT", "Response: " + response.nextString());
                    } else {
                        response.skipValue();
                    }
                }
                response.endObject();
                response.close();
                Log.d("LINK-ACCOUNT", "**Successful account linking**");

                return true;
            } catch (DistortJson.DistortException e) {
                if (e.getResponseCode() == 400) {    // Account to update does not exist
                    mErrorString = e.getMessage();
                } else if (e.getResponseCode() == 500) {
                    Log.w("LINK-ACCOUNT", e.getMessage());
                    mErrorString = getString(R.string.error_server_error);
                } else {
                    mErrorString = e.getMessage();
                }
                return false;
            } catch (IOException e) {
                mErrorString = e.getMessage();
                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            mLinkTask = null;
            if (success) {
                // Display success message (since updating password has the least intuitive feedback)
                Snackbar.make(findViewById(R.id.settingsConstraintLayout),
                        getString(R.string.message_account_link_success),
                        Snackbar.LENGTH_LONG)
                        .show();
            } else {
                Log.e("LINK-ACCOUNT", mErrorString);
                // Reset enable-status on error
                Snackbar.make(findViewById(R.id.settingsConstraintLayout), mErrorString,
                        Snackbar.LENGTH_LONG)
                        .show();
            }
        }
    }
}
