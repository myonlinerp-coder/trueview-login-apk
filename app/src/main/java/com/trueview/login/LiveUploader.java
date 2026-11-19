package com.trueview.login;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends the agent's live location straight to Firestore (REST API) from the foreground service,
 * so the Admin's Track Location map keeps moving even when the screen is locked and the web page
 * is asleep. It writes the SAME document the web page writes (trips/{tripId}/live/current).
 *
 * - The web page hands over the Firebase project id, API key, trip id and the agent's (anonymous)
 *   refresh token through AndroidTracker.configureLive(); nothing is hard-coded here.
 * - Only runs while the app is NOT on screen (the page already publishes while it is awake).
 * - Throttled to one write every MIN_GAP_MS to keep Firestore writes low
 *   (20 s = about 180 writes per hour per agent, only while the screen is off).
 * - Route points are NOT written here: they stay buffered in TrackStore and upload with their
 *   original timestamps when the page wakes up, exactly as before.
 */
final class LiveUploader {
    private static final String PREFS = "tv_live";
    private static final long MIN_GAP_MS = 20000L;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static volatile boolean busy = false;
    private static long lastUploadMs = 0;
    private static String idToken = "";
    private static long idTokenExpMs = 0;

    private LiveUploader() {}

    static void configure(Context c, String projectId, String apiKey, String tripId, String refreshToken) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pid", projectId == null ? "" : projectId)
                .putString("key", apiKey == null ? "" : apiKey)
                .putString("trip", tripId == null ? "" : tripId)
                .putString("rt", refreshToken == null ? "" : refreshToken)
                .apply();
        synchronized (LiveUploader.class) {
            idToken = "";
            idTokenExpMs = 0;
            lastUploadMs = 0;
        }
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        synchronized (LiveUploader.class) {
            idToken = "";
            idTokenExpMs = 0;
        }
    }

    /** Called from TrackingService for every GPS fix while the app is in the background. */
    static void maybeUpload(final Context ctx, final Location loc) {
        if (loc == null) return;
        long now = System.currentTimeMillis();
        synchronized (LiveUploader.class) {
            if (busy || now - lastUploadMs < MIN_GAP_MS) return;
            busy = true;
            lastUploadMs = now;
        }
        final Context app = ctx.getApplicationContext();
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    upload(app, loc);
                } catch (Exception ignored) {
                } finally {
                    synchronized (LiveUploader.class) {
                        busy = false;
                    }
                }
            }
        });
    }

    private static void upload(Context c, Location loc) throws Exception {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String pid = sp.getString("pid", "");
        String key = sp.getString("key", "");
        String trip = sp.getString("trip", "");
        String rt = sp.getString("rt", "");
        if (pid.isEmpty() || key.isEmpty() || trip.isEmpty()) return;

        String token = freshIdToken(sp, key, rt);

        long now = System.currentTimeMillis();
        long fixMs = loc.getTime();
        if (fixMs <= 0 || Math.abs(now - fixMs) > 10 * 60 * 1000L) fixMs = now;

        JSONObject fields = new JSONObject();
        fields.put("lat", new JSONObject().put("doubleValue", loc.getLatitude()));
        fields.put("lng", new JSONObject().put("doubleValue", loc.getLongitude()));
        fields.put("speed", new JSONObject().put("doubleValue", loc.hasSpeed() ? loc.getSpeed() : 0.0));
        fields.put("ts", new JSONObject().put("timestampValue", iso(fixMs)));
        fields.put("stAt", new JSONObject().put("timestampValue", iso(now)));
        fields.put("app", new JSONObject().put("stringValue", "hidden"));   // screen locked / app in background
        fields.put("net", new JSONObject().put("stringValue", "online"));
        fields.put("nat", new JSONObject().put("booleanValue", true));
        fields.put("ver", new JSONObject().put("stringValue", "svc"));
        int bat = batteryPercent(c);
        if (bat >= 0) fields.put("bat", new JSONObject().put("integerValue", String.valueOf(bat)));

        StringBuilder url = new StringBuilder("https://firestore.googleapis.com/v1/projects/")
                .append(URLEncoder.encode(pid, "UTF-8"))
                .append("/databases/(default)/documents/trips/")
                .append(URLEncoder.encode(trip, "UTF-8"))
                .append("/live/current?key=").append(URLEncoder.encode(key, "UTF-8"));
        String[] names = {"lat", "lng", "speed", "ts", "stAt", "app", "net", "nat", "ver", "bat"};
        for (String n : names) {
            if (fields.has(n)) url.append("&updateMask.fieldPaths=").append(n);   // merge, never wipes other fields
        }

        HttpURLConnection h = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            h.setConnectTimeout(10000);
            h.setReadTimeout(10000);
            h.setRequestMethod("POST");                         // HttpURLConnection has no PATCH:
            h.setRequestProperty("X-HTTP-Method-Override", "PATCH");   // Google APIs accept this override
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (token != null && !token.isEmpty()) h.setRequestProperty("Authorization", "Bearer " + token);
            h.setDoOutput(true);
            byte[] body = new JSONObject().put("fields", fields).toString().getBytes("UTF-8");
            OutputStream os = h.getOutputStream();
            os.write(body);
            os.close();
            int code = h.getResponseCode();
            if (code == 401 || code == 403) {
                synchronized (LiveUploader.class) {
                    idToken = "";       // force a fresh token next time
                    idTokenExpMs = 0;
                }
            }
        } finally {
            h.disconnect();
        }
    }

    /** Exchanges the anonymous user's refresh token for a short-lived ID token (cached ~55 min). */
    private static String freshIdToken(SharedPreferences sp, String apiKey, String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) return "";
        long now = System.currentTimeMillis();
        synchronized (LiveUploader.class) {
            if (!idToken.isEmpty() && now < idTokenExpMs - 60000L) return idToken;
        }
        try {
            URL u = new URL("https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(apiKey, "UTF-8"));
            HttpURLConnection h = (HttpURLConnection) u.openConnection();
            try {
                h.setConnectTimeout(10000);
                h.setReadTimeout(10000);
                h.setRequestMethod("POST");
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                h.setDoOutput(true);
                byte[] b = ("grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")).getBytes("UTF-8");
                OutputStream os = h.getOutputStream();
                os.write(b);
                os.close();
                if (h.getResponseCode() != 200) return "";
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(h.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject j = new JSONObject(sb.toString());
                String tok = j.optString("id_token", "");
                long secs = Long.parseLong(j.optString("expires_in", "3600"));
                String newRt = j.optString("refresh_token", "");
                if (!newRt.isEmpty()) sp.edit().putString("rt", newRt).apply();
                synchronized (LiveUploader.class) {
                    idToken = tok;
                    idTokenExpMs = now + secs * 1000L;
                }
                return tok;
            } finally {
                h.disconnect();
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private static int batteryPercent(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(level * 100f / scale);
        } catch (Exception e) {
            return -1;
        }
    }
}
