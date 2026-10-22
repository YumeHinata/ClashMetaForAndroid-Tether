package com.github.kr328.clash.tether

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class TetherTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val state = TetherManager.state.value
        if (state.status == TetherStatus.CONNECTED
            || state.status == TetherStatus.WAITING_HOTSPOT
        ) {
            TetherForegroundService.stop(this)
        } else {
            TetherForegroundService.start(this)
        }

        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = TetherManager.state.value

        tile.state = when (state.status) {
            TetherStatus.CONNECTED -> Tile.STATE_ACTIVE
            TetherStatus.WAITING_HOTSPOT -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }

        tile.label = when (state.status) {
            TetherStatus.CONNECTED -> "Tether: ON"
            TetherStatus.ERROR -> "Tether: Error"
            TetherStatus.WAITING_HOTSPOT -> "Tether: Waiting"
            TetherStatus.IDLE -> "Tether: OFF"
        }

        tile.updateTile()
    }
}
