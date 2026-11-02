package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;

/** Opens a card: Jellyfin item deep link (package launch fallback) or a stream in the player. */
final class MediaLaunch {
    private MediaLaunch() { }

    static void openItem(Context ctx, String jellyfinPackage, String itemId) {
        if (itemId == null) {
            Intent home = jellyfinPackage.isEmpty()
                    ? null : ctx.getPackageManager().getLaunchIntentForPackage(jellyfinPackage);
            if (home != null) ctx.startActivity(home);
            return;
        }
        Intent deep = new Intent(Intent.ACTION_VIEW, Uri.parse("jellyfin://item/" + itemId));
        deep.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!jellyfinPackage.isEmpty()) deep.setPackage(jellyfinPackage);
        if (ctx.getPackageManager().resolveActivity(deep, 0) != null) {
            ctx.startActivity(deep);
            return;
        }
        Intent home = jellyfinPackage.isEmpty()
                ? null : ctx.getPackageManager().getLaunchIntentForPackage(jellyfinPackage);
        if (home != null) ctx.startActivity(home);
    }

    /** The Jellyfin Android TV app's own item screen; false when that app is not installed. */
    static boolean openNativeItem(Context ctx, String itemId) {
        Intent item = new Intent();
        item.setComponent(new ComponentName("org.jellyfin.androidtv",
                "org.jellyfin.androidtv.ui.itemdetail.FullDetailsActivity"));
        item.putExtra("ItemId", itemId);
        item.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (ctx.getPackageManager().resolveActivity(item, 0) == null) return false;
        try {
            ctx.startActivity(item);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void playStream(Context ctx, String playerPackage, String url) {
        Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!playerPackage.isEmpty()) play.setPackage(playerPackage);
        if (ctx.getPackageManager().resolveActivity(play, 0) != null) ctx.startActivity(play);
    }
}
