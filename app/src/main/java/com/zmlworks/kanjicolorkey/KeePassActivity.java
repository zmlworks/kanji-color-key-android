package com.zmlworks.kanjicolorkey;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.zmlworks.kanjicolorkey.keepass.KeePassFacade;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.keemobile.kotpass.database.Credentials;
import app.keemobile.kotpass.database.KeePassDatabase;

/**
 * KeePass emergency-key / open / create UI. No network. Key material stays in memory.
 */
public class KeePassActivity extends android.app.Activity {
    public static final String EXTRA_COMPOSITE_HEX = "composite_hex";

    private static final int REQ_DB_EM = 1, REQ_KEY_EM = 2, REQ_DB_OPEN = 3, REQ_KEY_OPEN = 4,
            REQ_KEY_CREATE = 5, REQ_CREATE_DOC = 6, REQ_SAVE_DOC = 7;

    private Settings settings;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView status, dbNameEm, keyNameEm, dbNameOpen, keyNameOpen, keyNameCreate, browseInfo;
    private EditText passwordEm, passwordOpen, passwordCreate, compositeHex, compositeEncoded, createName;
    private LinearLayout panelEmergency, panelOpen, panelCreate, panelBrowse, entryList;
    private Button bShowKanji, tabBrowse, bAddEntry, bSaveDb;
    private RadioGroup openMode;
    private RadioButton modePassword, modeComposite, cipherAes;

    private byte[] dbBytesEm, keyBytesEm, dbBytesOpen, keyBytesOpen, keyBytesCreate;
    private Uri dbUriEm, dbUriOpen, createUri, openUri;
    private String createDisplayName = "database.kdbx";

    private KeePassDatabase openDb;
    private byte[] openComposite;
    private boolean readOnly;
    private byte[] lastEmergencyComposite;
    private String pendingCreatePassword;
    private byte[] pendingCreateKeyfile;
    private KeePassFacade.CipherChoice pendingCipher = KeePassFacade.CipherChoice.AES;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        String t = new Settings(base).theme();
        if (Settings.THEME_LIGHT.equals(t))
            base.getResources().getConfiguration().uiMode =
                    (base.getResources().getConfiguration().uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        else if (Settings.THEME_DARK.equals(t))
            base.getResources().getConfiguration().uiMode =
                    (base.getResources().getConfiguration().uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        applySecureFlags();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_keepass);

        status = findViewById(R.id.status);
        panelEmergency = findViewById(R.id.panelEmergency);
        panelOpen = findViewById(R.id.panelOpen);
        panelCreate = findViewById(R.id.panelCreate);
        panelBrowse = findViewById(R.id.panelBrowse);
        dbNameEm = findViewById(R.id.dbNameEm);
        keyNameEm = findViewById(R.id.keyNameEm);
        dbNameOpen = findViewById(R.id.dbNameOpen);
        keyNameOpen = findViewById(R.id.keyNameOpen);
        keyNameCreate = findViewById(R.id.keyNameCreate);
        passwordEm = findViewById(R.id.passwordEm);
        passwordOpen = findViewById(R.id.passwordOpen);
        passwordCreate = findViewById(R.id.passwordCreate);
        compositeHex = findViewById(R.id.compositeHex);
        compositeEncoded = findViewById(R.id.compositeEncoded);
        createName = findViewById(R.id.createName);
        browseInfo = findViewById(R.id.browseInfo);
        entryList = findViewById(R.id.entryList);
        bShowKanji = findViewById(R.id.bShowKanji);
        tabBrowse = findViewById(R.id.tabBrowse);
        bAddEntry = findViewById(R.id.bAddEntry);
        bSaveDb = findViewById(R.id.bSaveDb);
        openMode = findViewById(R.id.openMode);
        modePassword = findViewById(R.id.modePassword);
        modeComposite = findViewById(R.id.modeComposite);
        cipherAes = findViewById(R.id.cipherAes);

        dbNameEm.setText(R.string.kp_file_none);
        keyNameEm.setText(R.string.kp_file_none);
        dbNameOpen.setText(R.string.kp_file_none);
        keyNameOpen.setText(R.string.kp_file_none);
        keyNameCreate.setText(R.string.kp_file_none);
        createName.setText("Passwords");

        findViewById(R.id.bBack).setOnClickListener(v -> finish());
        findViewById(R.id.tabEmergency).setOnClickListener(v -> showPanel(0));
        findViewById(R.id.tabOpen).setOnClickListener(v -> showPanel(1));
        findViewById(R.id.tabCreate).setOnClickListener(v -> showPanel(2));
        tabBrowse.setOnClickListener(v -> { if (openDb != null) showPanel(3); });

        findViewById(R.id.bPickDbEm).setOnClickListener(v -> pick(REQ_DB_EM, "*/*"));
        findViewById(R.id.bPickKeyEm).setOnClickListener(v -> pick(REQ_KEY_EM, "*/*"));
        findViewById(R.id.bMakeEm).setOnClickListener(v -> makeEmergency());
        bShowKanji.setOnClickListener(v -> showAsKanji(lastEmergencyComposite));

        findViewById(R.id.bPickDbOpen).setOnClickListener(v -> pick(REQ_DB_OPEN, "*/*"));
        findViewById(R.id.bPickKeyOpen).setOnClickListener(v -> pick(REQ_KEY_OPEN, "*/*"));
        findViewById(R.id.bOpen).setOnClickListener(v -> openDb());
        findViewById(R.id.bRestorePaper).setOnClickListener(v -> {
            Intent i = new Intent(this, RestoreActivity.class);
            i.putExtra(RestoreActivity.EXTRA_FROM_KEEPASS, true);
            if (dbUriOpen != null) i.putExtra(RestoreActivity.EXTRA_KDBX_URI, dbUriOpen.toString());
            else if (dbUriEm != null) i.putExtra(RestoreActivity.EXTRA_KDBX_URI, dbUriEm.toString());
            startActivity(i);
        });
        openMode.setOnCheckedChangeListener((g, id) -> updateOpenModeUi());

        findViewById(R.id.bPickKeyCreate).setOnClickListener(v -> pick(REQ_KEY_CREATE, "*/*"));
        findViewById(R.id.bCreate).setOnClickListener(v -> createDb());

        bAddEntry.setOnClickListener(v -> editEntry(null));
        bSaveDb.setOnClickListener(v -> saveOpenDb());

        String prefill = getIntent().getStringExtra(EXTRA_COMPOSITE_HEX);
        if (prefill != null && !prefill.isEmpty()) {
            showPanel(1);
            modeComposite.setChecked(true);
            compositeHex.setText(prefill);
            updateOpenModeUi();
        } else showPanel(0);
    }

