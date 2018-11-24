package com.unix4all.rypi.distort;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;

import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;

import org.spongycastle.crypto.PBEParametersGenerator;
import org.spongycastle.crypto.digests.SHA256Digest;
import org.spongycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.util.encoders.Base64;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * A login screen that offers login via homeserver / password.
 */
public class LoginActivity extends AppCompatActivity {

    /**
     * Regex Pattern to identify if a string is a valid homeserver address, as well as
     * fetch relevant substring from the URL
     */
    private static final Pattern IS_ADDRESS_PATTERN = Pattern.compile("(http(s)?://)?([a-zA-Z0-9.-]+\\.[a-z]+)(:[0-9]*)?(/[a-zA-Z0-9%/.-]*)?");

    /**
     * Protocol strings for connecting to homeserver
     */
    public static final String PROTOCOL_HTTPS = "https://";
    public static final String PROTOCOL_HTTP = "http://";


    /**
     * Keep track of the login task to ensure we can cancel it if requested.
     */
    private UserLoginTask mAuthTask = null;


    /**
     * Pass login address and credentials to the conversations activity
     */
    public static final String EXTRA_HOMESERVER = "com.example.distort.HOMESERVER";
    public static final String EXTRA_HOMESERVER_PROTOCOL = "com.example.distort.HOMESERVER_PROTOCOL";
    public static final String EXTRA_CREDENTIAL = "com.example.distort.CREDENTIAL";
    public static final String EXTRA_PEER_ID = "com.example.distort.PEER_ID";


    // TEST VALUES
    private static final String TEST_IPFS_PEER_ID = "QmcEVUBmGfgqyTAhiAi5H9dYqGnb4uMYKugV9bnpNqPYrs";
    private static final String TEST_PASS = "test-pass";
    private static final String TEST_TOKEN = "z0Mi+8Ul/Cwm14raY/T8u39Y8CLSM5kBEAxKA/4B2os=";

    // UI references.
    private EditText mHomeserverView;
    private EditText mAccountNameView;
    private EditText mPasswordView;
    private View mProgressView;
    private View mLoginFormView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // TODO: If store exists with login credentials, attempt to bypass this step if login successful

