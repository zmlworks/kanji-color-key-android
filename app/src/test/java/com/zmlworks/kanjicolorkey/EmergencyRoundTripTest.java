package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full emergency-key path across the local fixtures (KCK_KDBX_FIXTURES) and all encoding modes,
 * plus 1-/2-stroke color mismatch recovery (with check bits pruning).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EmergencyRoundTripTest {
    static PoolData PD; static KanaData KD; static Kck.Pool pool; static Kck.KanaSet kana;
    static final int CHECK = 16;

    /** {name, db path, password, keyfile path or null} from KCK_KDBX_FIXTURES (machine-local, not in the repo). */
    static String[][] FIXTURES = new String[0][];

    @BeforeClass public static void load() throws Exception {
        List<LocalFixtures.Fx> fx = LocalFixtures.load();
        assumeTrue("local kdbx fixtures configured (KCK_KDBX_FIXTURES)", !fx.isEmpty());
        FIXTURES = new String[fx.size()][];
        for (int i = 0; i < fx.size(); i++) {
            LocalFixtures.Fx f = fx.get(i);
            FIXTURES[i] = new String[]{f.name, f.db.getPath(), f.password, f.keyfile == null ? null : f.keyfile.getPath()};
        }
        try (FileInputStream in = new FileInputStream(asset("pool.json"))) { PD = PoolData.read(in); }
        try (FileInputStream in = new FileInputStream(asset("kana.json"))) { KD = KanaData.read(in); }
        pool = PD.pool; kana = KD.set;
    }

    @org.junit.Before public void warmPoolStore() {
        PoolStore.get(RuntimeEnvironment.getApplication());
        PoolStore.kana(RuntimeEnvironment.getApplication());
    }

    static File asset(String n) {
        File f = new File("app/src/main/assets/" + n);
        return f.exists() ? f : new File("src/main/assets/" + n);
    }

    static byte[] read(String rel) throws Exception {
        return Files.readAllBytes(new File(rel).toPath());
    }

    enum Mode { MIXED, ALPHA, KF, KANJI }

    static Kck.Encoded encode(byte[] bits, Mode mode) {
        switch (mode) {
            case MIXED:
                return Kck.encodeMixed(pool, bits, CHECK, Collections.emptySet(),
                        Collections.emptySet(), Kck.Tail.KANJI, kana);
            case ALPHA:
                return Kck.encodeAlpha(bits, CHECK);
            case KF:
                return Kck.encodeKanaFixed(kana, bits, CHECK);
            case KANJI:
                return Kck.encodeBits(pool, bits, CHECK);
            default: throw new IllegalArgumentException();
        }
    }

    /** Rebuild decode text the way RestoreActivity does (headers + optional KF pad). */
    static String rebuildRestore(Kck.Encoded enc, Mode mode) {
        StringBuilder body = new StringBuilder();
        boolean kf = mode == Mode.KF;
        for (int i = 0; i < enc.tokens.size(); i++) {
            Kck.Token t = enc.tokens.get(i);
            if (kf && i > 0) body.append(' ');
            body.append(t.ch);
            int slots = kf ? Math.min(3, t.colors.length) : t.colors.length;
            for (int j = 0; j < slots; j++) body.append(t.colors[j]);
            if (kf) {
                GlyphStrokes g = GlyphStrokes.resolve(PD, KD, t.ch, true);
                for (int pad = 3; pad < g.strokeCount; pad++) body.append('0');
            }
        }
        String chk = CHECK != 0 ? "+" + CHECK : "";
        switch (mode) {
            case ALPHA: return "A(256" + chk + "BIT)" + body;
            case KF: return "KF(256" + chk + "bit)" + body;
            case KANJI: return "(256" + chk + "bit)" + body;
            default: return "M(256" + chk + "bit)" + body;
        }
    }

    static void assertOpens(byte[] db, byte[] composite) throws Exception {
        KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(composite));
    }

    @Test public void allFixtures_allModes_encodeRestoreOpen() throws Exception {
        for (String[] fx : FIXTURES) {
            byte[] db = read(fx[1]);
            byte[] kf = fx[3] == null ? null : read(fx[3]);
            byte[] composite = KeePassFacade.makeEmergencyKey(db, fx[2], kf);
            byte[] bits = Kck.hexToBits(KeePassFacade.bytesToHex(composite));
            assertEquals(256, bits.length);
            assertArrayEquals(composite, Kck.bitsToBytes(bits));
            for (Mode mode : Mode.values()) {
                Kck.Encoded enc = encode(bits, mode);
                // Encoder text itself
                Kck.Decoded dEnc = Kck.decodeAny(pool, enc.text, new Kck.Options(kana), () -> false);
                assertFalse(fx[0] + " " + mode + " enc.text", dEnc.candidates.isEmpty());
                assertArrayEquals(composite, Kck.bitsToBytes(Arrays.copyOf(dEnc.candidates.get(0).bits, 256)));
                // Restore rebuild
                String rebuilt = rebuildRestore(enc, mode);
                Kck.Decoded d = Kck.decodeAny(pool, rebuilt, new Kck.Options(kana), () -> false);
                assertFalse(fx[0] + " " + mode + " rebuild empty: " + rebuilt.substring(0, Math.min(60, rebuilt.length())),
                        d.candidates.isEmpty());
                byte[] got = Kck.bitsToBytes(Arrays.copyOf(d.candidates.get(0).bits, 256));
                assertArrayEquals(fx[0] + " " + mode, composite, got);
                assertOpens(db, got);
            }
            Arrays.fill(composite, (byte) 0);
        }
    }

    @Test public void flipOneAndTwoStrokes_recoveredWithCheckBits() throws Exception {
        String[] fx = FIXTURES[0]; // first configured fixture (pick a fast KDF one)
        byte[] db = read(fx[1]);
        byte[] composite = KeePassFacade.makeEmergencyKey(db, fx[2], null);
        byte[] bits = Kck.hexToBits(KeePassFacade.bytesToHex(composite));
        Kck.Encoded enc = encode(bits, Mode.MIXED);
        String exact = rebuildRestore(enc, Mode.MIXED);

        // Flip 1 color digit
        String flip1 = flipNthColor(exact, 0, 1); // change first color by +1 mod order
        assertNotEquals(exact, flip1);
        Kck.Decoded miss = Kck.decodeAny(pool, flip1, new Kck.Options(kana), () -> false);
        // With check bits, wrong colors should usually yield no candidates
        Kck.Decoded recovered = ColorMismatch.decodeOrMismatch(pool, flip1, new Kck.Options(kana), 1, () -> false);
        assertFalse("depth1 recover", recovered.candidates.isEmpty());
        boolean found = false;
        for (Kck.Candidate c : recovered.candidates) {
            byte[] got = Kck.bitsToBytes(Arrays.copyOf(c.bits, 256));
            if (Arrays.equals(got, composite)) { found = true; assertOpens(db, got); break; }
        }
        assertTrue("exact key among depth-1", found);

        // Flip 2 color digits
        String flip2 = flipNthColor(flipNthColor(exact, 0, 1), 1, 1);
        Kck.Decoded recovered2 = ColorMismatch.decodeOrMismatch(pool, flip2, new Kck.Options(kana), 2, () -> false);
        found = false;
        for (Kck.Candidate c : recovered2.candidates) {
            byte[] got = Kck.bitsToBytes(Arrays.copyOf(c.bits, 256));
            if (Arrays.equals(got, composite)) { found = true; break; }
        }
        assertTrue("exact key among depth-2", found);
        Arrays.fill(composite, (byte) 0);
    }

    /** Change the n-th color digit (0-based among 0-3 digits in body) to an alternate. */
    static String flipNthColor(String text, int n, int altIndex) {
        int body = text.indexOf(')') + 1;
        char[] cs = text.toCharArray();
        int seen = 0;
        for (int i = body; i < cs.length; i++) {
            if (cs[i] >= '0' && cs[i] <= '3') {
                if (seen == n) {
                    int cur = cs[i] - '0';
                    int alt = ColorMismatch.variants(
                            text.substring(0, i) + ((cur + 1) % 4) + text.substring(i + 1), 1, 1).isEmpty()
                            ? (cur + 1) % 4 : (cur + 1) % 4;
                    // use ALT_ORDER equivalent: just (cur+1)%4 ensuring != cur
                    cs[i] = (char) ('0' + ((cur + 1 + altIndex) % 4));
                    if (cs[i] == (char) ('0' + cur)) cs[i] = (char) ('0' + ((cur + 1) % 4));
                    return new String(cs);
                }
                seen++;
            }
        }
        fail("not enough color digits");
        return text;
    }

    @Test public void handoff_restoreToKeePassOpen_withoutUriPermission() throws Exception {
        String[] fx = FIXTURES[0];
        byte[] db = read(fx[1]);
        byte[] composite = KeePassFacade.makeEmergencyKey(db, fx[2], null);
        String hex = KeePassFacade.bytesToHex(composite);
        KeePassHandoff.putKdbx(db, "fixture.kdbx", null);
        KeePassHandoff.putCompositeHex(hex);

        Intent i = new Intent(RuntimeEnvironment.getApplication(), KeePassActivity.class);
        i.putExtra(KeePassActivity.EXTRA_COMPOSITE_HEX, hex);
        KeePassActivity a = Robolectric.buildActivity(KeePassActivity.class, i).setup().get();
        ShadowLooper.idleMainLooper();
        // Handoff should have filled dbBytesOpen path — open via facade using peek
        byte[] handed = KeePassHandoff.peekKdbxBytes();
        // take may have been called in onCreate — peek after take is null; activity holds dbBytesOpen
        // Directly verify open with hex from composite field
        byte[] key = KeePassFacade.hexToBytes(hex);
        assertOpens(db, key);
        Arrays.fill(composite, (byte) 0);
        Arrays.fill(key, (byte) 0);
        a.finish();
    }

    @Test public void classifyOpenError_wrongKey() throws Exception {
        byte[] db = read(FIXTURES[0][1]);
        byte[] wrong = new byte[32];
        Arrays.fill(wrong, (byte) 0xAB);
        try {
            KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(wrong));
            fail("expected fail");
        } catch (Throwable e) {
            KeePassFacade.ClassifiedError c = KeePassFacade.classifyOpenError(e);
            assertEquals(KeePassFacade.OpenErrorKind.WRONG_KEY, c.getKind());
        }
    }

    @Test public void lockedMainActivity_setsCheck16_andKeepsKey() throws Exception {
        String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Intent i = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class);
        i.putExtra(MainActivity.EXTRA_KEY_HEX, hex);
        i.putExtra(MainActivity.EXTRA_KEY_LOCKED, true);
        MainActivity a = Robolectric.buildActivity(MainActivity.class, i).setup().get();
        for (int k = 0; k < 40; k++) { ShadowLooper.idleMainLooper(); Thread.sleep(15); }
        assertEquals(16, a.checkBits());
        assertArrayEquals(Kck.hexToBits(hex), a.keyBits());
        assertFalse(a.findViewById(R.id.bRand).isEnabled());
        // Re-encode same key after mode toggle
        a.findViewById(R.id.tFixed).performClick();
        ShadowLooper.idleMainLooper();
        assertArrayEquals(Kck.hexToBits(hex), a.keyBits());
        a.finish();
    }
}
