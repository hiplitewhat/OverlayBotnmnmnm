package com.overlaybot.anime;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages AniList OAuth token storage.
 * Stores the access token in SharedPreferences.
 */
public class AuthManager {

    private static final String PREFS_NAME = "overlaybot_auth";
    private static final String KEY_TOKEN = "anilist_token";
    private static final String KEY_USERNAME = "anilist_username";
    private static final String KEY_USER_ID = "anilist_user_id";

    private final SharedPreferences prefs;

    public AuthManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Save AniList access token */
    public void saveToken(String token) {
        prefs.edit().putString(KEY_TOKEN, token).apply();
    }

    /** Get stored AniList access token, null if not logged in */
    public String getToken() {
        String t = prefs.getString(KEY_TOKEN, null);
        if (t != null && t.isEmpty()) return null;
        return t;
    }

    /** Is the user logged in to AniList? */
    public boolean isLoggedIn() {
        return getToken() != null;
    }

    /** Save AniList username */
    public void saveUsername(String name) {
        prefs.edit().putString(KEY_USERNAME, name).apply();
    }

    /** Get stored AniList username, null if not set */
    public String getUsername() {
        String n = prefs.getString(KEY_USERNAME, null);
        if (n != null && n.isEmpty()) return null;
        return n;
    }

    /** Save AniList user ID */
    public void saveUserId(int id) {
        prefs.edit().putInt(KEY_USER_ID, id).apply();
    }

    /** Get stored AniList user ID, 0 if not set */
    public int getUserId() {
        return prefs.getInt(KEY_USER_ID, 0);
    }

    /** Log out — clear all auth data */
    public void logout() {
        prefs.edit().clear().apply();
    }
}