        // Set up the login form.
        mHomeserverView = (EditText) findViewById(R.id.homeserver);
        mAccountNameView = (EditText) findViewById(R.id.accountName);
        mPasswordView = (EditText) findViewById(R.id.password);
        mPasswordView.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView textView, int id, KeyEvent keyEvent) {
            if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_NULL) {
                attemptLogin();
                return true;
            }
            return false;
            }
        });

        Button mSignInButton = (Button) findViewById(R.id.sign_in_button);
        mSignInButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptLogin();
            }
        });

        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);
    }


    /**
     * Attempts to sign in or register the account specified by the login form.
     * If there are form errors (invalid homeserver, missing fields, etc.), the
     * errors are presented and no actual login attempt is made.
     */
    private void attemptLogin() {
        if (mAuthTask != null) {
            return;
        }

        // Reset errors.
        mHomeserverView.setError(null);
        mPasswordView.setError(null);

        // Store values at the time of the login attempt.
        String homeserverAddr = mHomeserverView.getText().toString();
        String account = mAccountNameView.getText().toString();
        String password = mPasswordView.getText().toString();

        boolean cancel = false;
        View focusView = null;

        // Check for a valid password, if the user entered one.
        if (!TextUtils.isEmpty(password) && !isPasswordValid(password)) {
            mPasswordView.setError(getString(R.string.error_invalid_password));
            focusView = mPasswordView;
            cancel = true;
        }

        // Check for a valid address.
        if (TextUtils.isEmpty(homeserverAddr)) {
            mHomeserverView.setError(getString(R.string.error_field_required));
            focusView = mHomeserverView;
            cancel = true;
        } else if (!isAddressValid(homeserverAddr)) {
            mHomeserverView.setError(getString(R.string.error_invalid_address));
            focusView = mHomeserverView;
            cancel = true;
        }

        if (cancel) {
            // There was an error; don't attempt login and focus the first
            // form field with an error.
            focusView.requestFocus();
        } else {
            // Show a progress spinner, and kick off a background task to
            // perform the user login attempt.
            showProgress(true);
            mAuthTask = new UserLoginTask(this, homeserverAddr, password, account);
            mAuthTask.execute((Void) null);
        }
    }

    private boolean isAddressValid(String address) {
        return IS_ADDRESS_PATTERN.matcher(address).matches();
    }

    private boolean isPasswordValid(String password) {
        //TODO: Replace this with improved logic
        return true;
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(
                    show ? 0 : 1).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                }
            });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Represents an asynchronous login/registration task used to authenticate
     * the user.
     */
    public class UserLoginTask extends AsyncTask<Void, Void, Boolean> {

        private final String mAccount;
        private final String mPassword;
        private final Context mContext;
        private String mAddress;
        private String mToken;
        private String mPeerId;
        private String mProtocol;
        private int errorCode;

        UserLoginTask(Context ctx, String address, String password, String account) {
            mContext = ctx;
            mAddress = address;
            if(mAddress.charAt(mAddress.length()-1) != '/') {
                mAddress += "/";
            }

            mAccount = account;
            mPassword = password;
            mToken = "";

            errorCode = 0;
        }

        private int GetResponseFromURL(HttpURLConnection myConnection) throws ProtocolException, IOException {
            myConnection.setRequestMethod("GET");

            // Set request header fields
            myConnection.setRequestProperty("User-Agent", "distort-android-v0.1");
            myConnection.setRequestProperty("Accept","*/*");
            myConnection.setRequestProperty("peerid", mPeerId);
            myConnection.setRequestProperty("authtoken", mToken);

            // Make connection and determine response
            myConnection.connect();
            int response = myConnection.getResponseCode();
            myConnection.disconnect();

            return response;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            errorCode = 0;
            String ipfsNodeId = TEST_IPFS_PEER_ID;
            mPeerId = ipfsNodeId;
            if(mAccount.length() > 0) {
                mPeerId += ":" + mAccount;
            }

            // Attempt to generate token from password
            PKCS5S2ParametersGenerator generator = new PKCS5S2ParametersGenerator(new SHA256Digest());
            generator.init(mPassword.getBytes(), ipfsNodeId.getBytes(), 1000);
            KeyParameter passwordBasedKey = (KeyParameter)generator.generateDerivedMacParameters(256);
            mToken = new String(Base64.encode(passwordBasedKey.getKey()), Charset.forName("UTF-8"));

            Log.d("LOGIN-AUTH", mToken);

            // Attempt authentication against a network service.
            try {
                // Create string to test login
                String loginURL = mAddress + "groups";

                URL homeserverEndpoint = new URL(loginURL);
                Matcher matcher = IS_ADDRESS_PATTERN.matcher(mAddress);
                matcher.find();

                int response;
                if(PROTOCOL_HTTPS.equals(matcher.group(1))) {
                    mProtocol = PROTOCOL_HTTPS;

                    HttpsURLConnection myConnection;
                    myConnection = (HttpsURLConnection) homeserverEndpoint.openConnection();
                    response = GetResponseFromURL(myConnection);
                } else {
                    mProtocol = PROTOCOL_HTTP;

                    HttpURLConnection myConnection;
                    myConnection = (HttpURLConnection) homeserverEndpoint.openConnection();
                    response = GetResponseFromURL(myConnection);
                }

                if(response != 200) {
                    if(response == 401) {
                        // Connected but password token was incorrect
                        errorCode = -1;
                    } else {
                        // Some other error...
                        Log.e("LOGIN", String.valueOf(response));
                        errorCode = -2;
                    }
                }

                // Return true only if authentication was successful
                return response == 200;
            } catch (MalformedURLException e) {
                errorCode = -3;
                return false;
            } catch (IOException e) {
                errorCode = -4;
                e.printStackTrace();
                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            mAuthTask = null;
            showProgress(false);

            Log.d("LOGIN", String.valueOf(errorCode));

            if (success) {
                Intent intent = new Intent(mContext, ConversationsActivity.class);
                intent.putExtra(EXTRA_HOMESERVER, mAddress);
                intent.putExtra(EXTRA_HOMESERVER_PROTOCOL, mProtocol);
                intent.putExtra(EXTRA_PEER_ID, mPeerId);
                intent.putExtra(EXTRA_CREDENTIAL, mToken);
                startActivity(intent);
            } else {
                if(errorCode == -1) {
                    mPasswordView.setError(getString(R.string.error_incorrect_password));
                    mPasswordView.requestFocus();
                } else {
                    mHomeserverView.setError(getString(R.string.error_homeserver_could_not_reach));
                    mHomeserverView.requestFocus();
                }
            }
        }

        @Override
        protected void onCancelled() {
            mAuthTask = null;
            showProgress(false);
        }
    }
}

