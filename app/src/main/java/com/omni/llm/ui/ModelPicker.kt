package com.omni.llm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.llm.data.ModelCatalog
import com.omni.llm.ui.theme.OmniPalette

/**
 * The model chooser, shared by Chat and Build.
 *
 * Two things it refuses to do:
 *  - show a bare list of ids. Every row carries a one-line description and the capability badges
 *    that actually matter (context, thinking, tools, images), because "gemma4:31b" tells a user
 *    nothing.
 *  - hide the paywalled models. They appear under "Needs an account", greyed and unclickable, so
 *    the difference between "free" and "advertised as free" stays visible instead of mysterious.
 */
@Composable
fun ModelPickerSheet(
    title: String,
    subtitle: String,
    selectedKey: String?,
    category: ModelCatalog.Category?,
    onCategory: (ModelCatalog.Category?) -> Unit,
    onPick: (ModelCatalog.FreeModel) -> Unit,
    onClose: () -> Unit,
    /** Agent builds need tool-calling models; chat can use anything text. */
    agentOnly: Boolean = false,
    /** Thread sizes per thread key, shown as "12 messages". */
    threadSummary: Map<String, com.omni.llm.data.ChatStore.Summary> = emptyMap(),
    /** Ranks the least-restrictive lanes first when Unrestricted mode is on. */
    unrestricted: Boolean = false,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close picker")
                }
            }

            // Category filter. "All" first, because most people want the shortlist, not a taxonomy.
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    FilterChip(
                        selected = category == null,
                        onClick = { onCategory(null) },
                        label = { Text("All", fontSize = 12.sp) },
                    )
                }
                items(
                    ModelCatalog.Category.entries.filter { c ->
                        c != ModelCatalog.Category.LOCKED &&
                            ModelCatalog.models.any { it.category == c && (!agentOnly || it.agentReady) }
                    }
                ) { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { onCategory(c) },
                        label = { Text(c.label, fontSize = 12.sp) },
                    )
                }
            }

            // Under unrestricted mode the picker reorders instead of hiding: the lanes with the
            // most permissive terms of use float to the top of each group. Nothing is removed,
            // because a hidden model is worse than a labelled one.
            val ordered = if (unrestricted) {
                ModelCatalog.byCategory().map { (c, list) ->
                    c to list.sortedBy { if (it.usable) 0 else 1 }
                }
            } else ModelCatalog.byCategory()

            val groups = ordered
                .map { (c, list) ->
                    c to list.filter { m ->
                        (category == null || m.category == category) &&
                            (!agentOnly || m.agentReady || m.category == ModelCatalog.Category.LOCKED)
                    }
                }
                .filter { it.second.isNotEmpty() }

            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No model matches that filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (cat, list) ->
                    item(key = "h-${cat.name}") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp)) {
                            Text(
                                cat.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = OmniPalette.info,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                            )
                            Text(
                                cat.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    items(list, key = { it.threadKey }) { model ->
                        ModelRow(
                            model = model,
                            selected = model.threadKey == selectedKey,
                            summary = threadSummary[model.threadKey],
                            onPick = { if (model.usable) onPick(model) },
                        )
                    }
                    item(key = "d-${cat.name}") { Spacer(Modifier.height(4.dp)) }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelCatalog.FreeModel,
    selected: Boolean,
    summary: com.omni.llm.data.ChatStore.Summary?,
    onPick: () -> Unit,
) {
    val enabled = model.usable
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clickable(enabled = enabled, onClick = onPick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.surfaceVariant
                !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!enabled) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The description the user asked for, directly under the name.
                Text(
                    model.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
                Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.displayContext?.let { Badge("${it} ctx", OmniPalette.info) }
                    if (model.reasoning) Badge("thinking", OmniPalette.warn)
                    if (model.tools) Badge("tools", OmniPalette.ok)
                    if (model.vision) Badge("vision", OmniPalette.info)
                    if (model.image) Badge("image", OmniPalette.warn)
                    if (summary != null && summary.count > 0) {
                        Badge("${summary.count} msg", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, contentDescription = "Selected", tint = OmniPalette.ok, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Badge(text: String, colour: Color) {
    Text(
        text,
        color = colour,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(colour.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** Bottom sheet showing which other threads are attached as context. */
@Composable
fun ContextBridgeSheet(
    activeKey: String,
    selected: Set<String>,
    candidates: List<ModelCatalog.FreeModel>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Read context from", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Only cloud conversations can be borrowed, and they are only read — " +
                            "the other chat is never changed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            LazyColumn(Modifier.fillMaxSize()) {
                items(candidates.filter { it.threadKey != activeKey }, key = { it.threadKey }) { m ->
                    val on = m.threadKey in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(m.threadKey) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                m.blurb,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = if (on) "Attached" else null,
                            tint = if (on) OmniPalette.ok else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
