package com.tapscene.runtime.target;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Read-only, signature-checked synthetic state; never accepts or manufactures touch input. */
public final class TargetControlProvider extends ContentProvider {
    @Override public boolean onCreate() {
        TargetState.get(providerContext());
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Context context = providerContext();
        if (!BuildConfig.DEBUG || context.getPackageManager().checkSignatures(
                Binder.getCallingUid(), Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Only the same debug-signed test build may read this fixture");
        }
        if (!"snapshot".equals(method)) throw new IllegalArgumentException("Only snapshot is supported");
        TargetState.validateSession(arg);
        return TargetState.get(context).snapshot(arg);
    }

    private Context providerContext() {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider context unavailable");
        return context;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException("Use signature-checked snapshot call");
    }

    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("No external writes");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external writes");
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("No external writes");
    }
}
