package com.overlaybot.anime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * AniList OAuth login via WebView.
 *
 * Two login methods:
 * 1. WebView OAuth — opens AniList auth page, captures access token from redirect
 * 2. Manual paste — user pastes token from anilist.co/settings/developer
 *
 * AniList OAuth implicit flow:
 *   GET https://anilist.co/api/v2/oauth/authorize?client_id=XXX&response_type=token
 *   Redirect: https://anilist.co/api/v2/oauth/redirect#access_token=YYY
 *
 * The app intercepts the redirect URL and extracts the access_token.
 */
public class AniListLoginActivity extends Activity {

    // Default client ID — user should register their own at:
    // https://anilist.co/settings/developer
    // Redirect URI must be set to: overlaybot://anilist-auth
    private static final String DEFAULT_CLIENT_ID = "21011";
    private static final String REDIRECT_URI = "overlaybot://anilist-auth";
    private static final String AUTH_URL =
            "https://anilist.co/api/v2/oauth/authorize?client_id="
            + DEFAULT_CLIENT_ID + "&response_type=token";

    private WebView webView;
    private ProgressBar progressBar;
    private AuthManager auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        auth = new AuthManager(this);

        // If already logged in, show account info
        if (auth.isLoggedIn()) {
            showAccountInfo();
            return;
        }

        showLoginChoice();
    }

    private void showLoginChoice() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("AniList Login");
        builder.setMessage("Choose login method:");

        builder.setPositiveButton("AniList OAuth", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                startWebViewAuth();
            }
        });

        builder.setNeutralButton("Paste Token", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                showManualTokenDialog();
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        builder.show();
    }

    private void startWebViewAuth() {
        // Build layout with WebView + progress bar
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF0D0D1A);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 8));
        progressBar.setMax(100);
        layout.addView(progressBar);

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        layout.addView(webView);

        setContentView(layout);

        // WebView settings
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Check for our redirect URI
                if (url.startsWith(REDIRECT_URI)) {
                    // Extract access_token from fragment
                    String fragment = request.getUrl().getFragment();
                    if (fragment != null && fragment.contains("access_token=")) {
                        String token = extractToken(fragment);
                        if (token != null) {
                            onTokenReceived(token);
                            return true;
                        }
                    }

                    // Also check query params (some flows)
                    String query = request.getUrl().getQuery();
                    if (query != null && query.contains("access_token=")) {
                        String token = extractToken(query);
                        if (token != null) {
                            onTokenReceived(token);
                            return true;
                        }
                    }

                    // Token extraction failed
                    Toast.makeText(AniListLoginActivity.this,
                            "Login failed — try manual token", Toast.LENGTH_LONG).show();
                    finish();
                    return true;
                }

                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    if (newProgress == 100) {
                        progressBar.setVisibility(View.GONE);
                    }
                }
            }
        });

        // Load AniList auth page
        webView.loadUrl(AUTH_URL);
    }

    private String extractToken(String fragmentOrQuery) {
        // Parse "access_token=XXX&token_type=Bearer&expires_in=..."
        String[] pairs = fragmentOrQuery.split("&");
        for (String pair : pairs) {
            if (pair.startsWith("access_token=")) {
                String token = pair.substring("access_token=".length());
                // URL decode if needed
                try {
                    token = java.net.URLDecoder.decode(token, "UTF-8");
                } catch (Exception ignored) {}
                return token;
            }
        }
        return null;
    }

    private void onTokenReceived(String token) {
        auth.saveToken(token);
        Toast.makeText(this, "Logged in to AniList!", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void showManualTokenDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Paste AniList Token");

        // Instructions
        TextView instructions = new TextView(this);
        instructions.setText("1. Go to anilist.co/settings/developer\n"
                + "2. Click \"Create Client\"\n"
                + "3. Or paste an existing token below");
        instructions.setTextSize(13);
        instructions.setTextColor(0xFF9E9E9E);
        instructions.setPadding(40, 20, 40, 10);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Paste token here...");
        input.setTextColor(0xFFF0F0FF);
        input.setHintTextColor(0xFF616161);
        input.setPadding(40, 20, 40, 20);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0xFF0D0D1A);
        container.addView(instructions);
        container.addView(input);

        builder.setView(container);

        builder.setPositiveButton("Save", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                String token = input.getText().toString().trim();
                if (token.isEmpty()) {
                    Toast.makeText(AniListLoginActivity.this,
                            "Token cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                auth.saveToken(token);
                Toast.makeText(AniListLoginActivity.this,
                        "Token saved!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });

        builder.show();
    }

    private void showAccountInfo() {
        String user = auth.getUsername();
        String display = user != null ? user : "AniList User";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("AniList Account");
        builder.setMessage("Logged in as: " + display);

        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                setResult(RESULT_OK);
                finish();
            }
        });

        builder.setNeutralButton("Logout", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                auth.logout();
                Toast.makeText(AniListLoginActivity.this,
                        "Logged out", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            }
        });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface dialog) {
                setResult(RESULT_OK);
                finish();
            }
        });

        builder.show();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
