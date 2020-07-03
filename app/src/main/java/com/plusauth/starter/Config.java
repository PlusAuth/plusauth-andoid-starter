package com.plusauth.starter;

import android.net.Uri;

public class Config {
    public final static Uri AUTH_URI = Uri.parse("https://{YOUR-TENANT-NAME}.plusauth.com/");
    public final static String SCOPE = "openid email profile offline_access";
    public final static String CLIENT_ID = "{YOUR-CLIENT-ID}";
    public final static Uri REDIRECT_URI = Uri.parse("com.plusauth.starter:/oauth2redirect");
    public final static String SIGN_OUT_REDIRECT_URI = "com.plusauth.starter:/signout";
}
