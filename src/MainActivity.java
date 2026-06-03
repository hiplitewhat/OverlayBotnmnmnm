package com.overlaybot.anime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Dashboard activity with: AniList login, search anime, add to watchlist,
 * view/remove watchlist, import from AniList, tracking controls, alert history.
 */
public class MainActivity extends Activity {

    private static final int OVERLAY_PERMISSION_REQUEST = 1001;
    private static final int LOGIN_REQUEST = 2001;

    private TextView statusBadge;
    private TextView statShows;
    private TextView statWatching;
    private TextView statAlerts;
    private TextView fetchInfo;
    private EditText searchInput;
    private TextView searchResults;
    private TextView watchlistDisplay;
    private TextView alertHistory;
    private Button btnSearch;
    private Button btnLogin;
    private Handler uiHandler;
    private WatchlistManager watchlist;
    private AuthManager auth;

    private List<AnimeFetcher.AnimeEntry> lastSearchResults;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusBadge     = (TextView) findViewById(R.id.status_badge);
        statShows       = (TextView) findViewById(R.id.stat_shows);
        statWatching    = (TextView) findViewById(R.id.stat_watching);
        statAlerts      = (TextView) findViewById(R.id.stat_alerts);
        fetchInfo       = (TextView) findViewById(R.id.fetch_info);
        searchInput     = (EditText) findViewById(R.id.search_input);
        searchResults   = (TextView) findViewById(R.id.search_results);
        watchlistDisplay = (TextView) findViewById(R.id.watchlist_display);
        alertHistory    = (TextView) findViewById(R.id.alert_history);
        btnSearch       = (Button) findViewById(R.id.btn_search);
        btnLogin        = (Button) findViewById(R.id.btn_login);
        uiHandler       = new Handler(Looper.getMainLooper());
        watchlist       = new WatchlistManager(this);
        auth            = new AuthManager(this);

        // Set auth token for fetcher
        if (auth.isLoggedIn()) {
            AnimeFetcher.setAuthToken(auth.getToken());
        }

