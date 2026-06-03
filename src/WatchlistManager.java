package com.overlaybot.anime;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages the user's anime watchlist using SharedPreferences.
 * Stores AniList IDs as a comma-separated string + titles as a parallel list.
 *
 * Format: "1234|Solo Leveling,5678|Jujutsu Kaisen,..."
 */
public class WatchlistManager {

    private static final String PREFS_NAME = "overlaybot_watchlist";
    private static final String KEY_LIST = "watchlist";

    private final SharedPreferences prefs;

    public WatchlistManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * A single watchlist entry: AniList ID + display title.
     */
    public static class WatchlistItem {
        public final int animeId;
        public final String title;

        public WatchlistItem(int animeId, String title) {
            this.animeId = animeId;
            this.title = title;
        }
    }

    // ──────────── Read ────────────

    /** Returns the current watchlist. */
    public List<WatchlistItem> getWatchlist() {
        String raw = prefs.getString(KEY_LIST, "");
        return parseList(raw);
    }

    /** Returns just the AniList IDs in the watchlist. */
    public List<Integer> getWatchlistIds() {
        List<WatchlistItem> items = getWatchlist();
        List<Integer> ids = new ArrayList<>();
        for (WatchlistItem item : items) {
            ids.add(item.animeId);
        }
        return ids;
    }

    /** Check if an anime is in the watchlist. */
    public boolean isInWatchlist(int animeId) {
        return getWatchlistIds().contains(animeId);
    }

    /** Returns true if the watchlist is empty (meaning: alert for ALL shows). */
    public boolean isEmpty() {
        return getWatchlist().isEmpty();
    }

    // ──────────── Write ────────────

    /** Add an anime to the watchlist. */
    public void add(int animeId, String title) {
        List<WatchlistItem> items = new ArrayList<>(getWatchlist());
        // Check duplicate
        for (WatchlistItem item : items) {
            if (item.animeId == animeId) return; // Already in list
        }
        items.add(new WatchlistItem(animeId, title));
        saveList(items);
    }

    /** Remove an anime from the watchlist by AniList ID. */
    public void remove(int animeId) {
        List<WatchlistItem> items = new ArrayList<>(getWatchlist());
        List<WatchlistItem> filtered = new ArrayList<>();
        for (WatchlistItem item : items) {
            if (item.animeId != animeId) {
                filtered.add(item);
            }
        }
        saveList(filtered);
    }

    /** Clear the entire watchlist. */
    public void clear() {
        prefs.edit().remove(KEY_LIST).apply();
    }

    // ──────────── Internal ────────────

    private void saveList(List<WatchlistItem> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(items.get(i).animeId).append("|").append(items.get(i).title);
        }
        prefs.edit().putString(KEY_LIST, sb.toString()).apply();
    }

    private List<WatchlistItem> parseList(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<WatchlistItem> items = new ArrayList<>();
        String[] parts = raw.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int pipe = part.indexOf('|');
            if (pipe > 0) {
                try {
                    int id = Integer.parseInt(part.substring(0, pipe));
                    String title = part.substring(pipe + 1);
                    items.add(new WatchlistItem(id, title));
                } catch (NumberFormatException ignored) {}
            }
        }
        return items;
    }
}
