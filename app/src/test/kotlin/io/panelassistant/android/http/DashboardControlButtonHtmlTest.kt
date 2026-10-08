package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DashboardControlButtonHtmlTest {
    @Test
    fun rebootStyleClosesBeforeTheClickHandler() {
        assertEquals(
            """<button class="pbtn danger" style="margin-left:auto" onclick="act('reboot')">⟳ Reboot</button>""",
            dashboardControlButtonHtml(
                action = "reboot",
                labelHtml = "⟳ Reboot",
                disabledReason = null,
                style = "margin-left:auto",
                danger = true,
            ),
        )
    }

    @Test
    fun disabledTitleIsEscapedAndClosesBeforeTheClickHandler() {
        assertEquals(
            """<button class="pbtn" style="margin-left:auto" title="No &quot;other&quot; &amp; &lt;unsafe&gt; launcher" onclick="act('launcher')" disabled>Launcher</button>""",
            dashboardControlButtonHtml(
                action = "launcher",
                labelHtml = "Launcher",
                disabledReason = "No \"other\" & <unsafe> launcher",
                style = "margin-left:auto",
            ),
        )
    }

    @Test
    fun actionCannotInjectMarkupOrScript() {
        assertThrows(IllegalArgumentException::class.java) {
            dashboardControlButtonHtml("reboot');alert(1)//", "Reboot", null)
        }
    }
}
