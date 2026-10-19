package com.zmlworks.kanjicolorkey;

import android.app.Activity;
import android.os.Process;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Binds the shared RAM-store status bar (hint, held slots, Clear, Quit). */
public final class RamStoreUi {
    private RamStoreUi() {}

    public static void bind(Activity a) {
        View bar = a.findViewById(R.id.ramStoreBar);
        if (bar == null) return;
        refresh(a);
        View clear = a.findViewById(R.id.bRamClear);
        if (clear != null) clear.setOnClickListener(v -> {
            RamKeyStore.get().clear();
            refresh(a);
            Toast.makeText(a, R.string.ram_store_empty, Toast.LENGTH_SHORT).show();
        });
        View quit = a.findViewById(R.id.bRamQuit);
        if (quit != null) quit.setOnClickListener(v -> {
            RamKeyStore.get().clear();
            a.finishAndRemoveTask();
            Process.killProcess(Process.myPid());
        });
    }

    public static void refresh(Activity a) {
        TextView st = a.findViewById(R.id.ramStoreStatus);
        if (st == null) return;
        String s = RamKeyStore.get().statusSummary();
        if (s.isEmpty()) st.setText(R.string.ram_store_empty);
        else st.setText(a.getString(R.string.ram_store_held, s));
    }
}
