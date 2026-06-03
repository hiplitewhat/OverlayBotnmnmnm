package com.overlaybot.anime;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fetches anime data from the AniList GraphQL API.
 *
 * Supports both unauthenticated and authenticated (OAuth) access.
 * Authenticated access allows fetching the user's personal watching list.
 *
 * Endpoint: POST https://graphql.anilist.co
 * Rate limit: ~90 req/min (unauthenticated), higher when authenticated.
 */
public class AnimeFetcher {

    private static final String TAG = "OverlayBot.Fetcher";
    private static final String API_URL = "https://graphql.anilist.co";
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT    = 15000;

    // Auth token — set by AuthManager before calls
    private static String sAuthToken = null;

    /** Set the AniList auth token for authenticated requests */
    public static void setAuthToken(String token) {
        sAuthToken = token;
    }

    /** Get current auth token */
    public static String getAuthToken() {
        return sAuthToken;
    }

    /**
     * Represents a single anime entry from the AniList API.
     */
    public static class AnimeEntry {
        public int id;
        public String title;
        public String titleEnglish;
        public int episodes;
        public String score;
        public String airingDay;
        public String synopsis;
        public String imageUrl;
        public String broadcast;
        public int nextEp;
        public long nextEpAiringAt;
        public String siteUrl;

        public String displayTitle() {
            if (titleEnglish != null && !titleEnglish.isEmpty()) return titleEnglish;
            return title != null ? title : "Unknown";
        }

        public String nextEpisode() {
            if (nextEp > 0) return String.valueOf(nextEp);
            if (episodes > 0) return String.valueOf(episodes);
            return "NEW";
        }

        public String formatCountdown() {
            if (nextEpAiringAt <= 0) return null;
            long now = System.currentTimeMillis() / 1000L;
            long diff = nextEpAiringAt - now;
            if (diff <= 0) return "Airing now!";
            long hours = diff / 3600;
            long mins = (diff % 3600) / 60;
            if (hours > 24) {
                long days = hours / 24;
                return "EP " + nextEp + " in " + days + "d " + (hours % 24) + "h";
            }
            return "EP " + nextEp + " in " + hours + "h " + mins + "m";
        }
    }

    /**
     * Result of fetching the user's AniList watching list.
     */
    public static class UserListResult {
        public String username;
        public int userId;
        public List<AnimeEntry> watching = new ArrayList<AnimeEntry>();
        public String error;
    }

    // ──────────────── Public API ────────────────

    public static List<AnimeEntry> fetchCurrentSeason() {
        String query =
            "query{" +
            "Page(page:1,perPage:25){" +
            "media(season:CURRENT,type:ANIME,sort:POPULARITY_DESC){" +
            "id title{romaji english}episodes averageScore" +
            "nextAiringEpisode{episode airingAt}" +
            "siteUrl coverImage{medium}description" +
            "}}}";
        return fetchGraphQL(query, null);
    }

    public static List<AnimeEntry> fetchTodaySchedule() {
        String query =
            "query{" +
            "Page(page:1,perPage:25){" +
            "media(type:ANIME,status:RELEASING,sort:POPULARITY_DESC){" +
            "id title{romaji english}episodes averageScore" +
            "nextAiringEpisode{episode airingAt}" +
            "siteUrl coverImage{medium}description" +
            "}}}";
        return fetchGraphQL(query, null);
    }

    public static List<AnimeEntry> searchAnime(String queryText) {
        String query =
            "query($search:String){" +
            "Page(page:1,perPage:10){" +
            "media(search:$search,type:ANIME,sort:SCORE_DESC){" +
            "id title{romaji english}episodes averageScore" +
            "nextAiringEpisode{episode airingAt}" +
            "siteUrl coverImage{medium}description" +
            "}}}";
        JSONObject variables = new JSONObject();
        try {
            variables.put("search", queryText);
        } catch (Exception e) {
            Log.e(TAG, "JSON var failed: " + e.getMessage());
            return new ArrayList<AnimeEntry>();
        }
        return fetchGraphQL(query, variables);
    }

    public static AnimeEntry fetchRandomAiring() {
        List<AnimeEntry> list = fetchCurrentSeason();
        if (list.isEmpty()) return null;
        int idx = (int) (System.currentTimeMillis() % list.size());
        return list.get(idx);
    }