    private void applySecureFlags() {
        if (settings.blockScreenshots())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (settings.keepScreenOn())
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showPanel(int which) {
        panelEmergency.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        panelOpen.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        panelCreate.setVisibility(which == 2 ? View.VISIBLE : View.GONE);
        panelBrowse.setVisibility(which == 3 ? View.VISIBLE : View.GONE);
        if (which == 3) refreshEntries();
    }

    private void updateOpenModeUi() {
        boolean pw = modePassword.isChecked();
        passwordOpen.setVisibility(pw ? View.VISIBLE : View.GONE);
        findViewById(R.id.bPickKeyOpen).setVisibility(pw ? View.VISIBLE : View.GONE);
        keyNameOpen.setVisibility(pw ? View.VISIBLE : View.GONE);
        compositeHex.setVisibility(pw ? View.GONE : View.VISIBLE);
        compositeEncoded.setVisibility(pw ? View.GONE : View.VISIBLE);
    }

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        startActivityForResult(i, req);
    }

    private void createDocument(String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/x-keepass");
        i.putExtra(Intent.EXTRA_TITLE, name.endsWith(".kdbx") ? name : name + ".kdbx");
        startActivityForResult(i, REQ_CREATE_DOC);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            switch (requestCode) {
                case REQ_DB_EM:
                    dbUriEm = uri; dbBytesEm = readAll(uri);
                    dbNameEm.setText(displayName(uri));
                    break;
                case REQ_KEY_EM:
                    keyBytesEm = readAll(uri); keyNameEm.setText(displayName(uri));
                    break;
                case REQ_DB_OPEN:
                    openUri = uri; dbUriOpen = uri; dbBytesOpen = readAll(uri);
                    dbNameOpen.setText(displayName(uri));
                    break;
                case REQ_KEY_OPEN:
                    keyBytesOpen = readAll(uri); keyNameOpen.setText(displayName(uri));
                    break;
                case REQ_KEY_CREATE:
                    keyBytesCreate = readAll(uri); keyNameCreate.setText(displayName(uri));
                    break;
                case REQ_CREATE_DOC:
                    createUri = uri;
                    finishCreate(uri);
                    break;
                case REQ_SAVE_DOC:
                    writeVerified(uri, openDb);
                    break;
            }
        } catch (Exception e) {
            status.setText(getString(R.string.kp_err_generic, e.getMessage()));
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Cannot open");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment() != null ? uri.getLastPathSegment() : uri.toString();
    }

