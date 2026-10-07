package com.jacks.stocks.data;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.security.KeyStore;

/** Migration-only deletion. Yahoo never reads, stores, or transmits broker credentials. */
public final class LegacyCredentialCleanup {
    private LegacyCredentialCleanup() { }
    public static void clear(Context context) throws Exception {
        AtomicFile legacy=new AtomicFile(new File(context.getNoBackupFilesDir(),"upstox-token.bin"));
        legacy.delete();
        if(legacy.getBaseFile().exists())throw new java.io.IOException("Old provider credential file could not be removed.");
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        String alias="com.jacks.stocks.upstox.token.v1";
        if(keys.containsAlias(alias))keys.deleteEntry(alias);
    }
}