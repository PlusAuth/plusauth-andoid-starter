package com.plusauth.starter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.browser.customtabs.CustomTabsIntent;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.AuthorizationServiceDiscovery;
import net.openid.appauth.ClientAuthentication;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.browser.AnyBrowserMatcher;
import net.openid.appauth.browser.BrowserMatcher;
import net.openid.appauth.connectivity.DefaultConnectionBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import okio.Okio;


public class AuthManager {

    public static final int RC_AUTH = 100;
    public static final int RC_SIGN_OUT = 101;
    private static final String TAG = "AuthManager";
    private final AtomicReference<AuthorizationRequest> mAuthRequest = new AtomicReference<>();
    private final AtomicReference<CustomTabsIntent> mAuthIntent = new AtomicReference<>();
    private final Runnable onReadyCallback;
    private AuthorizationService mAuthService;
    private AuthStateManager mStateManager;
    private ExecutorService mExecutor;
    private Uri endSessionEndpoint;

    @NonNull
    private BrowserMatcher mBrowserMatcher = AnyBrowserMatcher.INSTANCE;


    public AuthManager(Context context, Runnable onReadyCallback) {
        this.onReadyCallback = onReadyCallback;
        mExecutor = Executors.newSingleThreadExecutor();
        mStateManager = AuthStateManager.getInstance(context);
        mAuthService = new AuthorizationService(
                context,
                new AppAuthConfiguration.Builder()
                        .setConnectionBuilder(DefaultConnectionBuilder.INSTANCE)
                        .setBrowserMatcher(mBrowserMatcher)
                        .build());
    }

    public void retry(Context context) {
        mExecutor.submit(() -> {
            initializeAppAuth(context);
        });
    }

    public void start() {
        if (mExecutor.isShutdown()) {
            mExecutor = Executors.newSingleThreadExecutor();
        }

    }

    public void stop() {
        mExecutor.shutdownNow();
    }

    public void destroy() {
        if (mAuthService != null) {
            mAuthService.dispose();
        }
    }

    public void authenticate(AuthenticateListener authenticateListener) {
        // WrongThread inference is incorrect for lambdas
        // noinspection WrongThread
        mExecutor.submit(() -> {
            doAuth(authenticateListener);
        });
    }


    /**
     * Initializes the authorization service configuration if necessary, either from the local
     * static values or by retrieving an OpenID discovery document.
     */
    public void initializeAppAuth(Context context) {
        mExecutor.submit(() -> {
            Log.i(TAG, "Initializing AppAuth");
            recreateAuthorizationService(context);

            if (mStateManager.getCurrent().getAuthorizationServiceConfiguration() != null) {
                // configuration is already created, skip to client initialization
                Log.i(TAG, "auth config already established");
                initializeAuthRequest();
                return;
            }

            Log.i(TAG, "Retrieving OpenID discovery doc");
            AuthorizationServiceConfiguration.fetchFromIssuer(
                    Config.AUTH_URI,
                    this::handleConfigurationRetrievalResult);
        });
    }

    private void handleConfigurationRetrievalResult(
            AuthorizationServiceConfiguration config,
            AuthorizationException ex) {
        if (config == null) {
            throw new RuntimeException("Failed to retrieve discovery document: " + ex.getMessage());
        }

        Log.i(TAG, "Discovery document retrieved");
        mStateManager.replace(new AuthState(config));
        initializeAuthRequest();
    }

    /**
     * Performs the authorization request
     */
    private void doAuth(AuthenticateListener authenticateListener) {
        Intent intent = mAuthService.getAuthorizationRequestIntent(
                mAuthRequest.get(),
                mAuthIntent.get());
        authenticateListener.startActivityForResult(intent, RC_AUTH);

    }

    private void recreateAuthorizationService(Context context) {
        if (mAuthService != null) {
            Log.i(TAG, "Discarding existing AuthService instance");
            mAuthService.dispose();
        }
        mAuthService = new AuthorizationService(
                context,
                new AppAuthConfiguration.Builder()
                        .setConnectionBuilder(DefaultConnectionBuilder.INSTANCE)
                        .setBrowserMatcher(mBrowserMatcher)
                        .build());
        mAuthRequest.set(null);
        mAuthIntent.set(null);
    }

    private CustomTabsIntent warmUpBrowser(Uri uri) {
        Log.i(TAG, "Warming up browser instance for auth request");
        CustomTabsIntent.Builder intentBuilder =
                mAuthService.createCustomTabsIntentBuilder(uri);
        CustomTabsIntent customTabsIntent = intentBuilder.build();

        customTabsIntent.intent.setData(uri);

        return customTabsIntent;
    }

    private void createAuthRequest() {
        Log.i(TAG, "Creating auth request");
        AuthorizationRequest.Builder authRequestBuilder = new AuthorizationRequest.Builder(
                mStateManager.getCurrent().getAuthorizationServiceConfiguration(),
                Config.CLIENT_ID,
                ResponseTypeValues.CODE,
                Config.REDIRECT_URI)
                .setScope(Config.SCOPE);

        mAuthRequest.set(authRequestBuilder.build());
    }

    private void initializeAuthRequest() {
        createAuthRequest();
        mAuthIntent.set(warmUpBrowser(mAuthRequest.get().toUri()));
        onReadyCallback.run();
    }

