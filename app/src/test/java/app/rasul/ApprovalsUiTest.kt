package app.rasul

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.rasul.bridge.*
import app.rasul.ui.theme.RasulTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h700dp")
class ApprovalsUiTest {
    @get:Rule val compose = createComposeRule()
    private val id = "a".repeat(32)
    private fun item(
        allowSession: Boolean = true,
        allowPermanent: Boolean = true,
        expires: Double = 1_900_000_000.0,
        requestId: String = id,
        command: String = "rclone move ./reports drive:backups",
    ) = ApprovalItemDto(requestId, "sk", "default", "shell", command, "Sync the reports folder.", allowSession, allowPermanent, expires)

    private fun screen(
        cards: List<ApprovalCardState>,
        feedError: ApprovalsError? = null,
        direct: Boolean = false,
        unsupported: Boolean = false,
        now: Long = 1_899_999_700,
        onAnswer: (String, String) -> Unit = { _, _ -> },
        onReload: () -> Unit = {},
    ) = compose.setContent {
        RasulTheme(false) {
            ApprovalsContent(
                ApprovalsScreenState(direct = direct, loading = false, feedError = feedError, unsupported = unsupported, cards = cards),
                "Research", nowEpochSec = now, onAnswer = onAnswer, onReload = onReload)
        }
    }

    @Test fun flagsControlWhichChoicesShow() {
        screen(listOf(ApprovalCardState(item(allowSession = false, allowPermanent = false, requestId = "1".repeat(32))),
            ApprovalCardState(item(allowSession = true, allowPermanent = false, requestId = "2".repeat(32), command = "echo two"))))
        compose.onAllNodesWithText("Approve once").assertCountEquals(2)
        compose.onAllNodesWithText("Deny").assertCountEquals(2)
        compose.onAllNodesWithText("This session").assertCountEquals(1)
        compose.onAllNodesWithText("Always").assertCountEquals(0)
    }

    @Test fun sessionOnlyAllowsSessionAndNeverAlways() {
        screen(listOf(ApprovalCardState(item(allowSession = false, allowPermanent = true))))
        compose.onNodeWithText("This session").assertDoesNotExist()
        compose.onNodeWithText("Always").assertDoesNotExist()
    }

