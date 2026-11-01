package de.harmssi.thorconquer;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two games, their native library, data folder and data checks.
 *
 * Data lives in the app's external files dir (no extra permissions needed):
 *   /sdcard/Android/data/de.harmssi.thorconquer/files/redalert
 *   /sdcard/Android/data/de.harmssi.thorconquer/files/tiberiandawn   (+ gdi/, nod/)
 * Config and save games: .../files/config/{vanillara,vanillatd}
 *
 * A requirement may list alternatives separated by '|', e.g. "GENERAL.MIX|gdi/GENERAL.MIX".
 * Matching is case-insensitive (like the engine); empty files count as missing.
 */
public enum Game {
    RED_ALERT("ra", R.string.ra_title, R.string.ra_sub, "vanillara", "redalert",
            new String[]{"REDALERT.MIX", "MAIN.MIX|allied/MAIN.MIX|soviet/MAIN.MIX"},
            new String[]{"EXPAND.MIX", "EXPAND2.MIX", "HIRES1.MIX", "LORES1.MIX"},
            new Content[]{
                    new Content(R.string.ra_allied, "MAIN.MIX|allied/MAIN.MIX|soviet/MAIN.MIX", true),
                    new Content(R.string.ra_soviet, "MAIN.MIX|allied/MAIN.MIX|soviet/MAIN.MIX", true),
                    new Content(R.string.ra_cs, "counterstrike/MAIN.MIX", true),
                    new Content(R.string.ra_am, "aftermath/MAIN.MIX", true),
            }),
    TIBERIAN_DAWN("td", R.string.td_title, R.string.td_sub, "vanillatd", "tiberiandawn",
            new String[]{"CONQUER.MIX", "GENERAL.MIX|gdi/GENERAL.MIX|nod/GENERAL.MIX", "CCLOCAL.MIX",
                    "TEMPERAT.MIX", "DESERT.MIX", "WINTER.MIX", "SOUNDS.MIX"},
            new String[]{"SPEECH.MIX", "SCORES.MIX", "MOVIES.MIX|gdi/MOVIES.MIX|nod/MOVIES.MIX", "TRANSIT.MIX",
                    "UPDATE.MIX", "UPDATEC.MIX", "UPDATA.MIX", "TEMPICNH.MIX", "DESEICNH.MIX", "WINTICNH.MIX",
                    "ZOUNDS.MIX", "LOCAL.MIX"},
            new Content[]{
                    new Content(R.string.td_gdi, "GENERAL.MIX|gdi/GENERAL.MIX", true),
                    new Content(R.string.td_nod, "nod/GENERAL.MIX", true),
                    new Content(R.string.td_covert, "", false),
            });

    /** A campaign / expansion and the file that indicates it is available. */
    public static final class Content {
        public final int label;
        public final String file;
        public final boolean supported;

        Content(int label, String file, boolean supported) {
            this.label = label;
            this.file = file;
            this.supported = supported;
        }
    }

    public final String id;
    public final int title;
    public final int subtitle;
    /** Native library name without "lib"/".so" (see redalert/ and tiberiandawn/CMakeLists.txt). */
    public final String nativeLib;
    public final String dataFolder;
    public final String[] requiredFiles;
    public final String[] optionalFiles;
    public final Content[] content;

    Game(String id, int title, int subtitle, String nativeLib, String dataFolder, String[] required,
         String[] optional, Content[] content) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.nativeLib = nativeLib;
        this.dataFolder = dataFolder;
        this.requiredFiles = required;
        this.optionalFiles = optional;
        this.content = content;
    }

    public static Game fromId(String id) {
        if (id != null) {
            for (Game g : values()) {
                if (g.id.equalsIgnoreCase(id) || g.name().equalsIgnoreCase(id)) {
                    return g;
                }
            }
        }
        return null;
    }

    public static File baseDir(Context ctx) {
        File f = ctx.getExternalFilesDir(null);
        return f != null ? f : ctx.getFilesDir();
    }

    public File dataDir(Context ctx) {
        return new File(baseDir(ctx), dataFolder);
    }

    public static File configDir(Context ctx) {
        return new File(baseDir(ctx), "config");
    }

    /** Case-insensitive lookup of a relative path below the data folder. */
    private static File find(File dir, String rel) {
        File cur = dir;
        for (String part : rel.split("/")) {
            File[] list = cur.listFiles();
            if (list == null) {
                return null;
            }
            File next = null;
            for (File f : list) {
                if (f.getName().toUpperCase(Locale.ROOT).equals(part.toUpperCase(Locale.ROOT))) {
                    next = f;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            cur = next;
        }
        return cur;
    }

    /** True if any of the '|'-separated alternatives exists and is not empty. */
    public boolean has(Context ctx, String spec) {
        if (spec == null || spec.isEmpty()) {
            return false;
        }
        File dir = dataDir(ctx);
        for (String alt : spec.split("\\|")) {
            File f = find(dir, alt);
            if (f != null && f.isFile() && f.length() > 0) {
                return true;
            }
        }
        return false;
    }

    /** Display name of a requirement: the first alternative. */
    public static String display(String spec) {
        int i = spec.indexOf('|');
        return i < 0 ? spec : spec.substring(0, i);
    }

    public List<String> missingRequired(Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String r : requiredFiles) {
            if (!has(ctx, r)) {
                missing.add(display(r));
            }
        }
        return missing;
    }

    public List<String> missingOptional(Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String r : optionalFiles) {
            if (!has(ctx, r)) {
                missing.add(display(r));
            }
        }
        return missing;
    }

    public void ensureDirs(Context ctx) {
        dataDir(ctx).mkdirs();
        new File(configDir(ctx), nativeLib).mkdirs();
    }
}
