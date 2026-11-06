package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Linked editable drafts still have two real menu actions; ordinary rows expose Delete directly. */
internal fun ComposeTestRule.openHistoryDelete(id: String) {
    if (onAllNodesWithTag("history_menu_$id").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithTag("history_menu_$id").performScrollTo().performClick()
        onNodeWithTag("history_delete_$id").performClick()
    } else onNodeWithTag("history_delete_$id").performScrollTo().performClick()
}
