// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads which Bloodborne trophies are unlocked from the native port's per-user trophy save XML.
 *  shadPS4 keeps a &lt;trophyconf&gt; document per user whose &lt;trophy&gt; nodes carry
 *  unlockstate="true" once awarded (libraries/np/np_trophy.cpp, ReadUnlockedTrophies). The file lives
 *  under the game's save folder, so this walks that folder for the document(s) and unions the unlocked
 *  ids — a locked config template contributes none, the user's save carries the unlocks. All work is on
 *  the app's own data, so no permission or root is needed; no file yet just means an empty set. */
final class TrophyProgress {
    private static final Pattern TROPHY_TAG = Pattern.compile("<trophy\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*\"(\\d+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNLOCK_ATTR = Pattern.compile("\\bunlockstate\\s*=\\s*\"(?:true|1)\"", Pattern.CASE_INSENSITIVE);

    private final File saves;
    private List<File> files;      // the trophyconf xml(s), located lazily
    private long stamp = Long.MIN_VALUE;
    private Set<Integer> cache = Collections.emptySet();

    TrophyProgress(File saves) { this.saves = saves; }

    /** The set of unlocked trophy ids, re-parsed only when a trophy file changes. Never null. */
    synchronized Set<Integer> unlocked() {
        if (files == null || files.isEmpty() || !allPresent()) {
            files = new ArrayList<>();
            locate(saves, 0, files);
            stamp = Long.MIN_VALUE; // force a re-parse against the fresh list
        }
        if (files.isEmpty()) { cache = Collections.emptySet(); return cache; }
        long max = 0;
        for (File f : files) max = Math.max(max, f.lastModified());
        if (max == stamp) return cache;
        stamp = max;
        Set<Integer> out = new HashSet<>();
        for (File f : files) parseInto(f, out);
        cache = out;
        return cache;
    }

    private boolean allPresent() { for (File f : files) if (!f.isFile()) return false; return true; }

    /** Walks the saves tree (bounded) collecting every *.xml whose root is &lt;trophyconf&gt;. */
    private static void locate(File dir, int depth, List<File> out) {
        if (dir == null || depth > 6) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isFile()) {
                String n = k.getName().toLowerCase();
                if (n.endsWith(".xml") && looksLikeTrophyConf(k)) out.add(k);
            } else if (k.isDirectory()) {
                locate(k, depth + 1, out);
            }
        }
    }

    private static boolean looksLikeTrophyConf(File f) {
        if (f.length() > 4_000_000) return false;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] head = new byte[(int) Math.min(4096, Math.max(1, f.length()))];
            int n = in.read(head);
            if (n <= 0) return false;
            return new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8).toLowerCase().contains("<trophyconf");
        } catch (Exception e) { return false; }
    }

    private static void parseInto(File f, Set<Integer> out) {
        try {
            String xml = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            Matcher tag = TROPHY_TAG.matcher(xml);
            while (tag.find()) {
                String t = tag.group();
                if (!UNLOCK_ATTR.matcher(t).find()) continue;
                Matcher id = ID_ATTR.matcher(t);
                if (id.find()) try { out.add(Integer.parseInt(id.group(1))); } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
    }
}
