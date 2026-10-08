package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal fun proximityLearningBanner(title: String, strings: AppStrings): String = notice(
    NoticeSeverity.WARNING, strings,
    title = esc(strings.get(title)),
    detail = """${esc(strings.get("dashboard.banner.proximity_learning.touch_available"))} <a href="${localizedHref("configure#cfg-proximity-learning", strings)}">${esc(strings.get("dashboard.banner.proximity_learning.action"))}</a>.""",
)

internal fun configureResumeBanner(strings: AppStrings): String = notice(
    NoticeSeverity.NOTE, strings,
    title = "",
    detail = """${esc(strings.get("configure.setup.question"))} <a href="${localizedHref("setup", strings)}"><b>${esc(strings.get("configure.setup.link"))}</b></a> ${esc(strings.get("configure.setup.explanation"))}""",
)

internal fun configureStrategyBanner(strings: AppStrings): String = notice(
    NoticeSeverity.NOTE, strings,
    title = esc(strings.get("configure.setup.strategy_allowed.title")),
    detail = """${esc(strings.get("configure.setup.strategy_allowed.body"))} <a href="${localizedHref("entities", strings)}">${esc(strings.get("configure.setup.strategy_allowed.link"))}</a>.""",
)

internal fun dashboardPanelBridgeBanner(strings: AppStrings): String =
    notice(NoticeSeverity.WARNING, strings, title = "", detail = esc(strings.get("dashboard.banner.panel_bridge_running")))
