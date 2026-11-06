package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Every message exposes Delete directly, including the latest linked text. */
internal fun ComposeTestRule.openHistoryDelete(id: String) {
    onNodeWithTag("history_menu_$id").assertDoesNotExist()
    onNodeWithTag("history_delete_$id").performScrollTo().performClick()
}
