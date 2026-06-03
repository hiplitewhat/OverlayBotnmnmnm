package com.overlaybot.anime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreground service: fetches real anime data from AniList, filters by
 * watchlist, shows overlay alerts only for tracked anime.
 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayBot.Service";

    public static final String ACTION_START       = "com.overlaybot.anime.START";
    public static final String ACTION_STOP        = "com.overlaybot.anime.STOP";
    public static final String ACTION_SHOW_ALERT  = "com.overlaybot.anime.SHOW_ALERT";
    public static final String ACTION_REFRESH     = "com.overlaybot.anime.REFETCH";
    public static final String ACTION_DISMISS_NOW = "com.overlaybot.anime.DISMISS_NOW";

    private static final String CHANNEL_ID = "overlay_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long AUTO_DISMISS_MS = 8000L;
    private static final long POLL_INTERVAL_MS = 15 * 60 * 1000L;

    private static boolean sRunning = false;
    private static String sLastFetchInfo = "";
    private static List<AnimeFetcher.AnimeEntry> sFetchedAnime = new ArrayList<>();
    private static List<String> sAlertHistory = new ArrayList<>();

    private WindowManager windowManager;
    private View overlayView;
    private Handler handler;
    private BroadcastReceiver alertReceiver;
    private WatchlistManager watchlist;

    private Runnable autoDismissRunnable;

    private final List<AnimeFetcher.AnimeEntry> alertQueue = new ArrayList<>();
    private boolean showing = false;
    private final Set<Integer> alertedIds = new HashSet<>();

    // ──────────── Static accessors for dashboard ────────────

    public static boolean isRunning() { return sRunning; }
    public static String getLastFetchInfo() { return sLastFetchInfo; }

    public static List<AnimeFetcher.AnimeEntry> getFetchedAnime() {
        return Collections.unmodifiableList(sFetchedAnime);
    }

    public static List<String> getAlertHistory() {
        return Collections.unmodifiableList(sAlertHistory);
    }

    // ──────────── Lifecycle ────────────

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        watchlist = new WatchlistManager(this);

        alertReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_SHOW_ALERT.equals(action)) {
                    AnimeFetcher.AnimeEntry entry = new AnimeFetcher.AnimeEntry();
                    entry.id = intent.getIntExtra("anime_id", 0);
                    entry.title = intent.getStringExtra("title");
                    entry.titleEnglish = intent.getStringExtra("title");
                    entry.episodes = intent.getIntExtra("ep_number", 0);
                    entry.nextEp = intent.getIntExtra("next_ep", 0);
                    entry.score = intent.getStringExtra("score");
                    entry.broadcast = intent.getStringExtra("broadcast");
                    enqueueAlert(entry);
                } else if (ACTION_STOP.equals(action)) {
                    forceRemoveOverlay();
                    stopPolling();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                    sRunning = false;
                } else if (ACTION_REFRESH.equals(action)) {
                    fetchSchedule();
                } else if (ACTION_DISMISS_NOW.equals(action)) {
                    forceRemoveOverlay();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_ALERT);
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_REFRESH);
        filter.addAction(ACTION_DISMISS_NOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alertReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(alertReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                createNotificationChannel();
                startForeground(NOTIFICATION_ID, buildNotification());
                sRunning = true;
                fetchSchedule();
                startPolling();
            } else if (ACTION_STOP.equals(action)) {
                forceRemoveOverlay();
                stopPolling();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                sRunning = false;
            } else if (ACTION_REFRESH.equals(action)) {
                fetchSchedule();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPolling();
        forceRemoveOverlay();
        if (alertReceiver != null) {
            unregisterReceiver(alertReceiver);
            alertReceiver = null;
        }
        sRunning = false;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────── AniList API Fetch ────────────

    private void fetchSchedule() {
        sLastFetchInfo = "Fetching AniList\u2026";
        // Re-read watchlist in case user updated it
        watchlist = new WatchlistManager(this);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // First try currently releasing (broadest set)
                    final List<AnimeFetcher.AnimeEntry> releasing =
                            AnimeFetcher.fetchTodaySchedule();

                    if (releasing.isEmpty()) {
                        // Fallback: current season
                        final List<AnimeFetcher.AnimeEntry> season =
                                AnimeFetcher.fetchCurrentSeason();
                        handler.post(new Runnable() {
                            @Override public void run() { processFetchedData(season, "season"); }
                        });
                    } else {
                        handler.post(new Runnable() {
                            @Override public void run() { processFetchedData(releasing, "releasing"); }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "AniList fetch error: " + e.getMessage());
                    handler.post(new Runnable() {
                        @Override public void run() {
                            sLastFetchInfo = "AniList fetch failed \u2014 will retry";
                        }
                    });
                }
            }
        }).start();
    }

    private void processFetchedData(List<AnimeFetcher.AnimeEntry> entries, String source) {
        if (entries.isEmpty()) {
            sLastFetchInfo = "No data from AniList API";
            return;
        }

        sFetchedAnime = new ArrayList<>(entries);

        // Get watchlist IDs — if empty, alert for ALL shows
        List<Integer> watchlistIds = watchlist.getWatchlistIds();
        boolean filterByWatchlist = !watchlistIds.isEmpty();

        int totalAiring = 0;
        int newCount = 0;
        int skippedCount = 0;

        for (AnimeFetcher.AnimeEntry entry : entries) {
            // Filter: only alert for watchlisted anime (or all if list is empty)
            if (filterByWatchlist && !watchlistIds.contains(entry.id)) {
                skippedCount++;
                continue;
            }

            totalAiring++;

            if (!alertedIds.contains(entry.id)) {
                alertedIds.add(entry.id);
                enqueueAlert(entry);
                newCount++;
            }
        }

        if (filterByWatchlist) {
            sLastFetchInfo = entries.size() + " fetched (AniList), " + totalAiring
                    + " tracked (" + watchlistIds.size() + " in list), "
                    + newCount + " new alerts";
        } else {
            sLastFetchInfo = entries.size() + " shows (" + source + "), "
                    + newCount + " new alerts \u2014 add anime to filter!";
        }

        Log.i(TAG, sLastFetchInfo);
    }

    // ──────────── Polling ────────────

    private Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (sRunning) {
                fetchSchedule();
                handler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private void startPolling() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollRunnable);
    }

    // ──────────── Alert Queue ────────────

    private void enqueueAlert(AnimeFetcher.AnimeEntry entry) {
        alertQueue.add(entry);
        sAlertHistory.add(0, entry.displayTitle() + " EP " + entry.nextEpisode());
        if (sAlertHistory.size() > 50) sAlertHistory.remove(sAlertHistory.size() - 1);
        showNextAlert();
    }

    private void showNextAlert() {
        if (showing) return;
        if (alertQueue.isEmpty()) return;
        AnimeFetcher.AnimeEntry entry = alertQueue.remove(0);
        showNewEpAlert(entry);
    }

    // ──────────── Overlay ────────────

    private void showNewEpAlert(AnimeFetcher.AnimeEntry entry) {
        forceRemoveOverlayImmediate();
        showing = true;

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_alert, null);

        TextView animeView  = (TextView) overlayView.findViewById(R.id.anime_title);
        TextView epNumView  = (TextView) overlayView.findViewById(R.id.ep_number);
        TextView bodyView   = (TextView) overlayView.findViewById(R.id.alert_body);
        TextView tagAiringV = (TextView) overlayView.findViewById(R.id.tag_airing);
        TextView tagSubbedV = (TextView) overlayView.findViewById(R.id.tag_subbed);
        TextView tagScoreV  = (TextView) overlayView.findViewById(R.id.tag_score);
        View dismissBtn     = overlayView.findViewById(R.id.btn_dismiss);
        View watchBtn       = overlayView.findViewById(R.id.btn_watch);
        View laterBtn       = overlayView.findViewById(R.id.btn_later);

        String displayTitle = entry.displayTitle();
        if (animeView != null)  animeView.setText(displayTitle);
        if (epNumView != null)  epNumView.setText("EP " + entry.nextEpisode());

        // Build body text: countdown + synopsis
        String body = "";
        // AniList provides countdown info
        String countdown = entry.formatCountdown();
        if (countdown != null) {
            body = countdown;
        } else if (entry.broadcast != null && !entry.broadcast.isEmpty()) {
            body = entry.broadcast;
        }
        if (entry.synopsis != null && !entry.synopsis.isEmpty()) {
            if (!body.isEmpty()) body += "\n";
            String syn = entry.synopsis.length() > 80
                    ? entry.synopsis.substring(0, 80) + "\u2026" : entry.synopsis;
            body += syn;
        }
        if (body.isEmpty()) body = "Now airing!";
        if (bodyView != null) bodyView.setText(body);

        if (tagAiringV != null) tagAiringV.setText("AIRING");
        if (tagSubbedV != null) tagSubbedV.setText("SUB");
        if (tagScoreV != null && entry.score != null) {
            tagScoreV.setText("\u2605 " + entry.score);
            tagScoreV.setVisibility(View.VISIBLE);
        }

        if (dismissBtn != null) dismissBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });
        if (laterBtn != null) laterBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });
        if (watchBtn != null) watchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismissOverlay(); }
        });

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = 100;

        windowManager.addView(overlayView, params);
        animateEntrance();

        autoDismissRunnable = new Runnable() {
            @Override public void run() { dismissOverlay(); }
        };
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS);
    }

    private void dismissOverlay() {
        if (autoDismissRunnable != null) {
            handler.removeCallbacks(autoDismissRunnable);
            autoDismissRunnable = null;
        }
        if (overlayView != null) {
            try {
                overlayView.animate()
                        .alpha(0f).scaleX(0.5f).scaleY(0.5f).translationY(-100f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                try { if (overlayView != null) windowManager.removeView(overlayView); }
                                catch (Exception ignored) {}
                                overlayView = null;
                                showing = false;
                                showNextAlert();
                            }
                        }).start();
            } catch (Exception e) {
                forceRemoveOverlayImmediate();
                showing = false;
                showNextAlert();
            }
        } else {
            showing = false;
            showNextAlert();
        }
    }

    private void forceRemoveOverlay() {
        if (autoDismissRunnable != null) { handler.removeCallbacks(autoDismissRunnable); autoDismissRunnable = null; }
        if (overlayView != null) { try { windowManager.removeView(overlayView); } catch (Exception ignored) {} overlayView = null; }
        showing = false;
        showNextAlert();
    }

    private void forceRemoveOverlayImmediate() {
        if (autoDismissRunnable != null) { handler.removeCallbacks(autoDismissRunnable); autoDismissRunnable = null; }
        if (overlayView != null) { try { windowManager.removeView(overlayView); } catch (Exception ignored) {} overlayView = null; }
        showing = false;
    }

    private void animateEntrance() {
        if (overlayView == null) return;
        overlayView.setAlpha(0f);
        overlayView.setScaleX(0.6f);
        overlayView.setScaleY(0.6f);
        overlayView.setTranslationY(-120f);
        overlayView.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f))
                .start();
    }

    // ──────────── Notification ────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_desc));
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_media_play)
               .setContentTitle(getString(R.string.app_name))
               .setContentText("Tracking your anime\u2026")
               .setOngoing(true);
        return builder.build();
    }
}