    private void setBusy(boolean busy) {
        status.setText(busy ? R.string.kp_working : android.R.string.ok);
        if (!busy && status.getText().toString().equals(getString(android.R.string.ok))) status.setText("");
    }

    private void makeEmergency() {
        if (dbBytesEm == null) { status.setText(R.string.kp_err_no_db); return; }
        final String pw = passwordEm.getText().toString();
        final byte[] kf = keyBytesEm;
        final byte[] db = dbBytesEm;
        setBusy(true);
        exec.execute(() -> {
            try {
                byte[] composite = KeePassFacade.makeEmergencyKey(db, pw, kf);
                main.post(() -> {
                    setBusy(false);
                    wipe(lastEmergencyComposite);
                    lastEmergencyComposite = composite;
                    bShowKanji.setEnabled(true);
                    status.setText(R.string.kp_ok_emergency);
                });

            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.kp_err_open, e.getMessage()));
                });
            }
        });
    }

    private void showAsKanji(byte[] composite) {
        if (composite == null || composite.length != 32) return;
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_KEY_HEX, KeePassFacade.bytesToHex(composite));
        i.putExtra(MainActivity.EXTRA_KEY_LOCKED, true);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private void openDb() {
        if (dbBytesOpen == null) { status.setText(R.string.kp_err_no_db); return; }
        final byte[] db = dbBytesOpen;
        final boolean usePw = modePassword.isChecked();
        final String pw = passwordOpen.getText().toString();
        final byte[] kf = keyBytesOpen;
        final String hex = compositeHex.getText().toString();
        final String encoded = compositeEncoded.getText().toString();
        setBusy(true);
        exec.execute(() -> {
            try {
                Credentials creds;
                if (usePw) {
                    creds = KeePassFacade.credentialsFromPassword(pw, kf);
                } else {
                    byte[] composite;
                    String h = hex.replaceAll("\\s+", "");
                    if (h.length() == 64) composite = KeePassFacade.hexToBytes(h);
                    else if (encoded != null && !encoded.trim().isEmpty()) {
                        // Decode M(...)/A(...)/KF(...) via Kck to 256-bit key bits, then to bytes
                        composite = decodeToKeyBytes(encoded.trim());
                    } else throw new IllegalArgumentException("Need 64 hex or encoded string");
                    creds = KeePassFacade.credentialsFromComposite(composite);
                    Arrays.fill(composite, (byte) 0);
                }
                KeePassFacade.OpenResult r = KeePassFacade.openBytes(db, creds);
                main.post(() -> {
                    setBusy(false);
                    adoptOpen(r);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.kp_err_open, e.getMessage()));
                });
            }
        });
    }

    private byte[] decodeToKeyBytes(String text) throws Exception {
        Kck.Pool pool = PoolStore.get(this).pool;
        Kck.Options opts = new Kck.Options(PoolStore.kana(this).set);
        Kck.Decoded d = Kck.decodeAny(pool, text, opts, null);
        if (d.candidates.isEmpty()) throw new IllegalArgumentException("No decode candidates");
        byte[] bits = d.candidates.get(0).bits;
        if (bits.length < 256) throw new IllegalArgumentException("Need 256-bit key, got " + bits.length);
        // Take the first 256 data bits as the composite key bytes
        byte[] first = java.util.Arrays.copyOf(bits, 256);
        return Kck.bitsToBytes(first);
    }

    private void adoptOpen(KeePassFacade.OpenResult r) {
        closeOpen();
        openDb = r.getDatabase();
        openComposite = r.getCompositeKey();
        readOnly = r.getReadOnly();
        tabBrowse.setEnabled(true);
        bAddEntry.setEnabled(!readOnly);
        bSaveDb.setEnabled(!readOnly && openUri != null);
        int n = KeePassFacade.listEntries(openDb).size();
        String msg = getString(R.string.kp_ok_opened, r.getCipherName(), r.getKdfName(), n);
        if (readOnly) msg = msg + "\n" + getString(R.string.kp_readonly_twofish);
        status.setText(msg);
        showPanel(3);
    }

    private void refreshEntries() {
        entryList.removeAllViews();
        if (openDb == null) return;
        List<KeePassFacade.EntrySummary> list = KeePassFacade.listEntries(openDb);
        browseInfo.setText(list.isEmpty() ? getString(R.string.kp_no_entries)
                : list.size() + " entries" + (readOnly ? " (read-only)" : ""));
        for (KeePassFacade.EntrySummary e : list) {
            entryList.addView(entryCard(e));
        }
    }

    private View entryCard(KeePassFacade.EntrySummary e) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(8, 8, 8, 8);
        box.setBackgroundResource(R.drawable.variant_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        box.setLayoutParams(lp);

        TextView head = new TextView(this);
        head.setText((e.getGroupPath().isEmpty() ? "" : e.getGroupPath() + " / ") + e.getTitle());
        head.setTextColor(getColor(R.color.text));
        head.setTextSize(15);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(head);

        if (!e.getUserName().isEmpty()) {
            TextView u = new TextView(this);
            u.setText(e.getUserName());
            u.setTextColor(getColor(R.color.dim));
            box.addView(u);
        }
        if (!e.getUrl().isEmpty()) {
            TextView u = new TextView(this);
            u.setText(e.getUrl());
            u.setTextColor(getColor(R.color.dim));
            box.addView(u);
        }
        if (!e.getNotes().isEmpty()) {
            TextView u = new TextView(this);
            u.setText(e.getNotes());
            u.setTextColor(getColor(R.color.dim));
            box.addView(u);
        }

        TextView pwView = new TextView(this);
        pwView.setText("••••••••");
        pwView.setTextColor(getColor(R.color.text));
        pwView.setTypeface(android.graphics.Typeface.MONOSPACE);
        box.addView(pwView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button reveal = new Button(this);
        reveal.setAllCaps(false);
        reveal.setText(R.string.kp_reveal);
        final boolean[] shown = {false};
        reveal.setOnClickListener(v -> {
            shown[0] = !shown[0];
            pwView.setText(shown[0] ? e.getPassword() : "••••••••");
            reveal.setText(shown[0] ? R.string.kp_hide : R.string.kp_reveal);
        });
        row.addView(reveal);
        Button copy = new Button(this);
        copy.setAllCaps(false);
        copy.setText(R.string.kp_copy_password);
        copy.setOnClickListener(v -> {
            SecureClip.copy(this, "password", e.getPassword(), settings.clipClear());
            Toast.makeText(this, settings.clipClear() ? R.string.copied_clear : R.string.copied, Toast.LENGTH_SHORT).show();
        });
        row.addView(copy);
        if (!readOnly) {
            Button edit = new Button(this);
            edit.setAllCaps(false);
            edit.setText(R.string.kp_edit);
            edit.setOnClickListener(v -> editEntry(e));
            row.addView(edit);
            Button del = new Button(this);
            del.setAllCaps(false);
            del.setText(R.string.kp_delete);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage(R.string.kp_confirm_delete)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        openDb = KeePassFacade.deleteEntry(openDb, e.getUuid());
                        refreshEntries();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            row.addView(del);
        }
        box.addView(row);
        return box;
    }

    private void editEntry(KeePassFacade.EntrySummary existing) {
        if (openDb == null || readOnly) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 16, 24, 8);
        EditText tTitle = field(form, R.string.kp_entry_title, existing != null ? existing.getTitle() : "");
        EditText tUser = field(form, R.string.kp_entry_user, existing != null ? existing.getUserName() : "");
        EditText tPass = field(form, R.string.kp_entry_pass, existing != null ? existing.getPassword() : "");
        tPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText tUrl = field(form, R.string.kp_entry_url, existing != null ? existing.getUrl() : "");
        EditText tNotes = field(form, R.string.kp_entry_notes, existing != null ? existing.getNotes() : "");
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.kp_add_entry : R.string.kp_edit)
                .setView(form)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (existing == null) {
                        openDb = KeePassFacade.addEntry(openDb,
                                tTitle.getText().toString(), tUser.getText().toString(),
                                tPass.getText().toString(), tUrl.getText().toString(),
                                tNotes.getText().toString());
                    } else {
                        openDb = KeePassFacade.updateEntry(openDb, existing.getUuid(),
                                tTitle.getText().toString(), tUser.getText().toString(),
                                tPass.getText().toString(), tUrl.getText().toString(),
                                tNotes.getText().toString());
                    }
                    refreshEntries();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText field(LinearLayout form, int label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        form.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSaveEnabled(false);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(e);
        return e;
    }

    private void saveOpenDb() {
        if (openDb == null || readOnly) return;
        if (openUri == null) {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/x-keepass");
            i.putExtra(Intent.EXTRA_TITLE, "database.kdbx");
            startActivityForResult(i, REQ_SAVE_DOC);
            return;
        }
        writeVerified(openUri, openDb);
    }

    private void writeVerified(Uri uri, KeePassDatabase db) {
        setBusy(true);
        exec.execute(() -> {
            try {
                byte[] bytes = KeePassFacade.encodeVerified(db);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot write");
                    out.write(bytes);
                    out.flush();
                }
                Arrays.fill(bytes, (byte) 0);
                main.post(() -> {
                    setBusy(false);
                    status.setText(R.string.kp_ok_saved);
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.kp_err_generic, e.getMessage()));
                });
            }
        });
    }

    private void createDb() {
        pendingCreatePassword = passwordCreate.getText().toString();
        pendingCreateKeyfile = keyBytesCreate;
        pendingCipher = cipherAes.isChecked() ? KeePassFacade.CipherChoice.AES : KeePassFacade.CipherChoice.CHACHA20;
        createDisplayName = createName.getText().toString().trim();
        if (createDisplayName.isEmpty()) createDisplayName = "Passwords";
        createDocument(createDisplayName);
    }

    private void finishCreate(Uri uri) {
        final String name = createDisplayName;
        final String pw = pendingCreatePassword != null ? pendingCreatePassword : "";
        final byte[] kf = pendingCreateKeyfile;
        final KeePassFacade.CipherChoice cipher = pendingCipher;
        setBusy(true);
        exec.execute(() -> {
            try {
                KeePassFacade.OpenResult created = KeePassFacade.createDatabase(name, pw, kf, cipher);
                byte[] bytes = KeePassFacade.encodeVerified(created.getDatabase());
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot write");
                    out.write(bytes);
                    out.flush();
                }
                Arrays.fill(bytes, (byte) 0);
                main.post(() -> {
                    setBusy(false);
                    openUri = uri;
                    adoptOpen(created);
                    status.setText(R.string.kp_ok_created);
                    new AlertDialog.Builder(this)
                            .setMessage(R.string.kp_offer_emergency)
                            .setPositiveButton(R.string.kp_yes, (d, w) -> showAsKanji(created.getCompositeKey()))
                            .setNegativeButton(R.string.kp_no, null)
                            .show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false);
                    status.setText(getString(R.string.kp_err_generic, e.getMessage()));
                });
            }
        });
    }

    private void closeOpen() {
        openDb = null;
        wipe(openComposite);
        openComposite = null;
        readOnly = false;
    }

    private static void wipe(byte[] b) {
        if (b != null) Arrays.fill(b, (byte) 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && settings.wipeOnBackground()) {
            wipe(lastEmergencyComposite); lastEmergencyComposite = null;
            wipe(dbBytesEm); wipe(keyBytesEm); wipe(dbBytesOpen); wipe(keyBytesOpen); wipe(keyBytesCreate);
            dbBytesEm = keyBytesEm = dbBytesOpen = keyBytesOpen = keyBytesCreate = null;
            passwordEm.setText(""); passwordOpen.setText(""); passwordCreate.setText("");
            compositeHex.setText(""); compositeEncoded.setText("");
            closeOpen();
            bShowKanji.setEnabled(false);
            tabBrowse.setEnabled(false);
            entryList.removeAllViews();
            showPanel(0);
        }
    }

    @Override
    protected void onDestroy() {
        wipe(lastEmergencyComposite);
        wipe(openComposite);
        wipe(dbBytesEm); wipe(keyBytesEm); wipe(dbBytesOpen); wipe(keyBytesOpen); wipe(keyBytesCreate);
        exec.shutdownNow();
        super.onDestroy();
    }
}
