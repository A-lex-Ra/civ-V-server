package com.unciv.ui.screens.worldscreen.mainmenu

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Cell
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle
import com.unciv.ui.components.extensions.disable
import com.unciv.ui.components.input.KeyboardBinding
import com.unciv.ui.components.input.onLongPress
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.savescreens.LoadGameScreen
import com.unciv.ui.screens.victoryscreen.VictoryScreen
import com.unciv.ui.screens.worldscreen.WorldScreen

/** The in-game menu called from the "Hamburger" button top-left
 *
 *  Popup automatically opens as soon as it's initialized
 */
class WorldScreenMenuPopup(
    val worldScreen: WorldScreen,
    expertMode: Boolean = false
) : Popup(worldScreen, scrollable = Scrollability.All) {
    private val singleColumn: Boolean
    private fun <T: Actor?> Cell<T>.nextColumn() {
        if (!singleColumn && column == 0) return
        row()
    }

    init {
        worldScreen.autoPlay.stopAutoPlay()
        defaults().fillX()

        val showSave = !worldScreen.gameInfo.gameParameters.isOnlineMultiplayer
        val showMusic = worldScreen.game.musicController.isMusicAvailable()
        val showConsole = showSave && expertMode
        val buttonCount = 8 + (if (showSave) 1 else 0) + (if (showMusic) 1 else 0) + (if (showConsole) 1 else 0)

        val emptyPrefHeight = this.prefHeight
        val firstCell = addButton("Main menu") {
            worldScreen.game.goToMainMenu()
        }
        singleColumn = worldScreen.isCrampedPortrait() ||
            2 * prefWidth > maxPopupWidth ||  // Very coarse: Assume width of translated "Main menu" is representative
            buttonCount * (prefHeight - emptyPrefHeight) + emptyPrefHeight < maxPopupHeight
        firstCell.nextColumn()

        addButton("Civilopedia", KeyboardBinding.Civilopedia) {
            close()
            worldScreen.openCivilopedia()
        }.nextColumn()
        if (showSave) {
            val saveCell = addButton("Save game", KeyboardBinding.SaveGame) {
                close()
                worldScreen.openSaveGameScreen()
            }
            // multiplayer-v3 joiner: it holds only a filtered view, so local saving is disabled (muted).
            // The HOST can save — it persists the authority's canonical state (see
            // WorldScreen.openSaveGameScreen / GameSession.cloneCanonicalForSave).
            if (worldScreen.game.v3GameManager?.let { !it.isHost } == true)
                saveCell.actor.disable()
            saveCell.nextColumn()
        }
        addButton("Load game", KeyboardBinding.LoadGame) {
            close()
            worldScreen.game.pushScreen(LoadGameScreen())
        }.nextColumn()
        addButton("Start new game", KeyboardBinding.NewGame) {
            close()
            worldScreen.openNewGameScreen()
        }.nextColumn()
        addButton("Victory status", KeyboardBinding.VictoryScreen) {
            close()
            worldScreen.game.pushScreen(VictoryScreen(worldScreen))
        }.nextColumn()
        val optionsCell = addButton("Options", KeyboardBinding.Options) {
            close()
            worldScreen.openOptionsPopup()
        }
        optionsCell.actor.onLongPress {
            close()
            worldScreen.openOptionsPopup(withDebug = true)
        }
        optionsCell.nextColumn()
        if (showMusic)
            addButton("Music", KeyboardBinding.MusicPlayer) {
                close()
                WorldScreenMusicPopup(worldScreen).open(force = true)
            }.nextColumn()

        if (showConsole)
            addButton("Developer Console", KeyboardBinding.DeveloperConsole) {
                close()
                worldScreen.openDeveloperConsole()
            }.nextColumn()
        
        addButton("Exit") {
            close()
            Gdx.app.exit()
        }.apply { actor.style = BaseScreen.skin.get("negative", TextButtonStyle::class.java) }
            .nextColumn()

        // EXPERIMENTAL multiplayer-v3 status: whether this client is the authority (host) or a joined
        // player, plus the relay Room ID (the label IS the id; tapping it re-copies it to the clipboard).
        // Their own full-width rows so the long id doesn't disrupt the two-column button grid above.
        val v3Manager = worldScreen.game.v3GameManager
        if (v3Manager != null) {
            val span = if (singleColumn) 1 else 2
            row()
            addGoodSizedLabel(
                if (v3Manager.isHost) "Multiplayer (v3): you are the host"
                else "Multiplayer (v3): you joined as a player"
            ).colspan(span).row()
            val v3RoomId = v3Manager.roomId
            if (v3RoomId != null)
                addButton("Room ID: $v3RoomId") {
                    Gdx.app.clipboard.contents = v3RoomId
                    ToastPopup("Room ID copied to clipboard: [$v3RoomId]", worldScreen)
                }.colspan(span).row()
        }

        addCloseButton().run { colspan(if (singleColumn || column == 1) 1 else 2) }
        pack()

        open(force = true)
    }
}
