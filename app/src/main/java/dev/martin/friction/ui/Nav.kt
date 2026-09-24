package dev.martin.friction.ui

import androidx.compose.runtime.mutableStateListOf

sealed class Screen {
    object Home : Screen()
    object Status : Screen()
    object Setup : Screen()
    object Log : Screen()
    object Groups : Screen()
    data class GroupEdit(val id: String?) : Screen()
    object StageSequences : Screen()
    data class StageSequenceEdit(val id: String?) : Screen()
    object TaskSequences : Screen()
    data class TaskSequenceEdit(val id: String?) : Screen()
    object TaskVariants : Screen()
    data class TaskVariantEdit(val id: String?, val typeId: String) : Screen()
    object InterruptionVariants : Screen()
    data class InterruptionVariantEdit(val id: String?, val typeId: String) : Screen()
}

/** Minimal back stack; the system Back button pops it. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> =
    if (any { id(it) == id(item) }) map { if (id(it) == id(item)) item else it } else this + item

fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices || from !in indices) return this
    val m = toMutableList()
    val x = m.removeAt(from)
    m.add(to, x)
    return m
}
