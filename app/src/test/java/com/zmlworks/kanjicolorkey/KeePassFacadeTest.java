package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import app.keemobile.kotpass.database.Credentials;
import app.keemobile.kotpass.database.KeePassDatabase;

/**
 * Fixture-based cases use machine-local files configured via KCK_KDBX_FIXTURES (see LocalFixtures);
 * they are skipped when it is not set. Nothing about those files is in the repo.
 */
public class KeePassFacadeTest {
    @Test public void localFixtures_emergencyKeyOpens() throws Exception {
        List<LocalFixtures.Fx> fixtures = LocalFixtures.load();
        assumeTrue("local kdbx fixtures configured (KCK_KDBX_FIXTURES)", !fixtures.isEmpty());
        for (LocalFixtures.Fx fx : fixtures) {
            byte[] db = fx.dbBytes();
            byte[] composite = KeePassFacade.makeEmergencyKey(db, fx.password, fx.keyfileBytes());
            assertEquals(32, composite.length);
            KeePassFacade.OpenResult r = KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(composite));
            if (fx.cipher != null) assertEquals(fx.name, fx.cipher, r.getCipherName());
            assertEquals(fx.name, fx.readOnly, r.getReadOnly());
            assertTrue(fx.name, KeePassFacade.listEntries(r.getDatabase()).size() >= 1);
            Arrays.fill(composite, (byte) 0);
        }
    }

    @Test public void create_roundtrip_aes_argon2id() throws Exception {
        KeePassFacade.OpenResult created = KeePassFacade.createDatabase(
                "TestDB", "test-pass-only", null, KeePassFacade.CipherChoice.AES,
                8L * 1024 * 1024, 2, 2); // smaller memory for unit test speed
        byte[] bytes = KeePassFacade.encodeVerified(created.getDatabase());
        byte[] composite = created.getCompositeKey().clone();
        KeePassFacade.OpenResult again = KeePassFacade.openBytes(bytes, KeePassFacade.credentialsFromComposite(composite));
        assertFalse(again.getReadOnly());
        KeePassDatabase edited = KeePassFacade.addEntry(again.getDatabase(), "t", "u", "p", "http://x", "n");
        byte[] saved = KeePassFacade.encodeVerified(edited);
        KeePassFacade.OpenResult third = KeePassFacade.openBytes(saved, KeePassFacade.credentialsFromComposite(composite));
        List<KeePassFacade.EntrySummary> entries = KeePassFacade.listEntries(third.getDatabase());
        assertEquals(1, entries.size());
        assertEquals("t", entries.get(0).getTitle());
        assertEquals("p", entries.get(0).getPassword());
        Arrays.fill(composite, (byte) 0);
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(saved, (byte) 0);
    }

    @Test public void create_chacha_roundtrip() throws Exception {
        KeePassFacade.OpenResult created = KeePassFacade.createDatabase(
                "Cha", "pw", null, KeePassFacade.CipherChoice.CHACHA20, 8L * 1024 * 1024, 2, 2);
        assertEquals("ChaCha20", created.getCipherName());
        byte[] bytes = KeePassFacade.encodeVerified(created.getDatabase());
        KeePassFacade.openBytes(bytes, KeePassFacade.credentialsFromComposite(created.getCompositeKey()));
    }

    @Test public void edit_localFixtureCopy_stillOpens() throws Exception {
        LocalFixtures.Fx fx = null;
        for (LocalFixtures.Fx f : LocalFixtures.load()) if (!f.readOnly) { fx = f; break; }
        assumeTrue("writable local kdbx fixture configured", fx != null);
        byte[] db = fx.dbBytes();
        byte[] kf = fx.keyfileBytes();
        Credentials creds = KeePassFacade.credentialsFromPassword(fx.password, kf);
        KeePassFacade.OpenResult r = KeePassFacade.openBytes(db, creds);
        KeePassDatabase edited = KeePassFacade.addEntry(r.getDatabase(), "from-test", "user", "secret", "", "");
        assertFalse(KeePassFacade.isTwofish(edited));
        byte[] out = KeePassFacade.encodeVerified(edited);
        KeePassFacade.OpenResult again = KeePassFacade.openBytes(out, creds);
        boolean found = false;
        for (KeePassFacade.EntrySummary e : KeePassFacade.listEntries(again.getDatabase())) {
            if ("from-test".equals(e.getTitle())) found = true;
        }
        assertTrue(found);
        byte[] composite = KeePassFacade.compositeKey(fx.password, kf);
        KeePassFacade.openBytes(out, KeePassFacade.credentialsFromComposite(composite));
        Arrays.fill(composite, (byte) 0);
        Arrays.fill(out, (byte) 0);
    }

    @Test public void dump_for_keepassxc_crosscheck() throws Exception {
        java.io.File out = new java.io.File("/tmp/kck-kotpass-roundtrip.kdbx");
        KeePassFacade.OpenResult created = KeePassFacade.createDatabase(
                "CrossCheck", "cross-pass", null, KeePassFacade.CipherChoice.AES, 8L * 1024 * 1024, 2, 2);
        KeePassDatabase with = KeePassFacade.addEntry(created.getDatabase(), "e1", "u1", "p1", "https://example.com", "n1");
        byte[] bytes = KeePassFacade.encodeVerified(with);
        Files.write(out.toPath(), bytes);
        assertTrue(out.length() > 100);
    }
}
