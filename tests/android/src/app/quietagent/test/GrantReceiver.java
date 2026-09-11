package app.quietagent.test;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Runs under the fixture-app UID so picker-like one-URI grants can be tested and revoked. */
public final class GrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Uri uri = Uri.parse(intent.getStringExtra("uri"));
        if (!FixtureProvider.AUTHORITY.equals(uri.getAuthority())) return;
        if ("app.quietagent.test.GRANT_FIXTURE".equals(intent.getAction())) {
            context.grantUriPermission("app.quietagent", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if ("app.quietagent.test.REVOKE_FIXTURE".equals(intent.getAction())) {
            context.revokeUriPermission("app.quietagent", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
}
