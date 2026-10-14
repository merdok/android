package io.panelassistant.android

import android.content.SharedPreferences
import io.panelassistant.android.config.SettingsRegistry
import java.lang.reflect.Proxy

/** An in-memory [SharedPreferences] for [Config] tests: [values] is the store, and [commitsSucceed]
 *  makes every commit fail while false, so a test can prove a failed commit publishes nothing. */
internal data class ConfigTestPreferences(
    val instance: SharedPreferences,
    val values: MutableMap<String, Any?>,
    val commitsSucceed: java.util.concurrent.atomic.AtomicBoolean,
)

internal fun configTestPreferences(
    initial: Map<String, Any?> = emptyMap(),
    commitSucceeds: Boolean = true,
    onGetString: ((String, String) -> Unit)? = null,
): ConfigTestPreferences {
    val values = initial.toMutableMap()
    val commitsSucceed = java.util.concurrent.atomic.AtomicBoolean(commitSucceeds)
    lateinit var prefs: SharedPreferences
    prefs = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "getString" -> (values[args!![0]] as? String ?: args[1]).also {
                if (it is String) onGetString?.invoke(args[0] as String, it)
            }
            "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
            "getInt" -> values[args!![0]] as? Int ?: args[1]
            "getLong" -> values[args!![0]] as? Long ?: args[1]
            "getFloat" -> values[args!![0]] as? Float ?: args[1]
            "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
            "contains" -> values.containsKey(args!![0])
            "edit" -> fakeEditor(values) { commitsSucceed.get() }
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "FakeSharedPreferences"
            else -> error("unexpected SharedPreferences call: ${method.name}")
        }
    } as SharedPreferences
    return ConfigTestPreferences(prefs, values, commitsSucceed)
}

private fun fakeEditor(
    values: MutableMap<String, Any?>,
    commitSucceeds: () -> Boolean,
): SharedPreferences.Editor {
    val writes = LinkedHashMap<String, Any?>()
    val removals = LinkedHashSet<String>()
    var clear = false
    lateinit var editor: SharedPreferences.Editor
    editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { _, method, args ->
        when {
            method.name.startsWith("put") -> editor.also {
                writes[args!![0] as String] = args[1]
                removals.remove(args[0])
            }
            method.name == "remove" -> editor.also {
                val key = args!![0] as String
                writes.remove(key)
                removals.add(key)
            }
            method.name == "clear" -> editor.also { clear = true; writes.clear(); removals.clear() }
            method.name == "commit" -> {
                val succeeds = commitSucceeds()
                if (succeeds) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    values.putAll(writes)
                }
                succeeds
            }
            method.name == "apply" -> {
                if (commitSucceeds()) {
                    if (clear) values.clear()
                    removals.forEach(values::remove)
                    values.putAll(writes)
                }
                null
            }
            method.name == "toString" -> "FakeEditor"
            else -> error("unexpected Editor call: ${method.name}")
        }
    } as SharedPreferences.Editor
    return editor
}

/** Stage a bundle import exactly as applyAccepted does: per-key first, then the dependency pass. */
internal fun stageImport(config: Config, editor: SharedPreferences.Editor, accepted: Map<String, String>) {
    accepted.forEach { (key, value) ->
        SettingsRegistry.spec(key)?.let { spec -> config.stage(editor, spec, value) }
    }
    config.stageImportDependencies(editor, accepted)
}
