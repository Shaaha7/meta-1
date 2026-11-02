// UstadDesign - Ustad's look: colours, hero header, action tiles, chips, screen header, tab bar
//
// One dark "HUD" style for every Ustad screen: deep navy background, cyan for Ustad, red for the
// failing part, green for confirmed/OK, amber for warnings, violet as a second accent.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val Surface2 = Color(0xFF13203A)
internal val CardBorder = Color(0xFF1C2C4C)
internal val Accent = Color(0xFF8B7BFF)
internal val Warn = Color(0xFFFBBF24)

/** Ustad's colours for every Material component (buttons, switches, chips, menus...). */
@Composable
internal fun UstadTheme(content: @Composable () -> Unit) {
  MaterialTheme(
      colorScheme =
          darkColorScheme(
              primary = Hud,
              onPrimary = Color(0xFF001018),
              primaryContainer = Color(0xFF0B3A4A),
              onPrimaryContainer = Color.White,
              secondary = Accent,
              onSecondary = Color(0xFF0E0A24),
              secondaryContainer = Color(0xFF2A2350),
              onSecondaryContainer = Color.White,
              tertiary = Ok,
              background = Bg,
              onBackground = Color.White,
              surface = CardBg,
              onSurface = Color.White,
              surfaceVariant = Surface2,
              onSurfaceVariant = Muted,
              outline = CardBorder,
              error = Fault,
          ),
      content = content,
  )
}

/** Big gradient header at the top of the hub. */
@Composable
internal fun HeroHeader(subtitle: String, version: String, chips: List<Pair<String, Color>>, onClose: () -> Unit) {
  Column(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(24.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF0B2E4E), Color(0xFF16204A), Color(0xFF241552))))
          .border(1.dp, Hud.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
          .padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).background(Hud, CircleShape))
      Spacer(Modifier.width(8.dp))
      Text("USTAD", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 5.sp)
      Spacer(Modifier.weight(1f))
      IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White) }
    }
    Text(subtitle, color = Color(0xFFC9D8F0), fontSize = 14.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      chips.forEach { (text, color) -> StatusChip(text, color) }
    }
    Text("v$version", color = Muted, fontSize = 11.sp)
  }
}

@Composable
internal fun StatusChip(text: String, color: Color) {
  Text(
      text,
      color = color,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
  )
}

/** A large tappable row: icon, title, one line of explanation. */
@Composable
internal fun ActionTile(icon: ImageVector, title: String, subtitle: String, accent: Color = Hud, enabled: Boolean = true, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .background(Surface2)
          .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(18.dp))
          .clickable(enabled = enabled, onClick = onClick)
          .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(46.dp).background(accent.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, tint = accent)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
      Text(subtitle, color = Muted, fontSize = 12.sp)
    }
    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Muted)
  }
}

/** Section title between groups of tiles. */
@Composable
internal fun SectionTitle(text: String) {
  Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.padding(top = 6.dp))
}

/** Back arrow, title and subtitle at the top of a full screen. */
@Composable
internal fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
    Column {
      Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
      Text(subtitle, color = Muted, fontSize = 12.sp)
    }
  }
}

internal enum class HubTab(val label: String, val icon: ImageVector) {
  INSPECT("Inspect", Icons.Default.Search),
  LEARN("Learn", Icons.Default.School),
  TEAM("Team", Icons.Default.Groups),
  SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
internal fun UstadNavBar(selected: HubTab, onSelect: (HubTab) -> Unit) {
  NavigationBar(containerColor = Color(0xFF0A1222)) {
    HubTab.entries.forEach { tab ->
      NavigationBarItem(
          selected = tab == selected,
          onClick = { onSelect(tab) },
          icon = { Icon(tab.icon, contentDescription = tab.label) },
          label = { Text(tab.label) },
          colors =
              NavigationBarItemDefaults.colors(
                  selectedIconColor = Color(0xFF001018),
                  selectedTextColor = Hud,
                  indicatorColor = Hud,
                  unselectedIconColor = Muted,
                  unselectedTextColor = Muted,
              ),
      )
    }
  }
}
