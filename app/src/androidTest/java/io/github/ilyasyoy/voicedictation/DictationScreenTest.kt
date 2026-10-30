package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DictationScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recordingIsExplicitlyUnavailable() {
        compose.onNodeWithTag("start_dictation").assertIsNotEnabled()
        compose.onNodeWithTag("clear_draft").performScrollTo().assertIsNotEnabled()
    }

    @Test fun exampleCanBeLoadedAndClearedRepeatedly() {
        repeat(2) {
            compose.onNodeWithTag("load_example").performScrollTo().performClick()
            compose.onNodeWithTag("clear_draft").performScrollTo().assertIsEnabled().performClick()
        }
        compose.onNodeWithTag("clear_draft").assertIsNotEnabled()
        compose.onNodeWithTag("transcript").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun typedDraftSurvivesActivityRecreation() {
        compose.onNodeWithTag("transcript").performScrollTo().performTextReplacement("A thought to keep")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("transcript")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("A thought to keep")))
    }
}