    @Test fun alwaysAsksForConfirmationBeforeSending() {
        var sent: String? = null
        screen(listOf(ApprovalCardState(item())), onAnswer = { _, choice -> sent = choice })
        compose.onNodeWithText("Always").performClick()
        assertNull(sent)
        compose.onNodeWithText("Always approve?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertNull(sent)
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText("Always approve").performClick()
        assertEquals("always", sent)
    }

    @Test fun alwaysConfirmationNamesThatRequestsCommand() {
        var sent: Pair<String, String>? = null
        screen(
            listOf(
                ApprovalCardState(item(requestId = "1".repeat(32), command = "alpha --one")),
                ApprovalCardState(item(requestId = "2".repeat(32), command = "beta --two"))),
            onAnswer = { id, choice -> sent = id to choice })
        compose.onAllNodesWithText("Always")[1].performScrollTo().performClick()
        compose.onNodeWithText("Always approve?").assertExists()
        // The dialog repeats the selected request's command; the other card's stays only on its card.
        compose.onAllNodesWithText("beta --two").assertCountEquals(2)
        compose.onAllNodesWithText("alpha --one").assertCountEquals(1)
        compose.onNodeWithText("Always approve").performClick()
        assertEquals("2".repeat(32) to "always", sent)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun alwaysDialogLongDescriptionKeepsActionsOnScreen() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            val command = "run --a --very --long --command --that --wraps"
            screen(listOf(ApprovalCardState(
                item(command = command).copy(
                    description = (1..40).joinToString("\n") { "line $it of a long approval description" }))))
            compose.onNodeWithText("Always").performScrollTo().performClick()
            compose.onNodeWithText("Always approve?").assertExists()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Always approve").assertIsDisplayed()
            // The dialog's own command copy lives inside the scrollable region: scrolling that
            // region to the bottom shows it, with the buttons still outside the scroll.
            val region = compose.onAllNodes(hasScrollAction() and
                    hasAnyDescendant(hasText("line 1 of a long approval description", substring = true)))
                .fetchSemanticsNodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex) == null }
            region.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(0f, Float.MAX_VALUE)
            compose.waitForIdle()
            compose.onNode(hasText(command) and hasParent(hasScrollAction())).assertIsDisplayed()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Always approve").assertIsDisplayed()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test fun alwaysConfirmationDisappearsWhenTheCardDoes() = run {
        var cards by mutableStateOf(listOf(ApprovalCardState(item())))
        compose.setContent { RasulTheme(false) {
            ApprovalsContent(ApprovalsScreenState(loading = false, cards = cards),
                "Research", nowEpochSec = 1_899_999_700)
        } }
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText("Always approve?").assertExists()
        cards = emptyList()
        compose.onNodeWithText("Always approve?").assertDoesNotExist()
    }

    @Test fun alwaysDialogClosesWhenItsRequestExpires() {
        var now by mutableStateOf(1_899_999_700L)
        var sent: String? = null
        // The request expires one second after this frame.
        compose.setContent { RasulTheme(false) {
            ApprovalsContent(
                ApprovalsScreenState(loading = false,
                    cards = listOf(ApprovalCardState(item(expires = 1_899_999_701.0)))),
                "Research", nowEpochSec = now, onAnswer = { _, choice -> sent = choice })
        } }
        compose.onNodeWithText("Always").performClick()
        compose.onNodeWithText("Always approve?").assertExists()
        // The request is dead by the next tick: the dialog must close, not confirm a stale request.
        now = 1_899_999_702
        compose.waitForIdle()
        compose.onNodeWithText("Always approve?").assertDoesNotExist()
        compose.onNodeWithText("Always approve").assertDoesNotExist()
        assertNull(sent)
    }

    @Test fun sendingDisablesEveryButtonOnThatCardOnly() {
        screen(listOf(
            ApprovalCardState(item(), sending = true),
            ApprovalCardState(item(requestId = "2".repeat(32), command = "echo two"))))
        compose.onAllNodesWithText("Approve once")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("Deny")[0].assertIsNotEnabled()
        compose.onAllNodesWithText("Approve once")[1].assertIsEnabled()
    }

    @Test fun emptyStateWhenNoCards() {
        screen(emptyList())
        compose.onNodeWithText("No approvals waiting").assertExists()
    }

    @Test fun expiredCardIsHiddenWhateverItsAnswerState() {
        screen(listOf(
            ApprovalCardState(item(expires = 1_000_000_000.0)),
            ApprovalCardState(item(expires = 1_000_000_000.0, requestId = "2".repeat(32)), sending = true,
                error = ApprovalsError.Expired)))
        compose.onNodeWithText("No approvals waiting").assertExists()
        compose.onNodeWithText("Approve once").assertDoesNotExist()
        compose.onNodeWithText("This approval has expired.").assertDoesNotExist()
    }

    @Test fun directTargetShowsTheBridgeOnlyTextAndNoActions() {
        screen(emptyList(), direct = true)
        compose.onNodeWithText("Phone approvals need Rasul on your PC. Connect this bot through a paired PC to approve requests here.").assertExists()
        compose.onNodeWithText("Approve once").assertDoesNotExist()
    }

    @Test fun unreachableFeedShowsErrorAndRetry() {
        var retried = false
        screen(emptyList(), feedError = ApprovalsError.Unreachable, onReload = { retried = true })
        compose.onNodeWithText("Cannot reach this PC. Check that it is online.").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test fun unsupportedShowsUpdateText() {
        screen(emptyList(), unsupported = true)
        compose.onNodeWithText("This PC does not offer approvals on the phone. Update Rasul on the computer.").assertExists()
    }

    @Test fun cardErrorShowsTheSpecificContractMessage() {
        screen(listOf(ApprovalCardState(item(), error = ApprovalsError.Conflict)))
        compose.onNodeWithText("This approval has already been answered or closed.").assertExists()
    }

    @Test fun countdownAnnouncesMinutesNotSeconds() {
        screen(listOf(ApprovalCardState(item(expires = 1_900_000_000.0))), now = 1_899_999_700)
        compose.onNodeWithText("Expires in 5:00").assertExists()
        compose.onNode(hasContentDescription("Expires in about 5 minutes")).assertExists()
    }

    @Test fun longCommandOffersExpand() {
        screen(listOf(ApprovalCardState(item(command = (1..12).joinToString("\n") { "line-$it" }))))
        compose.onNodeWithText("Show more").assertExists().performClick()
        compose.onNodeWithText("Show less").assertExists()
    }

    @Test fun bannerOpensTheScreen() {
        var opened = 0
        compose.setContent { RasulTheme(false) { ApprovalsBanner("1 approval waiting on Research") { opened++ } } }
        compose.onNodeWithText("1 approval waiting on Research")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals(1, opened)
    }

    @Test fun badgeIsANamedButtonThatOpensTheScreen() {
        var badgeClicks = 0
        compose.setContent { RasulTheme(false) { ApprovalsCountBadge(2) { badgeClicks++ } } }
        compose.onNodeWithContentDescription("2 approvals waiting. Open approvals.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        assertEquals(1, badgeClicks)
    }

    @Test
    @Config(qualifiers = "ar-w390dp-h700dp")
    fun arabicShowsLocalizedLabelsRightToLeft() {
        compose.setContent { RasulTheme(false) {
            ApprovalsContent(
                ApprovalsScreenState(loading = false, cards = listOf(ApprovalCardState(item()))),
                "البحث", nowEpochSec = 1_899_999_700)
        } }
        compose.onNodeWithText("الموافقات").assertExists()
        compose.onNodeWithText("موافقة لمرة واحدة").assertExists()
        compose.onNodeWithText("رفض").assertExists()
    }
}
