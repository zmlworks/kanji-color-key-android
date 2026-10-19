package com.zmlworks.kanjicolorkey;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Optional, machine-local KeePass fixtures for end-to-end tests. Nothing about them lives in the repo:
 * set the environment variable (or system property) {@code KCK_KDBX_FIXTURES} to a UTF-8 .properties file
 * <pre>
 * fixtures = a, b
 * a.db = /abs/path/to/file.kdbx
 * a.password = …
 * a.keyfile = /abs/path/to/keyfile      (optional)
 * a.cipher = AES-256                    (optional expectation)
 * a.readonly = true                     (optional expectation)
 * </pre>
 * Tests that need fixtures are skipped when it is not set.
 */
final class LocalFixtures {
    static final class Fx {
        final String name, password, cipher;
        final File db, keyfile;
        final boolean readOnly;
        Fx(String name, File db, String password, File keyfile, String cipher, boolean readOnly) {
            this.name = name; this.db = db; this.password = password; this.keyfile = keyfile;
            this.cipher = cipher; this.readOnly = readOnly;
        }
        byte[] dbBytes() throws Exception { return Files.readAllBytes(db.toPath()); }
        byte[] keyfileBytes() throws Exception { return keyfile == null ? null : Files.readAllBytes(keyfile.toPath()); }
    }

    private LocalFixtures() {}

    static List<Fx> load() {
        String path = System.getProperty("KCK_KDBX_FIXTURES");
        if (path == null || path.isEmpty()) path = System.getenv("KCK_KDBX_FIXTURES");
        if (path == null || path.isEmpty() || !new File(path).isFile()) return Collections.emptyList();
        Properties p = new Properties();
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        List<Fx> out = new ArrayList<>();
        for (String n : p.getProperty("fixtures", "").split(",")) {
            n = n.trim();
            if (n.isEmpty() || p.getProperty(n + ".db") == null) continue;
            String kf = p.getProperty(n + ".keyfile");
            out.add(new Fx(n, new File(p.getProperty(n + ".db")), p.getProperty(n + ".password", ""),
                    kf == null || kf.isEmpty() ? null : new File(kf), p.getProperty(n + ".cipher"),
                    Boolean.parseBoolean(p.getProperty(n + ".readonly", "false"))));
        }
        return out;
    }

    static boolean present() { return !load().isEmpty(); }
}
