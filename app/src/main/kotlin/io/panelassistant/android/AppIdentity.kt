package io.panelassistant.android


/**
 * This app's application id and component names. A component name is always
 * `<applicationId>/<fully.qualified.Class>`, shortened to `/.Class` when the class lives in the id's package.
 */
object AppIdentity {
    /** This build's own applicationId. */
    const val OWN: String = BuildConfig.APPLICATION_ID

    /**
     * The id this app was published under before 0.9.11. Kept so a stored setting or a stray old install
     * naming it still means this app; nothing installs, signs, tames or hands HOME to it.
     */
    const val LEGACY: String = "io.github.maxlyth.hapaneld"

    /** This app under either id it has carried. */
    fun isPanelApp(pkg: String?): Boolean = pkg == OWN || pkg == LEGACY

    /**
     * The flattened component for one of this app's own classes, given as the manifest's relative name
     * (`.DashboardActivity`). Android resolves the `/.Class` shorthand against the applicationId, while
     * the classes live in [CODE_PACKAGE], so the shorthand is only valid for the id that equals it.
     */
    fun component(applicationId: String, relativeClass: String): String =
        "$applicationId/${className(applicationId, relativeClass)}"

    /** The class half of [component]: relative for [CODE_PACKAGE], fully qualified for any other id. */
    fun className(applicationId: String, relativeClass: String): String {
        require(relativeClass.startsWith(".")) { "$relativeClass is not a relative class name" }
        return if (applicationId == CODE_PACKAGE) relativeClass else "$CODE_PACKAGE$relativeClass"
    }

    /** The Gradle namespace and Kotlin package. */
    const val CODE_PACKAGE: String = "io.panelassistant.android"
}