        // Control buttons
        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!canDrawOverlays()) { requestOverlayPermission(); return; }
                startOverlayService();
            }
        });

        findViewById(R.id.btn_stop).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopOverlayService(); }
        });

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (OverlayService.isRunning()) {
                    sendServiceAction(OverlayService.ACTION_REFRESH);
                    Toast.makeText(MainActivity.this, "Refreshing\u2026", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(MainActivity.this, "Start tracking first", Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.btn_dismiss).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendServiceAction(OverlayService.ACTION_DISMISS_NOW);
                Toast.makeText(MainActivity.this, "Overlay closed", Toast.LENGTH_SHORT).show();
            }
        });

        // Search button
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch(); }
        });

        // Login button
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (auth.isLoggedIn()) {
                    showAccountMenu();
                } else {
                    startLogin();
                }
            }
        });

        refreshWatchlistDisplay();
        updateLoginButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
        startDashboardUpdates();
        updateLoginButton();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopDashboardUpdates();
    }

    // ──────────── AniList Login ────────────

    private void startLogin() {
        Intent intent = new Intent(this, AniListLoginActivity.class);
        startActivityForResult(intent, LOGIN_REQUEST);
    }

    private void updateLoginButton() {
        if (auth.isLoggedIn()) {
            String name = auth.getUsername();
            btnLogin.setText(name != null ? name : "ANILIST");
            btnLogin.setBackgroundColor(getColor(R.color.alert_green));
            btnLogin.setTextColor(getColor(R.color.black));
        } else {
            btnLogin.setText("LOGIN");
            btnLogin.setBackgroundColor(getColor(R.color.sakura_pink));
            btnLogin.setTextColor(getColor(R.color.black));
        }
    }

    private void showAccountMenu() {
        String name = auth.getUsername();
        String display = name != null ? name : "AniList User";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("AniList: " + display);

        String[] options = {"Import My Watching List", "Logout", "Cancel"};
        builder.setItems(options, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    importFromAniList();
                } else if (which == 1) {
                    auth.logout();
                    AnimeFetcher.setAuthToken(null);
                    updateLoginButton();
                    Toast.makeText(MainActivity.this,
                            "Logged out", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.show();
    }

    private void importFromAniList() {
        if (!auth.isLoggedIn()) {
            Toast.makeText(this, "Login first", Toast.LENGTH_SHORT).show();
            return;
        }

        AnimeFetcher.setAuthToken(auth.getToken());
        btnLogin.setText("Importing\u2026");
        btnLogin.setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                final AnimeFetcher.UserListResult result =
                        AnimeFetcher.fetchUserWatching();

                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        btnLogin.setEnabled(true);
                        updateLoginButton();

                        if (result.error != null) {
                            Toast.makeText(MainActivity.this,
                                    "Error: " + result.error, Toast.LENGTH_LONG).show();
                            return;
                        }

                        if (result.username != null) {
                            auth.saveUsername(result.username);
                            auth.saveUserId(result.userId);
                        }

                        if (result.watching.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "Your AniList watching list is empty", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // Add all to watchlist
                        int added = 0;
                        int skipped = 0;
                        for (AnimeFetcher.AnimeEntry entry : result.watching) {
                            if (!watchlist.isInWatchlist(entry.id)) {
                                watchlist.add(entry.id, entry.displayTitle());
                                added++;
                            } else {
                                skipped++;
                            }
                        }

                        refreshWatchlistDisplay();
                        updateLoginButton();

                        String msg = "Imported " + added + " anime";
                        if (skipped > 0) msg += " (" + skipped + " already in list)";
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();

                        // Refresh service if running
                        if (OverlayService.isRunning()) {
                            sendServiceAction(OverlayService.ACTION_REFRESH);
                        }
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (canDrawOverlays()) startOverlayService();
            else Toast.makeText(this, "Overlay permission required!", Toast.LENGTH_LONG).show();
        }

        if (requestCode == LOGIN_REQUEST && resultCode == RESULT_OK) {
            // Re-read auth state
            auth = new AuthManager(this);
            if (auth.isLoggedIn()) {
                AnimeFetcher.setAuthToken(auth.getToken());
                updateLoginButton();
                Toast.makeText(this, "Logged in to AniList!", Toast.LENGTH_SHORT).show();

                // Auto-import watching list
                importFromAniList();
            }
        }
    }

    // ──────────── Search ────────────

    private void doSearch() {
        final String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            Toast.makeText(this, "Type an anime name", Toast.LENGTH_SHORT).show();
            return;
        }

        searchResults.setText("Searching \"" + query + "\" on AniList\u2026");
        btnSearch.setEnabled(false);

        new Thread(new Runnable() {
            @Override public void run() {
                final List<AnimeFetcher.AnimeEntry> results =
                        AnimeFetcher.searchAnime(query);

                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        btnSearch.setEnabled(true);
                        lastSearchResults = results;

                        if (results.isEmpty()) {
                            searchResults.setText("No results for \"" + query + "\" on AniList");
                            return;
                        }

                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < results.size(); i++) {
                            AnimeFetcher.AnimeEntry e = results.get(i);
                            boolean inList = watchlist.isInWatchlist(e.id);
                            sb.append((i + 1)).append(". ")
                              .append(e.displayTitle());
                            if (e.score != null) sb.append(" \u2605").append(e.score);
                            if (e.nextEp > 0) sb.append(" EP").append(e.nextEp);
                            if (inList) sb.append(" [IN LIST]");
                            sb.append("\n   Tap to add\n");
                        }
                        searchResults.setText(sb.toString());

                        searchResults.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                showSearchResultPicker();
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private void showSearchResultPicker() {
        if (lastSearchResults == null || lastSearchResults.isEmpty()) return;

        String[] names = new String[lastSearchResults.size()];
        for (int i = 0; i < lastSearchResults.size(); i++) {
            AnimeFetcher.AnimeEntry e = lastSearchResults.get(i);
            names[i] = e.displayTitle() + (e.score != null ? " \u2605" + e.score : "");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add to watchlist (AniList)");
        builder.setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                AnimeFetcher.AnimeEntry e = lastSearchResults.get(which);
                watchlist.add(e.id, e.displayTitle());
                refreshWatchlistDisplay();
                Toast.makeText(MainActivity.this,
                        "Added: " + e.displayTitle(), Toast.LENGTH_SHORT).show();

                if (OverlayService.isRunning()) {
                    sendServiceAction(OverlayService.ACTION_REFRESH);
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────── Watchlist Display ────────────

    private void refreshWatchlistDisplay() {
        watchlist = new WatchlistManager(this);
        final List<WatchlistManager.WatchlistItem> items = watchlist.getWatchlist();

        if (items.isEmpty()) {
            watchlistDisplay.setText("Empty \u2014 search and add anime above");
            watchlistDisplay.setAlpha(0.5f);
            watchlistDisplay.setOnClickListener(null);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            WatchlistManager.WatchlistItem item = items.get(i);
            sb.append((i + 1)).append(". ").append(item.title)
              .append("  [AL:").append(item.animeId).append("]\n");
        }
        watchlistDisplay.setText(sb.toString());
        watchlistDisplay.setAlpha(0.85f);

        watchlistDisplay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showRemoveDialog();
            }
        });
    }

    private void showRemoveDialog() {
        final List<WatchlistManager.WatchlistItem> items = watchlist.getWatchlist();
        if (items.isEmpty()) return;

        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = items.get(i).title;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Remove from watchlist");
        builder.setItems(names, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                WatchlistManager.WatchlistItem item = items.get(which);
                watchlist.remove(item.animeId);
                refreshWatchlistDisplay();
                Toast.makeText(MainActivity.this,
                        "Removed: " + item.title, Toast.LENGTH_SHORT).show();

                if (OverlayService.isRunning()) {
                    sendServiceAction(OverlayService.ACTION_REFRESH);
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // ──────────── Dashboard Refresh ────────────

    private Runnable dashboardUpdater = new Runnable() {
        @Override public void run() {
            refreshDashboard();
            uiHandler.postDelayed(this, 2000);
        }
    };

    private void startDashboardUpdates() {
        uiHandler.removeCallbacks(dashboardUpdater);
        uiHandler.post(dashboardUpdater);
    }

    private void stopDashboardUpdates() {
        uiHandler.removeCallbacks(dashboardUpdater);
    }

    private void refreshDashboard() {
        boolean running = OverlayService.isRunning();

        if (running) {
            statusBadge.setText("LIVE");
            statusBadge.setBackgroundColor(getColor(R.color.alert_green));
            statusBadge.setTextColor(getColor(R.color.black));
        } else {
            statusBadge.setText("OFF");
            statusBadge.setBackgroundColor(getColor(R.color.alert_red));
            statusBadge.setTextColor(getColor(R.color.white_text));
        }

        List<AnimeFetcher.AnimeEntry> fetched = OverlayService.getFetchedAnime();
        List<String> history = OverlayService.getAlertHistory();
        int watchlistSize = watchlist.getWatchlist().size();

        statShows.setText(String.valueOf(fetched.size()));
        statWatching.setText(String.valueOf(watchlistSize));
        statAlerts.setText(String.valueOf(history.size()));

        String info = OverlayService.getLastFetchInfo();
        if (info != null && !info.isEmpty()) fetchInfo.setText(info);
    }

    // ──────────── Service Control ────────────

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "Tracking started!", Toast.LENGTH_SHORT).show();
    }

    private void stopOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(OverlayService.ACTION_STOP);
        startService(intent);
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show();
    }
}