    public boolean isAuthorized() {
        return mStateManager.getCurrent().isAuthorized();
    }

    public AuthState getState() {
        return mStateManager.getCurrent();
    }

    /*
     * The stored AuthState is incomplete, so check if we are currently receiving the result of
     * the authorization flow from the browser.
     */
    public void completeAuthFlow(Intent intent, AuthorizationService.TokenResponseCallback callback) {
        AuthorizationResponse response = AuthorizationResponse.fromIntent(intent);
        AuthorizationException ex = AuthorizationException.fromIntent(intent);

        if (response != null || ex != null) {
            mStateManager.updateAfterAuthorization(response, ex);
        }

        if (response != null && response.authorizationCode != null) {
            // authorization code exchange is required
            mStateManager.updateAfterAuthorization(response, ex);
            exchangeAuthorizationCode(response, callback);
        } else if (ex != null) {
            callback.onTokenRequestCompleted(null, ex);
        } else {
            callback.onTokenRequestCompleted(null, new AuthorizationException(78, 7878, "No authorization state retained - reauthorization required", null, null, null));
        }
    }

    public void refreshAccessToken(AuthorizationService.TokenResponseCallback callback) {
        performTokenRequest(
                mStateManager.getCurrent().createTokenRefreshRequest(),
                (response, ex) -> {
                    mStateManager.updateAfterTokenResponse(response, ex);

                    callback.onTokenRequestCompleted(response, ex);
                });
    }

    public void exchangeAuthorizationCode(AuthorizationResponse authResponse, AuthorizationService.TokenResponseCallback callback) {
        performTokenRequest(
                authResponse.createTokenExchangeRequest(),
                (response, ex) -> {
                    mStateManager.updateAfterTokenResponse(response, ex);

                    callback.onTokenRequestCompleted(response, ex);
                });
    }

    private void performTokenRequest(
            TokenRequest request,
            AuthorizationService.TokenResponseCallback callback) {
        ClientAuthentication clientAuthentication;
        try {
            clientAuthentication = mStateManager.getCurrent().getClientAuthentication();
        } catch (ClientAuthentication.UnsupportedAuthenticationMethod ex) {
            throw new RuntimeException("Auth method not supported", ex);
        }

        mAuthService.performTokenRequest(
                request,
                clientAuthentication,
                callback);
    }

    /**
     * Demonstrates the use of {@link AuthState#} to retrieve
     * user info from the IDP's user info endpoint. This callback will negotiate a new access
     * token / id token for use in a follow-up action, or provide an error if this fails.
     */
    public void fetchUserInfo(UserInfoListener userInfoListener) {
        AuthorizationServiceDiscovery discovery =
                mStateManager.getCurrent()
                        .getAuthorizationServiceConfiguration()
                        .discoveryDoc;

        URL userInfoEndpoint;
        try {
            userInfoEndpoint = new URL(discovery.getUserinfoEndpoint().toString());
        } catch (MalformedURLException urlEx) {
            throw new RuntimeException("Failed to construct user info endpoint URL", urlEx);
        }

        mExecutor.submit(() -> {
            try {
                HttpURLConnection conn =
                        (HttpURLConnection) userInfoEndpoint.openConnection();
                conn.setRequestProperty("Authorization", "Bearer " + getState().getAccessToken());
                conn.setInstanceFollowRedirects(false);
                String response = Okio.buffer(Okio.source(conn.getInputStream()))
                        .readString(StandardCharsets.UTF_8);
                userInfoListener.onCompleted(new JSONObject(response), null);
            } catch (IOException | JSONException ex) {
                userInfoListener.onCompleted(null, ex);
            }
        });
    }

    private Uri getEndSessionEndpoint() {
        if (endSessionEndpoint == null) {
            try {
                AuthState currentState = mStateManager.getCurrent();
                AuthorizationServiceConfiguration config = currentState.getAuthorizationServiceConfiguration();
                String endSessionString = config.discoveryDoc.docJson.getString("end_session_endpoint");
                this.endSessionEndpoint = Uri.parse(endSessionString);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return endSessionEndpoint;
    }


    public void ssoSignOut(AuthenticateListener authenticateListener) {
        String idToken = mStateManager.getCurrent().getIdToken();
        String endSessionUriString = getEndSessionEndpoint().toString();
        String signOutRedirectUriString = Config.SIGN_OUT_REDIRECT_URI;
        Uri endSessionFinalUri = Uri.parse(String.format("%s?id_token_hint=%s&post_logout_redirect_uri=%s", endSessionUriString, idToken, signOutRedirectUriString));

        Intent signOutIntent =  warmUpBrowser(endSessionFinalUri).intent;

        authenticateListener.startActivityForResult(signOutIntent, RC_SIGN_OUT);
    }

    public void localSignOut() {
        // discard the authorization and token state, but retain the configuration to save from retrieving it again.
        // Note: this does not clear your browser session for SSO reasons
        AuthState currentState = mStateManager.getCurrent();
        AuthState clearedState =
                new AuthState(currentState.getAuthorizationServiceConfiguration());
        mStateManager.replace(clearedState);
    }


    public interface UserInfoListener {
        void onCompleted(JSONObject result, Exception ex);
    }

    public interface AuthenticateListener {
        void startActivityForResult(Intent intent, int code);
    }
}


