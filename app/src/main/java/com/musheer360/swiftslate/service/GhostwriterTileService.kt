package com.musheer360.swiftslate.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.musheer360.swiftslate.MainActivity
import com.musheer360.swiftslate.R

/**
 * Pull-down shade tile: "Ghostwriter" ON means the accessibility service is alive and
 * healthy; OFF means typing triggers anywhere would silently do nothing — the exact state
 * users previously discovered only by a dead command. Tapping goes to the app when healthy,
 * straight to the accessibility toggles when not.
 *
 * One-tap enable/disable from the tile is impossible here: MIUI denies
 * WRITE_SECURE_SETTINGS at the shell and a TileService has no UI permission flow, so on
 * hostile OEMs the tile guides instead of toggling. Truthful guidance beats a button that
 * throws SecurityException.
 */
class GhostwriterTileService : TileService() {

    override fun onStartListening() {
        update()
    }

    override fun onTileAdded() {
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val healthy = KeepAlive.isServiceAlive(this)
        try {
            tile.label = getString(R.string.tile_app_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.subtitle = getString(
                    if (healthy) R.string.tile_desc_active else R.string.tile_desc_stopped
                )
            }
            tile.state = if (healthy) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (_: Exception) {
            // The tile service is untethered from the bound QS panel when the shade
            // closes mid-update; a failed updateTile is cosmetic, never fatal.
        }
    }

    override fun onClick() {
        val healthy = KeepAlive.isServiceAlive(this)
        val intent = if (healthy) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+ deprecates the raw-Intent overload in favor of PendingIntent.
                val pending = PendingIntent.getActivity(
                    this,
                    1101,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
        }
        update()
    }
}