    /**
     * Fetches the logged-in user's AniList "Watching" list.
     * Requires auth token to be set via setAuthToken().
     * Returns username, user ID, and list of anime entries.
     */
    public static UserListResult fetchUserWatching() {
        UserListResult result = new UserListResult();
        if (sAuthToken == null || sAuthToken.isEmpty()) {
            result.error = "Not logged in";
            return result;
        }

        String query =
            "query{" +
            "Viewer{" +
            "  id name" +
            "  mediaList(type:ANIME,status:CURRENT,sort:UPDATED_TIME_DESC){" +
            "    media{" +
            "      id title{romaji english}episodes averageScore" +
            "      nextAiringEpisode{episode airingAt}" +
            "      siteUrl coverImage{medium}description" +
            "    }" +
            "  }" +
            "}}";

        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            String json = httpPost(API_URL, body.toString());
            if (json == null || json.isEmpty()) {
                result.error = "Empty response";
                return result;
            }

            JSONObject root = new JSONObject(json);

            JSONArray errors = root.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String errMsg = errors.getJSONObject(0).optString("message", "Unknown");
                result.error = errMsg;
                Log.e(TAG, "AniList user fetch error: " + errMsg);
                return result;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                result.error = "No data in response";
                return result;
            }

            JSONObject viewer = data.optJSONObject("Viewer");
            if (viewer == null) {
                result.error = "No Viewer in response — token may be invalid";
                return result;
            }

            result.userId = safeInt(viewer, "id", 0);
            result.username = safeString(viewer, "name");

            JSONArray mediaList = viewer.optJSONArray("mediaList");
            if (mediaList == null) {
                // Empty watching list is OK
                return result;
            }

