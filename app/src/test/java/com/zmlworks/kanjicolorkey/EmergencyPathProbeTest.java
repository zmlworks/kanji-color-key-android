package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EmergencyPathProbeTest {
    static PoolData PD; static KanaData KD; static Kck.Pool pool; static Kck.KanaSet kana;

    @BeforeClass public static void load() throws Exception {
        assumeTrue("local kdbx fixtures configured (KCK_KDBX_FIXTURES)", LocalFixtures.present());
        try (FileInputStream in = new FileInputStream(asset("pool.json"))) { PD = PoolData.read(in); }
        try (FileInputStream in = new FileInputStream(asset("kana.json"))) { KD = KanaData.read(in); }
        pool = PD.pool; kana = KD.set;
    }
    static File asset(String n) {
        File f = new File("app/src/main/assets/" + n);
        return f.exists() ? f : new File("src/main/assets/" + n);
    }

    static byte[] read(String rel) throws Exception {
        return Files.readAllBytes(new File(rel).toPath());
    }

    /** Simulate MainActivity display text → RestoreActivity flick rebuild → open */
    static void roundTrip(String label, byte[] db, String pw, byte[] kf, String mode) throws Exception {
        byte[] composite = KeePassFacade.makeEmergencyKey(db, pw, kf);
        String hex = KeePassFacade.bytesToHex(composite);
        byte[] bits = Kck.hexToBits(hex);
        assertEquals(256, bits.length);
        // hex→bits→bytes must recover composite
        assertArrayEquals(label + " bitsToBytes", composite, Kck.bitsToBytes(bits));
        assertArrayEquals(label + " facade hex", composite, KeePassFacade.hexToBytes(hex));

        Kck.Encoded enc;
        switch (mode) {
            case "kanji":
                enc = Kck.encodeBits(pool, bits, 0);
                break;
            case "mixed":
                enc = Kck.encodeMixed(pool, bits, 0, Collections.emptySet(), Collections.emptySet(), Kck.Tail.KANJI, kana);
                break;
            case "alpha":
                enc = Kck.encodeAlpha(bits, 0);
                break;
            case "kf":
                enc = Kck.encodeKanaFixed(kana, bits, 0);
                break;
            default: throw new IllegalArgumentException(mode);
        }
        // Rebuild like RestoreActivity (no spaces in body for M; KF with pads)
        String rebuilt = rebuildLikeRestore(enc, mode);
        Kck.Decoded d = Kck.decodeAny(pool, rebuilt, new Kck.Options(kana), () -> false);
        assertFalse(label + " " + mode + " no candidates for: " + rebuilt.substring(0, Math.min(80, rebuilt.length())), d.candidates.isEmpty());
        byte[] got = Kck.bitsToBytes(Arrays.copyOf(d.candidates.get(0).bits, 256));
        assertArrayEquals(label + " " + mode + " key", composite, got);
        KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(got));
        // Also open with original encoded text from encoder
        Kck.Decoded d2 = Kck.decodeAny(pool, enc.text, new Kck.Options(kana), () -> false);
        byte[] got2 = Kck.bitsToBytes(Arrays.copyOf(d2.candidates.get(0).bits, 256));
        assertArrayEquals(label + " " + mode + " from enc.text", composite, got2);
        Arrays.fill(composite, (byte) 0);
        Arrays.fill(got, (byte) 0);
    }

    static String rebuildLikeRestore(Kck.Encoded enc, String mode) {
        StringBuilder body = new StringBuilder();
        boolean kf = "kf".equals(mode);
        for (int i = 0; i < enc.tokens.size(); i++) {
            Kck.Token t = enc.tokens.get(i);
            if (kf && i > 0) body.append(' ');
            body.append(t.ch);
            int slots = kf ? 3 : t.colors.length;
            for (int j = 0; j < slots; j++) body.append(t.colors[j]);
            if (kf) {
                GlyphStrokes g = GlyphStrokes.resolve(PD, KD, t.ch, true);
                for (int pad = 3; pad < g.strokeCount; pad++) body.append('0');
            }
        }
        // Match RestoreActivity headers
        if ("alpha".equals(mode)) return "A(256BIT)" + body; // Restore uses BIT uppercase for A!
        if (kf) return "KF(256bit)" + body;
        if ("kanji".equals(mode)) {
            // pure kanji uses formatTokens → header "(" + dataLen + "bit)"
            return "(" + 256 + "bit)" + body;
        }
        return "M(256bit)" + body;
    }

    @Test public void allFixtures_allModes() throws Exception {
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        for (LocalFixtures.Fx f : LocalFixtures.load())
            fixtures.put(f.name, new String[]{f.db.getPath(), f.password, f.keyfile == null ? null : f.keyfile.getPath()});
        String[] modes = {"kanji", "mixed", "kf"};
        for (Map.Entry<String, String[]> e : fixtures.entrySet()) {
            byte[] db = read(e.getValue()[0]);
            byte[] kf = e.getValue()[2] == null ? null : read(e.getValue()[2]);
            for (String mode : modes) {
                try {
                    roundTrip(e.getKey(), db, e.getValue()[1], kf, mode);
                    System.out.println("OK " + e.getKey() + " " + mode);
                } catch (Throwable ex) {
                    System.out.println("FAIL " + e.getKey() + " " + mode + ": " + ex);
                    throw ex;
                }
            }
            // alpha via encode path used by WebModel
            try {
                byte[] composite = KeePassFacade.makeEmergencyKey(db, e.getValue()[1], kf);
                byte[] bits = Kck.hexToBits(KeePassFacade.bytesToHex(composite));
                // use WebModel all-alpha
                java.util.Map<Integer, Kck.Type> types = WebModel.allOf(Kck.Type.ALPHA);
                WebModel.Result r = WebModel.render(pool, kana, bits, 0, types, new WebModel.Enabled(true, true), false);
                Kck.Encoded enc = r.variants.get(0).enc;
                String rebuilt = rebuildLikeRestore(enc, "alpha");
                Kck.Decoded d = Kck.decodeAny(pool, rebuilt, new Kck.Options(kana), () -> false);
                assertFalse("alpha " + e.getKey(), d.candidates.isEmpty());
                byte[] got = Kck.bitsToBytes(Arrays.copyOf(d.candidates.get(0).bits, 256));
                assertArrayEquals(composite, got);
                KeePassFacade.openBytes(db, KeePassFacade.credentialsFromComposite(got));
                System.out.println("OK " + e.getKey() + " alpha");
                Arrays.fill(composite, (byte) 0);
            } catch (Throwable ex) {
                System.out.println("FAIL " + e.getKey() + " alpha: " + ex);
                throw ex;
            }
        }
    }
}
