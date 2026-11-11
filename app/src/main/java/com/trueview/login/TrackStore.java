package com.trueview.login;

import android.content.Context;
import android.location.Location;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Tiny on-phone buffer for GPS points recorded while the web page is asleep.
 * One JSON object per line in a private file. The page calls drain() (through
 * AndroidTracker.drain()) whenever it is awake and uploads the points to
 * Firestore with their ORIGINAL timestamps.
 */
final class TrackStore {
    private static final String FILE = "tv_points.jsonl";
    private static final long MAX_BYTES = 1_500_000L; // safety cap, roughly 15,000 points

    private TrackStore() {}

    static synchronized void add(Context c, Location l) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (f.length() > MAX_BYTES) {
                f.delete(); // drop an unreasonably old backlog instead of growing forever
            }
            long now = System.currentTimeMillis();
            long ts = l.getTime();
            if (ts <= 0 || Math.abs(now - ts) > 10 * 60 * 1000L) {
                ts = now;
            }
            String line = String.format(Locale.US,
                    "{\"lat\":%.6f,\"lng\":%.6f,\"acc\":%.1f,\"speed\":%.2f,\"ts\":%d}\n",
                    l.getLatitude(), l.getLongitude(),
                    l.hasAccuracy() ? l.getAccuracy() : 0f,
                    l.hasSpeed() ? l.getSpeed() : 0f,
                    ts);
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    /** Returns every buffered point as a JSON array string and empties the buffer. */
    static synchronized String drain(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists() || f.length() == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!first) sb.append(',');
                sb.append(line);
                first = false;
            }
        } catch (Exception e) {
            return "[]";
        }
        f.delete();
        return sb.append(']').toString();
    }

    static synchronized void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }
}