            for (int i = 0; i < mediaList.length(); i++) {
                try {
                    JSONObject item = mediaList.getJSONObject(i);
                    JSONObject media = item.optJSONObject("media");
                    if (media == null) continue;

                    AnimeEntry entry = parseEntry(media);
                    if (entry != null && entry.id > 0) {
                        result.watching.add(entry);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping user list entry: " + e.getMessage());
                }
            }

            Log.i(TAG, "Fetched " + result.watching.size()
                    + " watching anime for user " + result.username);

        } catch (Exception e) {
            result.error = e.getMessage();
            Log.e(TAG, "Fetch user watching failed: " + e.getMessage());
        }
        return result;
    }

    // ──────────────── Safe JSON helpers ────────────────

    private static String safeString(JSONObject obj, String key) {
        if (obj == null) return null;
        if (!obj.has(key)) return null;
        Object val = obj.opt(key);
        if (val == null || JSONObject.NULL.equals(val)) return null;
        if (val instanceof String) {
            String s = (String) val;
            return s.isEmpty() ? null : s;
        }
        return val.toString();
    }

    private static int safeInt(JSONObject obj, String key, int defVal) {
        if (obj == null) return defVal;
        if (!obj.has(key)) return defVal;
        Object val = obj.opt(key);
        if (val == null || JSONObject.NULL.equals(val)) return defVal;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); }
        catch (Exception e) { return defVal; }
    }

    private static long safeLong(JSONObject obj, String key, long defVal) {
        if (obj == null) return defVal;
        if (!obj.has(key)) return defVal;
        Object val = obj.opt(key);
        if (val == null || JSONObject.NULL.equals(val)) return defVal;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); }
        catch (Exception e) { return defVal; }
    }

    private static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return null;
        String s = html;
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li)>", "\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replaceAll("&amp;", "&");
        s = s.replaceAll("&lt;", "<");
        s = s.replaceAll("&gt;", ">");
        s = s.replaceAll("&quot;", "\"");
        s = s.replaceAll("&#39;", "'");
        s = s.replaceAll("&nbsp;", " ");
        s = s.replaceAll("\n{3,}", "\n\n");
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    // ──────────────── GraphQL Fetch ────────────────

    private static List<AnimeEntry> fetchGraphQL(String query, JSONObject variables) {
        List<AnimeEntry> results = new ArrayList<AnimeEntry>();
        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            if (variables != null) body.put("variables", variables);

            String json = httpPost(API_URL, body.toString());
            if (json == null || json.isEmpty()) {
                Log.w(TAG, "Empty response from AniList");
                return results;
            }

            JSONObject root = new JSONObject(json);

            JSONArray errors = root.optJSONArray("errors");
            if (errors != null && errors.length() > 0) {
                String errMsg = errors.getJSONObject(0).optString("message", "Unknown");
                Log.e(TAG, "AniList GraphQL error: " + errMsg);
                return results;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                Log.w(TAG, "No 'data' in AniList response");
                return results;
            }

            JSONObject page = data.optJSONObject("Page");
            if (page == null) {
                Log.w(TAG, "No 'Page' in AniList response");
                return results;
            }

            JSONArray media = page.optJSONArray("media");
            if (media == null) {
                Log.w(TAG, "No 'media' array in AniList response");
                return results;
            }

            for (int i = 0; i < media.length(); i++) {
                try {
                    JSONObject item = media.getJSONObject(i);
                    AnimeEntry entry = parseEntry(item);
                    if (entry != null && entry.id > 0) {
                        results.add(entry);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed entry [" + i + "]: " + e.getMessage());
                }
            }

            Log.i(TAG, "Fetched " + results.size() + " anime from AniList");

        } catch (Exception e) {
            Log.e(TAG, "AniList fetch failed: " + e.getMessage());
        }
        return results;
    }

    private static AnimeEntry parseEntry(JSONObject item) {
        try {
            AnimeEntry e = new AnimeEntry();

            e.id = safeInt(item, "id", 0);
            if (e.id <= 0) return null;

            e.episodes = safeInt(item, "episodes", 0);

            int scoreVal = safeInt(item, "averageScore", 0);
            if (scoreVal > 0) {
                e.score = String.format("%.1f", scoreVal / 10.0);
            }

            e.siteUrl = safeString(item, "siteUrl");

            JSONObject titleObj = item.optJSONObject("title");
            if (titleObj != null) {
                e.title = safeString(titleObj, "romaji");
                e.titleEnglish = safeString(titleObj, "english");
            }
            if ((e.title == null || e.title.isEmpty())
                    && (e.titleEnglish == null || e.titleEnglish.isEmpty())) {
                Log.w(TAG, "Skipping entry " + e.id + " — no title");
                return null;
            }

            JSONObject cover = item.optJSONObject("coverImage");
            if (cover != null) {
                e.imageUrl = safeString(cover, "medium");
            }

            String rawDesc = safeString(item, "description");
            e.synopsis = stripHtml(rawDesc);

            JSONObject nextAir = item.optJSONObject("nextAiringEpisode");
            if (nextAir != null) {
                e.nextEp = safeInt(nextAir, "episode", 0);
                e.nextEpAiringAt = safeLong(nextAir, "airingAt", 0L);

                String countdown = e.formatCountdown();
                if (countdown != null) {
                    e.broadcast = countdown;
                }

                if (e.nextEpAiringAt > 0) {
                    try {
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.setTimeInMillis(e.nextEpAiringAt * 1000L);
                        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday",
                                         "Thursday", "Friday", "Saturday"};
                        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
                        if (dow >= 1 && dow <= 7) {
                            e.airingDay = days[dow - 1];
                        }
                    } catch (Exception ignored) {}
                }
            }

            return e;

        } catch (Exception ex) {
            Log.e(TAG, "Parse entry failed: " + ex.getMessage());
            return null;
        }
    }

    // ──────────────── HTTP ────────────────

    private static String httpPost(String urlStr, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "OverlayBot/2.0 (AniList)");

            // Add auth header if token is available
            if (sAuthToken != null && !sAuthToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + sAuthToken);
            }

            byte[] bodyBytes = jsonBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();

            if (code == 429) {
                Log.w(TAG, "AniList rate limited — retry later");
                return null;
            }

            if (code == 401) {
                Log.w(TAG, "AniList unauthorized — token may be invalid");
                sAuthToken = null; // Clear bad token
                return null;
            }

            if (code != 200) {
                Log.w(TAG, "HTTP " + code + " from AniList");
                try {
                    BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                    StringBuilder errSb = new StringBuilder();
                    String line;
                    while ((line = errReader.readLine()) != null) errSb.append(line);
                    errReader.close();
                    String errBody = errSb.toString();
                    Log.w(TAG, "Error: " + errBody.substring(0, Math.min(300, errBody.length())));
                } catch (Exception ignored) {}
                return null;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();

        } catch (Exception e) {
            Log.e(TAG, "HTTP POST failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }
}
