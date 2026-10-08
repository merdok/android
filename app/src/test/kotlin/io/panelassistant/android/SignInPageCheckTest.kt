package io.panelassistant.android

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sign-in screen offers the panel's own address only once that page has answered there, and asks again
 * when the answer is a minute old (maintainer, 2026-10-08: the notice shows only if the URL is valid and
 * working).
 */
class SignInPageCheckTest {
    private val url = "http://192.0.2.10:8888/configure#cfg-ha-oauth"

    private class Fixture(var answer: Boolean) {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        var clock = 0L
        var asked = 0
        var redraws = 0
        val check = SignInPageCheck(scope, now = { clock }, io = dispatcher, answers = { asked++; answer })
    }

    @Test
    fun anUnprovenAddressIsNotOfferedAndAnAnswerRedrawsWithIt() {
        val f = Fixture(answer = true)
        assertNull("nothing is offered before the page has answered", f.check.proven(url) { f.redraws++ })
        f.scope.advanceUntilIdle()
        assertEquals(1, f.asked)
        assertEquals("the screen is redrawn once the page answers", 1, f.redraws)
        assertEquals(url, f.check.proven(url) { f.redraws++ })
        assertEquals("a proven answer is not asked again", 1, f.asked)
    }

    @Test
    fun aPageThatDoesNotAnswerIsNeverOffered() {
        val f = Fixture(answer = false)
        f.check.proven(url) { f.redraws++ }
        f.scope.advanceUntilIdle()
        assertNull(f.check.proven(url) { f.redraws++ })
        assertEquals("no redraw for a page that did not answer", 0, f.redraws)
        assertEquals(1, f.asked)
    }

    @Test
    fun anAnswerIsTrustedForAMinuteThenAskedAgain() {
        val f = Fixture(answer = true)
        f.check.proven(url) {}
        f.scope.advanceUntilIdle()
        f.clock = SignInPageCheck.TRUST_MS - 1
        assertEquals(url, f.check.proven(url) {})
        f.answer = false
        f.clock = SignInPageCheck.TRUST_MS
        assertNull("a minute-old answer is asked again", f.check.proven(url) {})
        f.scope.advanceUntilIdle()
        assertEquals(2, f.asked)
        assertNull("the newer answer wins", f.check.proven(url) {})
    }

    @Test
    fun aRepeatedRequestWhileAskingDoesNotAskTwice() {
        val f = Fixture(answer = true)
        f.check.proven(url) {}
        f.check.proven(url) {}
        f.scope.advanceUntilIdle()
        assertEquals(1, f.asked)
        assertNull("no address, no offer", f.check.proven(null) {})
    }
}
