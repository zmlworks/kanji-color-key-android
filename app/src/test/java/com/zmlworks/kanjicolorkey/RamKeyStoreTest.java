package com.zmlworks.kanjicolorkey;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RamKeyStoreTest {
    @Before public void clear() { RamKeyStore.get().clear(); }

    @Test public void holdsTypes_andClearWipes() {
        RamKeyStore s = RamKeyStore.get();
        s.setMasterPassword("secret");
        s.setCompositeHex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        s.setKdbx(new byte[]{1, 2, 3}, "t.kdbx", null);
        List<RamKeyStore.RestoreBlock> blocks = new ArrayList<>();
        RamKeyStore.RestoreBlock b = new RamKeyStore.RestoreBlock();
        b.ch = "あ";
        b.colors = new int[]{0, 1, 2};
        blocks.add(b);
        s.setRestoreDraft(blocks, 256, 16, true, 1);
        assertTrue(s.hasMasterPassword());
        assertTrue(s.hasComposite());
        assertTrue(s.hasKdbx());
        assertTrue(s.hasRestoreDraft());
        assertTrue(s.statusSummary().contains("暗証"));
        assertEquals(16, s.restoreCheckBits());
        s.clear();
        assertFalse(s.hasMasterPassword());
        assertFalse(s.hasComposite());
        assertFalse(s.hasKdbx());
        assertFalse(s.hasRestoreDraft());
        assertEquals("", s.statusSummary());
    }

    @Test public void passwordVisibility_attachesWithoutCrash() {
        android.widget.EditText e = new android.widget.EditText(org.robolectric.RuntimeEnvironment.getApplication());
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        PasswordVisibility.attach(e);
        PasswordVisibility.attach(e); // idempotent
        assertNotNull(e.getCompoundDrawablesRelative()[2]);
    }
}
