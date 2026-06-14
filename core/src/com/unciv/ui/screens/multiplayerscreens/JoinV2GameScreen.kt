package com.unciv.ui.screens.multiplayerscreens

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.UncivGame
import com.unciv.logic.multiplayer.v2.V2GameManager
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.enable
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.extensions.toTextButton
import com.unciv.ui.components.input.KeyCharAndCode
import com.unciv.ui.components.input.keyShortcuts
import com.unciv.ui.components.input.onActivation
import com.unciv.ui.components.input.onClick
import com.unciv.ui.components.widgets.UncivTextField
import com.unciv.ui.popups.Popup
import com.unciv.ui.popups.ToastPopup
import com.unciv.ui.screens.pickerscreens.PickerScreen
import com.unciv.utils.Concurrency
import com.unciv.utils.Log
import com.unciv.utils.launchOnGLThread

/**
 * EXPERIMENTAL / PREVIEW (multiplayer-v2, docs/multiplayer-v2.md §4/§7) — JOIN entry point.
 *
 * Mirrors [AddMultiplayerGameScreen]'s text-field + confirm pattern, but instead of registering a v1
 * PBEM game it joins a hosted authoritative v2 game by its relay **Room ID** and opens the resulting
 * client WorldScreen. The host side is wired in NewGameScreen; this is the matching client side.
 *
 * Join flow (off the GL thread, in a [Concurrency] coroutine — same convention as the v1 screens):
 *  1. [V2GameManager.joinGame] connects to the relay and joins the room.
 *  2. [V2GameManager.requestInitialView] asks the authority for an immediate filtered snapshot — the
 *     host only broadcasts on EndTurn, so a fresh joiner must ask or it would block until next turn.
 *  3. [V2GameManager.awaitFirstView] waits (with a timeout) for the first decoded filtered GameInfo.
 *  4. The manager is set on [UncivGame.v2GameManager] **before** [UncivGame.loadGame] so the new
 *     WorldScreen's init sees it (it checks `v2GameManager != null && !isHost` to wire its onView
 *     refresh and treat the local player as a client).
 *
 * On any failure (connect / bad room id / timeout) it shows an error in the popup and tears the
 * manager down via [V2GameManager.close], leaving no half-open transport or dangling manager.
 */
class JoinV2GameScreen : PickerScreen() {
    init {
        val roomIdTextField = UncivTextField("Room ID")
        val pasteRoomIdButton = "Paste from clipboard".toTextButton()
        pasteRoomIdButton.onClick {
            roomIdTextField.text = Gdx.app.clipboard.contents
        }

        topTable.add("Authoritative Multiplayer (experimental)".toLabel()).padBottom(10f).row()
        topTable.add(("This is an experimental preview. Enter the Room ID shared by the host of a v2 game.").toLabel()).padBottom(20f).row()

        topTable.add("Room ID".toLabel()).row()
        val roomIdTable = Table()
        roomIdTable.add(roomIdTextField).pad(10f).width(2 * stage.width / 3 - pasteRoomIdButton.width)
        roomIdTable.add(pasteRoomIdButton)
        topTable.add(roomIdTable).padBottom(30f).row()

        closeButton.setText("Back".tr())
        setDefaultCloseAction()

        rightSideButton.setText("Join experimental game (v2)".tr())
        rightSideButton.enable()
        rightSideButton.keyShortcuts.add(KeyCharAndCode.RETURN)
        rightSideButton.onActivation {
            val roomId = roomIdTextField.text.trim()
            if (roomId.isEmpty()) {
                ToastPopup("Invalid room ID!", this)
                return@onActivation
            }
            join(roomId)
        }
    }

    private fun join(roomId: String) {
        val popup = Popup(this)
        popup.addGoodSizedLabel("Joining experimental game...")
        popup.open()

        Concurrency.run("JoinV2Game") {
            val manager = V2GameManager()
            try {
                val serverUrl = UncivGame.Current.settings.multiplayer.getServer()
                val myUserId = UncivGame.Current.settings.multiplayer.getUserId()

                manager.joinGame(roomId, serverUrl, myUserId)
                // The host only broadcasts on EndTurn; a joiner must ask for an immediate snapshot.
                manager.requestInitialView()
                val firstView = manager.awaitFirstView()

                if (firstView == null) {
                    manager.close()
                    launchOnGLThread {
                        popup.reuseWith("Could not join experimental multiplayer game! (no response from host)", true)
                    }
                    return@run
                }

                // Must be set BEFORE loadGame so WorldScreen.init sees it (reads v2GameManager + !isHost).
                UncivGame.Current.v2GameManager = manager
                UncivGame.Current.loadGame(firstView)
            } catch (ex: Exception) {
                Log.error("Error while joining v2 game", ex)
                manager.close()
                // If we'd already attached this manager, drop it so we never leave a dangling one.
                if (UncivGame.Current.v2GameManager === manager) UncivGame.Current.v2GameManager = null
                launchOnGLThread {
                    popup.reuseWith("Could not join experimental multiplayer game!", true)
                }
            }
        }
    }
}
